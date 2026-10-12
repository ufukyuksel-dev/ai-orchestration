#!/usr/bin/env python
# Hidden test for v2-microblog task mb07 (F): POST /api/messages, behavior part.
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


class BenchMB07Case(unittest.TestCase):
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
        self.token = self.john.get_token()
        db.session.commit()

    def tearDown(self):
        db.session.remove()
        db.drop_all()
        self.app_context.pop()

    def post(self, payload, token=None, **kw):
        headers = {'Authorization': 'Bearer ' + (token or self.token)}
        if payload is None:
            return self.client.post('/api/messages', headers=headers, **kw)
        return self.client.post('/api/messages', headers=headers, json=payload)

    def message_count(self):
        return db.session.scalar(sa.select(sa.func.count(Message.id)))

    def test_send_message(self):
        rv = self.post({'recipient': 'susan', 'body': 'hello susan, "quoted"'})
        self.assertEqual(rv.status_code, 201)
        self.assertEqual(rv.mimetype, 'application/json')
        data = rv.get_json()
        self.assertIsInstance(data['id'], int)
        self.assertEqual(data['body'], 'hello susan, "quoted"')
        self.assertTrue(data['timestamp'].endswith('+00:00'), data['timestamp'])
        sent = datetime.fromisoformat(data['timestamp'])
        self.assertLess(abs(sent - datetime.now(timezone.utc)), timedelta(minutes=2))
        self.assertEqual(data['sender'], {'id': self.john.id, 'username': 'john'})
        self.assertEqual(data['recipient'], {'id': self.susan.id, 'username': 'susan'})
        db.session.expire_all()
        msg = db.session.get(Message, data['id'])
        self.assertIsNotNone(msg)
        self.assertEqual(msg.sender_id, self.john.id)
        self.assertEqual(msg.recipient_id, self.susan.id)
        self.assertEqual(msg.body, 'hello susan, "quoted"')
        self.assertEqual(db.session.get(User, self.susan.id).unread_message_count(), 1)
        self.assertEqual(db.session.get(User, self.john.id).unread_message_count(), 0)

    def test_sender_is_the_token_user(self):
        token = self.susan.get_token()
        db.session.commit()
        rv = self.post({'recipient': 'john', 'body': 'hi john'}, token=token)
        self.assertEqual(rv.status_code, 201)
        self.assertEqual(rv.get_json()['sender']['username'], 'susan')
        self.assertEqual(rv.get_json()['recipient']['username'], 'john')

    def test_body_length_limit(self):
        rv = self.post({'recipient': 'susan', 'body': 'x' * 140})
        self.assertEqual(rv.status_code, 201)
        rv = self.post({'recipient': 'susan', 'body': 'x' * 141})
        self.assertEqual(rv.status_code, 400)
        self.assertEqual(self.message_count(), 1)

    def test_invalid_input_is_400(self):
        for payload in ({'body': 'no recipient'}, {'recipient': 'susan'},
                        {'recipient': 'susan', 'body': ''},
                        {'recipient': 'susan', 'body': 123}, {}):
            rv = self.post(payload)
            self.assertEqual(rv.status_code, 400, payload)
            self.assertEqual(rv.mimetype, 'application/json', payload)
            self.assertIn('error', rv.get_json(), payload)
        rv = self.post(None, data='not json', content_type='text/plain')
        self.assertEqual(rv.status_code, 400)
        self.assertEqual(self.message_count(), 0)

    def test_unknown_recipient_is_404(self):
        rv = self.post({'recipient': 'nobody', 'body': 'hello?'})
        self.assertEqual(rv.status_code, 404)
        self.assertEqual(rv.mimetype, 'application/json')
        self.assertIn('error', rv.get_json())
        self.assertEqual(self.message_count(), 0)

    def test_token_is_required(self):
        rv = self.client.post('/api/messages', json={'recipient': 'susan', 'body': 'x'})
        self.assertEqual(rv.status_code, 401)
        rv = self.post({'recipient': 'susan', 'body': 'x'}, token='not-a-token')
        self.assertEqual(rv.status_code, 401)
        self.assertEqual(self.message_count(), 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
