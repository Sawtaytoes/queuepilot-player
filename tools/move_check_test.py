#!/usr/bin/env python3
"""Tests for tools/move_check.py with invented Java: python3 tools/move_check_test.py"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import move_check  # noqa: E402

BASE = '''package dev.queuepilot.player;

import java.util.*;

@androidx.media3.common.util.UnstableApi
public class MainActivity extends Activity {
  private final Store store = new Store();
  private List<String> profiles;
  private final int size = 3;

  /** Saves the profiles. */
  private void save(List<String> updated, boolean isHome) {
    store.write(updated);
    profiles = updated;
    if (isHome) {
      home();
    } else {
      label("home()");
    }
    store.listen(new Runnable() {
      @Override public void run() {
        MainActivity.this.home();
        System.out.println(this);
      }
    });
    list(this);
  }

  private int count() {
    List<String> names = null;
    names = profiles;
    return names.size() + size;
  }

  private void home() {}

  private void label(String text) {}

  private void tag(String text) {}

  void list(Object item) {}
}
'''

MAIN = '''package dev.queuepilot.player;

import java.util.*;

@androidx.media3.common.util.UnstableApi
public class MainActivity extends Activity {
  final Store store = new Store();
  List<String> profiles;
  final int size = 3;

  final Saver saver = new Saver(this);

  void home() {}

  void label(String text) {}

  private void tag(String text) {}

  void list(Object item) {}
}
'''

SAVER = '''package dev.queuepilot.player;

import java.util.List;

/** Saves the profiles. */
@androidx.media3.common.util.UnstableApi
final class Saver {
  private final MainActivity host;

  Saver(MainActivity host) {
    this.host = host;
  }

  /** Saves the profiles. */
  private void save(List<String> updated, boolean isHome) {
    host.store.write(updated);
    host.profiles = updated;
    if (isHome) {
      host.home();
    } else {
      host.label("home()");
    }
    host.store.listen(new Runnable() {
      @Override public void run() {
        host.home();
        System.out.println(this);
      }
    });
    host.list(host);
  }

  private int count() {
    List<String> names = null;
    names = host.profiles;
    return names.size() + host.size;
  }
}
'''


def check(main=MAIN, saver=SAVER, base=BASE):
    return move_check.Check({'MainActivity.java': base},
                            {'MainActivity.java': main, 'Saver.java': saver}).problems


def mutate(source, old, new):
    assert old in source, old
    return source.replace(old, new, 1)


class MoveCheckTest(unittest.TestCase):
    def assertFails(self, problems, words):
        self.assertTrue(problems, 'the check passed')
        self.assertIn(words, '\n'.join(problems))

    def test_a_faithful_move_passes(self):
        self.assertEqual(check(), [])

    def test_nothing_moved_passes(self):
        problems = move_check.Check({'MainActivity.java': BASE}, {'MainActivity.java': BASE}).problems
        self.assertEqual(problems, [])

    def test_reordered_statements_fail(self):
        swapped = mutate(SAVER, '    host.store.write(updated);\n    host.profiles = updated;\n',
                         '    host.profiles = updated;\n    host.store.write(updated);\n')
        self.assertFails(check(saver=swapped), 'save(List<String>, boolean) differs')

    def test_a_statement_moved_between_branches_fails(self):
        moved = mutate(SAVER, '      host.home();\n    } else {\n',
                       '    } else {\n      host.home();\n')
        self.assertFails(check(saver=moved), 'differs')

    def test_a_changed_string_literal_fails(self):
        self.assertFails(check(saver=mutate(SAVER, '"home()"', '"host.home()"')), 'differs')

    def test_this_in_an_anonymous_class_stays_that_object(self):
        self.assertFails(check(saver=mutate(SAVER, 'println(this)', 'println(host)')), 'differs')

    def test_the_activity_passed_as_itself_must_stay_the_activity(self):
        self.assertFails(check(saver=mutate(SAVER, 'host.list(host)', 'host.list(this)')), 'differs')

    def test_a_qualifier_past_a_local_variable_fails(self):
        self.assertFails(check(saver=mutate(SAVER, 'names.size()', 'host.names.size()')), 'differs')

    def test_a_qualifier_must_name_the_declaring_class(self):
        self.assertFails(check(saver=mutate(SAVER, 'return names', 'return host.count() + names')),
                         'Saver declares it')

    def test_a_changed_comment_fails(self):
        self.assertFails(check(saver=mutate(SAVER, '  /** Saves the profiles. */\n  private',
                                            '  /** Saves profiles. */\n  private')), 'comments')

    def test_reordered_members_fail(self):
        save = SAVER[SAVER.index('  /** Saves the profiles. */\n  private'):SAVER.index('  private int count')]
        swapped = SAVER.replace(save, '').replace('\n}\n', '\n' + save.rstrip() + '\n}\n')
        self.assertFails(check(saver=swapped), 'a move keeps the order')

    def test_a_needless_loss_of_private_fails(self):
        self.assertFails(check(main=mutate(MAIN, 'private void tag', 'void tag')), 'lost private')

    def test_an_import_main_activity_lacked_fails(self):
        self.assertFails(check(saver=mutate(SAVER, 'import java.util.List;', 'import java.io.File;')),
                         'not one of the base MainActivity imports')

    def test_extra_constructor_code_fails(self):
        changed = mutate(SAVER, 'this.host = host;', 'this.host = host;\n    host.home();')
        self.assertFails(check(saver=changed), 'host field, one constructor')

    def test_a_new_member_fails(self):
        changed = mutate(SAVER, '  private int count', '  void extra() {}\n\n  private int count')
        self.assertFails(check(saver=changed), 'not a member of the base')

    def test_a_missing_member_fails(self):
        self.assertFails(check(main=mutate(MAIN, '  void list(Object item) {}\n', '')), 'missing')

    def test_a_moved_field_that_reads_host_fails(self):
        # Field initializers run before the constructor body, so host is still null there.
        base = mutate(BASE, '  /** Saves', '  private final int limit = store.size();\n\n  /** Saves')
        saver = mutate(SAVER, '  /** Saves', '  private final int limit = host.store.size();\n\n  /** Saves')
        self.assertFails(check(saver=saver, base=base), 'runs code when it is built')
        later = saver.replace('host.store.size();', '0;').replace('= 0;', '= 0;', 1)
        base = base.replace('= store.size();', '= 0;')
        self.assertEqual(check(saver=later, base=base), [])

    def test_an_unbalanced_brace_fails(self):
        self.assertFails(check(saver=SAVER + '}\n'), '')
        self.assertFails(check(saver=SAVER.rstrip()[:-1]), 'could not read')


P = 'package p; '
OTHER = P + 'final class Other { private final MainActivity host; Other(MainActivity host){this.host=host;} int size=100; int value(){return 100;} }'
SAVER_HEAD = P + 'final class Saver { private final MainActivity host; Saver(MainActivity host){this.host=host;} '


def probe(base, head):
    return move_check.Check(base, head).problems


class ReviewProbeTest(unittest.TestCase):
    """The second review's probes, as small classes: each change must fail, and each faithful
    move must pass."""

    def assertFails(self, problems, words):
        self.assertTrue(problems, 'the check passed')
        self.assertIn(words, '\n'.join(problems))

    def test_a_qualifier_keeps_the_declaring_class(self):
        main = P + 'class MainActivity { final Other other = new Other(this); int size=3; int read(){return this.size;} }'
        changed = main.replace('return this.size', 'return other.size')
        self.assertFails(probe({'MainActivity.java': main, 'Other.java': OTHER},
                               {'MainActivity.java': changed, 'Other.java': OTHER}), 'differs')

    def test_a_parameter_named_like_a_holder_is_not_the_holder(self):
        main = P + 'class MainActivity { final Other other = new Other(this); int read(Other other){return other.size;} }'
        for old, new in (('return other.size', 'return this.other.size'),
                         ('read(Other other){return other.size;}', 'read(Other other){return use(other -> this.other.size);}')):
            changed = main.replace(old, new)
            self.assertFails(probe({'MainActivity.java': main, 'Other.java': OTHER},
                                   {'MainActivity.java': changed, 'Other.java': OTHER}), 'differs')

    def test_operators_keep_their_boundaries(self):
        main = P + 'class MainActivity { int calc(){int a=1,b=2; return a + ++b;} }'
        self.assertFails(probe({'MainActivity.java': main},
                               {'MainActivity.java': main.replace('a + ++b', 'a++ + b')}), 'differs')

    def test_unicode_names_are_kept(self):
        main = P + 'class MainActivity { int read(){int á=1; int β=2; return á;} }'
        self.assertFails(probe({'MainActivity.java': main},
                               {'MainActivity.java': main.replace('return á', 'return β')}), 'differs')

    def test_a_moved_initializer_that_runs_code_fails(self):
        cases = [
            ('private int a=Counter.next(); private int b=Counter.next();',
             'private int b=Counter.next();', 'private int a=Counter.next();'),
            ('int v=5; private final int x=read(); private int read(){return v;}',
             'int v=5;', 'private final int x=read(); private int read(){return host.v;}'),
        ]
        for base, kept, moved in cases:
            with self.subTest(moved):
                problems = probe({'MainActivity.java': P + 'class MainActivity { ' + base + ' }'},
                                 {'MainActivity.java': P + 'class MainActivity { ' + kept
                                  + ' final Saver saver = new Saver(this); }',
                                  'Saver.java': SAVER_HEAD + moved + ' }'})
                self.assertFails(problems, 'runs code when it is built')

    def test_a_call_that_fits_two_overloads_does_not_open_both(self):
        base = P + 'class MainActivity { private int f(int x){return 1;} private int f(String x){return 2;} private int read(){return f(1);} }'
        head = P + 'class MainActivity { int f(int x){return 1;} int f(String x){return 2;} final Saver saver = new Saver(this); }'
        saver = SAVER_HEAD + 'private int read(){return host.f(1);} }'
        self.assertFails(probe({'MainActivity.java': base}, {'MainActivity.java': head, 'Saver.java': saver}),
                         'fits more than one overload')

    def test_faithful_moves_pass(self):
        nested = 'class Nested { int n=1; int get(){return this.n;} }'
        callback = ('class MainActivity { private interface Callback { void run(); } private void take(Callback c){c.run();} '
                    'private void call(){take(() -> {});} }')
        cases = [
            ({'MainActivity.java': P + 'class MainActivity { private static ' + nested + ' }'},
             {'MainActivity.java': P + 'class MainActivity { }', 'Nested.java': P + nested}),
            ({'MainActivity.java': P + callback},
             {'MainActivity.java': P + 'class MainActivity { interface Callback { void run(); } void take(Callback c){c.run();} '
                                       'final Saver saver = new Saver(this); }',
              'Saver.java': SAVER_HEAD + 'private void call(){host.take(() -> {});} }'}),
        ]
        # Shapes from the real splits: an inherited method inside an anonymous class, a local
        # named like a method, and a second local declared after a comma.
        watch = ('void watch(){ listen(new Callback(){ public void ready(){ runOnUiThread(() -> {}); } }); '
                 'int columns = columns(3), server = 1; use(server); }')
        cases.append(({'MainActivity.java': P + 'class MainActivity { int server; private int columns(int n){return n;} '
                                                'private ' + watch + ' }'},
                      {'MainActivity.java': P + 'class MainActivity { int server; int columns(int n){return n;} '
                                                'final Saver saver = new Saver(this); }',
                       'Saver.java': SAVER_HEAD + 'private ' + watch.replace('runOnUiThread', 'host.runOnUiThread')
                       .replace('columns(3)', 'host.columns(3)').replace('listen(', 'host.listen(')
                       .replace('use(', 'host.use(') + ' }'}))
        for base, head in cases:
            with self.subTest(sorted(head)):
                self.assertEqual(probe(base, head), [])

    def test_object_methods_keep_their_class(self):
        base = P + 'class MainActivity { private String name(){return toString();} }'
        head = P + 'class MainActivity { final Saver saver = new Saver(this); }'
        saver = SAVER_HEAD + 'private String name(){return toString();} }'
        self.assertFails(probe({'MainActivity.java': base}, {'MainActivity.java': head, 'Saver.java': saver}), 'differs')
        self.assertEqual(probe({'MainActivity.java': base}, {'MainActivity.java': head, 'Saver.java': saver.replace(
            'return toString()', 'return host.toString()')}), [])

    def test_unchanged_java_reads(self):
        for body in ('String s="""\nhello\n""";', r"char c='\'';", 'int record=1; int read(){return record;}',
                     '@SuppressWarnings({"a", "b"}) int read(){return 1;}', 'java.util.Map<String,java.util.List<Integer>> m;',
                     'int shift(int x){x >>= 1; return x >>> 2;}'):
            source = {'MainActivity.java': P + 'class MainActivity { ' + body + ' }'}
            with self.subTest(body):
                self.assertEqual(probe(source, source), [])

    def test_initialization_order_is_kept(self):
        # Round 3: an activity initializer that calls moved code before its holder exists, and a
        # moved field that is now set before an initializer that ran before it.
        cases = [
            ('int x=read(); int read(){return 7;}', 'int x=this.saver.read(); final Saver saver = new Saver(this);',
             'int read(){return 7;}', 'before a new class exists'),
            ('int x=read(); int value=7; int read(){return value;}', 'final Saver saver = new Saver(this); int x=saver.read();',
             'int value=7; int read(){return value;}', 'now set before activity initializers'),
        ]
        cases += [
            # Round 4: an initializer block, static initializers, and a synchronized method.
            ('int x; { x = read(); } int read(){return 7;}', 'int x; { x = this.saver.read(); } '
             'final Saver saver = new Saver(this);', 'int read(){return 7;}', 'before a new class exists'),
            ('int x; { x = read(); } int value=7; int read(){return value;}', 'final Saver saver = new Saver(this); '
             'int x; { x = saver.read(); }', 'int value=7; int read(){return value;}', 'now set before'),
            ('static int x = read(); static int value = 7; static int read(){return value;}',
             'final Saver saver = new Saver(this); static int x = Saver.read();',
             'static int value = 7; static int read(){return value;}', 'now set before'),
            # Round 5: a static call qualified with the activity's name.
            ('static int x = MainActivity.read(); static int value = 9; static int read(){return value;}',
             'final Saver saver = new Saver(this); static int x = Saver.read();',
             'static int value = 9; static int read(){return value;}', 'now set before'),
            ('boolean read(){return Thread.holdsLock(this);} synchronized boolean go(){return read();}',
             'final Saver saver = new Saver(this); boolean read(){return Thread.holdsLock(this);}',
             'synchronized boolean go(){return host.read();}', 'synchronized'),
        ]
        for base, main, moved, words in cases:
            with self.subTest(words):
                self.assertFails(probe({'MainActivity.java': P + 'class MainActivity { ' + base + ' }'},
                                       {'MainActivity.java': P + 'class MainActivity { ' + main + ' }',
                                        'Saver.java': SAVER_HEAD + moved + ' }'}), words)

    def test_an_anonymous_class_method_is_not_the_activity_method(self):
        main = (P + 'class MainActivity { int value(){return 1;} int read(){return new IntSupplier(){ '
                'public int value(){return 2;} public int getAsInt(){return value();} }.getAsInt();} }')
        self.assertFails(probe({'MainActivity.java': main}, {'MainActivity.java': main.replace(
            'return value();', 'return MainActivity.this.value();')}), 'differs')

    def test_a_parameter_named_host_is_that_parameter(self):
        base = P + 'class MainActivity { int value=1; private int read(MainActivity host){return host.value;} }'
        head = P + 'class MainActivity { int value=1; final Saver saver = new Saver(this); }'
        self.assertEqual(probe({'MainActivity.java': base}, {'MainActivity.java': head, 'Saver.java': SAVER_HEAD
                               + 'private int read(MainActivity host){return host.value;} }'}), [])


if __name__ == '__main__':
    unittest.main()
