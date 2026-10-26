"""Bounded App Bot policy interpreter. No eval, exec, imports or arbitrary calls.

Unlike a fixed mode selector, candidates can edit state-dependent business
decisions with assignments, comparisons, if/else and early returns. The host
supplies only a scoped status and enqueue/refusal capabilities, never credentials,
page bodies, files, shell, environment or network handles. This is a small DSL
using Python syntax, not a general Python or OS sandbox.
"""
import ast

from .store import CareerError

STATES = {'idle', 'queued', 'running', 'collecting', 'analyzing', 'auth_required',
          'failed', 'succeeded', 'not_connected', 'disabled', 'partial', 'interrupted', 'unknown'}
REFUSALS = {'mypage_busy', 'mypage_auth_required', 'mypage_not_connected', 'mypage_not_ready', 'mypage_closed'}
ENQUEUE = {'enqueue_existing', 'enqueue_ready'}


def error():
    raise ValueError('restricted_bot_policy')


def scoped_call(node, methods):
    if (not isinstance(node, ast.Call) or node.keywords or not isinstance(node.func, ast.Attribute)
            or not isinstance(node.func.value, ast.Name) or node.func.value.id != 'adapter'
            or node.func.attr not in methods):
        error()
    if node.func.attr == 'refuse':
        if len(node.args) != 1 or not isinstance(node.args[0], ast.Constant) or node.args[0].value not in REFUSALS:
            error()
    elif (len(node.args) != 2 or any(not isinstance(arg, ast.Name) for arg in node.args)
          or [arg.id for arg in node.args] != ['company_id', 'profile']):
        error()
    return node.func.attr


def expression(node, assigned):
    if isinstance(node, ast.Name):
        if node.id != 'state' or not assigned: error()
    elif isinstance(node, ast.Constant):
        if type(node.value) is not bool and node.value not in STATES: error()
    elif isinstance(node, ast.Tuple):
        if not 1 <= len(node.elts) <= 12 or any(not isinstance(e, ast.Constant) or e.value not in STATES for e in node.elts): error()
    elif isinstance(node, ast.BoolOp):
        if not isinstance(node.op, (ast.And, ast.Or)) or not 2 <= len(node.values) <= 6: error()
        for value in node.values: expression(value, assigned)
    elif isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not):
        expression(node.operand, assigned)
    elif isinstance(node, ast.Compare):
        if len(node.ops) != 1 or not isinstance(node.ops[0], (ast.Eq, ast.NotEq, ast.In, ast.NotIn)): error()
        expression(node.left, assigned); expression(node.comparators[0], assigned)
    else: error()


def block(nodes, assigned=False, depth=0):
    if depth > 6 or len(nodes) > 16: error()
    for node in nodes:
        if isinstance(node, ast.Assign):
            if len(node.targets) != 1 or not isinstance(node.targets[0], ast.Name) or node.targets[0].id != 'state': error()
            scoped_call(node.value, {'read_state'}); assigned = True
        elif isinstance(node, ast.If):
            expression(node.test, assigned)
            body_assigned = block(node.body, assigned, depth+1)
            else_assigned = block(node.orelse, assigned, depth+1)
            assigned = body_assigned and else_assigned
        elif isinstance(node, ast.Return):
            scoped_call(node.value, ENQUEUE | {'refuse'})
        else: error()
    return assigned


def parse(source):
    tree = ast.parse(source)
    if sum(1 for _ in ast.walk(tree)) > 192: error()
    def strip_doc(nodes):
        nodes = list(nodes)
        if nodes and isinstance(nodes[0], ast.Expr) and isinstance(nodes[0].value, ast.Constant) and isinstance(nodes[0].value.value, str): nodes.pop(0)
        return nodes
    top = strip_doc(tree.body)
    if len(top) != 1 or not isinstance(top[0], ast.FunctionDef): error()
    fn = top[0]; args = fn.args
    if (fn.name != 'enqueue' or fn.decorator_list or fn.returns or fn.type_comment or getattr(fn, 'type_params', [])
            or args.posonlyargs or args.kwonlyargs or args.vararg or args.kwarg or args.defaults
            or [arg.arg for arg in args.args] != ['adapter', 'company_id', 'profile']
            or any(arg.annotation or arg.type_comment for arg in args.args)): error()
    body = strip_doc(fn.body)
    if not body: error()
    block(body)
    return body


def kind(source):
    body = parse(source)
    return scoped_call(body[0].value, ENQUEUE | {'refuse'}) if len(body) == 1 and isinstance(body[0], ast.Return) else 'policy'


def evaluate(body, adapter, company_id, profile):
    state_value = None
    def state():
        nonlocal state_value
        if state_value is None:
            from .mypage_bot import connection_id
            if (company_id, profile) != (adapter.company_id, adapter.profile):
                raise CareerError('mobile_target_mismatch', 'App target mismatch.', 409)
            row = adapter.records.get(connection_id(company_id, profile))
            value = (row.get('status') or {}).get('state', 'idle') if row else 'not_connected'
            state_value = value if isinstance(value, str) and value in STATES else 'unknown'
        return state_value
    def value(node):
        if isinstance(node, ast.Name): return state()
        if isinstance(node, ast.Constant): return node.value
        if isinstance(node, ast.Tuple): return tuple(item.value for item in node.elts)
        if isinstance(node, ast.UnaryOp): return not value(node.operand)
        if isinstance(node, ast.BoolOp):
            return all(value(item) for item in node.values) if isinstance(node.op, ast.And) else any(value(item) for item in node.values)
        if isinstance(node, ast.Compare):
            left,right=value(node.left),value(node.comparators[0]); op=node.ops[0]
            if isinstance(op, ast.Eq): return left == right
            if isinstance(op, ast.NotEq): return left != right
            if isinstance(op, ast.In): return left in right
            return left not in right
        error()
    def execute(nodes):
        for node in nodes:
            if isinstance(node, ast.Assign): state()
            elif isinstance(node, ast.If):
                done,result=execute(node.body if value(node.test) else node.orelse)
                if done: return done,result
            else:
                method=node.value.func.attr
                if method == 'refuse':
                    raise CareerError(node.value.args[0].value, 'App policy declined the check.', 409)
                if method == 'enqueue_ready' and state() in {'not_connected','auth_required','queued','running','collecting','analyzing'}:
                    raise CareerError('mypage_not_ready', 'App policy declined the check.', 409)
                return True,adapter.enqueue_existing(company_id, profile)
        return False,None
    done,result=execute(body)
    if not done: error()
    return result
