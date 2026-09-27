"""Synthetic, read-only fixture for the Space view performance and failure tests.

SAFETY CONTRACT (do not weaken):
  * Serves ONLY in-memory synthetic data on 127.0.0.1 (default port 18086). It never opens the
    application database, Qdrant, Neo4j or the shared reference root and never writes anything.
  * Every project key is prefixed DEMO_ and every text starts with "ÖRNEK VERİ".
  * It mimics the existing read-only /workspace/api surface used by /universe.html.

Scale (plan §3.3): 5 000 symbols, 15 000 call edges, 300 memories, 40 rules.
Failure injection: GET /__fixture/slow?ms=N delays every API answer; /__fixture/down makes the API
return 503 until /__fixture/up.

Run:  python3 src/test/browser/space_scale_fixture.py [port]
Then: http://127.0.0.1:18086/universe.html?project=DEMO_SCALE
"""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs
import json
import random
import sys
import time

ROOT = Path(__file__).resolve().parents[2] / 'main/resources/static'
PROJECT = 'DEMO_SCALE'
DEMO = 'ÖRNEK VERİ — '
rng = random.Random(42)

MODULES = ['billing', 'catalog', 'identity', 'inventory', 'notifications', 'orders', 'payments', 'reporting',
           'search', 'shipping']
LAYERS = ['api', 'service', 'domain', 'persistence', 'events', 'config']

symbols, edges = [], []
classes = []
sid = 0
while len(symbols) < 5000:
    module = MODULES[len(classes) % len(MODULES)]
    layer = LAYERS[(len(classes) // len(MODULES)) % len(LAYERS)]
    name = f'{module.capitalize()}{layer.capitalize()}{len(classes):03d}'
    package = f'demo.{module}.{layer}'
    path = f'{module}/src/main/java/demo/{module}/{layer}/{name}.java'
    cid = f'00000000-0000-4000-8000-{sid:012d}'
    sid += 1
    symbols.append(dict(id=cid, project_key=PROJECT, symbol_kind='class', name=name, fqn=f'{package}.{name}',
                        role=None, start_line=1, file_path=path))
    classes.append(cid)
    for m in range(rng.randint(3, 11)):
        if len(symbols) >= 5000:
            break
        mid = f'00000000-0000-4000-8000-{sid:012d}'
        sid += 1
        method = rng.choice(['find', 'save', 'validate', 'map', 'publish', 'load', 'apply', 'handle']) + str(m)
        symbols.append(dict(id=mid, project_key=PROJECT, symbol_kind='method', name=method,
                            fqn=f'{package}.{name}#{method}', role=None, start_line=10 + m * 12, file_path=path))

members = [s['id'] for s in symbols if s['symbol_kind'] == 'method']
for k in range(15000):
    a = rng.choice(members)
    b = rng.choice(members if rng.random() < 0.8 else classes)
    if a != b:
        edges.append(dict(id=f'10000000-0000-4000-8000-{k:012d}', source_symbol_id=a, target_symbol_id=b,
                          target_ref=None, edge_type='CALLS', resolution='resolved', confidence=1.0))

memories, memory_links = [], []
for k in range(300):
    mid = f'20000000-0000-4000-8000-{k:012d}'
    target = rng.choice(classes)
    memories.append(dict(id='memory:' + mid, kind='memory', title=f'Karar {k}: {rng.choice(MODULES)} akışı',
                         status='active', project=PROJECT, updatedAt='2026-09-20T10:00:00Z',
                         text=DEMO + f'Sentetik hafıza kaydı {k}.', tags=[], editableFields=[], blockedReason=None,
                         info={'memoryType': rng.choice(['decision', 'discovery', 'correction'])}))
    memory_links.append(dict(id=f'l{k}', source='memory:' + mid, target='symbol:' + target, label='CONSTRAINS',
                             evidence=None, inferred=False, stale=False))

rules = []
for k in range(40):
    rid = f'30000000-0000-4000-8000-{k:012d}'
    module = MODULES[k % len(MODULES)]
    rules.append(dict(id='rule:' + rid, kind='rule', title=DEMO + f'{module} modülünde DTO kullan ({k})',
                      status='active', project=PROJECT if k else None, updatedAt='2026-09-20T10:00:00Z',
                      text=DEMO + 'Kural metni.', tags=[], editableFields=[], blockedReason=None,
                      info={'version': 1, 'appliesAll': k % 3 == 0, 'module': module}))

STATE = {'slow_ms': 0, 'down': False}


def page(items, cursor, limit, key='id'):
    start = 0
    if cursor:
        start = next((i + 1 for i, row in enumerate(items) if row[key] == cursor), len(items))
    chunk = items[start:start + limit]
    more = start + limit < len(items)
    return chunk, (chunk[-1][key] if more and chunk else None)


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def log_message(self, *args):
        pass

    def send_json(self, body, status=200):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path.startswith('/__fixture/'):
            if url.path == '/__fixture/slow':
                STATE['slow_ms'] = int(q.get('ms', '0'))
            elif url.path == '/__fixture/down':
                STATE['down'] = True
            elif url.path == '/__fixture/up':
                STATE['down'] = False
            return self.send_json(STATE)
        if not url.path.startswith('/workspace/api/'):
            return super().do_GET()
        if STATE['slow_ms']:
            time.sleep(STATE['slow_ms'] / 1000)
        if STATE['down']:
            return self.send_json({'error': 'fixture down'}, 503)
        path = url.path[len('/workspace/api/'):]
        if path == 'projects':
            return self.send_json({'projects': [PROJECT, 'DEMO_EMPTY'], 'defaultProject': PROJECT})
        if path == 'health':
            return self.send_json({'status': 'UP'})
        project = q.get('project', '')
        if path == 'code-graph':
            if project != PROJECT:
                return self.send_json({'items': [], 'nextCursor': None})
            rows = symbols if q.get('part') == 'nodes' else edges
            chunk, cursor = page(rows, q.get('cursor'), int(q.get('limit', '5000')))
            return self.send_json({'items': chunk, 'nextCursor': cursor})
        if path == 'code-memory':
            if project != PROJECT:
                return self.send_json({'nodes': [], 'links': [], 'nextCursor': None, 'warnings': []})
            chunk, cursor = page(memories, q.get('cursor'), 40)
            ids = {m['id'] for m in chunk}
            links = [l for l in memory_links if l['source'] in ids]
            targets = [dict(id=l['target'], kind='symbol', title=l['target'], status='recorded', project=PROJECT)
                       for l in links]
            return self.send_json({'nodes': chunk + targets, 'links': links, 'nextCursor': cursor, 'warnings': []})
        if path == 'items':
            kind = q.get('kind')
            source = memories if kind == 'memory' else rules if kind == 'rule' else []
            if project not in ('', PROJECT):
                source = []
            text = q.get('query', '').lower()
            rows = [r for r in source if not text or text in r['title'].lower()]
            chunk, cursor = page(rows, q.get('cursor'), int(q.get('limit', '50')))
            return self.send_json({'items': chunk, 'nextCursor': cursor})
        if path.startswith('rules/') and path.endswith('/scope'):
            rid = 'rule:' + path.split('/')[1]
            rule = next((r for r in rules if r['id'] == rid), None)
            module = rule['info']['module'] if rule else 'billing'
            return self.send_json({'ruleId': rid, 'modulePaths': [f'{module}/src/main/java/demo/{module}/api']})
        if path.endswith('/neighbors'):
            return self.send_json({'nodes': [], 'links': [], 'nextCursor': None, 'warnings': []})
        return self.send_json({'error': 'not in fixture'}, 404)


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18086
    print(f'fixture: {len(symbols)} symbols, {len(edges)} edges, {len(memories)} memories, {len(rules)} rules '
          f'on http://127.0.0.1:{port}/universe.html?project={PROJECT}')
    ThreadingHTTPServer(('127.0.0.1', port), Handler).serve_forever()
