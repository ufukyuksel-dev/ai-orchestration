#!/usr/bin/env python3
"""`cat` for agents: whole small files, an outline of large ones.

Agents often open a task with `cat a b c d e`. Every line read stays in the context and is read again by every later
model call, so five whole files can cost more than the rest of the task. The command hook replaces such a `cat` with
this script. When all files together have at most READ_ALL lines, they are printed whole (with a `==> file <==`
header each, like `head`). Otherwise every file of at most SMALL lines is still printed whole. A larger one gets its
first lines (package, imports), the line numbers of its definitions and a hint how to read a range, so the agent reads
the part it needs instead of the whole file.

Usage: ai_orch_read.py <file>...      (exit status 1 when a file cannot be read, like cat)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

READ_ALL = 400
SMALL = 120
HEAD = 25
DEFINITION = re.compile(r"^\s*(def |async def |class |func |type |interface |enum |record |public |protected |private |"
                        r"export |@\w*(Mapping|Get|Post|Put|Delete|route|app\.)|\w+\.(GET|POST|PUT|DELETE|Group)\()")


def outline(path: str, lines: list[str]) -> list[str]:
    out = [f"==> {path} ({len(lines)} lines: first {HEAD} lines and the definitions; read a range with "
           f"`sed -n 'A,Bp' {path}`) <=="]
    out += lines[:HEAD]
    out.append("...")
    for number, line in enumerate(lines[HEAD:], HEAD + 1):
        if DEFINITION.match(line):
            out.append(f"{number}: {line.rstrip()[:160]}")
    return out


def main(paths: list[str]) -> int:
    texts: dict[str, list[str]] = {}
    status = 0
    for path in paths:
        try:
            texts[path] = Path(path).read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError as error:
            print(f"cat: {path}: {error.strerror}", file=sys.stderr)
            status = 1
    whole = sum(len(lines) for lines in texts.values()) <= READ_ALL
    for path, lines in texts.items():
        if whole or len(lines) <= SMALL:
            if len(texts) > 1:
                print(f"==> {path} <==")
            print("\n".join(lines))
        else:
            print("\n".join(outline(path, lines)))
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
