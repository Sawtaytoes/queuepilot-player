#!/usr/bin/env python3
"""Fails when a source file is longer than the size limit; needs no build.

Every tracked Java, Kotlin, Python, shell, JavaScript and C file may hold at most LIMIT lines.
A file over the limit must be listed in tools/size-exceptions.txt with a ceiling and a reason;
it fails when it passes its ceiling, and an entry for a file that is gone or back under the
limit fails too, so the list only shrinks. Why 400 lines: docs/decisions/2026-11-17-the-tv-app-
moves-to-kotlin-and-compose-for-tv-screen-by-screen-and-no-source-file-grows-past-a-size-limit.md
"""
import subprocess
import sys
from pathlib import Path

LIMIT = 400
ROOT = Path(__file__).resolve().parent.parent
EXCEPTIONS = ROOT / 'tools/size-exceptions.txt'
SOURCE_SUFFIXES = {'.java', '.kt', '.kts', '.py', '.sh', '.js', '.mjs', '.c', '.h'}


def tracked_sources():
    listed = subprocess.run(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'],
                            cwd=ROOT, capture_output=True, check=True).stdout.decode().split('\0')
    return sorted(name for name in listed
                  if Path(name).suffix in SOURCE_SUFFIXES and (ROOT / name).is_file())


def line_count(name):
    with open(ROOT / name, 'rb') as source:
        return sum(1 for _ in source)


def exceptions():
    ceilings = {}
    for number, line in enumerate(EXCEPTIONS.read_text().splitlines(), 1):
        line = line.split('#', 1)[0].strip()
        if not line:
            continue
        parts = line.rsplit(None, 1)
        if len(parts) != 2 or not parts[1].isdigit():
            sys.exit(f'{EXCEPTIONS.name}:{number}: expected "<path> <ceiling>"')
        ceilings[parts[0]] = int(parts[1])
    return ceilings


def main():
    ceilings = exceptions()
    failures = []
    sources = tracked_sources()
    for name in sources:
        lines = line_count(name)
        ceiling = ceilings.get(name)
        if ceiling is None and lines > LIMIT:
            failures.append(f'{name}: {lines} lines, over the {LIMIT}-line limit. Split it into '
                            f'focused files (see docs/decisions/README.md).')
        elif ceiling is not None and lines > ceiling:
            failures.append(f'{name}: {lines} lines, over its recorded ceiling of {ceiling}. '
                            f'Move code out of it instead of adding to it.')
    for name, ceiling in ceilings.items():
        if name not in sources:
            failures.append(f'{EXCEPTIONS.name}: {name} is not a tracked source file; remove its entry')
        elif line_count(name) <= LIMIT:
            failures.append(f'{EXCEPTIONS.name}: {name} is now within {LIMIT} lines; remove its entry')
    if failures:
        print('\n'.join(failures))
        sys.exit(1)
    largest = max(sources, key=line_count)
    print(f'PASS: {len(sources)} source files are within {LIMIT} lines or their recorded ceiling '
          f'({len(ceilings)} exceptions; the largest is {largest}, {line_count(largest)} lines)',
          flush=True)


if __name__ == '__main__':
    main()
