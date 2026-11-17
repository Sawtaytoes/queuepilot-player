"""What names in Java code mean, as far as tools/move_check.py needs: the variables and
methods a member declares, call arguments, overloads, and initializers.

Like tools/java_source.py, it reads code shape, not types.
"""
from java_source import KEYWORDS


def locals_of(code, nest=1):
    """(variables, methods) that a member declares: parameters, local variables, lambda
    parameters, and its anonymous classes' fields; and its anonymous classes' methods (code
    deeper than `nest` class bodies). A name counts in the whole member, whatever its scope."""
    found, methods, depth, declaring = set(), set(), 0, []  # declaring: depths of open declarations
    for index in range(1, len(code) - 1):
        token, before, after = code[index], code[index - 1], code[index + 1]
        if token.text in ('(', '[', '{'):
            depth += 1
        elif token.text in (')', ']', '}'):
            depth -= 1
            declaring = [d for d in declaring if d <= depth]
        elif token.text == ';':
            declaring = [d for d in declaring if d < depth]
        if token.kind != 'word' or token.text in KEYWORDS:
            continue
        if before.text == ',' and declaring and declaring[-1] == depth and after.text in ('=', ';', ','):
            found.add(token.text)  # the next name in `int a = 1, b = 2;`
            continue
        is_typed = (before.kind == 'word' and before.text not in KEYWORDS - PRIMITIVES) \
            or before.text in ('>', ']')
        if is_typed and after.text in ('=', ';', ',', ')', ':'):
            found.add(token.text)
            if after.text in ('=', ';', ',') and before.text != ',':
                declaring.append(depth)
        elif is_typed and after.text == '(' and token.depth > nest:
            methods.add(token.text)
        elif after.text == '->' and before.text in ('(', ',', '=', 'return', '->', '?', ':'):
            found.add(token.text)
        elif after.text in (',', ')') and before.text in ('(', ','):
            close = next((k for k in range(index, len(code)) if code[k].text == ')'), None)
            if close is not None and close + 1 < len(code) and code[close + 1].text == '->':
                found.add(token.text)
    return found, methods


NOT_MEMBERS = {'this', 'class', 'new', 'super'}
PRIMITIVES = {'boolean', 'byte', 'char', 'double', 'float', 'int', 'long', 'short', 'void', 'var', 'final'}


def arguments(code, at):
    """The number of arguments in the call after code[at], or None when it is not a call."""
    if at + 1 >= len(code) or code[at + 1].text != '(':
        return None
    depth, count, is_empty = 0, 1, True
    for token in code[at + 1:]:
        depth += {'(': 1, '[': 1, '{': 1, ')': -1, ']': -1, '}': -1}.get(token.text, 0)
        if depth == 0:
            return 0 if is_empty else count
        if depth == 1 and token.text == ',':
            count += 1
        if token.text != '(' or depth > 1:
            is_empty = False
    return None


def is_inert(code, package_classes):
    """True when an initializer only builds a value: literals, array initializers, and `new` of a
    class outside the package with no arguments. It reads no other field and calls no code of
    the package directly, though such a constructor may have effects, or call back."""
    index = 0
    while index < len(code):
        token = code[index]
        if token.kind in ('string', 'char', 'text', 'number') or token.text in (
                'true', 'false', 'null', '{', '}', ',', '-', '+', ';'):
            index += 1
            continue
        if token.text != 'new':
            return False
        index += 1
        names = []
        while index < len(code) and (code[index].kind == 'word' or code[index].text == '.'):
            names += [code[index].text] if code[index].kind == 'word' else []
            index += 1
        if not names or names[-1] in package_classes:
            return False
        depth = 0
        while index < len(code) and (depth or code[index].text == '<'):  # type arguments
            depth += {'<': 1, '>': -1}.get(code[index].text, 0)
            index += 1
        if [t.text for t in code[index:index + 2]] == ['(', ')']:
            index += 2
            continue
        while index < len(code) and code[index].text == '[':  # `new int[]`, `new int[3]`
            close = index + (2 if code[index + 1].text == ']' else 3)
            if code[close - 1].text != ']' or (close - index == 3 and code[index + 1].kind != 'number'):
                return False
            index = close
    return True


def by_name(members):
    found = {}
    for item in members:
        for name in item.names:
            found.setdefault(name, []).append(item)
    return found


def fits(item, count):
    """Whether a member can be the one meant by a reference: a field or type with no `count`, a
    method with a `reference` (`::name`), or a method that takes `count` arguments."""
    if count is None:
        return item.kind != 'method'
    if item.kind != 'method':
        return False
    if count == 'reference':
        return True
    params = item.params or []
    return len(params) == count or (bool(params) and params[-1].endswith('...') and count >= len(params) - 1)


def is_member(code, at):
    """Whether code[at:at + 2] is `.name` or `::name`, a member access."""
    return at + 1 < len(code) and code[at].text in ('.', '::') and code[at + 1].kind == 'word' \
        and code[at + 1].text not in NOT_MEMBERS


def inherited(cls, name):
    """A name the package does not declare comes from a superclass, such as the activity's
    runOnUiThread, and reads the same however it is reached. Object's methods differ in each
    class, so they keep their class."""
    return f'<{cls}>.{name}' if name in OBJECT_METHODS else name


OBJECT_METHODS = {'equals', 'hashCode', 'toString', 'getClass', 'notify', 'notifyAll', 'wait', 'clone', 'finalize'}


def reaches(code, names, is_block=False):
    """Whether a field's initializer, or an initializer block, may run the activity's code: it
    names `this`, `host`, or an activity member or holder, or calls a method without a qualifier."""
    at = -1 if is_block else next((i for i, t in enumerate(code) if t.text == '='), len(code))
    for index in range(at + 1, len(code)):
        token, before = code[index], code[index - 1]
        if token.kind != 'word' or before.text in ('.', '::', 'new'):
            continue
        if token.text in ('this', 'host') or token.text in names or arguments(code, index) is not None:
            return True
    return False
