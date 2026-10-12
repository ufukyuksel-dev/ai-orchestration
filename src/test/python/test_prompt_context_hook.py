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
            mock.patch.object(hook, "code_map", return_value=[]), \
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

    def test_stale_card_names_the_missing_code_and_how_to_fix_it(self):
        card = {"id": "s1", "summary": "Pricing rule", "text": "See PriceService.", "stale": True,
                "missingRefs": ["p.PriceService#quote", "src/Price.java"]}
        text = run_hook(self.event(), {"projectKey": "P", "memory": [card]})
        self.assertIn("Memory card (code it points to is missing: p.PriceService#quote, src/Price.java; "
                      "fix its locators with ai_orch memory update or archive it): Pricing rule.", text)
        self.assertNotIn("may be outdated", text)

    def test_a_pointer_card_is_one_line_with_how_to_open_it(self):
        text = run_hook(self.event(), {"projectKey": "P", "complete": True, "memory": [
            {"id": "m9", "summary": "CSV helper quoting", "pointer": True}]})
        self.assertIn("Possibly relevant memory (not loaded): CSV helper quoting.", text)
        self.assertIn('extras(op="memory.get", args={memoryId: "m9"})', text)
        self.assertNotIn("Memory card", text)

    def test_a_pointer_does_not_hide_the_full_card_later_and_neither_repeats(self):
        pointer = {"projectKey": "P", "complete": True, "memory": [{"id": "m9", "summary": "CSV helper", "pointer": True}]}
        full = {"projectKey": "P", "complete": True, "memory": [{"id": "m9", "summary": "CSV helper", "text": "Use Csv.line."}]}
        self.assertIn("Possibly relevant memory", run_hook(self.event("csv one"), pointer))
        self.assertEqual("", run_hook(self.event("csv two"), pointer))           # the same pointer: not again
        self.assertIn("Memory card: CSV helper. Use Csv.line.", run_hook(self.event("csv three"), full))
        self.assertEqual("", run_hook(self.event("csv four"), full))             # the full card: not again
        self.assertEqual("", run_hook(self.event("csv five"), pointer))          # nor its pointer after it

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


class CodeMapTest(unittest.TestCase):
    def test_best_files_with_their_matching_symbols_low_scores_dropped(self):
        hits = {"hits": [
            {"filePath": "src/vet/VetController.java", "fqn": "x.VetController#showVetList", "lineStart": 44,
             "lineEnd": 52, "capsuleKind": "endpoint_flow", "summary": "GET /vets.html -> showVetList", "score": 0.57},
            {"filePath": "src/vet/Vet.java", "fqn": "x.Vet#getSpecialties", "lineStart": 59, "lineEnd": 64, "score": 0.5},
            {"filePath": "src/vet/VetController.java", "fqn": "x.VetController#list", "lineStart": 70, "lineEnd": 76,
             "score": 0.45},
            {"filePath": "src/other/Noise.java", "fqn": "x.Noise#n", "lineStart": 1, "lineEnd": 2, "score": 0.1}]}
        with mock.patch.object(hook, "_call", return_value=hits):
            lines = hook.code_map("P", "list vets as JSON")
        self.assertEqual(3, len(lines))
        self.assertIn("VetController#showVetList 44-52 (GET /vets.html -> showVetList); VetController#list 70-76", lines[1])
        self.assertIn("src/vet/Vet.java: Vet#getSpecialties 59-64", lines[2])
        with mock.patch.object(hook, "_call", return_value=None):
            self.assertEqual([], hook.code_map("P", "anything"))


class TrailContextActivationTest(unittest.TestCase):
    """Trail context is experimental: off under the default "all", delivered only when "trailctx" is named; capture
    (the request snapshot) stays on with "trails"/"all"."""

    def run_with(self, levers: set[str]) -> tuple[str, mock.MagicMock, mock.MagicMock]:
        event = {"prompt": "fix the follower count", "cwd": "/repo", "session_id": "t-" + os.urandom(4).hex()}
        with mock.patch.object(hook, "LEVERS", levers), \
                mock.patch.object(hook.session, "repo_root", return_value="/repo"), \
                mock.patch.object(hook.session, "fingerprint", return_value="fp"), \
                mock.patch.object(hook.session, "begin_request") as begin, \
                mock.patch.object(hook.trail_retrieve, "context", return_value="[ai-orch] Earlier work …") as ctx:
            return run_hook(event, {"projectKey": "P", "rules": [], "memory": []}), begin, ctx

    def test_default_all_captures_but_shows_no_trail_context(self):
        out, begin, ctx = self.run_with({"all"})
        self.assertTrue(begin.called)
        self.assertFalse(ctx.called)
        self.assertNotIn("Earlier work", out)

    def test_explicit_opt_in_shows_it(self):
        out, _, ctx = self.run_with({"all", "trailctx"})
        self.assertTrue(ctx.called)
        self.assertIn("[ai-orch] Earlier work", out)


class CardCodeTest(unittest.TestCase):
    CODE = "Current code, app/models.py:2-3 app.models.User#to_dict (method):\n2│     def to_dict(self):"

    def test_a_cards_current_code_follows_its_line(self):
        card = dict(BOOT["memory"][0], code=self.CODE)
        lines = hook.render({"memory": [card]}, {"rules": True})
        self.assertEqual(lines, ["- Memory card: Adding a JSON endpoint. Use a @RestController. Files: "
                                 "src/owner/OwnerRest.java.\n" + self.CODE])

    def test_the_step_block_adds_code_only_when_it_fits(self):
        spec = importlib.util.spec_from_file_location("ai_orch_step_memory_t", ROOT / "scripts/hooks/ai_orch_step_memory.py")
        sys.path.insert(0, str(ROOT / "scripts/hooks"))
        step = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(step)
        card = dict(BOOT["memory"][0], code=self.CODE)
        lines, _ = step.lines_of([card], 1000)
        self.assertTrue(lines[0].endswith(self.CODE))
        lines, shown = step.lines_of([card], 120)  # the card fits, its code does not: the card without code
        self.assertEqual(shown, [card])
        self.assertNotIn("Current code", lines[0])
