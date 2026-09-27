#!/usr/bin/env python3
"""Install the Copilot user hook, launchers, bootstrap block and skill links.

Owned artifacts only:

* the dedicated preToolUse hook JSON,
* the ``ai-orch-memory`` and ``ai_orch`` launcher symlinks,
* one marked managed block inside the Copilot user instructions file,
* checkout-linked Copilot skill directories,
* a private request-staging directory,
* an ownership manifest describing exactly the above.

It never edits Copilot settings and never creates or removes an MCP server
registration. Personal instruction text outside the managed markers is preserved
byte for byte. ``--dry-run`` and ``--check`` mutate nothing; ``--uninstall``
removes only owned artifacts and restores owned backups.

A verified installation is not proof that the runtime invoked the hook or loaded
the skills. Confirm that separately with a real session.
"""

from __future__ import annotations

import argparse
import difflib
import json
import os
import shutil
import tempfile
from pathlib import Path
from typing import Any


HOOK_FILENAME = "ai-orchestration-safety.json"
LAUNCHER_NAME = "ai-orch-memory"
AI_ORCH_LAUNCHER_NAME = "ai_orch"
BOOTSTRAP_FILENAME = "copilot-instructions.md"
MANIFEST_FILENAME = ".ai-orchestration-copilot-install.json"
MANIFEST_VERSION = 2
BEGIN = "<!-- ai-orchestration:copilot:begin -->"
END = "<!-- ai-orchestration:copilot:end -->"
STAGING_DIRNAME = "copilot-staging"


class InstallError(RuntimeError):
    """A safe, actionable installation failure."""


# --------------------------------------------------------------------------
# source layout
# --------------------------------------------------------------------------


def integration_paths() -> tuple[Path, Path, Path]:
    scripts = Path(__file__).resolve(strict=True).parent
    guard = (scripts / "copilot_pre_tool_guard.py").resolve(strict=True)
    memory_bridge = (scripts / "memory_bridge.py").resolve(strict=True)
    ai_orch = (scripts / "ai_orch.py").resolve(strict=True)
    return guard, memory_bridge, ai_orch


def repository_root() -> Path:
    # scripts/ -> ai-orchestration-memory/ -> copilot/ -> skills/ -> repo
    return Path(__file__).resolve(strict=True).parents[4]


def skill_sources() -> list[Path]:
    root = repository_root() / "skills" / "copilot"
    if not root.is_dir():
        raise InstallError(f"Copilot skill source directory is missing: {root}")
    supported = (
        "ai-orchestration-learning",
        "ai-orchestration-memory",
        "ai-orchestration-references",
        "ai-orchestration-rules",
    )
    sources = [root / name for name in supported]
    missing = [source for source in sources if not (source / "SKILL.md").is_file()]
    if missing:
        raise InstallError(f"supported Copilot skill is missing: {missing[0]}")
    return sources


def bootstrap_source() -> Path:
    path = repository_root() / "skills" / "copilot" / "session-instructions.md"
    if not path.is_file():
        raise InstallError(f"the Copilot startup workflow is missing: {path}")
    return path


def expected_hook(guard: Path) -> dict:
    return {
        "version": 1,
        "hooks": {
            "preToolUse": [
                {
                    "type": "command",
                    "bash": str(guard),
                    "timeoutSec": 5,
                }
            ]
        },
    }


def bootstrap_block() -> str:
    workflow = bootstrap_source()
    body = (
        "## AI Orchestration (Copilot runtime)\n"
        "\n"
        "At the first request of every new session, use Copilot's built-in "
        "`ask_user` tool to ask `Bu oturum için AI Orchestration global ve proje "
        "kurallarını yükleyeyim mi?` with choices `Evet, yükle (Recommended)` and "
        "`Hayır, bu oturumda atla`. On yes, load them as stated below; on no, skip effective and module "
        "rule calls for this session/project and continue. Remember the choice and "
        "do not ask on every turn. Use the installed project skills "
        "for task-specific workflows. Do not spend a tool call reading or printing "
        f"the external installer source path `{workflow}`.\n"
        "\n"
        "- The only supported interface to AI Orchestration is the `ai_orch` terminal "
        "command. Do not register, discover or call this service's MCP tools "
        "natively, and never fall back to a native call after a terminal failure.\n"
        "- After yes, load the complete approved instructions before dependent work by running "
        "exactly `ai_orch rule instructions --scope effective` as a single shell "
        "command. Load `ai_orch rule instructions --scope module --module-path <dir>` "
        "only for a directory explicitly listed by that response; never probe an "
        "unlisted path.\n"
        "- The supported task operations are `memory search`, `memory learn`, "
        "`reference list`, `reference read`, "
        "`rule instructions`, `rule draft`, `rule preview`, and `rule promote`. "
        "`capabilities` and `doctor` are diagnostics only; do not attempt retired "
        "commands.\n"
        "- Every `ai_orch` call must be ONE direct command on its own: no `which`, "
        "no `;`, no `&&`, no `| head`, no redirection and no `$(...)`. If the hook "
        "denies a call, retry it as a single bare command instead of giving up.\n"
        "- Never run a scan, never ask which provider/model to use, never permanently "
        "delete memory and never bypass an approval gate.\n"
        "- Being run inside Copilot with a Claude model selected does not change the "
        "runtime: the Copilot terminal protocol still applies.\n"
    )
    return f"{BEGIN}\n{body}{END}"


# --------------------------------------------------------------------------
# small filesystem helpers
# --------------------------------------------------------------------------


def _exists(path: Path) -> bool:
    return path.exists() or path.is_symlink()


def _read_text(path: Path) -> str:
    if path.is_symlink():
        raise InstallError(f"expected a regular file, found a symlink: {path}")
    if _exists(path) and not path.is_file():
        raise InstallError(f"expected a regular file: {path}")
    if not path.exists():
        return ""
    with path.open(newline="", encoding="utf-8") as stream:
        return stream.read()


def _managed_span(text: str) -> tuple[int, int] | None:
    if BEGIN not in text and END not in text:
        return None
    if text.count(BEGIN) != 1 or text.count(END) != 1 or text.index(END) < text.index(BEGIN):
        raise InstallError("malformed or duplicate AI Orchestration markers")
    return text.index(BEGIN), text.index(END) + len(END)


def _apply_block(text: str, block: str) -> str:
    span = _managed_span(text)
    if span is None:
        if not text:
            return block + "\n"
        separator = "" if text.endswith("\n\n") else ("\n" if text.endswith("\n") else "\n\n")
        return text + separator + block + "\n"
    start, end = span
    return text[:start] + block + text[end:]


def _remove_block(text: str) -> str:
    span = _managed_span(text)
    if span is None:
        return text
    start, end = span
    remainder = text[:start] + text[end:]
    return remainder.rstrip("\n") + ("\n" if remainder.strip() else "")


def _atomic_write(path: Path, content: str, mode: int) -> None:
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.chmod(mode)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def _atomic_private_json(path: Path, value: dict) -> None:
    _atomic_write(path, json.dumps(value, indent=2, ensure_ascii=True) + "\n", 0o600)


def _symlink_matches(link: Path, target: Path) -> bool:
    if not link.is_symlink():
        return False
    return (link.parent / os.readlink(link)).resolve(strict=False) == target


def _owned_hook(value: object, guard: Path) -> bool:
    return value == expected_hook(guard)


def _reject_redirected_directory(path: Path) -> None:
    """Never follow a redirected configuration directory while mutating it.

    The target directory itself must not be a symlink. Parents are only checked
    inside the user's home directory, where a redirected configuration directory
    is the real risk; platform-level symlinks such as macOS ``/tmp`` and ``/var``
    are not configuration redirection and must not fail the install.
    """
    if path.is_symlink():
        raise InstallError(f"configuration directory is a symlink: {path}")
    home = Path.home()
    for parent in path.parents:
        if parent == home or parent == parent.parent:
            break
        try:
            parent.relative_to(home)
        except ValueError:
            break
        if parent.is_symlink():
            raise InstallError(f"configuration parent is a symlink: {parent}")


# --------------------------------------------------------------------------
# ownership manifest
# --------------------------------------------------------------------------


def load_manifest(copilot_home: Path) -> dict[str, Any]:
    raw = _read_text(copilot_home / MANIFEST_FILENAME)
    if not raw:
        return {"version": MANIFEST_VERSION, "hook": None, "launchers": [], "links": [], "instructions": []}
    try:
        data = json.loads(raw)
    except ValueError as exc:
        raise InstallError(f"invalid ownership manifest: {exc}") from exc
    if not isinstance(data, dict) or data.get("version") not in (1, MANIFEST_VERSION):
        raise InstallError("unsupported ownership manifest version")
    for key in ("launchers", "links", "instructions"):
        data.setdefault(key, [])
        if not isinstance(data[key], list):
            raise InstallError(f"invalid ownership manifest section: {key}")
    data.setdefault("hook", None)
    return data


# --------------------------------------------------------------------------
# planning
# --------------------------------------------------------------------------


class Plan:
    def __init__(self) -> None:
        self.actions: list[str] = []
        self.diffs: list[str] = []

    def record(self, action: str) -> None:
        self.actions.append(action)

    def diff(self, label: str, before: str, after: str) -> None:
        if before == after:
            return
        rendered = "".join(
            difflib.unified_diff(
                before.splitlines(keepends=True),
                after.splitlines(keepends=True),
                fromfile=f"a/{label}",
                tofile=f"b/{label}",
            )
        )
        if rendered:
            self.diffs.append(rendered)


def staging_directory() -> Path:
    configured = os.environ.get("AI_ORCH_STAGING_DIR")
    if configured and configured.strip():
        return Path(configured).expanduser()
    return Path.home() / ".ai-orchestration" / STAGING_DIRNAME


def backup_directory(copilot_home: Path) -> Path:
    # Backups live outside any skill discovery directory.
    return copilot_home.parent / ".ai-orchestration-backups" / "copilot"


def install(
    copilot_home: Path,
    bin_dir: Path,
    check: bool = False,
    *,
    dry_run: bool = False,
    uninstall: bool = False,
    with_bootstrap: bool = True,
    with_skills: bool = True,
    plan: Plan | None = None,
) -> tuple[Path, Path, Path]:
    guard, memory_bridge, ai_orch = integration_paths()
    # Check for redirected configuration directories on the *unresolved* path:
    # resolve() would silently follow a symlinked parent.
    raw_home = copilot_home.expanduser()
    raw_bin = bin_dir.expanduser()
    _reject_redirected_directory(raw_home)
    _reject_redirected_directory(raw_bin)
    home = raw_home.resolve(strict=False)
    launcher_dir = raw_bin.expanduser().resolve(strict=False)
    hook_dir = home / "hooks"
    hook_path = hook_dir / HOOK_FILENAME
    memory_launcher_path = launcher_dir / LAUNCHER_NAME
    ai_orch_launcher_path = launcher_dir / AI_ORCH_LAUNCHER_NAME
    launchers = (
        (memory_launcher_path, memory_bridge),
        (ai_orch_launcher_path, ai_orch),
    )
    hook_value = expected_hook(guard)
    bootstrap_path = home / BOOTSTRAP_FILENAME
    skills_dir = home / "skills"
    sources = skill_sources() if with_skills else []
    prior_manifest = load_manifest(home)
    desired_skill_targets = {str(skills_dir / source.name) for source in sources}
    stale_owned_skill_links: list[Path] = []
    if with_skills:
        for row in prior_manifest["links"]:
            if not isinstance(row, dict):
                continue
            raw_target = row.get("target")
            raw_source = row.get("source")
            if not isinstance(raw_target, str) or not isinstance(raw_source, str):
                continue
            target = Path(raw_target)
            if raw_target in desired_skill_targets or not _exists(target):
                continue
            source = Path(raw_source)
            if target.parent != skills_dir or not _symlink_matches(target, source):
                raise InstallError(f"refusing to remove a changed former skill entry: {target}")
            stale_owned_skill_links.append(target)
    plan = plan or Plan()

    if uninstall:
        return _uninstall(
            home,
            hook_path,
            launchers,
            bootstrap_path,
            skills_dir,
            sources,
            guard,
            plan,
            dry_run=dry_run,
        )

    if check:
        if hook_path.is_symlink():
            raise InstallError("the Copilot safety hook must not be a symlink")
        try:
            installed_hook = json.loads(hook_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise InstallError("the Copilot safety hook is missing or invalid") from exc
        if installed_hook != hook_value:
            raise InstallError("the Copilot safety hook does not point to this checkout")
        if hook_path.stat().st_mode & 0o077:
            raise InstallError("the Copilot safety hook permissions are broader than 0600")
        for launcher_path, target in launchers:
            if not _symlink_matches(launcher_path, target):
                raise InstallError(
                    f"the {launcher_path.name} launcher does not point to this checkout"
                )
        if with_bootstrap:
            text = _read_text(bootstrap_path)
            span = _managed_span(text)
            if span is None:
                raise InstallError(
                    f"the managed Copilot bootstrap block is missing from {bootstrap_path}"
                )
            if text[span[0] : span[1]] != bootstrap_block():
                raise InstallError(
                    "the managed Copilot bootstrap block has drifted from this checkout"
                )
        if with_skills:
            if stale_owned_skill_links:
                raise InstallError(
                    f"retired Copilot skill link is still installed: {stale_owned_skill_links[0]}"
                )
            _check_skill_links(skills_dir, sources)
        return hook_path, memory_launcher_path, ai_orch_launcher_path

    # ---- validation phase: nothing is written until every target is safe ----
    if hook_path.is_symlink():
        raise InstallError(f"refusing to replace a symlinked hook file: {hook_path}")
    if hook_path.exists():
        try:
            existing_hook = json.loads(hook_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise InstallError(f"refusing to replace an invalid hook file: {hook_path}") from exc
        if not _owned_hook(existing_hook, guard):
            raise InstallError(f"refusing to replace a hook not owned by this integration: {hook_path}")
    for launcher_path, target in launchers:
        if launcher_path.is_symlink():
            if not _symlink_matches(launcher_path, target):
                raise InstallError(
                    f"refusing to replace an existing launcher symlink: {launcher_path}"
                )
        elif launcher_path.exists():
            raise InstallError(f"refusing to replace an existing launcher file: {launcher_path}")

    bootstrap_before = _read_text(bootstrap_path) if with_bootstrap else ""
    bootstrap_after = (
        _apply_block(bootstrap_before, bootstrap_block()) if with_bootstrap else ""
    )
    if with_skills:
        _validate_skill_links(skills_dir, sources)

    if dry_run:
        plan.record(f"write hook: {hook_path}")
        for launcher_path, target in launchers:
            if not _symlink_matches(launcher_path, target):
                plan.record(f"link launcher: {launcher_path} -> {target}")
        if with_bootstrap:
            plan.diff(str(bootstrap_path), bootstrap_before, bootstrap_after)
        for source in sources:
            link = skills_dir / source.name
            if not _symlink_matches(link, source):
                plan.record(f"link skill: {link} -> {source}")
        for link in stale_owned_skill_links:
            plan.record(f"remove retired skill: {link}")
        plan.record(f"ensure staging directory: {staging_directory()}")
        plan.record(f"write manifest: {home / MANIFEST_FILENAME}")
        return hook_path, memory_launcher_path, ai_orch_launcher_path

    # ---- write phase ----
    hook_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    launcher_dir.mkdir(mode=0o755, parents=True, exist_ok=True)
    home.mkdir(mode=0o700, parents=True, exist_ok=True)
    _atomic_private_json(hook_path, hook_value)
    plan.record(f"hook: {hook_path}")

    for launcher_path, target in launchers:
        if not launcher_path.is_symlink():
            launcher_path.symlink_to(target)
        plan.record(f"launcher: {launcher_path}")

    manifest: dict[str, Any] = {
        "version": MANIFEST_VERSION,
        "checkout": str(repository_root()),
        "hook": str(hook_path),
        "launchers": [str(path) for path, _ in launchers],
        "links": [],
        "instructions": [],
        "staging": str(staging_directory()),
    }

    if with_bootstrap and bootstrap_after != bootstrap_before:
        if bootstrap_before:
            _backup(bootstrap_path, backup_directory(home))
        _atomic_write(bootstrap_path, bootstrap_after, 0o600)
        plan.record(f"bootstrap: {bootstrap_path}")
    if with_bootstrap:
        manifest["instructions"].append(
            {"target": str(bootstrap_path), "block": bootstrap_block()}
        )

    if with_skills:
        skills_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        for link in stale_owned_skill_links:
            link.unlink()
            plan.record(f"removed retired skill: {link}")
        for source in sources:
            link = skills_dir / source.name
            if not _symlink_matches(link, source):
                if link.is_symlink() or link.exists():
                    raise InstallError(f"refusing to replace a foreign skill entry: {link}")
                link.symlink_to(source)
            manifest["links"].append({"target": str(link), "source": str(source)})
            plan.record(f"skill: {link}")

    staging = staging_directory()
    staging.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        staging.chmod(0o700)
    except OSError:
        pass
    plan.record(f"staging: {staging}")

    _atomic_private_json(home / MANIFEST_FILENAME, manifest)
    plan.record(f"manifest: {home / MANIFEST_FILENAME}")
    return hook_path, memory_launcher_path, ai_orch_launcher_path


def _validate_skill_links(skills_dir: Path, sources: list[Path]) -> None:
    for source in sources:
        link = skills_dir / source.name
        if _symlink_matches(link, source):
            continue
        if link.is_symlink():
            raise InstallError(f"refusing to replace a foreign skill symlink: {link}")
        if link.exists():
            raise InstallError(f"refusing to replace an existing skill directory: {link}")


def _check_skill_links(skills_dir: Path, sources: list[Path]) -> None:
    for source in sources:
        link = skills_dir / source.name
        if not _symlink_matches(link, source):
            raise InstallError(f"the {source.name} skill link does not target this checkout")


def _backup(path: Path, backups: Path) -> None:
    backups.mkdir(mode=0o700, parents=True, exist_ok=True)
    shutil.copy2(path, backups / path.name)


def _uninstall(
    home: Path,
    hook_path: Path,
    launchers: tuple[tuple[Path, Path], ...],
    bootstrap_path: Path,
    skills_dir: Path,
    sources: list[Path],
    guard: Path,
    plan: Plan,
    *,
    dry_run: bool,
) -> tuple[Path, Path, Path]:
    manifest = load_manifest(home)
    owned_links = {row.get("target") for row in manifest["links"] if isinstance(row, dict)}

    if hook_path.exists() and not hook_path.is_symlink():
        try:
            value = json.loads(hook_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            value = None
        if _owned_hook(value, guard):
            plan.record(f"remove hook: {hook_path}")
            if not dry_run:
                hook_path.unlink()
        else:
            plan.record(f"kept foreign hook: {hook_path}")

    for launcher_path, target in launchers:
        # The legacy launchers stay in place unless they are ours and current.
        if _symlink_matches(launcher_path, target):
            plan.record(f"remove launcher: {launcher_path}")
            if not dry_run:
                launcher_path.unlink()
        elif _exists(launcher_path):
            plan.record(f"kept foreign launcher: {launcher_path}")

    text = _read_text(bootstrap_path)
    if text:
        remaining = _remove_block(text)
        if remaining != text:
            plan.diff(str(bootstrap_path), text, remaining)
            plan.record(f"remove managed block: {bootstrap_path}")
            if not dry_run:
                _backup(bootstrap_path, backup_directory(home))
                _atomic_write(bootstrap_path, remaining, 0o600)

    for source in sources:
        link = skills_dir / source.name
        if _symlink_matches(link, source) and str(link) in owned_links:
            plan.record(f"remove skill link: {link}")
            if not dry_run:
                link.unlink()
        elif _exists(link):
            plan.record(f"kept unowned skill entry: {link}")

    manifest_path = home / MANIFEST_FILENAME
    if manifest_path.exists():
        plan.record(f"remove manifest: {manifest_path}")
        if not dry_run:
            manifest_path.unlink()
    plan.record(
        "server data untouched: memory, rules, jobs and references created through "
        "the service are never removed by uninstall"
    )
    return hook_path, launchers[0][0], launchers[1][0]


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def default_copilot_home() -> Path:
    configured = os.environ.get("COPILOT_HOME")
    if configured and configured.strip():
        return Path(configured)
    return Path.home() / ".copilot"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Install, verify or remove the Copilot AI Orchestration terminal "
            "integration. Never creates an MCP server registration."
        )
    )
    parser.add_argument(
        "--copilot-home",
        default=None,
        help="Copilot configuration directory (default: $COPILOT_HOME or ~/.copilot)",
    )
    parser.add_argument(
        "--bin-dir",
        default=str(Path.home() / ".local" / "bin"),
        help="user executable directory (default: ~/.local/bin)",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="verify without writing")
    mode.add_argument(
        "--dry-run", action="store_true", help="print the exact plan without writing"
    )
    mode.add_argument(
        "--uninstall", action="store_true", help="remove owned artifacts only"
    )
    parser.add_argument(
        "--runtime",
        choices=("cli", "all"),
        default="cli",
        help=(
            "target runtime. Only the Copilot CLI hook location is verified; no "
            "VS Code or JetBrains hook file is written."
        ),
    )
    parser.add_argument(
        "--no-bootstrap",
        action="store_true",
        help="do not manage the Copilot user instructions block",
    )
    parser.add_argument(
        "--no-skills", action="store_true", help="do not link the Copilot skills"
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    copilot_home = Path(args.copilot_home) if args.copilot_home else default_copilot_home()
    plan = Plan()
    try:
        hook, memory_launcher, ai_orch_launcher = install(
            copilot_home,
            Path(args.bin_dir),
            check=args.check,
            dry_run=args.dry_run,
            uninstall=args.uninstall,
            with_bootstrap=not args.no_bootstrap,
            with_skills=not args.no_skills,
            plan=plan,
        )
    except InstallError as exc:
        print(f"installation error: {exc}")
        return 2
    if args.uninstall:
        action = "would remove" if args.dry_run else "removed"
    elif args.check:
        action = "verified"
    elif args.dry_run:
        action = "would install"
    else:
        action = "installed"
    if args.check:
        print(f"{action} hook: {hook}")
        print(f"{action} launcher: {memory_launcher}")
        print(f"{action} launcher: {ai_orch_launcher}")
    for entry in plan.actions:
        print(f"{action}: {entry}")
    for diff in plan.diffs:
        print(diff, end="")
    if not args.check and not args.dry_run and not args.uninstall:
        print(
            "note: a completed installation is not proof that Copilot invoked the "
            "hook or loaded the skills; verify that in a real session."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
