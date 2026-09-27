#!/usr/bin/env python3
"""Tests for the per-project Copilot instruction installer. Stdlib only, no network."""
import contextlib
import io
import os
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import install_project_copilot_instructions as installer  # noqa: E402


def run(*argv):
    out = io.StringIO()
    err = io.StringIO()
    argv = list(argv)
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        old, sys.argv = sys.argv, ['install_project_copilot_instructions.py'] + argv
        try:
            code = installer.main()
        finally:
            sys.argv = old
    return code, out.getvalue() + err.getvalue()


class ProjectInstallerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        self.repo = base / 'ai-orch'
        (self.repo / 'skills' / 'copilot').mkdir(parents=True)
        (self.repo / '.git').mkdir()
        (self.repo / 'skills' / 'copilot' / 'session-instructions.md').write_text('workflow\n')
        (self.repo / '.github').mkdir()
        (self.repo / '.github' / 'copilot-instructions.md').write_text(
            '# Canonical Copilot instructions\n\n`ai_orch memory search`\n'
        )
        self.skill_names = ('ai-orchestration-memory', 'debugging-this-project')
        for name in self.skill_names:
            skill = self.repo / 'skills' / 'copilot' / name
            skill.mkdir()
            (skill / 'SKILL.md').write_text(f'---\nname: {name}\n---\n')
        self.project = base / 'other-project'
        (self.project / '.git').mkdir(parents=True)
        self.target = self.project / '.github' / 'copilot-instructions.md'
        self.addCleanup(self.tmp.cleanup)

    def install(self, *extra):
        return run('--target', str(self.project), '--repo', str(self.repo), *extra)

    def test_creates_file_and_github_directory(self):
        code, _ = self.install()
        self.assertEqual(code, 0)
        text = self.target.read_text()
        self.assertIn(installer.BEGIN, text)
        self.assertIn(installer.END, text)
        self.assertIn('# Canonical Copilot instructions', text)
        self.assertIn('`ai_orch memory search`', text)

    def test_refreshes_managed_block_from_current_canonical_template(self):
        self.install()
        source = self.repo / '.github' / 'copilot-instructions.md'
        source.write_text('# New canonical instructions\n\nlatest\n')
        self.assertEqual(self.install()[0], 0)
        text = self.target.read_text()
        self.assertIn('# New canonical instructions', text)
        self.assertIn('latest', text)
        self.assertNotIn('# Canonical Copilot instructions', text)

    def test_repository_copilot_entrypoints_share_ask_user_gates(self):
        root = Path(__file__).resolve().parents[1]
        entrypoints = [
            root / '.github' / 'copilot-instructions.md',
            root / 'skills' / 'copilot' / 'session-instructions.md',
        ]
        # The shared Claude/Codex contract only routes Copilot to its own terminal contract.
        shared = (root / 'skills' / 'session-instructions.md').read_text(encoding='utf-8')
        self.assertIn('`ai_orch`', shared)
        self.assertIn('`ask_user`', shared)
        self.assertIn('skills/copilot/session-instructions.md', shared)
        for path in entrypoints:
            text = path.read_text(encoding='utf-8')
            self.assertIn('`ask_user`', text, path)
            self.assertIn('global ve proje kurallarını yükleyeyim mi?', text, path)
            self.assertIn('Hayır, bu oturumda atla', text, path)
            self.assertIn('do not ask again', text.lower(), path)

    def test_preserves_personal_text_byte_for_byte(self):
        self.target.parent.mkdir(parents=True)
        personal = '# My rules\n\n- use tabs\n'
        self.target.write_text(personal)
        self.assertEqual(self.install()[0], 0)
        self.assertTrue(self.target.read_text().startswith(personal))

    def test_idempotent_and_check_clean(self):
        self.install()
        before = self.target.read_text()
        self.assertEqual(self.install()[0], 0)
        self.assertEqual(self.target.read_text(), before)
        self.assertEqual(self.install('--check')[0], 0)

    def test_check_reports_drift_without_writing(self):
        self.install()
        text = self.target.read_text().replace(
            '# Canonical Copilot instructions',
            'Run scans freely')
        self.target.write_text(text)
        code, _ = self.install('--check')
        self.assertEqual(code, 1)
        self.assertEqual(self.target.read_text(), text)
        self.assertEqual(self.install()[0], 0)
        self.assertIn('# Canonical Copilot instructions', self.target.read_text())

    def test_dry_run_writes_nothing(self):
        code, output = self.install('--dry-run')
        self.assertEqual(code, 0)
        self.assertIn(installer.BEGIN, output)
        self.assertFalse(self.target.exists())

    def test_duplicate_markers_conflict(self):
        self.target.parent.mkdir(parents=True)
        self.target.write_text(f'{installer.BEGIN}\nx\n{installer.END}\n{installer.BEGIN}\ny\n{installer.END}\n')
        before = self.target.read_text()
        code, output = self.install()
        self.assertEqual(code, 2)
        self.assertIn('duplicate managed markers', output)
        self.assertEqual(self.target.read_text(), before)

    def test_reversed_markers_conflict(self):
        self.target.parent.mkdir(parents=True)
        self.target.write_text(f'{installer.END}\nx\n{installer.BEGIN}\n')
        self.assertEqual(self.install()[0], 2)

    def test_symlinked_instruction_file_conflicts(self):
        self.target.parent.mkdir(parents=True)
        decoy = self.project / 'decoy.md'
        decoy.write_text('personal\n')
        self.target.symlink_to(decoy)
        code, output = self.install()
        self.assertEqual(code, 2)
        self.assertIn('symlink', output)
        self.assertEqual(decoy.read_text(), 'personal\n')

    def test_symlinked_github_directory_conflicts(self):
        elsewhere = Path(self.tmp.name) / 'elsewhere'
        elsewhere.mkdir()
        (self.project / '.github').symlink_to(elsewhere, target_is_directory=True)
        code, output = self.install()
        self.assertEqual(code, 2)
        self.assertIn('symlink', output)
        self.assertEqual(list(elsewhere.iterdir()), [])

    def test_non_git_target_rejected_unless_opted_in(self):
        plain = Path(self.tmp.name) / 'plain'
        plain.mkdir()
        code, output = run('--target', str(plain), '--repo', str(self.repo))
        self.assertEqual(code, 2)
        self.assertIn('not a git repository', output)
        self.assertFalse((plain / '.github').exists())
        self.assertEqual(run('--target', str(plain), '--repo', str(self.repo), '--allow-non-git')[0], 0)
        self.assertTrue((plain / '.github' / 'copilot-instructions.md').is_file())

    def test_defaults_to_the_git_root_of_the_working_directory(self):
        nested = self.project / 'src' / 'deep'
        nested.mkdir(parents=True)
        previous = Path.cwd()
        os.chdir(nested)
        try:
            code, _ = run('--repo', str(self.repo))
        finally:
            os.chdir(previous)
        self.assertEqual(code, 0)
        self.assertIn(installer.BEGIN, self.target.read_text())

    def test_source_repository_does_not_append_its_own_template(self):
        code, _ = run('--repo', str(self.repo), '--target', str(self.repo))
        self.assertEqual(code, 0)
        self.assertNotIn(installer.BEGIN, (self.repo / '.github' / 'copilot-instructions.md').read_text())

    def test_default_without_a_git_root_is_rejected(self):
        plain = Path(self.tmp.name) / 'loose'
        plain.mkdir()
        previous = Path.cwd()
        os.chdir(plain)
        try:
            code, output = run('--repo', str(self.repo))
        finally:
            os.chdir(previous)
        self.assertEqual(code, 2)
        self.assertIn('no git repository found', output)
        self.assertFalse((plain / '.github').exists())

    def test_links_project_skills_into_dot_github(self):
        self.assertEqual(self.install()[0], 0)
        directory = self.project / '.github' / 'skills'
        self.assertEqual(sorted(p.name for p in directory.iterdir()), sorted(self.skill_names))
        for name in self.skill_names:
            link = directory / name
            self.assertTrue(link.is_symlink())
            self.assertEqual(link.resolve(), (self.repo / 'skills' / 'copilot' / name).resolve())
            self.assertTrue((link / 'SKILL.md').is_file())

    def test_skill_links_are_idempotent(self):
        self.install()
        self.assertEqual(self.install()[0], 0)
        self.assertEqual(self.install('--check')[0], 0)

    def test_no_skills_flag_skips_the_skill_directory(self):
        self.assertEqual(self.install('--no-skills')[0], 0)
        self.assertFalse((self.project / '.github' / 'skills').exists())

    def test_missing_skill_link_is_drift(self):
        self.install()
        (self.project / '.github' / 'skills' / self.skill_names[0]).unlink()
        self.assertEqual(self.install('--check')[0], 1)
        self.assertEqual(self.install()[0], 0)
        self.assertTrue((self.project / '.github' / 'skills' / self.skill_names[0]).is_symlink())

    def test_foreign_skill_entry_conflicts(self):
        directory = self.project / '.github' / 'skills'
        directory.mkdir(parents=True)
        foreign = directory / self.skill_names[0]
        foreign.mkdir()
        (foreign / 'SKILL.md').write_text('someone else\n')
        code, output = self.install()
        self.assertEqual(code, 2)
        self.assertIn('not an owned symlink', output)
        self.assertEqual((foreign / 'SKILL.md').read_text(), 'someone else\n')
        self.assertFalse(self.target.exists())

    def test_relocated_skill_symlink_conflicts(self):
        directory = self.project / '.github' / 'skills'
        directory.mkdir(parents=True)
        elsewhere = Path(self.tmp.name) / 'elsewhere'
        elsewhere.mkdir()
        (directory / self.skill_names[0]).symlink_to(elsewhere, target_is_directory=True)
        code, output = self.install()
        self.assertEqual(code, 2)
        self.assertIn('foreign or relocated skill symlink', output)

    def test_uninstall_removes_owned_links_only(self):
        self.install()
        directory = self.project / '.github' / 'skills'
        foreign = directory / 'someone-elses-skill'
        foreign.mkdir()
        self.assertEqual(self.install('--uninstall')[0], 0)
        self.assertEqual([p.name for p in directory.iterdir()], ['someone-elses-skill'])
        self.assertTrue(foreign.is_dir())

    def test_missing_target_rejected(self):
        code, output = run('--target', str(Path(self.tmp.name) / 'nope'), '--repo', str(self.repo))
        self.assertEqual(code, 2)
        self.assertIn('cannot be resolved', output)

    def test_missing_workflow_source_rejected(self):
        (self.repo / 'skills' / 'copilot' / 'session-instructions.md').unlink()
        code, output = self.install()
        self.assertEqual(code, 2)
        self.assertIn('startup workflow is missing', output)
        self.assertFalse(self.target.exists())

    def test_second_target_conflict_writes_nothing(self):
        broken = Path(self.tmp.name) / 'broken'
        (broken / '.git').mkdir(parents=True)
        (broken / '.github').mkdir()
        (broken / '.github' / 'copilot-instructions.md').write_text(
            f'{installer.BEGIN}\nx\n{installer.END}\n{installer.BEGIN}\ny\n{installer.END}\n')
        code, _ = run('--target', str(self.project), '--target', str(broken), '--repo', str(self.repo))
        self.assertEqual(code, 2)
        self.assertFalse(self.target.exists())

    def test_duplicate_target_rejected(self):
        code, output = run('--target', str(self.project), '--target', str(self.project), '--repo', str(self.repo))
        self.assertEqual(code, 2)
        self.assertIn('duplicate target', output)

    def test_uninstall_removes_only_the_block(self):
        self.target.parent.mkdir(parents=True)
        personal = '# My rules\n\n- use tabs\n'
        self.target.write_text(personal)
        self.install()
        self.assertEqual(self.install('--uninstall')[0], 0)
        self.assertEqual(self.target.read_text(), personal)

    def test_uninstall_leaves_file_when_only_block(self):
        self.install()
        self.assertEqual(self.install('--uninstall')[0], 0)
        self.assertTrue(self.target.is_file())
        self.assertEqual(self.target.read_text(), '')
        self.assertEqual(self.install('--uninstall')[0], 0)

    def test_stale_temporary_file_conflicts(self):
        self.target.parent.mkdir(parents=True)
        self.target.with_name(self.target.name + '.ai-orch-tmp').write_text('stale')
        code, output = self.install()
        self.assertEqual(code, 2)
        self.assertIn('temporary file already exists', output)
        self.assertFalse(self.target.exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
