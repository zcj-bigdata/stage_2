"""Checks that the running services match the API specification.

Calls every endpoint of the four services and checks each response against openapi.yaml:
the status code is documented, the required fields are present, there are no undocumented
fields, and the values have the documented types and enum values.

Start the four services with the sample books first (see the README), then run from the
repository root:

    pip install pyyaml
    python docs/api/check_spec.py docs/api/openapi.yaml

Expected result: "24/24 responses match the specification" and exit code 0.
"""
import json
import sys
import urllib.error
import urllib.request

import yaml

spec = yaml.safe_load(open(sys.argv[1], encoding='utf8'))
schemas = spec['components']['schemas']
responses = spec['components']['responses']

BASE = {'7000': 'http://localhost:7000', '7001': 'http://localhost:7001',
        '7002': 'http://localhost:7002', '7003': 'http://localhost:7003'}
TYPES = {'integer': int, 'number': (int, float), 'string': str, 'boolean': bool,
         'array': list, 'object': dict}


def resolve(node):
    while '$ref' in node:
        parts = node['$ref'].split('/')
        node = spec
        for p in parts[1:]:
            node = node[p]
    return node


def props(schema):
    schema = resolve(schema)
    if 'allOf' in schema:
        req, pr = set(), {}
        for sub in schema['allOf']:
            r, p = props(sub)
            req |= r
            pr.update(p)
        return req, pr
    return set(schema.get('required', [])), schema.get('properties', {})


def check(value, schema, where, problems):
    schema = resolve(schema)
    if value is None:
        if not schema.get('nullable'):
            problems.append(f'{where}: null but not nullable')
        return
    if 'oneOf' in schema:
        return
    t = schema.get('type')
    if t == 'object' or 'allOf' in schema or 'properties' in schema:
        req, pr = props(schema)
        for k in req - value.keys():
            problems.append(f'{where}: missing required field {k}')
        for k in value.keys() - pr.keys():
            if pr:
                problems.append(f'{where}: undocumented field {k}')
        for k, v in value.items():
            if k in pr:
                check(v, pr[k], f'{where}.{k}', problems)
    elif t == 'array':
        for i, item in enumerate(value[:3]):
            check(item, schema.get('items', {}), f'{where}[{i}]', problems)
    elif t:
        ok = isinstance(value, TYPES[t]) and not (t in ('integer', 'number') and isinstance(value, bool))
        if not ok:
            problems.append(f'{where}: expected {t}, got {type(value).__name__}')
        if 'enum' in schema and value not in schema['enum']:
            problems.append(f'{where}: {value!r} not in enum {schema["enum"]}')


def call(method, port, path, spec_path):
    req = urllib.request.Request(BASE[port] + path, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            status, body = r.status, json.loads(r.read() or b'null')
    except urllib.error.HTTPError as e:
        status, body = e.code, json.loads(e.read() or b'null')
    problems = []
    op = spec['paths'].get(spec_path, {}).get(method.lower())
    if op is None:
        print(f'FAIL {method} :{port}{path} -> {status}')
        print(f'        route {method} {spec_path} is not in the specification')
        return False
    resp = op['responses'].get(str(status))
    if resp is None:
        problems.append(f'status {status} not documented')
    else:
        resp = resolve(resp)
        schema = resp['content']['application/json']['schema']
        check(body, schema, 'body', problems)
    print(f"{'OK  ' if not problems else 'FAIL'} {method} :{port}{path} -> {status}")
    for p in problems:
        print('       ', p)
    return not problems


cases = [
    ('GET', '7003', '/search?q=darcy', '/search'),
    ('GET', '7003', '/search?q=the&author=doyle&language=en&year=1999', '/search'),
    ('GET', '7003', '/search?q=zzzzqx', '/search'),
    ('GET', '7003', '/search?q=the&limit=500', '/search'),
    ('GET', '7003', '/books/1342', '/books/{book_id}'),
    ('GET', '7003', '/books/999999', '/books/{book_id}'),
    ('GET', '7003', '/books/abc', '/books/{book_id}'),
    ('GET', '7003', '/search/filters', '/search/filters'),
    ('POST', '7001', '/ingest/1342', '/ingest/{book_id}'),
    ('POST', '7001', '/ingest/abc', '/ingest/{book_id}'),
    ('GET', '7001', '/ingest/status/1342', '/ingest/status/{book_id}'),
    ('GET', '7001', '/ingest/status/5', '/ingest/status/{book_id}'),
    ('GET', '7001', '/ingest/list', '/ingest/list'),
    ('POST', '7002', '/index/update/1342', '/index/update/{book_id}'),
    ('POST', '7002', '/index/update/999999', '/index/update/{book_id}'),
    ('POST', '7002', '/index/update/abc', '/index/update/{book_id}'),
    ('POST', '7002', '/index/rebuild', '/index/rebuild'),
    ('GET', '7002', '/index/status', '/index/status'),
    ('GET', '7000', '/control/status', '/control/status'),
    ('GET', '7000', '/control/metrics', '/control/metrics'),
] + [('GET', p, '/status', '/status') for p in ('7000', '7001', '7002', '7003')]

results = [call(*c) for c in cases]
print(f'\n{sum(results)}/{len(results)} responses match the specification')
sys.exit(0 if all(results) else 1)
