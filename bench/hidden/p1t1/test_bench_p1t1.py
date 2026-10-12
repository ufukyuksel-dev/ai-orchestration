#!/usr/bin/env python
# Hidden test for py-holdout task p1t1: GET /api/posts/<id>.
from datetime import datetime, timezone
import unittest
from app import create_app, db
from app.models import User, Post
from config import Config


class TestConfig(Config):
    TESTING = True
    SQLALCHEMY_DATABASE_URI = 'sqlite://'
    ELASTICSEARCH_URL = None


def utc(*args):
    return datetime(*args, tzinfo=timezone.utc)


class BenchP1T1Case(unittest.TestCase):
    def setUp(self):
        self.app = create_app(TestConfig)
        self.app_context = self.app.app_context()
        self.app_context.push()
        db.create_all()
        self.client = self.app.test_client()
        self.john = User(username='john', email='john@example.com')
        self.susan = User(username='susan', email='susan@example.com')
        db.session.add_all([self.john, self.susan])
        db.session.commit()
        self.p1 = Post(body='hello from john', author=self.john,
                       language='en', timestamp=utc(2024, 5, 1, 10, 30, 0))
        self.p2 = Post(body='hola de susan', author=self.susan,
                       language='es', timestamp=utc(2023, 12, 31, 23, 59, 58))
        self.p3 = Post(body='no language', author=self.susan,
                       timestamp=utc(2024, 1, 2, 3, 4, 5))
        db.session.add_all([self.p1, self.p2, self.p3])
        db.session.commit()
        self.token = self.john.get_token()
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

    def assert_timestamp(self, value, expected):
        self.assertIsInstance(value, str)
        self.assertTrue(value.endswith('+00:00'), value)
        self.assertEqual(datetime.fromisoformat(value), expected)

    def test_post_as_json(self):
        data = self.get_json(f'/api/posts/{self.p1.id}')
        self.assertIsInstance(data['id'], int)
        self.assertEqual(data['id'], self.p1.id)
        self.assertEqual(data['body'], 'hello from john')
        self.assert_timestamp(data['timestamp'], utc(2024, 5, 1, 10, 30, 0))
        self.assertEqual(data['language'], 'en')
        self.assertEqual(data['author'], {'id': self.john.id,
                                          'username': 'john'})

    def test_post_of_another_user_and_null_language(self):
        data = self.get_json(f'/api/posts/{self.p2.id}')
        self.assertEqual(data['body'], 'hola de susan')
        self.assert_timestamp(data['timestamp'],
                              utc(2023, 12, 31, 23, 59, 58))
        self.assertEqual(data['language'], 'es')
        self.assertEqual(data['author']['id'], self.susan.id)
        self.assertEqual(data['author']['username'], 'susan')

        data = self.get_json(f'/api/posts/{self.p3.id}')
        self.assertIn('language', data)
        self.assertIsNone(data['language'])
        self.assert_timestamp(data['timestamp'], utc(2024, 1, 2, 3, 4, 5))

    def test_unknown_post_is_404_json(self):
        rv = self.client.get('/api/posts/9999', headers=self.auth())
        self.assertEqual(rv.status_code, 404)
        self.assertEqual(rv.mimetype, 'application/json')
        self.assertIn('error', rv.get_json())

    def test_token_is_required(self):
        rv = self.client.get(f'/api/posts/{self.p1.id}')
        self.assertEqual(rv.status_code, 401)
        rv = self.client.get(f'/api/posts/{self.p1.id}',
                             headers=self.auth('not-a-valid-token'))
        self.assertEqual(rv.status_code, 401)

    def test_existing_api_still_works(self):
        data = self.get_json(f'/api/users/{self.susan.id}')
        self.assertEqual(data['username'], 'susan')
        self.assertEqual(data['post_count'], 2)
        data = self.get_json('/api/users')
        self.assertEqual(data['_meta']['total_items'], 2)


if __name__ == '__main__':
    unittest.main(verbosity=2)
