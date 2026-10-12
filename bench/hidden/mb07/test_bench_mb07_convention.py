#!/usr/bin/env python
# Hidden convention probe for v2-microblog task mb07 (F): POST /api/messages must follow two conventions that are
# visible only in the code: (1) API validation errors carry `error` and a human-readable `message`
# (app/api/errors.py bad_request), (2) a new message refreshes the recipient's `unread_message_count` notification,
# like the web view that sends messages.
import unittest
import sqlalchemy as sa
from app import create_app, db
from app.models import User, Notification
from config import Config


class TestConfig(Config):
    TESTING = True
    SQLALCHEMY_DATABASE_URI = 'sqlite://'
    ELASTICSEARCH_URL = None


class BenchMB07ConventionCase(unittest.TestCase):
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
        self.headers = {'Authorization': 'Bearer ' + self.john.get_token()}
        db.session.commit()

    def tearDown(self):
        db.session.remove()
        db.drop_all()
        self.app_context.pop()

    def test_validation_errors_use_the_bad_request_format(self):
        for payload in ({'body': 'no recipient'}, {'recipient': 'susan', 'body': 'x' * 141}):
            rv = self.client.post('/api/messages', headers=self.headers, json=payload)
            self.assertEqual(rv.status_code, 400, payload)
            data = rv.get_json()
            self.assertEqual(data.get('error'), 'Bad Request', payload)
            self.assertIsInstance(data.get('message'), str, payload)
            self.assertTrue(data['message'].strip(), payload)

    def test_recipient_unread_notification_is_refreshed(self):
        for body in ('one', 'two'):
            rv = self.client.post('/api/messages', headers=self.headers,
                                  json={'recipient': 'susan', 'body': body})
            self.assertEqual(rv.status_code, 201)
        db.session.expire_all()
        notes = db.session.scalars(sa.select(Notification).where(
            Notification.user_id == self.susan.id,
            Notification.name == 'unread_message_count')).all()
        self.assertEqual(len(notes), 1)
        self.assertEqual(notes[0].get_data(), 2)
        self.assertEqual(db.session.scalar(sa.select(sa.func.count(Notification.id)).where(
            Notification.user_id == self.john.id)), 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
