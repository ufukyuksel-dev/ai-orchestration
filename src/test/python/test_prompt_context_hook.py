import importlib.util
import io
import json
import os
import sys
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("ai_orch_prompt_context", ROOT / "scripts/hooks/ai_orch_prompt_context.py")
hook = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(hook)

BOOT = {
    "projectKey": "P",
    "complete": True,
    "rules": [{"scope": "project", "statement": "Never log personal data."}],
    "pathRules": [{"paths": ["src/owner/**"], "statement": "Owner code is audited."}],
    "memory": [{"id": "m1", "summary": "Adding a JSON endpoint", "text": "Use a @RestController.",
                "files": ["src/owner/OwnerRest.java"]}],
}


def run_hook(event: dict, answer=None, error=None) -> str:
    out = io.StringIO()
    with mock.patch.object(hook, "bootstrap", side_effect=error, return_value=answer), \
            mock.patch.object(sys, "stdin", io.StringIO(json.dumps(event))), mock.patch.object(sys, "stdout", out):
        assert hook.main() == 0
    return out.getvalue()


class PromptContextHookTest(unittest.TestCase):
    def setUp(self):
        self.session = "test-" + os.urandom(4).hex()

    def event(self, prompt="add an endpoint"):
        return {"prompt": prompt, "cwd": "/repo", "session_id": self.session}

    def test_rules_and_matching_cards_go_into_the_context_once(self):
        first = run_hook(self.event(), BOOT)
        self.assertIn("Never log personal data.", first)
        self.assertIn("Rule for src/owner/**", first)
        self.assertIn("Adding a JSON endpoint", first)
        self.assertIn("src/owner/OwnerRest.java", first)
        # the next prompt of the same session repeats neither the rules nor the card
        self.assertEqual("", run_hook(self.event("and a test"), BOOT))

    def test_nothing_relevant_prints_nothing(self):
        self.assertEqual("", run_hook(self.event(), {"projectKey": "P", "rules": [], "memory": []}))

    def test_unreachable_server_never_blocks_the_prompt(self):
        self.assertEqual("", run_hook(self.event(), error=OSError("connection refused")))

    def test_ask_user_goes_through_bootstrap_and_path_index_points_to_rules_instructions(self):
        text = run_hook(self.event(), {"projectKey": "P", "askUser": "Load the project rules?", "complete": True,
                                       "pathIndex": ["src/billing"], "memory": []})
        self.assertIn("Ask the user once: Load the project rules?", text)
        self.assertIn('extras(op="session.bootstrap"', text)
        self.assertIn('rules.instructions(projectKey="P", scope="module"', text)
        self.assertEqual("", run_hook(self.event("next"), {"projectKey": "P", "askUser": "Load the project rules?",
                                                           "complete": True, "memory": []}))

    def test_rules_of_every_page_arrive_and_only_a_complete_delivery_counts(self):
        first = {"projectKey": "P", "rulesLoaded": False, "nextCursor": "c1", "memory": BOOT["memory"],
                 "rules": [{"scope": "global", "statement": "Rule one."}]}
        last = {"projectKey": "P", "rulesLoaded": True, "rules": [{"scope": "project", "statement": "Rule two."}],
                "pathRules": [{"paths": ["src/a"], "statement": "Path rule."}]}
        with mock.patch.object(hook, "_bootstrap", side_effect=[first, last]) as call:
            data = hook.bootstrap("/repo", "task")
        self.assertEqual("c1", call.call_args_list[1].args[2])
        self.assertTrue(data["complete"])
        self.assertEqual(["Rule one.", "Rule two."], [r["statement"] for r in data["rules"]])
        self.assertEqual("Path rule.", data["pathRules"][0]["statement"])

        with mock.patch.object(hook, "_bootstrap", side_effect=[first, None]):
            partial = hook.bootstrap("/repo", "task")
        self.assertFalse(partial["complete"])
        # an incomplete delivery is shown but not remembered: the next prompt delivers the rules again
        self.assertIn("Rule one.", run_hook(self.event(), partial))
        self.assertIn("Rule one.", run_hook(self.event("again"), partial))


if __name__ == "__main__":
    unittest.main()
