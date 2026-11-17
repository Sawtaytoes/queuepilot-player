#!/usr/bin/env python3
"""Static checks for the TV theme and Display size; needs no emulator.

1. No color literal in the player's Java or Kotlin outside TvTheme.java. Java: no 8-digit hex int,
   no Color.rgb/argb/parseColor/valueOf, and no named Color constant except TRANSPARENT. Kotlin:
   tools/kotlin_colors.py, which also refuses Compose's named colors and factories, and allows
   Color(...) only in QueuePilotTvTheme.kt around one TvTheme constant. Both source roots are
   read, src/main/java and src/main/kotlin, at any depth.
   AssMatroskaExtractor, AssCueIndex and BifIndex parse binary formats whose element IDs and
   sentinels look like colors, so they are skipped; the subtitle renderer takes its colors from
   the subtitle file.
2. The XML colors (window theme and launcher banner) are TvTheme values.
3. Auto's formula, recomputed here, gives 65% for a 97 in screen at 6.5 ft and 95% for the
   55 in, 9 ft default, and DisplaySize.java states the same defaults.
"""
import math
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kotlin_colors  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / 'android/app/src/main/java/dev/queuepilot/player'
SOURCE_ROOTS = [ROOT / 'android/app/src/main/java', ROOT / 'android/app/src/main/kotlin']
RES = ROOT / 'android/app/src/main/res'
BINARY_FORMATS = {'AssMatroskaExtractor.java', 'AssCueIndex.java', 'BifIndex.java'}
HEX = re.compile(r'\b0x[0-9a-fA-F]{8}\b(?![lL])')
CALL = re.compile(r'\bColor\.(rgb|argb|parseColor|valueOf|pack)\s*\(')
NAMED = re.compile(r'\bColor\.(?!TRANSPARENT\b)([A-Z][A-Z_]+)\b')
COMPOSE_THEME = 'QueuePilotTvTheme.kt'
failures = []

theme = (JAVA / 'TvTheme.java').read_text()
theme_colors = {int(v, 16) & 0xFFFFFF for v in re.findall(r'0x[fF]{2}([0-9a-fA-F]{6})', theme)}
if not theme_colors:
    failures.append('TvTheme.java declares no colors')

if not (JAVA / COMPOSE_THEME).is_file():
    failures.append(f'{COMPOSE_THEME} is missing; Compose screens take their colors from it')
sources = sorted(path for root in SOURCE_ROOTS if root.is_dir()
                 for pattern in ('*.java', '*.kt') for path in root.rglob(pattern))
for path in sources:
    if path.name == 'TvTheme.java' or path.name in BINARY_FORMATS:
        continue
    if path.suffix == '.kt':
        for number, found in kotlin_colors.findings(path.name, path.read_text(),
                                                    path.name == COMPOSE_THEME):
            failures.append(f'{path.name}:{number}: color literal {found!r}; use TvTheme')
        continue
    for number, line in enumerate(path.read_text().splitlines(), 1):
        code = line.split('//', 1)[0]
        if code.lstrip().startswith('*'):
            continue
        for pattern in (HEX, CALL, NAMED):
            for match in pattern.finditer(code):
                failures.append(f'{path.name}:{number}: color literal {match.group(0)!r}; use TvTheme')

for path in [RES / 'values/colors.xml', RES / 'values/styles.xml', RES / 'drawable/banner.xml']:
    for value in re.findall(r'#([0-9a-fA-F]{6,8})\b', path.read_text()):
        rgb = int(value[-6:], 16)
        if rgb not in theme_colors:
            failures.append(f'{path.relative_to(ROOT)}: #{value} is not a TvTheme color')


def angle(diagonal, feet):
    width = diagonal * 16 / math.hypot(16, 9)
    return math.degrees(2 * math.atan(width / 2 / (feet * 12)))


def auto(diagonal, feet):
    percent = round(math.sqrt(angle(55, 10) / angle(diagonal, feet)) * 100 / 5) * 5
    return max(50, min(125, percent))


for (diagonal, feet), expected in {(97, 6.5): 65, (55, 9): 95, (55, 10): 100, (150, 2): 50}.items():
    if auto(diagonal, feet) != expected:
        failures.append(f'Auto for {diagonal} in at {feet} ft is {auto(diagonal, feet)}%, expected {expected}%')
size = (JAVA / 'DisplaySize.java').read_text()
for fragment in ('DEFAULT_PERCENT = AUTO', 'DEFAULT_SCREEN_INCHES = 55', 'DEFAULT_DISTANCE_FEET = 9',
                 'MINIMUM = 50', 'MAXIMUM = 125', 'STEP = 5', 'angle(55, 10)'):
    if fragment not in size:
        failures.append(f'DisplaySize.java no longer states {fragment!r}')

if failures:
    print('\n'.join(failures))
    sys.exit(1)
print(f'PASS: no color literal in Java or Kotlin outside TvTheme ({len(theme_colors)} theme colors), XML colors are theme '
      f'colors, and Auto is {auto(97, 6.5)}% for 97 in at 6.5 ft and {auto(55, 9)}% by default', flush=True)
