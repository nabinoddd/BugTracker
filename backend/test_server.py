import unittest
import tempfile
import uuid
from pathlib import Path
from server import Store

class BackendTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).parent)
        self.path = Path(self.temp.name) / 'test.db'
        self.store = Store(self.path)
    def tearDown(self): self.temp.cleanup()
    def body(self, rev=0, title='Crash on login'):
        return dict(title=title, description='Steps to reproduce', priority='HIGH', status='OPEN',
                    createdAt=1000, baseRevision=rev, mutation=str(uuid.uuid4()))
    def test_crud_and_durable_remote_tombstone(self):
        self.assertEqual(200, self.store.mutate('a', self.body())[0])
        self.assertEqual('Crash on login', self.store.one('a')['title'])
        self.assertEqual(200, self.store.mutate('a', self.body(1, 'Fixed'))[0])
        self.assertEqual('Fixed', Store(self.path).one('a')['title'])
        self.assertEqual(200, self.store.mutate('a', dict(baseRevision=2, mutation='delete-a'), True)[0])
        self.assertTrue(Store(self.path).all()[0]['deleted'])
    def test_lost_response_retry_is_idempotent(self):
        body = self.body()
        first = self.store.mutate('a', body)
        self.assertEqual(first, self.store.mutate('a', body))
        self.assertEqual(1, self.store.one('a')['revision'])
    def test_conflict_preserves_server_version(self):
        self.store.mutate('a', self.body())
        self.assertEqual(409, self.store.mutate('a', self.body(0, 'Stale'))[0])
        self.assertEqual('Crash on login', self.store.one('a')['title'])
    def test_delete_before_create_and_retry(self):
        body = dict(baseRevision=0, mutation='delete-new')
        self.assertEqual(self.store.mutate('a', body, True), self.store.mutate('a', body, True))
        self.assertTrue(self.store.one('a')['deleted'])
    def test_validation_and_token_reuse(self):
        self.assertEqual(400, self.store.mutate('a', self.body(title=' '))[0])
        body = self.body()
        self.store.mutate('a', body)
        body['title'] = 'Different'
        self.assertEqual(400, self.store.mutate('a', body)[0])
    def test_explicit_resolution_after_remote_delete(self):
        self.store.mutate('a', self.body())
        self.store.mutate('a', dict(baseRevision=1, mutation='delete'), True)
        self.assertEqual(409, self.store.mutate('a', self.body(1))[0])
        self.assertEqual(200, self.store.mutate('a', self.body(2))[0])
        self.assertFalse(self.store.one('a')['deleted'])

if __name__ == '__main__': unittest.main(verbosity=2)
