#!/usr/bin/env python
# Hidden test for py-holdout task p1t3: GET /api/messages.
from datetime import datetime, timezone, timedelta
import unittest
import sqlalchemy as sa
from app import create_app, db
from app.models import User, Message
from config import Config


class TestConfig(Config):
    TESTING = True
    SQLALCHEMY_DATABASE_URI = 'sqlite://'
    ELASTICSEARCH_URL = None


BASE = datetime(2024, 6, 1, 8, 0, 0, tzinfo=timezone.utc)


class BenchP1T3Case(unittest.TestCase):
    def setUp(self):
        self.app = create_app(TestConfig)
        self.app_context = self.app.app_context()
        self.app_context.push()
        db.create_all()
        self.client = self.app.test_client()
        self.john = User(username='john', email='john@example.com')
        self.susan = User(username='susan', email='susan@example.com')
        self.mary = User(username='mary', email='mary@example.com')
        self.david = User(username='david', email='david@example.com')
        db.session.add_all([self.john, self.susan, self.mary, self.david])
        db.session.commit()
        senders = [self.susan, self.mary]
        hours = [4, 1, 9, 6, 6, 0, 11, 3, 8, 2, 10]
        self.times = {}
        self.sender_of = {}
        for i, h in enumerate(hours):
            ts = BASE + timedelta(hours=h)
            m = Message(author=senders[i % 2], recipient=self.john,
                        body=f'to john {i}', timestamp=ts)
            db.session.add(m)
            db.session.flush()
            self.times[m.id] = ts
            self.sender_of[m.id] = senders[i % 2]
        # not received by john
        db.session.add(Message(author=self.john, recipient=self.susan,
                               body='from john', timestamp=BASE))
        db.session.add(Message(author=self.mary, recipient=self.susan,
                               body='mary to susan',
                               timestamp=BASE + timedelta(hours=30)))
        db.session.commit()
        self.expected = sorted(self.times,
                               key=lambda mid: (self.times[mid], mid),
                               reverse=True)
        self.token = self.john.get_token()
        self.david_token = self.david.get_token()
        db.session.commit()

    def tearDown(self):
        db.session.remove()
        db.drop_all()
        self.app_context.pop()

    def auth(self, token=None):
        return {'Authorization': 'Bearer ' + (token or self.token)}

    def get_json(self, url, token=None):
        rv = self.client.get(url, headers=self.auth(token))
        self.assertEqual(rv.status_code, 200, url)
        self.assertEqual(rv.mimetype, 'application/json', url)
        return rv.get_json()

    def test_first_page(self):
        data = self.get_json('/api/messages')
        self.assertEqual([m['id'] for m in data['items']], self.expected[:10])
        self.assertEqual(data['_meta'], {'page': 1, 'per_page': 10,
                                         'total_pages': 2, 'total_items': 11})
        self.assertIsInstance(data['_links']['self'], str)
        self.assertIsInstance(data['_links']['next'], str)
        self.assertIsNone(data['_links']['prev'])

        first = data['items'][0]
        self.assertEqual(first['body'], 'to john 6')
        self.assertEqual(first['sender'], {'id': self.susan.id,
                                           'username': 'susan'})
        for item in data['items']:
            self.assertIsInstance(item['id'], int)
            self.assertTrue(item['timestamp'].endswith('+00:00'))
            self.assertEqual(datetime.fromisoformat(item['timestamp']),
                             self.times[item['id']])
            sender = self.sender_of[item['id']]
            self.assertEqual(item['sender'], {'id': sender.id,
                                              'username': sender.username})

        rv = self.client.get(data['_links']['next'], headers=self.auth())
        self.assertEqual(rv.status_code, 200)
        self.assertEqual([m['id'] for m in rv.get_json()['items']],
                         self.expected[10:])

    def test_paging_parameters(self):
        data = self.get_json('/api/messages?page=2')
        self.assertEqual([m['id'] for m in data['items']], self.expected[10:])
        self.assertIsNone(data['_links']['next'])
        self.assertIsInstance(data['_links']['prev'], str)

        data = self.get_json('/api/messages?page=3&per_page=4')
        self.assertEqual([m['id'] for m in data['items']],
                         self.expected[8:])
        self.assertEqual(data['_meta'], {'page': 3, 'per_page': 4,
                                         'total_pages': 3, 'total_items': 11})

        data = self.get_json('/api/messages?per_page=1000')
        self.assertEqual(data['_meta']['per_page'], 100)
        self.assertEqual([m['id'] for m in data['items']], self.expected)

    def test_only_messages_received_by_the_token_user(self):
        data = self.get_json('/api/messages', token=self.david_token)
        self.assertEqual(data['items'], [])
        self.assertEqual(data['_meta']['total_items'], 0)

    def test_reading_does_not_mark_messages_as_read(self):
        self.assertEqual(self.john.unread_message_count(), 11)
        self.get_json('/api/messages')
        self.get_json('/api/messages?page=2')
        db.session.expire_all()
        john = db.session.get(User, self.john.id)
        self.assertIsNone(john.last_message_read_time)
        self.assertEqual(john.unread_message_count(), 11)
        self.assertEqual(db.session.scalar(
            sa.select(sa.func.count()).select_from(Message)), 13)

    def test_token_is_required(self):
        rv = self.client.get('/api/messages')
        self.assertEqual(rv.status_code, 401)
        rv = self.client.get('/api/messages',
                             headers=self.auth('not-a-valid-token'))
        self.assertEqual(rv.status_code, 401)

    def test_existing_api_still_works(self):
        data = self.get_json(f'/api/users/{self.john.id}')
        self.assertEqual(data['username'], 'john')
        data = self.get_json('/api/users?per_page=2')
        self.assertEqual(data['_meta']['total_items'], 4)


if __name__ == '__main__':
    unittest.main(verbosity=2)
