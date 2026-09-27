#!/usr/bin/env python3
"""Bind a repository to AI Orchestration for the JetBrains/IntelliJ Copilot plugin.

The plugin reads neither ~/.copilot/copilot-instructions.md nor ~/.copilot/skills:
its instruction surface is <repo>/.github/copilot-instructions.md and its skill
surface is <repo>/.github/skills/ (global skills are not detected). So this
installer owns two things inside the target repository:

* one marked block in .github/copilot-instructions.md, and
* checkout-linked symlinks under .github/skills/ for every Copilot skill.

Run it with no arguments from inside the target repository; it installs into the
nearest .git root of the working directory. --target is only for driving other
repositories from elsewhere.

Skills are symlinks into this checkout, never copies, so edits to the source
skills apply everywhere at once. Use --dry-run for the exact plan and diff,
--check for drift, --uninstall to remove only owned artifacts. Text outside the
markers is preserved byte for byte. No network calls.
Exit codes: 0 ok, 1 drift (--check only), 2 conflict.
"""
from __future__ import annotations

import argparse
import difflib
from pathlib import Path
import sys

BEGIN = '<!-- ai-orchestration:copilot-project:begin -->'
END = '<!-- ai-orchestration:copilot-project:end -->'
INSTRUCTIONS = Path('.github') / 'copilot-instructions.md'
SKILLS = Path('.github') / 'skills'


class Conflict(Exception):
    pass


def block(repo: Path) -> str:
    template = repo / INSTRUCTIONS
    if not template.is_file() or template.is_symlink():
        raise Conflict(f'canonical Copilot instructions are missing or invalid: {template}')
    with template.open(encoding='utf-8', newline='') as stream:
        instructions = stream.read().strip()
    if BEGIN in instructions or END in instructions:
        raise Conflict(f'canonical Copilot instructions contain managed markers: {template}')
    return f"""{BEGIN}
{instructions}
{END}"""


def exists(path: Path) -> bool:
    return path.exists() or path.is_symlink()


def text_file(path: Path) -> str:
    if path.is_symlink():
        raise Conflict(f'instruction file is a symlink: {path}')
    if exists(path) and not path.is_file():
        raise Conflict(f'expected regular file: {path}')
    if not path.exists():
        return ''
    with path.open(newline='') as stream:
        return stream.read()


def managed_span(text: str) -> tuple[int, int] | None:
    if BEGIN not in text and END not in text:
        return None
    if text.count(BEGIN) != 1 or text.count(END) != 1 or text.index(END) < text.index(BEGIN):
        raise Conflict('malformed or duplicate managed markers')
    return text.index(BEGIN), text.index(END) + len(END)


def default_target() -> Path:
    """The nearest Git root of the working directory, so a bare run needs no flag."""
    try:
        cwd = Path.cwd().resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise Conflict('the working directory cannot be resolved') from exc
    for candidate in (cwd, *cwd.parents):
        if exists(candidate / '.git'):
            return candidate
    raise Conflict(f'no git repository found at or above {cwd}; pass --target or --allow-non-git')


def resolve_root(raw: Path, allow_non_git: bool) -> Path:
    try:
        root = raw.expanduser().resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise Conflict(f'target cannot be resolved: {raw}') from exc
    if not root.is_dir():
        raise Conflict(f'target is not a directory: {root}')
    if not allow_non_git and not exists(root / '.git'):
        raise Conflict(f'target is not a git repository (use --allow-non-git): {root}')
    # Do not follow a redirected configuration directory while mutating it.
    for relative in (INSTRUCTIONS, SKILLS):
        for parent in list((root / relative).parents)[:len(relative.parts) - 1]:
            if parent.is_symlink():
                raise Conflict(f'configuration parent is a symlink: {parent}')
            if exists(parent) and not parent.is_dir():
                raise Conflict(f'configuration parent is not a directory: {parent}')
    return root


def skill_sources(repo: Path) -> list[Path]:
    sources = sorted(p.parent for p in (repo / 'skills' / 'copilot').glob('*/SKILL.md'))
    if not sources:
        raise Conflict(f'no Copilot skills found under {repo / "skills" / "copilot"}')
    return sources


def plan_instructions(root: Path, repo: Path, uninstall: bool) -> list[tuple]:
    target = root / INSTRUCTIONS
    if not uninstall and root.resolve() == repo.resolve():
        print(f'in sync: {target} (canonical source)')
        return []
    current = text_file(target)
    span = managed_span(current)
    if uninstall:
        if not span:
            print(f'no managed block: {target}')
            return []
        # Drop the blank-line framing this installer added around its block.
        prefix, suffix = current[:span[0]].rstrip('\n'), current[span[1]:].lstrip('\n')
        if not prefix and not suffix:
            desired = ''
        elif prefix and suffix:
            desired = f'{prefix}\n\n{suffix}'
        else:
            desired = f'{prefix or suffix}\n'
    elif span:
        desired = current[:span[0]] + block(repo) + current[span[1]:]
    else:
        separator = '\n\n' if current and not current.endswith('\n\n') else ''
        desired = current + separator + block(repo) + '\n'
    if exists(target.with_name(target.name + '.ai-orch-tmp')):
        raise Conflict(f'temporary file already exists for {target}')
    if desired == current:
        print(f'in sync: {target}')
        return []
    print(''.join(difflib.unified_diff(
        current.splitlines(True), desired.splitlines(True),
        fromfile=str(target), tofile=str(target) + ' (proposed)')))
    return [('write', target, desired)]


def plan_skills(root: Path, repo: Path, uninstall: bool) -> list[tuple]:
    directory = root / SKILLS
    sources = {source.name: source for source in skill_sources(repo)}
    actions: list[tuple] = []
    if uninstall:
        if not directory.is_dir():
            return []
        for entry in sorted(directory.iterdir()):
            if entry.is_symlink() and entry.name in sources and entry.resolve() == sources[entry.name]:
                actions.append(('unlink', entry, None))
        return actions
    for name, source in sources.items():
        link = directory / name
        if link.is_symlink():
            if link.resolve() != source:
                raise Conflict(f'foreign or relocated skill symlink: {link}')
            print(f'in sync: {link}')
            continue
        if exists(link):
            raise Conflict(f'skill path exists and is not an owned symlink: {link}')
        actions.append(('link', link, source))
    return actions


def plan(args) -> list[tuple]:
    repo = args.repo.resolve()
    if not (repo / 'skills/copilot/session-instructions.md').is_file():
        raise Conflict(f'canonical Copilot startup workflow is missing under {repo}')
    if not (repo / INSTRUCTIONS).is_file():
        raise Conflict(f'canonical Copilot instructions are missing under {repo}')
    raws = args.target or [default_target() if not args.allow_non_git else Path.cwd()]
    actions: list[tuple] = []
    seen: set[Path] = set()
    for raw in raws:  # complete all preflight before any write
        root = resolve_root(raw, args.allow_non_git)
        if root in seen:
            raise Conflict(f'duplicate target: {root}')
        seen.add(root)
        actions += plan_instructions(root, repo, args.uninstall)
        if not args.no_skills:
            actions += plan_skills(root, repo, args.uninstall)
    return actions


def atomic_write(target: Path, data: str) -> None:
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


def apply(actions: list[tuple]) -> None:
    done: list[tuple] = []
    try:
        for kind, target, data in actions:
            if kind == 'write':
                previous = text_file(target) if target.exists() else None
                target.parent.mkdir(parents=True, exist_ok=True)
                atomic_write(target, data)
                done.append(('write', target, previous))
            elif kind == 'link':
                target.parent.mkdir(parents=True, exist_ok=True)
                target.symlink_to(data, target_is_directory=True)
                done.append(('unlink', target, None))
            elif kind == 'unlink':
                source = target.resolve()
                target.unlink()
                done.append(('link', target, source))
    except (OSError, Conflict):
        # Restore completed mutations; untouched files stay untouched.
        for kind, target, previous in reversed(done):
            if kind == 'write':
                if previous is None:
                    target.unlink(missing_ok=True)
                else:
                    atomic_write(target, previous)
            elif kind == 'unlink':
                target.unlink(missing_ok=True)
            elif kind == 'link':
                target.symlink_to(previous, target_is_directory=True)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--target', type=Path, action='append',
                        help='repository root to install into; repeatable. '
                             'Defaults to the nearest git root of the working directory')
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1],
                        help='AI Orchestration checkout the block and skills point at')
    parser.add_argument('--allow-non-git', action='store_true')
    parser.add_argument('--no-skills', action='store_true',
                        help='install only the instruction block')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--check', action='store_true')
    mode.add_argument('--dry-run', action='store_true')
    mode.add_argument('--uninstall', action='store_true')
    args = parser.parse_args()
    try:
        actions = plan(args)
        for kind, target, _ in actions:
            print(f'{kind}: {target}')
        if args.check or args.dry_run:
            return 1 if args.check and actions else 0
        apply(actions)
        if actions and not args.uninstall:
            print('note: .github/copilot-instructions.md and .github/skills are inside the target '
                  'repository, so git sees them; the skills are symlinks into this checkout. '
                  'Enable Settings > GitHub Copilot > Chat > Agent > Agent Skills in the IDE. '
                  'A written file is not proof the plugin loaded it; verify in a real session.')
        return 0
    except (Conflict, OSError) as exc:
        print(f'CONFLICT: {exc}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
