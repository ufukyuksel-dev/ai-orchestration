#!/usr/bin/env python
# Hidden test for py-holdout task p1t2: GET /api/users/<id>/posts.
from datetime import datetime, timezone, timedelta
import unittest
from app import create_app, db
from app.models import User, Post
from config import Config


class TestConfig(Config):
    TESTING = True
    SQLALCHEMY_DATABASE_URI = 'sqlite://'
    ELASTICSEARCH_URL = None


BASE = datetime(2024, 3, 1, 12, 0, 0, tzinfo=timezone.utc)


class BenchP1T2Case(unittest.TestCase):
    def setUp(self):
        self.app = create_app(TestConfig)
        self.app_context = self.app.app_context()
        self.app_context.push()
        db.create_all()
        self.client = self.app.test_client()
        self.john = User(username='john', email='john@example.com')
        self.susan = User(username='susan', email='susan@example.com')
        self.mary = User(username='mary', email='mary@example.com')
        db.session.add_all([self.john, self.susan, self.mary])
        db.session.commit()
        # Inserted out of time order; posts 'p5' and 'p6' share a timestamp.
        self.times = {}
        hours = [3, 0, 7, 1, 5, 5, 2, 9, 4, 8, 6, 10]
        for i, h in enumerate(hours):
            ts = BASE + timedelta(hours=h)
            p = Post(body=f'john post {i}', author=self.john, timestamp=ts,
                     language='en' if i % 2 == 0 else None)
            db.session.add(p)
            db.session.flush()
            self.times[p.id] = ts
        db.session.add(Post(body='susan post', author=self.susan,
                            timestamp=BASE + timedelta(hours=20)))
        db.session.commit()
        # newest first, same timestamp: higher id first
        self.expected = sorted(self.times,
                               key=lambda pid: (self.times[pid], pid),
                               reverse=True)
        self.token = self.susan.get_token()
        db.session.commit()

    def tearDown(self):
        db.session.remove()
        db.drop_all()
        self.app_context.pop()

    def auth(self, token=None):
        return {'Authorization': 'Bearer ' + (token or self.token)}

    def get_json(self, url):
        rv = self.client.get(url, headers=self.auth())
        self.assertEqual(rv.status_code, 200, url)
        self.assertEqual(rv.mimetype, 'application/json', url)
        return rv.get_json()

    def test_first_page_defaults(self):
        data = self.get_json(f'/api/users/{self.john.id}/posts')
        self.assertEqual([p['id'] for p in data['items']], self.expected[:10])
        self.assertEqual(data['_meta'], {'page': 1, 'per_page': 10,
                                         'total_pages': 2, 'total_items': 12})
        self.assertIsInstance(data['_links']['self'], str)
        self.assertIsInstance(data['_links']['next'], str)
        self.assertIsNone(data['_links']['prev'])

        first = data['items'][0]
        pid = self.expected[0]
        self.assertEqual(first['body'], 'john post 11')
        self.assertTrue(first['timestamp'].endswith('+00:00'))
        self.assertEqual(datetime.fromisoformat(first['timestamp']),
                         self.times[pid])
        self.assertIsNone(first['language'])
        self.assertEqual(first['author'], {'id': self.john.id,
                                           'username': 'john'})
        for item in data['items']:
            self.assertEqual(datetime.fromisoformat(item['timestamp']),
                             self.times[item['id']])
            self.assertEqual(item['author']['username'], 'john')
            self.assertIn(item['language'], ('en', None))

        # the next link is usable as it is
        rv = self.client.get(data['_links']['next'], headers=self.auth())
        self.assertEqual(rv.status_code, 200)
        self.assertEqual([p['id'] for p in rv.get_json()['items']],
                         self.expected[10:])

    def test_second_page_and_per_page(self):
        data = self.get_json(f'/api/users/{self.john.id}/posts?page=2')
        self.assertEqual([p['id'] for p in data['items']], self.expected[10:])
        self.assertEqual(data['_meta']['page'], 2)
        self.assertIsNone(data['_links']['next'])
        self.assertIsInstance(data['_links']['prev'], str)

        data = self.get_json(
            f'/api/users/{self.john.id}/posts?page=2&per_page=5')
        self.assertEqual([p['id'] for p in data['items']],
                         self.expected[5:10])
        self.assertEqual(data['_meta'], {'page': 2, 'per_page': 5,
                                         'total_pages': 3, 'total_items': 12})
        self.assertIsInstance(data['_links']['next'], str)
        self.assertIsInstance(data['_links']['prev'], str)

        data = self.get_json(f'/api/users/{self.john.id}/posts?per_page=500')
        self.assertEqual(data['_meta']['per_page'], 100)
        self.assertEqual([p['id'] for p in data['items']], self.expected)

    def test_only_that_users_posts(self):
        data = self.get_json(f'/api/users/{self.susan.id}/posts')
        self.assertEqual([p['body'] for p in data['items']], ['susan post'])
        self.assertEqual(data['_meta']['total_items'], 1)

        data = self.get_json(f'/api/users/{self.mary.id}/posts')
        self.assertEqual(data['items'], [])
        self.assertEqual(data['_meta']['total_items'], 0)

    def test_unknown_user_is_404_json(self):
        rv = self.client.get('/api/users/9999/posts', headers=self.auth())
        self.assertEqual(rv.status_code, 404)
        self.assertEqual(rv.mimetype, 'application/json')
        self.assertIn('error', rv.get_json())

    def test_token_is_required(self):
        rv = self.client.get(f'/api/users/{self.john.id}/posts')
        self.assertEqual(rv.status_code, 401)
        rv = self.client.get(f'/api/users/{self.john.id}/posts',
                             headers=self.auth('not-a-valid-token'))
        self.assertEqual(rv.status_code, 401)

    def test_existing_api_still_works(self):
        data = self.get_json(f'/api/users/{self.john.id}')
        self.assertEqual(data['post_count'], 12)
        data = self.get_json(f'/api/users/{self.john.id}/followers')
        self.assertEqual(data['items'], [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
