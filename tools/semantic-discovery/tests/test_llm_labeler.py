import unittest

import sys
sys.path.append("tools/semantic-discovery/experiments")
from llm_labeler_benchmark import build_labeling_prompt, parse_json_label


class LLMLabelerTest(unittest.TestCase):
    def test_build_labeling_prompt_structure(self):
        cluster = {
            "members": [
                {"title": "Note A", "section": "Sec 1", "excerpt": "Here is content."},
                {"title": "Note B", "section": "Sec 2", "excerpt": "Here is more content."}
            ]
        }
        prompt = build_labeling_prompt(cluster)
        self.assertIn("topic_name", prompt)
        self.assertIn("Note A", prompt)
        self.assertIn("Here is content", prompt)

    def test_parse_json_label_valid_json(self):
        raw = '{"topic_name": "Forest Ecosystems", "confidence": "high"}'
        parsed = parse_json_label(raw)
        self.assertEqual("Forest Ecosystems", parsed["topic_name"])
        self.assertEqual("high", parsed["confidence"])

    def test_parse_json_label_fallback(self):
        raw = 'Topic: Forest Ecosystems'
        parsed = parse_json_label(raw)
        self.assertIn("Forest Ecosystems", parsed["topic_name"])


if __name__ == "__main__":
    unittest.main()
