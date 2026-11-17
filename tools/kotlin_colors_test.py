#!/usr/bin/env python3
"""Tests for tools/kotlin_colors.py: python3 tools/kotlin_colors_test.py"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kotlin_colors import findings, lex  # noqa: E402

REFUSED_EVERYWHERE = {
    'hex literal': 'val a = 0xFF112233',
    'named color': 'val a = Color.Red',
    'named color in backticks': 'val a = Color.`Red`',
    'android named color': 'val a = Color.WHITE',
    'android factory': 'val a = Color.rgb(1, 2, 3)',
    'hsv': 'val a = Color.hsv(120f, 1f, 1f)',
    'hsl': 'val a = Color.hsl(0f, 1f, 0.5f)',
    'companion factory': 'val a = Color.Companion.hsv(120f, 1f, 1f)',
    'factory over lines': 'val a = Color\n    .hsv(120f, 1f, 1f)',
    'imported companion member': 'import androidx.compose.ui.graphics.Color.Companion.hsv',
    'aliased import': 'import androidx.compose.ui.graphics.Color as UiColor',
    'imported lerp': 'import androidx.compose.ui.graphics.lerp',
    'compositeOver': 'val a = Color(TvTheme.SCRIM).compositeOver(b)',
    'constructor reference': 'val make: (Int) -> Color = ::Color',
    'copy with red': 'val a = Color(TvTheme.SCRIM).copy(red = 1f, green = 0f, blue = 0f)',
    'copy with red after a nested call': 'val c = theme.copy(alpha = o.coerceIn(0f, 1f), red = 1f)',
    'copy with blue over lines': 'val c = theme.copy(\n    alpha = 0.5f,\n    blue = 1f,\n)',
    'code after an apostrophe in a backticked name': 'fun `don\'t use literals`() = Color.Red',
    'templates nested too deeply': '"${' * 500,
    'template code': 'val s = "${run { background = Color.Red; title }}"',
    'raw string template code': 'val s = """x ${Color.Red} y"""',
    'code after nested comment': '/* outer /* inner */ " */ val danger = Color.Red',
    'code after raw string with a closing quote': 'val t = """a""""; val danger = Color.Red',
}

REFUSED_OUTSIDE_BRIDGE = {
    'constructor': 'val a = Color(TvTheme.SCRIM)',
    'constructor over lines': 'val a = Color(\n    0xFF112233)',
}

REFUSED_IN_BRIDGE = {
    'float constructor': 'val a = Color(1f, 0f, 0f)',
    'int constructor': 'val a = Color(255, 0, 0)',
    'long constructor': 'val a = Color(4279312947)',
}

ALLOWED_EVERYWHERE = {
    'comment': '// Color.Red in a comment',
    'nested comment': '/* outer /* inner */ Color.Red */',
    'string': 'val s = "Color.Red in a string"',
    'string inside a template': 'val s = "${"Color.Red"}"',
    'char literals': "val q = '\"'; val r = '\\''",
    'generics': 'val g = List<Map<String, Int>>()',
    'transparent': 'val t = Color.Transparent',
    'own hsv': 'fun hsv(value: String) = value',
    'own lerp': 'fun lerp(start: Float, stop: Float, fraction: Float) = start',
    'type import': 'import androidx.compose.ui.graphics.Color\nimport androidx.tv.material3.Text',
    'alpha copy': 'val a = theme.copy(alpha = 0.5f)',
    'data class copy': 'val b = item.copy(title = "x", year = 1)',
    'copy with a nested red argument': 'val b = theme.copy(alpha = pick(red = 1f))',
    'copy comparing red': 'val b = theme.copy(alpha = if (x.red == 1f) 1f else 0.5f)',
    'backticked test name': 'fun `focus moves to the poster`() = Unit',
}

ALLOWED_IN_BRIDGE = {
    'token constructor': 'val a = Color(TvTheme.ACCENT_SOLID)',
    'token constructor over lines': 'val a = Color(\n    TvTheme.SCRIM)',
}


class KotlinColorsTest(unittest.TestCase):
    def check(self, cases, is_bridge, is_refused):
        for label, code in cases.items():
            with self.subTest(label, bridge=is_bridge):
                found = findings('Probe.kt', code, is_bridge)
                self.assertEqual(bool(found), is_refused, f'{code!r} -> {found}')

    def test_refused(self):
        for is_bridge in (False, True):
            self.check(REFUSED_EVERYWHERE, is_bridge, True)
        self.check(REFUSED_OUTSIDE_BRIDGE, False, True)
        self.check(REFUSED_IN_BRIDGE, True, True)

    def test_allowed(self):
        for is_bridge in (False, True):
            self.check(ALLOWED_EVERYWHERE, is_bridge, False)
        self.check(ALLOWED_IN_BRIDGE, True, False)

    def test_finding_names_its_line(self):
        code = '// one\nval s = """\ntwo\n"""\nval a = Color\n    .Red\n'
        self.assertEqual(findings('Probe.kt', code, False), [(5, 'Color .Red')])

    def test_extension_call_names_the_line_of_its_dot(self):
        code = 'val c = theme\n    .compositeOver(background)'
        self.assertEqual(findings('Probe.kt', code, False), [(2, '.compositeOver(')])

    def test_lex_keeps_length_and_lines(self):
        code = 'val s = "a\\"b ${x}"\n/* c\n */ val t = \'"\''
        lexed = lex(code)
        self.assertEqual(len(lexed), len(code))
        self.assertEqual(lexed.count('\n'), code.count('\n'))

    def test_unterminated_string_ends_cleanly(self):
        self.assertEqual(findings('Probe.kt', 'val s = "abc\\', False), [])


if __name__ == '__main__':
    unittest.main()
