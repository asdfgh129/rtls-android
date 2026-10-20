#!/usr/bin/env python3
import os
import subprocess
import sys


def main() -> int:
    if os.environ.get('ACTOR') == 'dependabot[bot]':
        print('dependabot PR, skipping changelog gate')
        return 0

    base = os.environ.get('BASE_REF')
    if not base:
        print('BASE_REF is not set, skipping')
        return 0

    subprocess.run(['git', 'fetch', '--no-tags', 'origin', base], check=True)
    changed = subprocess.check_output(
        ['git', 'diff', '--name-only', f'origin/{base}...HEAD'], text=True
    ).split()
    if not changed:
        print('no changes')
        return 0

    code_changed = [
        path for path in changed if not path.startswith('Docs/') and path != 'CHANGELOG.md'
    ]
    if code_changed and 'CHANGELOG.md' not in changed:
        print('CHANGELOG.md must be updated for these changes:')
        for path in code_changed:
            print(f'  {path}')
        return 1

    print('changelog gate ok')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
