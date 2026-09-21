"""Persistent classroom REST backend with optimistic revisions and idempotency."""
import argparse
import json
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from pathlib import Path

class Store:
    def __init__(self, path):
        self.path = str(path)
        with self.connect() as db:
            db.executescript('CREATE TABLE IF NOT EXISTS issues(id TEXT PRIMARY KEY, data TEXT NOT NULL);'
                             'CREATE TABLE IF NOT EXISTS mutations(token TEXT PRIMARY KEY, request TEXT NOT NULL, response TEXT NOT NULL);')
    def connect(self):
        return sqlite3.connect(self.path, timeout=20)
    def all(self):
        with self.connect() as db:
            return [json.loads(x[0]) for x in db.execute('SELECT data FROM issues ORDER BY id')]
    def one(self, id):
        with self.connect() as db:
            row = db.execute('SELECT data FROM issues WHERE id=?', (id,)).fetchone()
            return json.loads(row[0]) if row else None
    def mutate(self, id, body, deleted=False):
        token = body.get('mutation')
        if not isinstance(token, str) or not token or len(token) > 100:
            return 400, {'error': 'Invalid mutation identifier'}
        fingerprint = json.dumps([id, deleted, body], sort_keys=True)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            prior = db.execute('SELECT request,response FROM mutations WHERE token=?', (token,)).fetchone()
            if prior:
                return (200, json.loads(prior[1])) if prior[0] == fingerprint else (400, {'error': 'Token reused for different data'})
            row = db.execute('SELECT data FROM issues WHERE id=?', (id,)).fetchone()
            old = json.loads(row[0]) if row else None
            if type(body.get('baseRevision')) is not int or body['baseRevision'] < 0:
                return 400, {'error': 'Invalid base revision'}
            if body['baseRevision'] != (old['revision'] if old else 0):
                return 409, {'error': 'Revision conflict'}
            if deleted:
                new = old or dict(id=id, title='', description='', priority='MEDIUM', status='OPEN', createdAt=0)
            else:
                if (not isinstance(body.get('title'), str) or not 1 <= len(body['title'].strip()) <= 120
                    or not isinstance(body.get('description'), str) or len(body['description']) > 10000
                    or body.get('priority') not in ('LOW', 'MEDIUM', 'HIGH')
                    or body.get('status') not in ('OPEN', 'IN_PROGRESS', 'CLOSED')
                    or type(body.get('createdAt')) is not int):
                    return 400, {'error': 'Invalid issue fields'}
                new = {k: body[k] for k in ('title', 'description', 'priority', 'status', 'createdAt')}
                new['id'] = id
                if old: new['createdAt'] = old['createdAt']
            new.update(revision=(old['revision'] if old else 0) + 1, deleted=deleted)
            encoded = json.dumps(new)
            db.execute('INSERT OR REPLACE INTO issues VALUES (?,?)', (id, encoded))
            db.execute('INSERT INTO mutations VALUES (?,?,?)', (token, fingerprint, encoded))
            return 200, new

def handler(store):
    class Handler(BaseHTTPRequestHandler):
        def respond(self, code, body):
            payload = json.dumps(body).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        def route(self):
            parsed = urlparse(self.path)
            pieces = parsed.path.strip('/').split('/')
            if pieces[0] != 'issues' or len(pieces) > 2: return self.respond(404, {})
            if self.command == 'GET':
                result = store.all() if len(pieces) == 1 else store.one(pieces[1])
                return self.respond(200 if result is not None else 404, result or ([] if len(pieces) == 1 else {}))
            if len(pieces) != 2: return self.respond(404, {})
            try:
                if self.command == 'DELETE':
                    query = parse_qs(parsed.query)
                    body = dict(baseRevision=int(query['baseRevision'][0]), mutation=query['mutation'][0])
                else:
                    size = int(self.headers.get('Content-Length', 0))
                    if size < 0 or size > 100000: return self.respond(413, {})
                    body = json.loads(self.rfile.read(size))
                    if not isinstance(body, dict): return self.respond(400, {})
                code, result = store.mutate(pieces[1], body, self.command == 'DELETE')
                self.respond(code, result)
            except (ValueError, KeyError, TypeError): self.respond(400, {'error': 'Malformed request'})
        do_GET = route
        do_PUT = route
        do_DELETE = route
    return Handler

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--db', default=str(Path(__file__).with_name('issues.db')))
    args = parser.parse_args()
    ThreadingHTTPServer(('127.0.0.1', args.port), handler(Store(args.db))).serve_forever()
