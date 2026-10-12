#!/usr/bin/env python
# Hidden test for v2-microblog task mb01 (B): API collections lose their last, partial page.
import unittest
from app import create_app, db
from app.models import User
from config import Config


class TestConfig(Config):
    TESTING = True
    SQLALCHEMY_DATABASE_URI = 'sqlite://'
    ELASTICSEARCH_URL = None


class BenchMB01Case(unittest.TestCase):
    def setUp(self):
        self.app = create_app(TestConfig)
        self.app_context = self.app.app_context()
        self.app_context.push()
        db.create_all()
        self.client = self.app.test_client()
        self.users = [User(username=f'user{i:02d}', email=f'u{i}@example.com')
                      for i in range(12)]
        db.session.add_all(self.users)
        db.session.commit()
        self.john = self.users[0]
        for other in self.users[1:8]:      # 7 followers of john
            other.follow(self.john)
        for other in self.users[2:8]:      # john follows 6 users
            self.john.follow(other)
        db.session.commit()
        self.token = self.john.get_token()
        db.session.commit()

    def tearDown(self):
        db.session.remove()
        db.drop_all()
        self.app_context.pop()

    def get(self, url):
        rv = self.client.get(url, headers={'Authorization': 'Bearer ' + self.token})
        self.assertEqual(rv.status_code, 200, url)
        return rv.get_json()

    def walk(self, url):
        """Follow _links.next like a client would; return (usernames, pages)."""
        names, pages = [], []
        while url:
            data = self.get(url)
            pages.append(data)
            names.extend(item['username'] for item in data['items'])
            url = data['_links']['next']
            self.assertLessEqual(len(pages), 20, 'next links never end')
        return names, pages

    def test_walking_all_users_reaches_the_partial_last_page(self):
        names, pages = self.walk('/api/users?per_page=5')
        self.assertEqual(sorted(names), sorted(u.username for u in self.users))
        self.assertEqual(len(pages), 3)
        for data in pages:
            self.assertEqual(data['_meta']['total_pages'], 3)
            self.assertEqual(data['_meta']['total_items'], 12)
        self.assertEqual(len(pages[-1]['items']), 2)
        self.assertIsNone(pages[-1]['_links']['next'])
        self.assertIsNotNone(pages[-1]['_links']['prev'])

    def test_default_page_size(self):
        data = self.get('/api/users')
        self.assertEqual(data['_meta']['total_pages'], 2)
        self.assertIsNotNone(data['_links']['next'])
        names, pages = self.walk('/api/users')
        self.assertEqual(len(names), 12)
        self.assertEqual(len(pages), 2)

    def test_followers_partial_page(self):
        names, pages = self.walk(f'/api/users/{self.john.id}/followers?per_page=3')
        self.assertEqual(sorted(names), [f'user{i:02d}' for i in range(1, 8)])
        self.assertEqual([d['_meta']['total_pages'] for d in pages], [3, 3, 3])

    def test_exact_multiple_has_no_extra_page(self):
        names, pages = self.walk(f'/api/users/{self.john.id}/following?per_page=3')
        self.assertEqual(sorted(names), [f'user{i:02d}' for i in range(2, 8)])
        self.assertEqual(len(pages), 2)
        self.assertEqual(pages[0]['_meta']['total_pages'], 2)
        self.assertIsNone(pages[1]['_links']['next'])

    def test_single_partial_page_and_empty_collection(self):
        data = self.get('/api/users?per_page=100')
        self.assertEqual(data['_meta']['total_pages'], 1)
        self.assertEqual(len(data['items']), 12)
        self.assertIsNone(data['_links']['next'])
        lonely = self.users[11]
        data = self.get(f'/api/users/{lonely.id}/followers')
        self.assertEqual(data['items'], [])
        self.assertEqual(data['_meta']['total_items'], 0)
        self.assertIsNone(data['_links']['next'])
        self.assertIsNone(data['_links']['prev'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
