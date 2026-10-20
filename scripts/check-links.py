#!/usr/bin/env python3
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKIP_DIRS = {'.git', 'node_modules', 'dist', 'coverage', '.omo', 'data'}
LINK = re.compile(r'\[[^\]]*\]\(([^)\s]+)\)')
failures: list[str] = []


def is_external(target: str) -> bool:
    return bool(re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:', target)) or target.startswith('//')


def check(target: str, source: pathlib.Path) -> None:
    bare = target.split('#', 1)[0]
    if not bare:
        return
    resolved = (source.parent / bare).resolve()
    if resolved.exists():
        return
    if not bare.endswith(('.md', '.png', '.jpg', '.jpeg', '.svg', '.gif', '.json', '.txt', '.sql')):
        for suffix in ('.md', ''):
            if resolved.with_name(resolved.name + suffix).exists():
                return
    failures.append(f"{source.relative_to(ROOT)} -> {target}")


for path in ROOT.rglob('*.md'):
    if any(part in SKIP_DIRS for part in path.parts):
        continue
    text = path.read_text(encoding='utf-8')
    for match in LINK.finditer(text):
        target = match.group(1)
        if is_external(target) or target.startswith('#'):
            continue
        check(target, path)

if failures:
    print(f'{len(failures)} broken link(s):')
    for item in failures:
        print(f'  {item}')
    sys.exit(1)

print('all relative links resolve')
