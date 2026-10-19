"""Copy native shipping-prepared opportunities into the shared graph artifact; no inference."""
import argparse
import json
from pathlib import Path
import numpy as np

from public_vectors import file_hash, write_artifact

POLICY = "shipping:SectionHierarchicalEngine:v4"


def promote(native, output, expected_notes=500):
    path = native / "opportunity-manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("complete") is not True or manifest.get("notes") != expected_notes or manifest.get("formationOpportunityPolicy") != POLICY:
        raise ValueError("Complete matching shipping opportunity export required")
    for field, checksum in (("rawVectorsFile", "rawVectorsSha256"), ("rowsFile", "rowsSha256")):
        file = native / manifest[field]
        if file.parent.resolve() != native.resolve() or file_hash(file) != manifest[checksum]:
            raise ValueError("Native opportunity payload path/hash mismatch")
    rows = json.loads((native / manifest["rowsFile"]).read_text(encoding="utf-8"))
    dimensions = manifest["identity"]["dimensions"]
    raw = np.fromfile(native / manifest["rawVectorsFile"], dtype="<f4")
    if len(rows) != manifest["opportunities"] or raw.size != len(rows) * dimensions:
        raise ValueError("Incomplete native opportunity payload")
    members = []
    for i, row in enumerate(rows):
        if row["opportunityIndex"] != i or not row.get("memberPassageIds"):
            raise ValueError("Missing opportunity order/provenance")
        members.extend(row["memberPassageIds"])
    if len(members) != manifest["eligiblePassages"] or len(set(members)) != len(members):
        raise ValueError("Lost/duplicated eligible passage provenance")
    vectors = raw.reshape(len(rows), dimensions)
    identity = {**manifest["identity"], "sourceVectorManifestSha256": manifest["sourceVectorManifestSha256"],
                "nativeOpportunityManifestSha256": file_hash(path), "preparation": POLICY,
                "operation": "copy-native-prepared-opportunities-no-inference"}
    return write_artifact(rows, identity, output, lambda row: vectors[row["opportunityIndex"]], formation_policy=POLICY)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    result = promote(args.native_dir, args.output_dir)
    print(f"Verified {result['passages']} shipping opportunities; no new inference or quality/Android verdict")
