"""Guards for the lean Claude/Codex instruction surface."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CONTRACT = ROOT / "skills/session-instructions.md"
CONTRACTS_DIR = ROOT / "skills/contracts"
LEGACY = ROOT / "src/test/resources/instructions/session-instructions-v1.md"
CONTRACT_BUDGET_BYTES = 8 * 1024


def _normalize(text: str) -> str:
    return " ".join(text.split())


def _sentences(text: str) -> list[str]:
    body = re.sub(r"^#+ .*$", "", text, flags=re.M)
    blocks = re.split(r"\n\s*\n|\n(?=\s*(?:[-*] |\d+\. ))", body)
    out = []
    for block in blocks:
        for part in re.split(r"(?<=[.!?])\s+(?=[A-Z`*\[(])", _normalize(block)):
            if len(part.strip()) > 20:
                out.append(part.strip())
    return out


class LeanAgentInstructionsTest(unittest.TestCase):
    def test_claude_and_agents_md_differ_only_in_runtime_names(self):
        claude = (ROOT / "CLAUDE.md").read_text(encoding="utf-8")
        codex = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
        neutral = lambda t: (t.replace("Claude Code", "RUNTIME").replace("Claude", "RUNTIME")
                             .replace("Codex", "RUNTIME"))
        self.assertEqual(neutral(claude), neutral(codex))

    def test_session_contract_stays_small(self):
        self.assertLessEqual(len(CONTRACT.read_bytes()), CONTRACT_BUDGET_BYTES)
        self.assertLessEqual(len(CONTRACT.read_text(encoding="utf-8").splitlines()), 60)

    def test_session_contract_states_the_lean_flow(self):
        text = _normalize(CONTRACT.read_text(encoding="utf-8"))
        for required in (
            "session.bootstrap(rootPath=<cwd>, task=",
            "do not retry",
            "`askUser`",
            "`knownEtag`",
            "memory.search(query, projectKey)",
            "once your final check has passed",
            "never a task log or a list of the files you changed",
            "also when the user asked you to remember something",
            "{kind:\"symbol\", ref:\"pkg.Class#method\"",
            "`reference: {details}`",
            "`procedure`",
            "`locators`",
        ):
            self.assertIn(required, text)

    def test_contract_directory_matches_the_pointers(self):
        text = CONTRACT.read_text(encoding="utf-8")
        for path in CONTRACTS_DIR.glob("*.md"):
            self.assertIn(f"`{path.name}`", text, path.name)

    def test_project_skills_do_not_force_an_extra_instruction_read(self):
        for skill in (ROOT / "skills").glob("c*/*/SKILL.md"):
            if skill.parts[-3] not in ("claude", "codex"):
                continue
            self.assertNotIn("../../session-instructions.md", skill.read_text(encoding="utf-8"), skill)

    def test_our_runtime_hooks_are_gone(self):
        self.assertFalse((ROOT / "scripts/runtime/learning_hook.py").exists())
        self.assertFalse((ROOT / ".codex/hooks.json").exists())
        settings = ROOT / ".claude/settings.json"
        if settings.exists():
            self.assertNotIn("learning_hook", settings.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
