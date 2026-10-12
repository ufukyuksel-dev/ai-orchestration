#!/usr/bin/env python
# Hidden test for v2-microblog task mb02 (B): "last seen" is off by the server's UTC offset.
# The server runs in a non-UTC zone (fixed offset, no DST) so the symptom shows on any machine.
from datetime import datetime, timezone, timedelta
import os
import time
import unittest
from app import create_app, db
from app.models import User
from config import Config


class TestConfig(Config):
    TESTING = True
    SQLALCHEMY_DATABASE_URI = 'sqlite://'
    ELASTICSEARCH_URL = None
    WTF_CSRF_ENABLED = False


def utc(*args):
    return datetime(*args, tzinfo=timezone.utc)


class BenchMB02Case(unittest.TestCase):
    ZONE = 'Asia/Kolkata'  # UTC+05:30 all year

    def setUp(self):
        self._old_tz = os.environ.get('TZ')
        os.environ['TZ'] = self.ZONE
        time.tzset()
        self.app = create_app(TestConfig)
        self.app_context = self.app.app_context()
        self.app_context.push()
        db.create_all()
        self.client = self.app.test_client()
        self.john = User(username='john', email='john@example.com',
                         last_seen=utc(2024, 5, 1, 10, 30, 0))
        self.susan = User(username='susan', email='susan@example.com',
                          last_seen=utc(2024, 1, 2, 3, 4, 5))
        db.session.add_all([self.john, self.susan])
        db.session.commit()
        self.token = self.susan.get_token()
        db.session.commit()

    def tearDown(self):
        db.session.remove()
        db.drop_all()
        self.app_context.pop()
        if self._old_tz is None:
            os.environ.pop('TZ', None)
        else:
            os.environ['TZ'] = self._old_tz
        time.tzset()

    def login(self, user):
        with self.client.session_transaction() as sess:
            sess['_user_id'] = str(user.id)
            sess['_fresh'] = True

    def api_last_seen(self, user):
        rv = self.client.get(f'/api/users/{user.id}',
                             headers={'Authorization': 'Bearer ' + self.token})
        self.assertEqual(rv.status_code, 200)
        value = rv.get_json()['last_seen']
        self.assertTrue(value.endswith('+00:00'), value)
        return datetime.fromisoformat(value)

    def assert_now(self, value):
        now = datetime.now(timezone.utc)
        self.assertLess(abs(value - now), timedelta(minutes=2),
                        f'last_seen {value} is not the current UTC time {now}')

    def test_stored_utc_time_is_reported_unchanged(self):
        self.assertEqual(self.api_last_seen(self.john), utc(2024, 5, 1, 10, 30, 0))

    def test_activity_updates_last_seen_to_now(self):
        self.login(self.john)
        rv = self.client.get('/explore')
        self.assertEqual(rv.status_code, 200)
        self.assert_now(self.api_last_seen(self.john))
        db.session.expire_all()
        stored = db.session.get(User, self.john.id).last_seen
        self.assert_now(stored.replace(tzinfo=timezone.utc)
                        if stored.tzinfo is None else stored)

    def test_activity_on_other_pages_too(self):
        self.login(self.john)
        rv = self.client.get('/user/susan')
        self.assertEqual(rv.status_code, 200)
        self.assert_now(self.api_last_seen(self.john))
        self.assertEqual(self.api_last_seen(self.susan), utc(2024, 1, 2, 3, 4, 5))


if __name__ == '__main__':
    unittest.main(verbosity=2)
