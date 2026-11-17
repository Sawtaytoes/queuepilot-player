"""Reads Java source as tokens and splits a top-level class into its members, for move_check.py.

It is not a compiler. It knows enough Java to keep string, character, and text-block literals and
comments as whole tokens; to find where each member of a top-level class begins and ends; and to
mark the tokens inside a nested or anonymous class, where `this` is that object, not the outer one.
"""
import re

TOKEN = re.compile(r'''
    (?P<block>/\*.*?\*/)
  | (?P<line>//[^\n]*)
  | (?P<text>"""(?:\\.|[^\\])*?""")
  | (?P<string>"(?:\\.|[^"\\\n])*")
  | (?P<char>'(?:\\.|[^'\\\n])+')
  | (?P<word>(?:[^\W\d]|\$)(?:\w|\$)*)
  | (?P<number>\d[\w.]*)
  | (?P<op>>>>=|<<=|\.\.\.|->|::|\+\+|--|&&|\|\||[=!<>+\-*/%&|^]=|<<|[^\s\w])
''', re.S | re.X)
# `>>` and `>>>` stay single `>` tokens, because they also close nested generic types; Java has no
# other code where two `>` tokens may stand side by side, so nothing is lost.
KEYWORDS = set('''abstract assert boolean break byte case catch char class const continue default do
double else enum extends final finally float for goto if implements import instanceof int interface
long native new package private protected public return short static strictfp super switch
synchronized this throw throws transient try void volatile while var yield record true false
null'''.split())
TYPE_WORDS = {'class', 'interface', 'enum', 'record'}
MODIFIERS = {'public', 'protected', 'private', 'static', 'final', 'abstract', 'synchronized',
             'volatile', 'transient', 'native', 'default', 'strictfp', 'sealed', 'non-sealed'}


class Token:
    __slots__ = ('kind', 'text', 'line', 'depth')

    def __init__(self, kind, text, line):
        self.kind, self.text, self.line = kind, text, line
        self.depth = 0  # how many class bodies, the top-level one included, hold this token

    @property
    def is_nested(self):
        """Inside a nested or anonymous class body, where `this` is that object."""
        return self.depth >= 2

    @property
    def is_code(self):
        return self.kind not in ('block', 'line')

    def __repr__(self):
        return self.text


def tokens(source):
    found, line, last, end = [], 1, 0, 0
    for match in TOKEN.finditer(source):
        line += source.count('\n', last, match.start())
        last = match.start()
        if source[end:match.start()].strip():
            raise ValueError(f'unreadable text at line {line}: {source[end:match.start()].strip()[:20]!r}')
        end = match.end()
        found.append(Token(match.lastgroup, match.group(), line))
    if source[end:].strip():
        raise ValueError('unreadable text at the end')
    _mark_nested([t for t in found if t.is_code])
    return found


def _is_type_word(code, index):
    text = code[index].text
    if text not in TYPE_WORDS or (index and code[index - 1].text in ('.', '@')):
        return False
    if text == 'record':  # `record` is a type only in `record Name(`; elsewhere it is a name
        return index + 2 < len(code) and code[index + 1].kind == 'word' and code[index + 2].text in '(<'
    return True


def _mark_nested(code):
    stack, is_pending = [], False  # stack: one entry per open brace, True when it opens a class body
    for index, token in enumerate(code):
        if _is_type_word(code, index):
            is_pending = True
        elif token.text == '{':
            stack.append(is_pending or _opens_anonymous(code, index))
            is_pending = False
        token.depth = sum(stack)
        if token.text == '}' and stack:
            stack.pop()


def _opens_anonymous(code, index):
    """True when the `{` at index follows `new Type(...)`, which opens an anonymous class."""
    if index == 0 or code[index - 1].text != ')':
        return False
    back, depth = index - 1, 0
    while back >= 0:
        depth += {')': 1, '(': -1}.get(code[back].text, 0)
        if depth == 0:
            break
        back -= 1
    back, angle = back - 1, 0
    while back >= 0:
        text = code[back].text
        if text == '>':
            angle += 1
        elif text == '<':
            angle -= 1
        elif angle == 0 and text == 'new':
            return True
        elif angle == 0 and code[back].kind != 'word' and text != '.':
            return False
        back -= 1
    return False


class Member:
    def __init__(self, kind, names, key, tokens, header, params=None):
        self.kind, self.names, self.key = kind, names, key
        self.tokens = tokens  # every token of the member, its leading comments included
        self.header = header  # the code tokens before its body, initializer, or semicolon
        self.params = params  # a method's parameter types

    @property
    def modifiers(self):
        return [t.text for t in self.header if t.text in MODIFIERS]

    @property
    def code(self):
        return [t for t in self.tokens if t.is_code]

    @property
    def line(self):
        return self.code[0].line if self.code else self.tokens[0].line

    def __repr__(self):
        return self.key


def split(found):
    """(prologue, class head, members, epilogue) of the file's top-level class."""
    keyword = next((i for i, t in enumerate(found) if t.is_code and t.text in TYPE_WORDS), None)
    if keyword is None:
        raise ValueError('no top-level class')
    open_at = next(i for i in range(keyword, len(found)) if found[i].text == '{')
    start = keyword
    while start > 0 and not (found[start - 1].is_code and found[start - 1].text == ';'):
        start -= 1
    members, current, depth, has_body, has_value = [], [], 0, False, False
    for index in range(open_at + 1, len(found)):
        token = found[index]
        if token.is_code and depth == 0 and token.text == '}':
            if any(t.is_code for t in current):
                raise ValueError(f'unterminated member at line {current[0].line}')
            _number_duplicates(members)
            return found[:start], found[start:open_at + 1], members, current + found[index:]
        if not token.is_code and not current and members and token.line == members[-1].tokens[-1].line:
            members[-1].tokens.append(token)  # a comment after a member, on its last line
            continue
        current.append(token)
        if not token.is_code:
            continue
        if token.text in ('(', '[', '{'):
            has_body = has_body or (token.text == '{' and depth == 0 and not has_value)
            depth += 1
        elif token.text in (')', ']', '}'):
            depth -= 1
            if token.text == '}' and depth == 0 and has_body:
                members.append(member(current))
                current, has_body, has_value = [], False, False
        elif token.text == '=' and depth == 0:
            has_value = has_value or not has_body
        elif token.text == ';' and depth == 0:
            members.append(member(current))
            current, has_body, has_value = [], False, False
    raise ValueError('unterminated class')


def _number_duplicates(members):
    seen = {}
    for item in members:
        seen[item.key] = seen.get(item.key, 0) + 1
        if seen[item.key] > 1:
            item.key += f' #{seen[item.key]}'


def member(member_tokens):
    code = [t for t in member_tokens if t.is_code]
    header, depth = [], 0
    for token in code:
        if depth == 0 and token.text in ('{', '=', ';'):
            break
        depth += {'(': 1, '<': 1, ')': -1, '>': -1}.get(token.text, 0)
        header.append(token)
    plain = without_annotations(header)
    texts = [t.text for t in plain]
    for index, text in enumerate(texts):
        if text in TYPE_WORDS and index + 1 < len(plain) and plain[index + 1].kind == 'word':
            return Member('type', [texts[index + 1]], f'type {texts[index + 1]}', member_tokens, header)
    if all(text in MODIFIERS for text in texts):
        return Member('initializer', [], ' '.join(texts + ['initializer']), member_tokens, header)
    if '(' in texts:
        at = texts.index('(')
        params = _parameters(plain[at:])
        key = f'method {texts[at - 1]}({", ".join(params)})'
        return Member('method', [texts[at - 1]], key, member_tokens, header, params)
    names = _declarators(code)
    return Member('field', names, 'field ' + ','.join(names), member_tokens, header)


def without_annotations(header):
    out, index = [], 0
    while index < len(header):
        if header[index].text == '@' and index + 1 < len(header) and header[index + 1].text != 'interface':
            index += 2
            while index + 1 < len(header) and header[index].text == '.':
                index += 2
            if index < len(header) and header[index].text == '(':
                depth = 0
                while index < len(header):
                    depth += {'(': 1, ')': -1}.get(header[index].text, 0)
                    index += 1
                    if depth == 0:
                        break
            continue
        out.append(header[index])
        index += 1
    return out


def _parameters(tokens_from_paren):
    """Parameter types, without names, annotations, `final`, or package and outer-class prefixes."""
    depth, end = 0, len(tokens_from_paren)
    for index, token in enumerate(tokens_from_paren):
        depth += {'(': 1, ')': -1}.get(token.text, 0)
        if depth == 0:
            end = index
            break
    groups, current, angle = [], [], 0
    for token in without_annotations(tokens_from_paren[1:end]):
        angle += {'<': 1, '>': -1}.get(token.text, 0)
        if token.text == ',' and angle == 0:
            groups.append(current)
            current = []
        elif token.text != 'final':
            current.append(token)
    if current:
        groups.append(current)
    types = []
    for group in groups:
        kept = [t.text for i, t in enumerate(group[:-1])
                if not (t.kind == 'word' and i + 1 < len(group) - 1 and group[i + 1].text == '.')
                and not (t.text == '.' and i > 0 and group[i - 1].kind == 'word')]
        types.append(''.join(kept))
    return types


def _declarators(code):
    names, depth, angle, is_value, previous = [], 0, 0, False, None
    for token in code:
        text = token.text
        if text in ('(', '[', '{'):
            depth += 1
        elif text in (')', ']', '}'):
            depth -= 1
        elif depth == 0 and not is_value and text in ('<', '>'):
            angle += 1 if text == '<' else -1
        elif depth == 0 and angle == 0 and text in ('=', ',', ';'):
            if not is_value and previous is not None and previous.kind == 'word':
                names.append(previous.text)
            is_value = text == '='
        previous = token
    return names


def text(tokens):
    return ' '.join(t.text for t in tokens if t.is_code)


class JavaFile:
    def __init__(self, name, source):
        self.name, self.source = name, source
        self.prologue, self.head, self.members, self.epilogue = split(tokens(source))
        words = [t for t in self.head if t.is_code]
        self.cls = next(words[i + 1].text for i, t in enumerate(words) if t.text in TYPE_WORDS)

    def epilogue_text(self):
        return ' '.join(' '.join(t.text.split()) for t in self.epilogue)

    def statements(self):
        return [s.strip() + ' ;' for s in text(self.prologue).split(';') if s.strip()]
