#!/usr/bin/env python
# Hidden test for v2-microblog-pilot task mbp1 (B): follower numbers show the wrong count.
import unittest
from app import create_app, db
from app.models import User
from config import Config


class TestConfig(Config):
    TESTING = True
    SQLALCHEMY_DATABASE_URI = 'sqlite://'
    ELASTICSEARCH_URL = None


class BenchMBP1Case(unittest.TestCase):
    def setUp(self):
        self.app = create_app(TestConfig)
        self.app_context = self.app.app_context()
        self.app_context.push()
        db.create_all()
        self.client = self.app.test_client()
        names = ['john', 'susan', 'mary', 'david', 'anna']
        self.u = {n: User(username=n, email=f'{n}@example.com') for n in names}
        db.session.add_all(self.u.values())
        db.session.commit()
        for n in ('susan', 'mary', 'david'):
            self.u[n].follow(self.u['john'])   # john has 3 followers
        self.u['john'].follow(self.u['anna'])  # john follows 1 user
        db.session.commit()
        self.token = self.u['anna'].get_token()
        db.session.commit()

    def tearDown(self):
        db.session.remove()
        db.drop_all()
        self.app_context.pop()

    def api_user(self, name):
        rv = self.client.get(f'/api/users/{self.u[name].id}',
                             headers={'Authorization': 'Bearer ' + self.token})
        self.assertEqual(rv.status_code, 200)
        return rv.get_json()

    def test_api_counts(self):
        john = self.api_user('john')
        self.assertEqual(john['follower_count'], 3)
        self.assertEqual(john['following_count'], 1)
        anna = self.api_user('anna')
        self.assertEqual(anna['follower_count'], 1)
        self.assertEqual(anna['following_count'], 0)
        susan = self.api_user('susan')
        self.assertEqual(susan['follower_count'], 0)
        self.assertEqual(susan['following_count'], 1)

    def test_profile_page_counts(self):
        with self.client.session_transaction() as sess:
            sess['_user_id'] = str(self.u['anna'].id)
            sess['_fresh'] = True
        rv = self.client.get('/user/john')
        self.assertEqual(rv.status_code, 200)
        html = rv.get_data(as_text=True)
        self.assertIn('3 followers', html)
        self.assertIn('1 following', html)


if __name__ == '__main__':
    unittest.main(verbosity=2)
