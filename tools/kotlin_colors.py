"""Finds color literals in Kotlin source, for tools/theme_check.py.

Kotlin screens take every color from QueuePilotTvTheme.kt, which may only wrap one TvTheme
constant per color: Color(TvTheme.NAME). This guard catches the ways a color is ordinarily
written by mistake. It is not a sandbox against deliberate evasion; code review covers that.

The source is first lexed: comments (Kotlin's nest) and the text of string and character
literals are blanked, and the code inside a string template, "${...}", is kept and checked
like any other code. In a backticked name only the letters, digits, and underscores are kept,
so Color.`Red` reads as Color. Red  and `don't` cannot open a character literal. Every pattern
allows white space and line breaks around a dot, so a call split over lines is still seen.
Positions are unchanged, so a finding names its real line.

A copy() call that sets red, green, or blue at its own level is refused, whatever it copies:
a lexer cannot tell a Color from a data class. So no Kotlin class here names a property red,
green, or blue. Source nested too deeply to lex is refused, not skipped.
"""
import re

DOT = r'\s*\.\s*'
PATTERNS = [
    # An 8-digit hex number: an ARGB color.
    re.compile(r'\b0x[0-9a-fA-F]{8}\b(?![lL])'),
    # android.graphics.Color's factories and named constants.
    re.compile(r'\bColor' + DOT + r'(rgb|argb|parseColor|valueOf|pack)\s*\('),
    re.compile(r'\bColor' + DOT + r'(?!TRANSPARENT\b)([A-Z][A-Z_]+)\b'),
    # Compose's named colors; Transparent and Unspecified carry no color.
    re.compile(r'\bColor' + DOT + r'(Black|DarkGray|Gray|LightGray|White|Red|Green|Blue|Yellow|'
               r'Cyan|Magenta)\b'),
    # Compose's factories on Color, its companion, and its constructor as a value.
    re.compile(r'\bColor' + DOT + r'(hsv|hsl|lerp|fromRgb|fromArgb)\b'),
    re.compile(r'\bColor' + DOT + r'Companion\b'),
    re.compile(r'::\s*Color\b'),
    # Blending makes a color that is not a token.
    re.compile(DOT + r'compositeOver\s*\('),
    # Importing a Color member (such as Color.Companion.hsv), or Color under another name.
    re.compile(r'\bimport\s+(?:\w+' + DOT + r')*Color(' + DOT + r'[\w*]|\s+as\b)'),
    re.compile(r'\bimport\s+androidx' + DOT + r'compose' + DOT + r'ui' + DOT + r'graphics' + DOT +
               r'(lerp|compositeOver)\b'),
]
COPY = re.compile(DOT + r'copy\s*\(')
# A copy() argument that replaces red, green, or blue; .copy(alpha = ...) stays allowed.
COMPONENT = re.compile(r'(?:^|,)\s*(red|green|blue)\s*=(?!=)')
ANY_CONSTRUCTOR = re.compile(r'\bColor\s*\(')
# In the bridge: a Color(...) that does not hold exactly one TvTheme constant.
NOT_TOKEN_CONSTRUCTOR = re.compile(r'\bColor\s*\((?!\s*TvTheme' + DOT + r'[A-Z][A-Z0-9_]*\s*\))')


def lex(text):
    """Returns text with comments and literal text blanked; template code is kept."""
    out = list(text)
    n = len(text)

    def blank(start, end):
        for k in range(start, min(end, n)):
            if out[k] != '\n':
                out[k] = ' '

    def code(i, until_brace):
        depth = 0
        while i < n:
            if text.startswith('//', i):
                end = text.find('\n', i)
                end = n if end < 0 else end
                blank(i, end)
                i = end
            elif text.startswith('/*', i):
                level, j = 0, i
                while j < n:
                    if text.startswith('/*', j):
                        level += 1
                        j += 2
                    elif text.startswith('*/', j):
                        level -= 1
                        j += 2
                        if level == 0:
                            break
                    else:
                        j += 1
                blank(i, j)
                i = j
            elif text.startswith('"""', i):
                i = raw_string(i + 3)
            elif text[i] == '"':
                i = string(i + 1)
            elif text[i] == "'":
                j = i + 1
                while j < n and text[j] not in "'\n":
                    j += 2 if text[j] == '\\' else 1
                blank(i + 1, j)
                i = j + 1
            elif text[i] == '`':
                j = text.find('`', i + 1)
                j = n if j < 0 else j + 1
                for k in range(i, j):
                    if not (text[k].isalnum() or text[k] in '_\n'):
                        out[k] = ' '
                i = j
            elif text[i] == '{':
                depth += 1
                i += 1
            elif text[i] == '}':
                if depth == 0 and until_brace:
                    return i + 1
                depth -= 1
                i += 1
            else:
                i += 1
        return n

    def string(i):
        while i < n and text[i] not in '"\n':
            if text[i] == '\\':
                blank(i, i + 2)
                i += 2
            elif text.startswith('${', i):
                i = code(i + 2, True)
            else:
                blank(i, i + 1)
                i += 1
        return i + 1

    def raw_string(i):
        while i < n:
            if text.startswith('"""', i):
                run = i
                while run < n and text[run] == '"':
                    run += 1
                # The delimiter is the last three quotes of the run; any before it are text.
                blank(i, run - 3)
                return run
            if text.startswith('${', i):
                i = code(i + 2, True)
            else:
                blank(i, i + 1)
                i += 1
        return n

    code(0, False)
    return ''.join(out)


def top_level_arguments(source, start):
    """The text of a call's arguments that sits at its own level; nested calls are blanked."""
    depth, out = 1, []
    for character in source[start:]:
        if character in '([{':
            depth += 1
        elif character in ')]}':
            depth -= 1
            if depth == 0:
                break
        out.append(character if depth == 1 else ' ')
    return ''.join(out)


def findings(name, text, is_bridge):
    """(line, matched text) for each color literal in one Kotlin file."""
    try:
        source = lex(text)
    except RecursionError:
        return [(1, 'string templates nested too deeply to check')]
    patterns = PATTERNS + [NOT_TOKEN_CONSTRUCTOR if is_bridge else ANY_CONSTRUCTOR]
    matches = [match for pattern in patterns for match in pattern.finditer(source)]
    matches += [match for match in COPY.finditer(source)
                if COMPONENT.search(top_level_arguments(source, match.end()))]
    found = []
    for match in matches:
        # A pattern may begin with the white space before a dot; name the line of the code.
        start = match.start() + len(match.group(0)) - len(match.group(0).lstrip())
        found.append((source.count('\n', 0, start) + 1, ' '.join(match.group(0).split())))
    return sorted(found)
