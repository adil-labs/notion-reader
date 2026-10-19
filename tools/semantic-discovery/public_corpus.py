"""Revision-pinned public baseline; never reads private Notes or supplies human judgments."""
import argparse
from collections import Counter, deque
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import time
import urllib.parse
import urllib.request
import urllib.error

API = "https://en.wikipedia.org/w/api.php"
FAMILIES = [
    "Distributed computing", "Database management systems", "Machine learning", "Computer network security",
    "Programming paradigms", "Software design patterns", "Probability theory", "Statistics", "Ecology",
    "Neuroscience", "Nutrition", "Sleep", "Cognitive psychology", "Music theory", "Cooking", "Astronomy",
    "Geology", "Climate change", "Renewable energy", "Microeconomics", "Macroeconomics", "Architecture",
    "Education", "Ethics", "Ancient history",
]
SAMPLING = "round-robin-section-paragraphs-v1-max32-min100chars"


def encoded(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def digest(value):
    return hashlib.sha256(encoded(value)).hexdigest()


class ProseParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.sections = [{"title": "Body", "paragraphs": []}]
        self.skip = 0
        self.active = None
        self.parts = []
        self.void = {"br", "hr", "img", "input", "link", "meta", "wbr", "source", "area", "base", "embed", "param", "track"}

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if self.skip:
            if tag not in self.void:
                self.skip += 1
            return
        classes = set(attributes.get("class", "").split())
        if tag in {"table", "script", "style", "sup"} or classes & {"mw-editsection", "navbox", "metadata", "reflist"}:
            if tag not in self.void:
                self.skip = 1
            return
        if tag in {"p", "h2", "h3", "h4"}:
            self.active, self.parts = tag, []
        elif tag == "br" and self.active:
            self.parts.append(" ")

    def handle_endtag(self, tag):
        if self.skip:
            if tag not in self.void:
                self.skip -= 1
            return
        if tag == self.active:
            text = re.sub(r"\s+", " ", "".join(self.parts)).strip()
            if text:
                if tag == "p":
                    paragraphs = self.sections[-1]["paragraphs"]
                    paragraphs.append({"ordinal": len(paragraphs), "text": text})
                else:
                    self.sections.append({"title": text, "paragraphs": []})
            self.active, self.parts = None, []

    def handle_data(self, data):
        if self.active and not self.skip:
            self.parts.append(data)


def extract_sections(html):
    parser = ProseParser()
    parser.feed(html)
    return [s for s in parser.sections if s["paragraphs"]]


def sample_sections(sections, budget=32):
    selected = [[] for _ in sections]
    for ordinal in range(max((len(s["paragraphs"]) for s in sections), default=0)):
        for i, section in enumerate(sections):
            if ordinal < len(section["paragraphs"]) and budget:
                selected[i].append(section["paragraphs"][ordinal])
                budget -= 1
        if not budget:
            break
    return [{"title": s["title"], "paragraphs": p} for s, p in zip(sections, selected) if p]


def validate_corpus(notes, expected=500):
    if len(notes) != expected:
        raise ValueError(f"Partial corpus: expected {expected}, found {len(notes)}")
    identities, sources, contents = set(), set(), set()
    families = {}
    for note in notes:
        source = note["source"]
        if not source.get("revisionId") or source.get("kind") != "public" or not source.get("license"):
            raise ValueError("Missing public revision/license provenance")
        if note["partition"] not in {"calibration", "held_out"} or not note.get("family"):
            raise ValueError("Missing family partition")
        if note["family"] in families and families[note["family"]] != note["partition"]:
            raise ValueError("Family leakage across partitions")
        families[note["family"]] = note["partition"]
        body = [p["text"] for s in note["sections"] for p in s["paragraphs"]]
        if not body or any(not p.strip() for p in body):
            raise ValueError("Empty source evidence")
        fingerprint = digest(body)
        if note["id"] in identities or source["pageId"] in sources or fingerprint in contents:
            raise ValueError("Duplicate Note, canonical source or exact prose")
        if note.get("judgmentOrigin") != "unrated":
            raise ValueError("Capture cannot invent quality judgments")
        identities.add(note["id"]); sources.add(source["pageId"]); contents.add(fingerprint)
    return families


def freeze_manifest(notes, expected=500):
    families = validate_corpus(notes, expected)
    return {"schemaVersion": 1, "samplingPolicy": SAMPLING, "corpusSha256": digest(notes), "notes": len(notes),
            "families": families, "partitions": dict(Counter(n["partition"] for n in notes)),
            "humanQualityGate": "unmeasured", "humanJudgments": 0, "unratedNotes": len(notes),
            "limitations": ["English encyclopedia baseline; not representative personal-Note authoring styles",
                            "Category families are selection strata, not gold topic labels or hierarchy edges",
                            "Exact duplicates rejected; cross-family near-duplicate/concept leakage still needs audit",
                            "Paragraph sampling omits tables, lists and some source content; no full-article coverage claim"],
            "sources": [{"id": n["id"], "family": n["family"], "partition": n["partition"], "title": n["title"],
                         "source": n["source"], "noteSha256": digest(n)} for n in notes]}


def verify_frozen(notes, manifest):
    reproduced = freeze_manifest(notes, expected=manifest["notes"])
    if reproduced != manifest:
        raise ValueError("Frozen corpus content/provenance/partition fingerprint mismatch")
    return reproduced


def fetch(params, cache):
    target = cache / (digest(params) + ".json")
    if target.exists():
        return json.loads(target.read_text(encoding="utf-8"))
    request = urllib.request.Request(API + "?" + urllib.parse.urlencode({"format": "json", "formatversion": 2, **params}),
                                    headers={"User-Agent": "NotionReaderResearch/0.1 (public corpus reproducibility)"})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                result = json.load(response)
            if "error" in result:
                raise ValueError(result["error"].get("code", "MediaWiki error"))
            temporary = target.with_suffix(".partial")
            temporary.write_bytes(encoded(result)); temporary.replace(target)
            time.sleep(2)
            return result
        except urllib.error.HTTPError as error:
            retry_after = error.headers.get("Retry-After", "0")
            error.close()
            if attempt == 5 or error.code not in {429, 502, 503, 504}:
                raise
            delay = max(30 * (attempt + 1), int(retry_after))
            print(f"Public API backoff: HTTP {error.code}; retry in {delay}s", flush=True)
            time.sleep(delay)
        except (OSError, ValueError):
            if attempt == 5:
                raise
            time.sleep(2 ** attempt)


def candidates(category, cache):
    queue, seen = deque([(category, 0)]), set()
    while queue:
        title, depth = queue.popleft()
        if title in seen:
            continue
        seen.add(title)
        result = fetch({"action": "query", "list": "categorymembers", "cmtitle": "Category:" + title,
                        "cmlimit": 500, "cmsort": "sortkey"}, cache)
        members = result["query"]["categorymembers"]
        for member in members:
            if member["ns"] == 0:
                yield member["title"]
        if depth < 1:
            queue.extend((m["title"].removeprefix("Category:"), depth + 1) for m in members if m["ns"] == 14)


def capture(output, lock):
    output.mkdir(parents=True, exist_ok=True)
    cache = output / "api-cache"
    cache.mkdir(exist_ok=True)
    notes, used, content = [], set(), set()
    # Freeze whole families before inspecting any discovery output.
    calibration = set(sorted(FAMILIES, key=lambda f: digest("split-v1:" + f))[:10])
    for family in FAMILIES:
        accepted = 0
        for title in candidates(family, cache):
            page = fetch({"action": "parse", "page": title, "prop": "text|categories|revid", "redirects": 1}, cache)["parse"]
            if page["pageid"] in used:
                continue
            categories = " ".join(c["category"] for c in page["categories"])
            if re.search(r"(?:\bLiving_people\b|\b\d{3,4}_births\b|\b\d{3,4}_deaths\b|[Dd]isambiguation)", categories):
                continue
            raw_sections = extract_sections(page["text"])
            substantive = [{**s, "paragraphs": [p for p in s["paragraphs"] if len(p["text"]) >= 100]}
                           for s in raw_sections if s["title"].lower() not in {"references", "external links", "further reading", "see also", "notes"}]
            sampled = sample_sections([s for s in substantive if s["paragraphs"]])
            paragraphs = [p["text"] for s in sampled for p in s["paragraphs"]]
            if len(paragraphs) < 4 or sum(map(len, paragraphs)) < 2000 or digest(paragraphs) in content:
                continue
            revision = page["revid"]
            note = {"id": hashlib.sha256(f"enwiki:{page['pageid']}".encode()).hexdigest()[:32],
                    "title": page["title"], "family": family,
                    "partition": "calibration" if family in calibration else "held_out",
                    "source": {"kind": "public", "pageId": page["pageid"], "revisionId": revision,
                               "url": f"https://en.wikipedia.org/w/index.php?oldid={revision}",
                               "license": "CC-BY-SA-4.0", "rawSectionsSha256": digest(raw_sections)},
                    "sections": sampled, "judgmentOrigin": "unrated"}
            notes.append(note); used.add(page["pageid"]); content.add(digest(paragraphs)); accepted += 1
            (output / "capture-progress.json").write_bytes(encoded(notes))
            if accepted == 20:
                break
        if accepted != 20:
            raise ValueError(f"Incomplete family {family}: {accepted}/20; capture retained for reproducible resume")
        print(f"Captured {len(notes)}/500 public Notes", flush=True)
    manifest = freeze_manifest(notes)
    (output / "corpus.json").write_bytes(encoded({"manifest": manifest, "notes": notes}))
    lock.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Frozen 500 Notes; sha256={manifest['corpusSha256']}; human judgments=0", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--verify", action="store_true", help="Verify saved corpus bytes and all source/partition fingerprints offline")
    args = parser.parse_args()
    if args.verify:
        manifest = json.loads(args.lock.read_text(encoding="utf-8"))
        corpus = json.loads((args.output_dir / "corpus.json").read_text(encoding="utf-8"))
        if manifest["notes"] != 500:
            raise SystemExit("Expected a complete 500-Note manifest")
        verify_frozen(corpus["notes"], manifest)
        if corpus["manifest"] != manifest:
            raise SystemExit("Embedded manifest disagrees with external lock")
        print(f"Verified 500 Notes; sha256={manifest['corpusSha256']}; human judgments=0")
        raise SystemExit(0)
    if args.lock.exists():
        raise SystemExit("Refusing to replace a frozen corpus lock; use a new version for a new selection")
    capture(args.output_dir, args.lock)
