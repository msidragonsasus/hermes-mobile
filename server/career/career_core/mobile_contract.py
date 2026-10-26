"""Bounded SDUI v1. No arbitrary actions, paths, URLs, code or payload pass-through."""
import re

APP_ID = 'career.mypage'
ACTIONS = {'refresh', 'mypage.check', 'native.settings', 'native.browser'}
TOP_KEYS = {'app_id', 'protocol_version', 'component_version', 'release_id',
            'company_id', 'profile', 'title', 'state', 'components'}


def bounded_string(value, limit=2000):
    if not isinstance(value, str) or len(value) > limit or '\x00' in value:
        raise ValueError('invalid_string')
    return value


def validate_protocol(value):
    if set(value) != {'app_id', 'protocol_version', 'component_version', 'release_id'}:
        raise ValueError('unsupported_protocol')
    if value['app_id'] != APP_ID or type(value['protocol_version']) is not int or value['protocol_version'] != 1:
        raise ValueError('unsupported_protocol')
    if type(value['component_version']) is not int or value['component_version'] != 1:
        raise ValueError('unsupported_component')
    if not re.fullmatch(r'[A-Za-z0-9._-]{1,80}', value['release_id']):
        raise ValueError('invalid_release')


def validate_components(components):
    count = 0

    def nodes(items, depth):
        nonlocal count
        if not isinstance(items, list) or depth > 6:
            raise ValueError('component_depth')
        for node in items:
            count += 1
            if count > 64 or not isinstance(node, dict):
                raise ValueError('component_count')
            kind = node.get('type')
            keys = {'Section': {'type', 'title', 'children'}, 'Column': {'type', 'children'},
                    'Text': {'type', 'text'}, 'Status': {'type', 'text', 'tone'},
                    'KeyValue': {'type', 'label', 'value'},
                    'Button': {'type', 'label', 'action', 'enabled'}}.get(kind)
            if keys is None or set(node) != keys:
                raise ValueError('unsupported_component')
            for field in keys - {'type', 'children', 'enabled'}:
                limit = 200 if field in {'label', 'title'} else 2000
                bounded_string(node[field], limit)
                if field in {'label', 'title'} and not node[field].strip():
                    raise ValueError('empty_label')
            if kind in {'Section', 'Column'}:
                nodes(node['children'], depth + 1)
            elif kind == 'Button' and (node['action'] not in ACTIONS or type(node['enabled']) is not bool):
                raise ValueError('unsupported_action')
            elif kind == 'Status' and node['tone'] not in {'info', 'success', 'warning', 'error'}:
                raise ValueError('unsupported_status')
    nodes(components, 1)


def validate_screen(screen):
    if not isinstance(screen, dict) or set(screen) != TOP_KEYS:
        raise ValueError('unsupported_screen')
    validate_protocol({key: screen[key] for key in {'app_id', 'protocol_version', 'component_version', 'release_id'}})
    for key, limit in {'company_id': 128, 'profile': 100, 'title': 200, 'state': 64}.items():
        bounded_string(screen[key], limit)
        if not screen[key].strip():
            raise ValueError('empty_screen_identity')
    validate_components(screen['components'])
