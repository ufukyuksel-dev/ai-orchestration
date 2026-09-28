#!/usr/bin/env python3
"""Install checkout-linked Codex/Claude skills and the lean owned session-contract block.

The block inlines skills/session-instructions.md, so no extra instruction read is needed at startup.
Legacy sections this project authored (MCP Lifecycle, Provider Selection, Project Map/Defaults) are
removed only when their normalized hash is in scripts/legacy_instruction_hashes.json; unknown text in
those sections is preserved and blocks the install unless --adopt-legacy-section is given. Every removal
keeps a byte-exact backup that --uninstall restores. No hooks are installed. No network calls.
Use --dry-run for the exact diff, --check for drift.
"""
import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import sys

BEGIN = '<!-- ai-orchestration:startup:begin -->'
END = '<!-- ai-orchestration:startup:end -->'
RECORD = '.ai-orchestration-install.json'
OWNED_LEGACY_SECTIONS = ('Approved behavioral instructions', 'MCP Lifecycle', 'Provider Selection Protocol',
                         'Project Map & Conventions', 'Project Defaults')


class Conflict(Exception):
    pass


def exists(path):
    return path.exists() or path.is_symlink()


def text_file(path):
    if path.is_symlink():
        raise Conflict(f'instruction/record file is a symlink: {path}')
    if exists(path) and not path.is_file():
        raise Conflict(f'expected regular file: {path}')
    if not path.exists():
        return ''
    with path.open(newline='') as stream:
        return stream.read()


def same_tree(a, b):
    def files(root):
        return {str(p.relative_to(root)): p for p in root.rglob('*') if not p.is_dir()}
    aa, bb = files(a), files(b)
    return aa.keys() == bb.keys() and all(not aa[k].is_symlink() and not bb[k].is_symlink()
                                        and aa[k].read_bytes() == bb[k].read_bytes() for k in aa)


def relocated_link(target, runtime, repo, previous):
    """True if target links to this skill in another checkout of this project (the checkout moved)."""
    old = Path(os.readlink(target))
    if not old.is_absolute():
        old = target.parent / old
    if len(old.parts) < 5 or old.parts[-3:] != ('skills', runtime, target.name):
        return False
    old_repo = old.parents[2]
    if old_repo == repo:
        return False
    return (previous is not None and previous['source'] == str(old)) or (old_repo / 'skills/session-instructions.md').is_file()


def managed_span(text):
    if not text.count(BEGIN) and not text.count(END):
        return None
    if text.count(BEGIN) != 1 or text.count(END) != 1 or text.index(END) < text.index(BEGIN):
        raise Conflict('malformed or duplicate startup markers')
    return text.index(BEGIN), text.index(END) + len(END)


def block(repo, runtime):
    # Claude Code gets its session start from the prompt hook, so its block has no session.bootstrap step
    hook_contract = repo / 'skills' / 'session-instructions-claude-hook.md'
    source = hook_contract if runtime == 'claude' and hook_contract.exists() else repo / 'skills' / 'session-instructions.md'
    contract = source.read_text()
    contract = contract.replace('`skills/contracts/` (relative to this file)', f'`{repo / "skills" / "contracts"}/`')
    lines = contract.strip().split('\n')
    if lines and lines[0].startswith('# '):
        lines[0] = '#' + lines[0]
    return f'{BEGIN}\n' + '\n'.join(lines) + f'\n{END}'


def normalized_hash(text):
    return hashlib.sha256(' '.join(text.split()).encode()).hexdigest()


def normalized_line(line):
    line = re.sub(r'`/[^`]*/skills/session-instructions\.md`', '`skills/session-instructions.md`', line)
    line = line.replace('the included `skills/', '`skills/')
    return ' '.join(line.split())


def known_legacy_lines(repo):
    path = repo / 'scripts' / 'legacy_instruction_hashes.json'
    if not path.is_file():
        return {name: set() for name in OWNED_LEGACY_SECTIONS}
    data = json.loads(path.read_text())
    return {name: set(data.get('lines', {}).get(name, [])) for name in OWNED_LEGACY_SECTIONS}


def foreign_lines(body, known_lines):
    """Lines of a legacy section that no repo revision of that section contains (i.e. user-authored)."""
    out = []
    for line in body.splitlines()[1:]:
        if line.strip() and hashlib.sha256(normalized_line(line).encode()).hexdigest() not in known_lines:
            out.append(line.strip())
    return out


def known_legacy_hashes(repo):
    path = repo / 'scripts' / 'legacy_instruction_hashes.json'
    if not path.is_file():
        return {name: set() for name in OWNED_LEGACY_SECTIONS}
    data = json.loads(path.read_text())
    return {name: set(data.get('sections', {}).get(name, [])) for name in OWNED_LEGACY_SECTIONS}


def legacy_sections(text):
    """(name, start, end) of owned-name '## ' sections outside the managed block."""
    found = []
    span = managed_span(text)
    for match in re.finditer(r'^## (.+?)[ \t\r]*$', text, re.M):
        name = match.group(1)
        if name not in OWNED_LEGACY_SECTIONS:
            continue
        if span and span[0] <= match.start() < span[1]:
            continue
        nxt = re.compile(r'^(?:#{1,2} |' + re.escape(BEGIN) + ')', re.M).search(text, match.end())
        end = nxt.start() if nxt else len(text)
        found.append((name, match.start(), end))
    return found


def sha256_text(text):
    return hashlib.sha256(text.encode()).hexdigest()


def load_record(home):
    raw = text_file(home / RECORD)
    if not raw:
        return {'version': 2, 'links': [], 'instructions': []}
    try:
        data = json.loads(raw)
        if data['version'] not in (1, 2) or not isinstance(data['links'], list) or not isinstance(data['instructions'], list):
            raise ValueError('unsupported ownership record')
        return data
    except (ValueError, KeyError, TypeError) as exc:
        raise Conflict(f'invalid ownership record: {exc}') from exc


def validate_record(record, allowed_links, allowed_instructions, backups):
    if len({r.get('target') for r in record['links'] if isinstance(r, dict)}) != len(record['links']):
        raise Conflict('invalid or duplicate owned link records')
    if len({r.get('target') for r in record['instructions'] if isinstance(r, dict)}) != len(record['instructions']):
        raise Conflict('invalid or duplicate instruction records')
    for row in record['links']:
        if not isinstance(row, dict) or not isinstance(row.get('source'), str):
            raise Conflict('invalid owned link record')
        if row.get('target') not in allowed_links:
            raise Conflict('ownership record has an unexpected skill target')
        if row.get('backup') not in (None, row['target'] + '.ai-orch-backup', str(backups[row['target']])):
            raise Conflict('ownership record has an unexpected backup')
    for row in record['instructions']:
        if not isinstance(row, dict):
            raise Conflict('invalid instruction record')
        if row.get('target') not in allowed_instructions or not isinstance(row.get('block'), str):
            raise Conflict('ownership record has an unexpected instruction target')
        if managed_span(row['block']) != (0, len(row['block'])):
            raise Conflict('ownership record has an invalid block')
        if row.get('legacyBackup') is not None and not isinstance(row.get('writtenSha'), str):
            raise Conflict('ownership record has a legacy backup without a written-content hash')


def plan(args):
    home, repo = args.home.resolve(), args.repo.resolve()
    codex_home = args.codex_home.expanduser().resolve() if args.codex_home else home / '.codex'
    record = load_record(home)
    sources = []
    backups = {}
    for runtime, dest in [('codex', codex_home / 'skills'), ('claude', home / '.claude' / 'skills')]:
        paths = sorted((repo / 'skills' / runtime).glob('*/SKILL.md'))
        if not paths:
            raise Conflict(f'no {runtime} skills in {repo}')
        sources += [(p.parent, dest / p.parent.name, runtime) for p in paths]
        backups.update({str(dest / p.parent.name): home / '.ai-orchestration-backups' / f'{runtime}-skills' / p.parent.name for p in paths})
    if not (repo / 'skills/session-instructions.md').is_file():
        raise Conflict('canonical startup workflow is missing')
    codex_instruction = codex_home / 'AGENTS.md'
    override = codex_home / 'AGENTS.override.md'
    if text_file(override).strip():
        codex_instruction = override
    targets = [(codex_instruction, 'codex'), (home / '.claude/CLAUDE.md', 'claude')]
    validate_record(record, {str(t) for _, t, _ in sources},
                    {str(codex_home / n) for n in ['AGENTS.md', 'AGENTS.override.md']} | {str(home / '.claude/CLAUDE.md')}, backups)
    # Do not follow redirected configuration directories while mutating them.
    for target in [t for _, t, _ in sources] + [t for t, _ in targets] + list(backups.values()):
        for parent in target.parents:
            if parent.is_symlink():
                raise Conflict(f'configuration parent is a symlink: {parent}')
            if exists(parent) and not parent.is_dir():
                raise Conflict(f'configuration parent is not a directory: {parent}')
    actions, new = [], {'version': 2, 'links': [], 'instructions': []}
    old_links = {r['target']: r for r in record['links']}
    for source, target, runtime in sources:
        previous = old_links.get(str(target))
        if args.uninstall:
            if previous:
                if not target.is_symlink() or str(target.resolve()) != previous['source']:
                    raise Conflict(f'owned link was changed: {target}')
                backup = Path(previous['backup']) if previous.get('backup') else None
                if backup and (not backup.is_dir() or backup.is_symlink()):
                    raise Conflict(f'owned backup is missing/changed: {backup}')
                actions.append(('unlink', target, backup))
            continue
        backup = previous.get('backup') if previous else None
        if previous and backup and (not Path(backup).is_dir() or Path(backup).is_symlink()):
            raise Conflict(f'owned backup is missing: {backup}')
        if target.is_symlink() and target.resolve() != source and relocated_link(target, runtime, repo, previous):
            # The checkout moved: point the link at this checkout and take ownership of it.
            print(f'INFO: relinking {target} from {os.readlink(target)} to this checkout')
            actions.append(('relink', target, source))
        elif target.is_symlink():
            if target.resolve() != source:
                raise Conflict(f'foreign or relocated symlink: {target}')
            if previous and previous['source'] != str(source):
                raise Conflict(f'ownership source mismatch: {target}')
            # Identical foreign links are usable but not adopted/deleted by uninstall.
            if not previous:
                continue
        elif exists(target):
            if previous:
                raise Conflict(f'owned link was replaced: {target}')
            if not target.is_dir() or not same_tree(target, source):
                raise Conflict(f'divergent copied skill: {target}')
            backup = str(backups[str(target)])
            if exists(Path(backup)):
                raise Conflict(f'backup already exists: {backup}')
            actions.append(('link', target, (source, Path(backup))))
        else:
            actions.append(('link', target, (source, None)))
        if backup == str(target) + '.ai-orch-backup':
            destination = backups[str(target)]
            if exists(destination):
                raise Conflict(f'backup destination already exists: {destination}')
            actions.append(('move', Path(backup), destination))
            backup = str(destination)
        new['links'].append({'target': str(target), 'source': str(source), 'backup': backup})
    old_instructions = {r['target']: r for r in record['instructions']}
    if not args.uninstall and any(k not in {str(t) for t, _ in targets} for k in old_instructions):
        raise Conflict('effective instruction file changed; uninstall previous integration before switching override')
    if args.uninstall:
        targets = [(Path(k), '') for k in old_instructions]
    known = known_legacy_hashes(repo)
    known_lines = known_legacy_lines(repo)
    for target, runtime in targets:
        current = text_file(target)
        span = managed_span(current)
        previous = old_instructions.get(str(target))
        if previous and not exists(target):
            # The whole file was deleted since install: nothing left to own or restore.
            print(f'INFO: {target} no longer exists; forgetting its old ownership record')
            previous = None
            if args.uninstall:
                continue
        if span and (not previous or current[span[0]:span[1]] != previous['block']):
            raise Conflict(f'unowned or edited managed block: {target}')
        if previous and not span:
            raise Conflict(f'owned managed block is missing: {target}')
        legacy_backup = previous.get('legacyBackup') if previous else None
        if args.uninstall:
            if legacy_backup:
                if sha256_text(current) != previous['writtenSha']:
                    raise Conflict(f'{target} changed after install; restore it manually from {legacy_backup}')
                backup_path = Path(legacy_backup)
                if not backup_path.is_file() or backup_path.is_symlink():
                    raise Conflict(f'legacy backup is missing: {backup_path}')
                desired = text_file(backup_path)
            else:
                desired = current[:span[0]] + current[span[1]:]
        else:
            desired_block = block(repo, runtime)
            removable, unknown = [], []
            for name, start, end in legacy_sections(current):
                body = current[start:end]
                # Project-authored if the whole section matches a repo revision, or every line of it does
                # (older installs copied our text with an absolutised path or dropped lines).
                foreign = [] if normalized_hash(body) in known[name] else foreign_lines(body, known_lines[name])
                (removable if not foreign or args.adopt_legacy_section else unknown).append((name, start, end, foreign))
            if unknown:
                detail = '; '.join(f'{name}: ' + ' | '.join(lines[:3]) for name, _, _, lines in unknown)
                raise Conflict(f'{target} has legacy section text not authored by this project: {detail}. '
                               'Move those lines elsewhere, or review and rerun with --adopt-legacy-section.')
            removable = [(name, start, end) for name, start, end, _ in removable]
            desired = current
            anchor = None
            for name, start, end in sorted(removable, key=lambda r: r[1], reverse=True):
                print(f'INFO: replacing legacy section "{name}" in {target}')
                desired = desired[:start] + desired[end:]
                anchor = start
            span = managed_span(desired)
            if span:
                desired = desired[:span[0]] + desired_block + desired[span[1]:]
            elif anchor is not None:
                desired = desired[:anchor] + desired_block + '\n\n' + desired[anchor:]
            else:
                desired = desired + ('\n\n' if desired else '') + desired_block
            if removable and not legacy_backup:
                backup_path = home / '.ai-orchestration-backups' / 'instructions' / f'{runtime}-{target.name}.legacy'
                if exists(backup_path) and text_file(backup_path) != current:
                    raise Conflict(f'legacy backup already exists: {backup_path}')
                if not exists(backup_path):
                    actions.append(('backup', backup_path, current))
                legacy_backup = str(backup_path)
            row = {'target': str(target), 'block': desired_block}
            if legacy_backup:
                row.update({'legacyBackup': legacy_backup, 'writtenSha': sha256_text(desired)})
            new['instructions'].append(row)
        if desired != current:
            actions.append(('write', target, desired))
            print(''.join(difflib.unified_diff(current.splitlines(True), desired.splitlines(True),
                                             fromfile=str(target), tofile=str(target) + ' (proposed)')))
    if exists((home / RECORD).with_name(RECORD + '.ai-orch-tmp')):
        raise Conflict('ownership record temporary file already exists')
    for kind, target, _ in actions:
        if kind == 'write' and exists(target.with_name(target.name + '.ai-orch-tmp')):
            raise Conflict(f'temporary file already exists for {target}')
    return home, actions, new


def report_size(args):
    home = args.home.resolve()
    codex_home = args.codex_home.expanduser().resolve() if args.codex_home else home / '.codex'
    total = 0
    for root in [codex_home, args.repo.resolve()]:
        override = root / 'AGENTS.override.md'
        target = override if override.is_file() and override.read_text().strip() else root / 'AGENTS.md'
        size = target.stat().st_size if target.is_file() else 0
        print(f'Instruction bytes {target}: {size}')
        total += size
    print(f'Visible global + repo bytes: {total}; documented default cap: 32768 (configured cap unverified)')


def atomic_write(target, data):
    temporary = target.with_name(target.name + '.ai-orch-tmp')
    with temporary.open('x', newline='') as stream:
        try:
            stream.write(data)
        except OSError:
            temporary.unlink(missing_ok=True)
            raise
    try:
        temporary.replace(target)
    except OSError:
        temporary.unlink(missing_ok=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--home', type=Path, default=Path.home())
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--codex-home', type=Path, default=Path(os.environ['CODEX_HOME']) if os.environ.get('CODEX_HOME') else None)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--check', action='store_true')
    mode.add_argument('--dry-run', action='store_true')
    mode.add_argument('--uninstall', action='store_true')
    parser.add_argument('--adopt-legacy-section', action='store_true',
                        help='back up and replace edited legacy MCP Lifecycle/Provider/Project Map sections')
    args = parser.parse_args()
    try:
        report_size(args)
        home, actions, record = plan(args)  # complete all preflight before writes
        for kind, target, data in actions:
            print(f'{kind}: {target}' + (f' -> {data}' if kind == 'move' else ''))
        if args.check or args.dry_run:
            return 1 if args.check and actions else 0
        applied = []
        try:
            for kind, target, data in actions:
                target.parent.mkdir(parents=True, exist_ok=True)
                if kind == 'backup':
                    with target.open('x', newline='') as stream:
                        stream.write(data)
                    applied.append(('write', target, None))
                elif kind == 'write':
                    old = text_file(target) if target.exists() else None
                    atomic_write(target, data)
                    applied.append(('write', target, old))
                elif kind == 'move':
                    data.parent.mkdir(parents=True, exist_ok=True)
                    target.rename(data)
                    applied.append(('move', data, target))
                elif kind == 'link':
                    source, backup = data
                    if backup:
                        backup.parent.mkdir(parents=True, exist_ok=True)
                        target.rename(backup)
                    applied.append(('unlink', target, backup))
                    target.symlink_to(source, target_is_directory=True)
                elif kind == 'relink':
                    old = os.readlink(target)
                    target.unlink()
                    applied.append(('restore-link', target, old))
                    target.symlink_to(data, target_is_directory=True)
                elif kind == 'unlink':
                    source = target.resolve()
                    target.unlink()
                    applied.append(('relink', target, (source, data)))
                    if data:
                        data.rename(target)
            record_path = home / RECORD
            if args.uninstall:
                if record_path.exists():
                    record_path.unlink()
            else:
                home.mkdir(parents=True, exist_ok=True)
                atomic_write(record_path, json.dumps(record, indent=2) + '\n')
        except (OSError, Conflict):
            # Restore completed mutations if application fails; untouched user files stay untouched.
            for kind, target, old in reversed(applied):
                if kind == 'write':
                    if old is None:
                        target.unlink(missing_ok=True)
                    else:
                        atomic_write(target, old)
                elif kind == 'move':
                    target.rename(old)
                elif kind == 'unlink':
                    if target.is_symlink():
                        target.unlink()
                    if old:
                        old.rename(target)
                elif kind == 'restore-link':
                    if target.is_symlink():
                        target.unlink()
                    target.symlink_to(old, target_is_directory=True)
                elif kind == 'relink':
                    source, backup = old
                    if backup and target.is_dir():
                        target.rename(backup)
                    target.symlink_to(source, target_is_directory=True)
            raise
        return 0
    except (Conflict, OSError) as exc:
        print(f'CONFLICT: {exc}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
