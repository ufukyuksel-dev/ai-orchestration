import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('install_agent_instructions.py')

# Exact pre-first-request installer output; only the checkout path is parameterized.
LEGACY_CODEX_BLOCK = (
    '<!-- ai-orchestration:startup:begin -->\n'
    '## Approved session instructions\n'
    'When first using AI Orchestration through MCP or a terminal client in a session/project, '
    'read `{workflow}` and load its global/project instructions before ordinary service work, '
    'including lookup-only requests.\n'
    '<!-- ai-orchestration:startup:end -->'
)


class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.home = self.root / 'home'
        self.repo = self.root / 'repo'
        for runtime in ['codex', 'claude']:
            for name in ['example', 'ai-orchestration-learning']:
                skill = self.repo / 'skills' / runtime / name
                skill.mkdir(parents=True)
                (skill / 'SKILL.md').write_text(f'canonical {name} skill')
        (self.repo / 'skills/session-instructions.md').write_text('startup workflow')
        self.home.mkdir()

    def run_cli(self, *args, code=0):
        env = dict(os.environ)
        env.pop('CODEX_HOME', None)
        p = subprocess.run([sys.executable, str(SCRIPT), '--home', str(self.home), '--repo', str(self.repo), *args],
                           text=True, capture_output=True, env=env)
        self.assertEqual(p.returncode, code, p.stdout + p.stderr)
        return p

    def write(self, name, text):
        path = self.home / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def test_install_check_source_propagation_and_uninstall_preserve_foreign_content(self):
        foreign = '<!-- ai-developer:managed:begin -->\nKeep me\n<!-- ai-developer:managed:end -->\n'
        codex = self.write('.codex/AGENTS.md', foreign)
        self.run_cli()
        self.run_cli('--check')
        self.run_cli()
        self.assertEqual(codex.read_text().count('ai-orchestration:startup:begin'), 1)
        link = self.home / '.codex/skills/example'
        self.assertTrue(link.is_symlink())
        self.assertFalse((self.home / '.agents').exists())
        (self.repo / 'skills/codex/example/SKILL.md').write_text('changed source')
        self.assertEqual((link / 'SKILL.md').read_text(), 'changed source')
        codex.write_text(codex.read_text() + '\nLater preference\n')
        self.run_cli('--uninstall')
        self.assertFalse(link.exists())
        self.assertFalse((self.home / '.codex/skills/ai-orchestration-learning').exists())
        self.assertIn(foreign, codex.read_text())
        self.assertIn('Later preference', codex.read_text())
        self.assertNotIn('ai-orchestration:startup', codex.read_text())

    def test_unknown_legacy_section_blocks_install_until_adopted_and_uninstall_restores_bytes(self):
        legacy = b'# Personal\r\n\r\n## MCP Lifecycle\r\nCustom topK=3; no mandatory preflight\r\n\r\n## Preferences\r\nKeep this\r\n'
        codex = self.write('.codex/AGENTS.md', '')
        codex.write_bytes(legacy)
        failed = self.run_cli(code=2)
        self.assertIn('--adopt-legacy-section', failed.stderr)
        self.assertEqual(codex.read_bytes(), legacy)
        self.assertFalse((self.home / '.codex/skills').exists())
        self.run_cli('--adopt-legacy-section')
        installed = codex.read_text()
        self.assertNotIn('Custom topK=3', installed)
        self.assertIn('## Preferences', installed)
        self.assertIn('Keep this', installed)
        self.assertIn('startup workflow', installed)
        self.assertLess(installed.index('ai-orchestration:startup:begin'), installed.index('## Preferences'))
        backup = self.home / '.ai-orchestration-backups/instructions/codex-AGENTS.md.legacy'
        self.assertEqual(backup.read_bytes(), legacy)
        self.run_cli('--check')
        self.run_cli('--uninstall')
        self.assertEqual(codex.read_bytes(), legacy)

    def test_known_legacy_sections_are_replaced_and_foreign_sections_kept(self):
        known = json.loads((SCRIPT.parent / 'legacy_instruction_hashes.json').read_text())
        (self.repo / 'skills/session-instructions.md').write_text(
            '# AI Orchestration — session contract\n\nCall `session.bootstrap` once. '
            'Contracts: `skills/contracts/` (relative to this file).\n')
        (self.repo / 'scripts').mkdir(exist_ok=True)
        lifecycle = '## MCP Lifecycle\n\nOld mandatory startup: resolve, then rules.instructions.\n\n'
        provider = '## Provider Selection Protocol\n\nAsk the provider menu at session start.\n\n'
        known['sections']['MCP Lifecycle'] = [self._hash(lifecycle)]
        known['sections']['Provider Selection Protocol'] = [self._hash(provider)]
        (self.repo / 'scripts/legacy_instruction_hashes.json').write_text(json.dumps(known))
        foreign = '## Global AI Engineering Skill System\n\nKeep my skills.\n'
        original = '# Title\n\n' + lifecycle + '## MCP Memory Delete Etiquette\n\nAsk first.\n\n' + provider + foreign
        claude = self.write('.claude/CLAUDE.md', original)
        self.run_cli()
        text = claude.read_text()
        self.assertNotIn('Old mandatory startup', text)
        self.assertNotIn('Ask the provider menu', text)
        self.assertIn('## MCP Memory Delete Etiquette', text)
        self.assertIn(foreign, text)
        self.assertIn('## AI Orchestration — session contract', text)
        self.assertIn(str(self.repo / 'skills' / 'contracts'), text)
        self.run_cli('--check')
        self.run_cli()
        self.assertEqual(claude.read_text(), text)
        self.run_cli('--uninstall')
        self.assertEqual(claude.read_text(), original)

    def test_line_provenance_accepts_path_absolutised_copy_and_blocks_user_lines(self):
        import hashlib
        line_hash = lambda text: hashlib.sha256(' '.join(text.split()).encode()).hexdigest()
        repo_lines = ['Follow the contract in `skills/session-instructions.md`.', 'Search memory only when it matters.']
        known = {'sections': {}, 'lines': {'MCP Lifecycle': [line_hash(line) for line in repo_lines]}}
        (self.repo / 'scripts').mkdir(exist_ok=True)
        (self.repo / 'scripts/legacy_instruction_hashes.json').write_text(json.dumps(known))
        derived = ('## MCP Lifecycle\n\nFollow the contract in the included `/abs/checkout/skills/session-instructions.md`.\n'
                   '\n## Preferences\nKeep this\n')
        codex = self.write('.codex/AGENTS.md', derived)
        self.run_cli()
        self.assertNotIn('## MCP Lifecycle', codex.read_text())
        self.assertIn('Keep this', codex.read_text())
        self.run_cli('--uninstall')
        self.assertEqual(codex.read_text(), derived)
        mixed = derived.replace('.md`.\n', '.md`.\nMy own rule: always answer in Turkish.\n', 1)
        self.assertNotEqual(mixed, derived)
        codex.write_text(mixed)
        failed = self.run_cli(code=2)
        self.assertIn('My own rule: always answer in Turkish.', failed.stderr)
        self.assertEqual(codex.read_text(), mixed)

    def test_uninstall_refuses_to_clobber_edits_made_after_a_legacy_migration(self):
        known = {'sections': {'MCP Lifecycle': [self._hash('## MCP Lifecycle\n\nOld.\n')]}}
        (self.repo / 'scripts').mkdir(exist_ok=True)
        (self.repo / 'scripts/legacy_instruction_hashes.json').write_text(json.dumps(known))
        codex = self.write('.codex/AGENTS.md', '## MCP Lifecycle\n\nOld.\n')
        self.run_cli()
        codex.write_text(codex.read_text() + '\nLater personal note\n')
        self.assertIn('restore it manually', self.run_cli('--uninstall', code=2).stderr)
        self.assertIn('Later personal note', codex.read_text())

    @staticmethod
    def _hash(text):
        import hashlib
        return hashlib.sha256(' '.join(text.split()).encode()).hexdigest()

    def test_override_is_effective_and_both_runtimes_inline_the_contract(self):
        base = self.write('.codex/AGENTS.md', 'base remains')
        override = self.write('.codex/AGENTS.override.md', 'override preferences')
        self.run_cli()
        self.assertEqual(base.read_text(), 'base remains')
        self.assertIn('startup workflow', override.read_text())
        self.assertIn('startup workflow', (self.home / '.claude/CLAUDE.md').read_text())
        self.assertNotIn('@' + str(self.repo), (self.home / '.claude/CLAUDE.md').read_text())
        self.run_cli('--check')

    def test_upgrade_owned_conditional_startup_preserves_foreign_content(self):
        target = self.write('.codex/AGENTS.md', 'Personal preference\n')
        self.run_cli()
        record_path = self.home / '.ai-orchestration-install.json'
        record = json.loads(record_path.read_text())
        row = next(r for r in record['instructions'] if r['target'] == str(target))
        current = row['block']
        legacy = LEGACY_CODEX_BLOCK.format(workflow=self.repo / 'skills/session-instructions.md')
        target.write_text(target.read_text().replace(current, legacy))
        row['block'] = legacy
        record_path.write_text(json.dumps(record))
        self.run_cli('--check', code=1)
        self.run_cli()
        self.run_cli('--check')
        self.assertIn('Personal preference', target.read_text())
        self.assertIn('startup workflow', target.read_text())
        self.assertNotIn('When first using AI Orchestration', target.read_text())

    def test_exact_copy_backed_up_and_restored(self):
        target = self.home / '.codex/skills/example'
        target.parent.mkdir(parents=True)
        shutil.copytree(self.repo / 'skills/codex/example', target)
        self.run_cli()
        self.assertTrue(target.is_symlink())
        self.assertTrue((self.home / '.ai-orchestration-backups/codex-skills/example').is_dir())
        for runtime in ['codex', 'claude']:
            root = self.home / f'.{runtime}/skills'
            self.assertEqual(set(root.glob('*/SKILL.md')), {
                root / 'example/SKILL.md', root / 'ai-orchestration-learning/SKILL.md'})
        self.run_cli('--uninstall')
        self.assertFalse(target.is_symlink())
        self.assertEqual((target / 'SKILL.md').read_text(), 'canonical example skill')

    def test_owned_legacy_backup_repair_and_conflict(self):
        target = self.home / '.codex/skills/example'
        target.parent.mkdir(parents=True)
        shutil.copytree(self.repo / 'skills/codex/example', target)
        self.run_cli()
        backup = self.home / '.ai-orchestration-backups/codex-skills/example'
        legacy = target.with_name('example.ai-orch-backup')
        backup.rename(legacy)
        record_path = self.home / '.ai-orchestration-install.json'
        record = json.loads(record_path.read_text())
        next(r for r in record['links'] if r['target'] == str(target))['backup'] = str(legacy)
        record_path.write_text(json.dumps(record))
        foreign = target.with_name('unowned.ai-orch-backup')
        foreign.mkdir()
        (foreign / 'SKILL.md').write_text('user owned')
        self.run_cli('--check', code=1)
        self.assertIn(f'move: {legacy} -> {backup}', self.run_cli('--dry-run').stdout)
        self.assertTrue(legacy.exists())
        backup.mkdir()
        self.run_cli(code=2)
        self.assertTrue(legacy.exists())
        backup.rmdir()
        import importlib.util
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location('repair_installer', SCRIPT)
        installer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(installer)
        argv = [str(SCRIPT), '--home', str(self.home), '--repo', str(self.repo), '--codex-home', str(self.home / '.codex')]
        before = record_path.read_bytes()
        with patch.object(sys, 'argv', argv), patch.object(installer, 'atomic_write', side_effect=OSError('record write failed')):
            self.assertEqual(installer.main(), 2)
        self.assertTrue(legacy.is_dir())
        self.assertFalse(backup.exists())
        self.assertEqual(record_path.read_bytes(), before)
        self.assertTrue(target.is_symlink())
        self.run_cli()
        self.run_cli('--check')
        self.assertFalse(legacy.exists())
        self.assertTrue(backup.is_dir())
        self.assertEqual((foreign / 'SKILL.md').read_text(), 'user owned')
        self.run_cli('--uninstall')
        self.assertEqual((target / 'SKILL.md').read_text(), 'canonical example skill')

    def test_divergent_copy_prevents_all_writes(self):
        self.write('.claude/skills/example/SKILL.md', 'custom changes')
        self.run_cli(code=2)
        self.assertFalse((self.home / '.codex').exists())
        self.assertFalse((self.home / '.ai-orchestration-install.json').exists())

    def test_corrupt_record_or_changed_owned_block_is_conflict(self):
        self.run_cli()
        target = self.home / '.codex/AGENTS.md'
        target.write_text(target.read_text().replace('startup workflow', 'Tampered'))
        self.run_cli('--check', code=2)
        self.run_cli('--uninstall', code=2)
        self.assertTrue((self.home / '.codex/skills/example').is_symlink())

    def test_custom_codex_home_and_foreign_symlink(self):
        custom = self.home / 'custom'
        self.run_cli('--codex-home', str(custom))
        self.assertTrue((custom / 'skills/example').is_symlink())
        self.assertFalse((self.home / '.codex').exists())
        self.run_cli('--codex-home', str(custom), '--uninstall')
        target = self.home / '.codex/skills/example'
        target.parent.mkdir(parents=True)
        target.symlink_to(self.root / 'unrelated')
        self.run_cli(code=2)

    def test_apply_failure_restores_completed_links(self):
        import importlib.util
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location('instruction_installer', SCRIPT)
        installer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(installer)
        original = Path.symlink_to
        calls = []
        def fail_second(path, *args, **kwargs):
            calls.append(path)
            if len(calls) == 2:
                raise OSError('simulated disk error')
            return original(path, *args, **kwargs)
        argv = [str(SCRIPT), '--home', str(self.home), '--repo', str(self.repo), '--codex-home', str(self.home / '.codex')]
        with patch.object(sys, 'argv', argv), patch.object(Path, 'symlink_to', fail_second):
            self.assertEqual(installer.main(), 2)
        self.assertFalse((self.home / '.codex/skills/example').exists())
        self.assertFalse((self.home / '.ai-orchestration-install.json').exists())

    def test_temporary_file_conflict_is_preflight(self):
        self.write('.claude/CLAUDE.md.ai-orch-tmp', 'do not replace')
        self.run_cli(code=2)
        self.assertFalse((self.home / '.codex').exists())

    def test_repository_learning_workflows_preserve_runtime_transport_boundaries(self):
        repo = SCRIPT.parents[1]
        codex = (repo / 'skills/codex/ai-orchestration-learning/SKILL.md').read_text()
        claude = (repo / 'skills/claude/ai-orchestration-learning/SKILL.md').read_text()
        copilot = (repo / 'skills/copilot/ai-orchestration-learning/SKILL.md').read_text()
        for text in (codex, claude):
            self.assertIn('memory.learn(rootPath, candidates)', text)
            self.assertIn('at most once', text)
            self.assertNotIn('ai_orch', text)
            self.assertNotIn('ai-orch-learning', text)
        self.assertIn('memory learn --file memory-learn.json --consume', copilot)
        self.assertIn('native MCP', copilot)

        safe_change = (repo / 'skills/copilot/changing-code-safely/SKILL.md').read_text()
        self.assertIn('Focused fast path', safe_change)
        self.assertNotIn('plan check', safe_change)
        self.assertIn('security/auth/permission change', safe_change)

        focused_verification = (repo / 'skills/copilot/implementation-verification/SKILL.md').read_text()
        self.assertIn('run the user-requested repository test command once', focused_verification)
        self.assertIn('Do not read external checklist files', focused_verification)

if __name__ == '__main__':
    unittest.main()
