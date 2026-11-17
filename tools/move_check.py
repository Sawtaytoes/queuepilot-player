#!/usr/bin/env python3
"""Shows that a split of MainActivity.java moved code without changing it.

    python3 tools/move_check.py <base-ref>

A split moves members of MainActivity into new classes. Each new class reaches the activity
through a `host` field, and the activity reaches the new class through a field such as
`final SignInScreen signInScreen = new SignInScreen(this);`. This check reads every Java file of
the package at the base commit and in the working tree as tokens. It compares each member of the
base MainActivity, token by token and in order, with the one member that holds it now.

Each reference to a member reads as `<Class>.name`, where Class is the class that declares that
member after the split. So `ui`, `this.ui`, and `host.ui` match, but `other.size` does not match
`this.size`. A name that no class of the package declares, such as `runOnUiThread`, is
inherited and reads as itself, except Object's methods. The activity itself reads as `<outer>`, whether written `this`, `MainActivity.this`,
or `host`; `this` inside an anonymous class stays that object. Literals and comments must be the
same. Members keep their order in each class.

Other rules:
- `private` may come off a member only when another class uses it. When a call from another class
  fits more than one overload, which one it calls is not known, so the check fails;
- a new class may add only its package line, imports that the activity had, its class line, its
  `host` field and constructor, and comments; the activity may add only the fields that hold the
  new classes;
- a new class builds its fields when its holder field is set, before its constructor sets
  `host`. So a moved field's initializer may only build a value (literals, arrays, or `new` of
  a class outside the package with no arguments); an activity field initializer or initializer
  block that may run activity code must follow every new holder; and a moved field with an
  initializer, instance or static, must have preceded every such initializer of its kind;
- a synchronized method may not move, because it would lock another object.

It reads code shape, not types: a local name counts in the whole member, overloads are told
apart by argument count, imports are not bound, and an inherited name inside an anonymous class
may belong to its superclass or to the activity. So it can fail a faithful move, and it does
not replace the build, the tests, and the emulator checks. Exit 1 when anything differs.
"""
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import java_names as naming  # noqa: E402
import java_source as java  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'android/app/src/main/java/dev/queuepilot/player'
SOURCE = 'MainActivity'
MAIN = f'{SOURCE}.java'
UNSTABLE = '@ androidx . media3 . common . util . UnstableApi'
HOST_FIELD = 'private final MainActivity host ;'


class Check:
    def __init__(self, base_sources, head_sources):
        self.problems, self.notes, self.uses, self.privates, self.later = [], [], [], [], []
        try:
            self.base = {n: java.JavaFile(n, s) for n, s in base_sources.items()}
            self.head = {n: java.JavaFile(n, s) for n, s in head_sources.items()}
        except (ValueError, StopIteration) as error:
            self.problems.append(f'could not read the Java: {error}')
            return
        if MAIN not in self.base or MAIN not in self.head:
            self.problems.append(f'{MAIN} must exist before and after the split')
            return
        self.source = self.base[MAIN]
        self.new = [f for n, f in self.head.items() if n not in self.base]
        self.problems += [f'{n} was deleted' for n in self.base if n not in self.head]
        self.new_names = {f.cls for f in self.new}
        self.package = {f.cls for f in self.head.values()}
        # Fields such as `final HomeScreen homeScreen = new HomeScreen(this);`, and the classes
        # with a host field, on each side; a split may add them, and earlier splits did.
        self.holders, self.hosted = {}, {}
        for side, files in (('base', self.base), ('head', self.head)):
            self.hosted[side] = {f.cls for f in files.values()
                                 if any(java.text(m.tokens) == HOST_FIELD for m in f.members)}
            self.holders[side] = {}
            for item in files[MAIN].members:
                match = re.fullmatch(r'final (\w+) (\w+) = new \1 \( this \) ;', java.text(item.tokens))
                if item.kind == 'field' and match and match.group(1) in self.hosted[side]:
                    self.holders[side][match.group(2)] = match.group(1)
        self.new_holders = {f: c for f, c in self.holders['head'].items() if c in self.new_names}
        # Members by name in each class on each side. A nested type of the base activity counts
        # as a class, so that a type moved to its own file compares with itself.
        self.names = {side: {f.cls: naming.by_name(f.members) for f in files.values()}
                      for side, files in (('base', self.base), ('head', self.head))}
        for item in self.source.members:
            if item.kind == 'type':
                try:
                    self.names['base'].setdefault(item.names[0], naming.by_name(java.split(item.tokens)[2]))
                except ValueError:
                    pass
        self.types = self.package | {m.names[0] for m in self.source.members if m.kind == 'type'}
        self.declared = {}
        for cls, names in self.names['head'].items():
            for name in names:
                self.declared.setdefault(name, set()).add(cls)
        self.home = {}  # each base activity member's key: the class that holds it now
        self.run()

    def problem(self, where, message):
        self.problems.append(f'{where}: {message}')

    # Normalizing one member's code ------------------------------------------------------------

    def identity(self, side, cls, name, count):
        """`<Class>.name` for a member reference, naming the class that declares the member after
        the split. A base activity member that moved names its new class. None for a name the
        class does not declare."""
        members = self.names[side].get(cls, {}).get(name, [])
        if name in self.types or (members and all(m.kind == 'type' for m in members)):
            return name  # types, and so constructors, keep their simple names when they move
        if not members:
            return None
        if side == 'base' and cls == SOURCE:
            homes = {self.home.get(m.key, '?') for m in [m for m in members if naming.fits(m, count)] or members}
            cls = homes.pop() if len(homes) == 1 else '?'
        return f'<{cls}>.{name}'

    def normalize(self, item, current, side, dropped=('private',), nest=1):
        """The member's code with each member reference written as `<Class>.name`, and the
        activity itself as `<outer>`, plus its comments. `nest` is how many class bodies hold
        the member; code deeper than that is in a nested or anonymous class."""
        qualifiers = {SOURCE: SOURCE, **self.holders[side], **{c: c for c in self.hosted[side]}}
        if side == 'head':
            qualifiers.update({cls: cls for cls in self.new_names})
        if current != SOURCE:
            qualifiers['host'] = SOURCE
        drop = {id(t) for t in item.header if t.text in dropped}
        code = [t for t in item.code if id(t) not in drop]
        local, methods = naming.locals_of(code, nest)
        out, index = [], 0
        while index < len(code):
            token = code[index]
            is_start = token.kind == 'word' and (index == 0 or code[index - 1].text not in ('.', '::'))
            target, width, is_outer = None, 0, False
            if is_start and token.text == 'host' and 'host' in qualifiers and 'host' not in local:
                target, width, is_outer = SOURCE, 1, True
            elif is_start and token.text == 'this' and token.depth <= nest:
                target, width, is_outer = current, 1, current == SOURCE
            elif is_start and token.text == SOURCE and [t.text for t in code[index + 1:index + 3]] == ['.', 'this']:
                target, width, is_outer = SOURCE, 3, True
            elif is_start and token.text in qualifiers and token.text not in local:
                target, width = qualifiers[token.text], 1
            if target:
                at = index + width
                while naming.is_member(code, at):
                    name = code[at + 1].text
                    count = 'reference' if code[at].text == '::' else naming.arguments(code, at + 1)
                    if side == 'head':
                        self.use(current, target, name, count, code[at + 1].line)
                    holder = self.holders[side].get(name) if target == SOURCE else None
                    if holder and code[at].text == '.' and naming.is_member(code, at + 2) and code[at + 2].text == '.':
                        target, at = holder, at + 2  # host.homeScreen.home() calls HomeScreen.home
                        continue
                    if code[at].text == '::':
                        out.append(('::', code[at].line))
                    ident = self.identity(side, target, name, count)
                    if ident is None:  # a local's name reached through a qualifier is not that local
                        ident = f'<{target}>.{name}' if name in local | methods else naming.inherited(target, name)
                    out.append((ident, token.line))
                    index = at + 2
                    break
                else:
                    out.append(('<outer>' if is_outer else ' '.join(t.text for t in code[index:at]), token.line))
                    index = at
                continue
            count = naming.arguments(code, index) if is_start else None
            if is_start and token.text not in java.KEYWORDS and token.text not in (local if count is None else methods) \
                    and (index == 0 or code[index - 1].text != 'new'):
                ident = self.identity(side, current, token.text, count)
                if ident is None and count is not None and token.depth <= nest:
                    ident = naming.inherited(current, token.text)
                if ident:
                    out.append((ident, token.line))
                    index += 1
                    continue
            out.append((token.text, token.line))
            index += 1
        comments = [' '.join(t.text.split()) for t in item.tokens if not t.is_code]
        return out, comments

    def use(self, current, target, name, count, line):
        where = f'{current}.java:{line}'
        if name in self.declared and target not in self.declared[name]:
            self.problem(where, f'{name} is qualified with {target}, but '
                                f'{" and ".join(sorted(self.declared[name]))} declares it')
        elif name not in self.declared and target != SOURCE:
            self.problem(where, f'{target} does not declare {name}')
        self.uses.append((target, name, count, current))

    # Comparing ---------------------------------------------------------------------------------

    def compare(self, before, after, current, where, base_drop=('private',), base_current=SOURCE, base_nest=1):
        b_code, b_comments = self.normalize(before, base_current, 'base', base_drop, base_nest)
        a_code, a_comments = self.normalize(after, current, 'head')
        if [t for t, _ in b_code] != [t for t, _ in a_code]:
            at = next((i for i, (x, y) in enumerate(zip(b_code, a_code)) if x[0] != y[0]),
                      min(len(b_code), len(a_code)))
            show = lambda code: ' '.join(t for t, _ in code[max(0, at - 4):at + 5]) or '(end)'
            line = lambda code: code[min(at, len(code) - 1)][1] if code else '?'
            self.problem(where, f'{before.key} differs. Base line {line(b_code)}: {show(b_code)!r}; '
                                f'now line {line(a_code)}: {show(a_code)!r}')
        elif b_comments != a_comments:
            self.problem(where, f'the comments of {before.key} changed')

    def run(self):
        base_main, head_main = self.source, self.head[MAIN]
        position = {m.key: i for i, m in enumerate(base_main.members)}
        if java.text(base_main.head) != java.text(head_main.head) or base_main.epilogue_text() != head_main.epilogue_text():
            self.problem(MAIN, 'the class line or the end of the class changed')
        self.check_prologue(head_main)
        for file in self.head.values():
            if file.name != MAIN and file.name in self.base and file.source != self.base[file.name].source:
                self.check_existing_file(self.base[file.name], file)
        for file in [head_main] + self.new:
            moved_type = next((m for m in base_main.members if m.kind == 'type' and m.names == [file.cls]), None)
            if moved_type is not None and file in self.new:
                self.check_moved_type(moved_type, file)
                self.home[moved_type.key] = file.cls
                continue
            if file in self.new:
                self.check_scaffolding(file)
            last = None
            for item in file.members:
                if file is head_main and item.kind == 'field' and item.names[0] in self.new_holders:
                    continue
                if file is not head_main and self.is_scaffolding(file, item):
                    continue
                where = f'{file.name}:{item.line}'
                if item.key not in position:
                    self.problem(where, f'{item.key} is not a member of the base {SOURCE}')
                    continue
                if item.key in self.home:
                    self.problem(where, f'{item.key} is also in {self.home[item.key]}')
                    continue
                self.home[item.key] = file.cls
                if last is not None and position[item.key] < position[last]:
                    self.problem(where, f'{item.key} now comes after {last}; a move keeps the order')
                last = item.key
                before = base_main.members[position[item.key]]
                self.later.append(lambda b=before, i=item, f=file, w=where: self.compare(b, i, f.cls, w))
                self.privates.append((before, item, file))
                if file is not head_main:
                    self.check_initializer(item, where)
        for item in base_main.members:
            if item.key not in self.home:
                self.problem(f'{MAIN}:{item.line}', f'{item.key} is missing after the split')
        self.check_field_order(base_main, head_main)
        for compare in self.later:  # once every member's new class is known
            compare()
        for before, item, file in self.privates:  # once every use is known
            self.check_private(before, item, file)
        moved = [k for k, cls in self.home.items() if cls != SOURCE]
        self.notes.append(f'{len(base_main.members)} members of the base {SOURCE}: '
                          f'{len(base_main.members) - len(moved)} stayed, {len(moved)} moved')

    def check_existing_file(self, before, after):
        """A class from an earlier split keeps its members; only qualifiers and `private` change."""
        if [m.key for m in before.members] != [m.key for m in after.members] \
                or java.text(before.head) != java.text(after.head) or before.epilogue_text() != after.epilogue_text():
            self.problem(after.name, 'a file that existed before may not gain, lose, or reorder members')
            return
        self.check_prologue(after, before)
        for old, new in zip(before.members, after.members):
            self.later.append(lambda o=old, n=new: self.compare(
                o, n, after.cls, f'{after.name}:{n.line}', base_current=after.cls))
            self.privates.append((old, new, after))

    def check_moved_type(self, before, file):
        self.check_prologue(file)
        whole = java.member(file.head + [t for m in file.members for t in m.tokens] + file.epilogue)
        self.later.append(lambda: self.compare(before, whole, file.cls, f'{file.name}:{whole.line}',
                                               ('private', 'static'), file.cls, 2))

    def check_prologue(self, file, before=None):
        base = self.source.statements()
        allowed = set(s for s in base + (before.statements() if before else []) if s.startswith('import '))
        statements = file.statements()
        if not statements or statements[0] != base[0]:
            self.problem(file.name, f'the first statement must be {base[0]!r}')
        for statement in statements[1:]:
            on_demand = re.sub(r'\. \w+ ;$', '. * ;', statement)
            if statement not in allowed and on_demand not in allowed:
                self.problem(file.name, f'{statement!r} is not one of the base {SOURCE} imports')

    def check_scaffolding(self, file):
        self.check_prologue(file)
        head = java.text(file.head)
        if head not in (f'{UNSTABLE} final class {file.cls} {{', f'final class {file.cls} {{'):
            self.problem(file.name, f'unexpected class line: {head!r}')
        if file.epilogue_text() != '}':
            self.problem(file.name, 'unexpected text after the last member')
        hosts = [m for m in file.members if self.is_scaffolding(file, m)]
        holders = [f for f, cls in self.new_holders.items() if cls == file.cls]
        if hosts and (len(hosts) != 2 or len(holders) != 1):
            self.problem(file.name, f'needs one host field, one constructor, and one field in {SOURCE} '
                                    f'that holds it; found {len(hosts)} and {len(holders)}')
        if not hosts and holders:
            self.problem(file.name, f'{SOURCE} holds it, but it has no host field')

    @staticmethod
    def is_scaffolding(file, item):
        return java.text(item.tokens) in (HOST_FIELD, f'{file.cls} ( MainActivity host ) {{ this . host = host ; }}')

    def check_private(self, before, after, file):
        if 'private' not in before.modifiers or 'private' in after.modifiers:
            return
        others = [(name, count) for target, name, count, user in self.uses if target == file.cls and user != file.cls]
        if after.kind == 'type':
            # Another class also uses a type when it calls a method that takes it, as with a lambda.
            takers = {m.names[0] for m in file.members if m.kind == 'method'
                      and any(re.match(re.escape(after.names[0]) + r'\b', p) for p in m.params)}
            if any(name in takers or name in after.names for name, _ in others):
                return
        elif after.kind != 'method':
            if any(name in after.names for name, _ in others):
                return
        else:
            overloads = [m for m in file.members if m.kind == 'method' and m.names == after.names]
            calls = [[m for m in overloads if naming.fits(m, count)] for name, count in others if name in after.names]
            if any(fitting == [after] for fitting in calls):
                return
            if any(after in fitting for fitting in calls):
                self.problem(f'{file.name}:{after.line}', f'{after.key} lost private, but the other class\'s '
                                                          'call fits more than one overload')
                return
        self.problem(f'{file.name}:{after.line}', f'{after.key} lost private, but no other class uses it')

    def check_field_order(self, base_main, head_main):
        """Initializers keep their order, for instance and static members alike."""
        activity = set(self.names['base'][SOURCE]) | set(self.names['head'][SOURCE]) | set(self.holders['head']) \
            | {SOURCE} | self.new_names | self.hosted['base'] | self.hosted['head']  # `MainActivity.read()`
        holders = {**self.holders['base'], **self.holders['head']}

        def reaching(item, is_static):
            return item.kind in ('field', 'initializer') and ('static' in item.modifiers) == is_static \
                and not (item.kind == 'field' and item.names[0] in holders) \
                and naming.reaches(item.code, activity - set(item.names), item.kind == 'initializer')
        new = [i for i, m in enumerate(head_main.members) if m.kind == 'field' and m.names[0] in self.new_holders]
        for index, item in enumerate(head_main.members):
            if new and reaching(item, False) and index < max(new):
                self.problem(f'{MAIN}:{item.line}', f'{item.key} runs code of the activity before a new class '
                                                    'exists; put the new holder fields first')
        for is_static in (False, True):
            earlier = [i for i, m in enumerate(base_main.members) if reaching(m, is_static) and self.home.get(m.key) == SOURCE]
            for index, item in enumerate(base_main.members):
                if item.kind == 'field' and ('static' in item.modifiers) == is_static and self.home.get(item.key) != SOURCE \
                        and '=' in [t.text for t in item.code] and any(i < index for i in earlier):
                    self.problem(f'{MAIN}:{item.line}', f'{item.key} moved, so it is now set before activity '
                                                        'initializers that ran before it')

    def check_initializer(self, item, where):
        """A moved field runs its initializer when its new class is built, so it may only build a
        value; and a synchronized method locks its own object, which a move changes."""
        code = item.code
        at = next((i for i, t in enumerate(code) if t.text == '='), None)
        if item.kind == 'initializer' or (item.kind == 'field' and at is not None
                                          and not naming.is_inert(code[at + 1:], self.package)):
            self.problem(where, f'{item.key} runs code when it is built; moving it would change when that '
                                'code runs')
        if 'synchronized' in item.modifiers:
            self.problem(where, f'{item.key} is synchronized, and a move changes the object it locks')


def git(*args):
    return subprocess.run(['git', '-C', str(ROOT), *args], capture_output=True, text=True,
                          check=True).stdout


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    base = sys.argv[1]
    base_sources = {Path(p).name: git('show', f'{base}:{p}')
                    for p in git('ls-tree', '--name-only', base, f'{PACKAGE}/').split() if p.endswith('.java')}
    head_sources = {p.name: p.read_text() for p in sorted((ROOT / PACKAGE).glob('*.java'))}
    check = Check(base_sources, head_sources)
    changed = sorted(n for n in set(base_sources) | set(head_sources)
                     if base_sources.get(n) != head_sources.get(n))
    print(f'Changed files: {", ".join(changed)}')
    for note in check.notes:
        print(note)
    if check.problems:
        for problem in check.problems:
            print(f'PROBLEM {problem}')
        sys.exit(1)
    print('PASS: every member is the same, token by token and in order, apart from what a move allows.')


if __name__ == '__main__':
    main()
