#!/usr/bin/env python
# Hidden test for py-holdout task p2t2: GET /user/<username>/followers.csv.
from datetime import datetime, timezone, timedelta
import unittest
from app import create_app, db
from app.models import User, Post, Message
from config import Config


class TestConfig(Config):
    TESTING = True
    SQLALCHEMY_DATABASE_URI = 'sqlite://'
    ELASTICSEARCH_URL = None


def utc(*args):
    return datetime(*args, tzinfo=timezone.utc)


def parse_csv(text):
    """Strict RFC 4180 parser: CRLF or LF record ends, every record (also the
    last) ends with a line break, quoting of any field allowed; rejects a bare
    quote in an unquoted field, junk after a closing quote, a bare CR and an
    unterminated quoted field."""
    records, record, field = [], [], []
    i, n = 0, len(text)
    if n and not text.endswith('\n'):
        raise AssertionError('the last record does not end with a line break')
    while i < n:
        if text[i] == '"' and not field:
            i += 1
            while True:
                if i >= n:
                    raise AssertionError('unterminated quoted field')
                if text[i] == '"':
                    if i + 1 < n and text[i + 1] == '"':
                        field.append('"')
                        i += 2
                        continue
                    i += 1
                    break
                field.append(text[i])
                i += 1
            if i < n and text[i] not in ',\r\n':
                raise AssertionError(f'junk after closing quote at {i}')
            field.append('')  # marks the field as present even if empty
            continue
        c = text[i]
        if c == ',':
            record.append(''.join(field))
            field = []
            i += 1
        elif c == '\r':
            if i + 1 >= n or text[i + 1] != '\n':
                raise AssertionError(f'bare CR at {i}')
            i += 1
        elif c == '\n':
            record.append(''.join(field))
            records.append(record)
            record, field = [], []
            i += 1
        elif c == '"':
            raise AssertionError(f'bare quote in unquoted field at {i}')
        else:
            field.append(c)
            i += 1
    return records


class CsvCase(unittest.TestCase):
    def setUp(self):
        self.app = create_app(TestConfig)
        self.app_context = self.app.app_context()
        self.app_context.push()
        db.create_all()
        self.client = self.app.test_client()

    def tearDown(self):
        db.session.remove()
        db.drop_all()
        self.app_context.pop()

    def login(self, user):
        with self.client.session_transaction() as sess:
            sess['_user_id'] = str(user.id)
            sess['_fresh'] = True

    def get_csv(self, url):
        rv = self.client.get(url)
        self.assertEqual(rv.status_code, 200, url)
        self.assertEqual(rv.mimetype, 'text/csv', url)
        return parse_csv(rv.get_data(as_text=True))

    def assert_login_required(self, url):
        rv = self.client.get(url)
        self.assertEqual(rv.status_code, 302, url)
        self.assertIn('/auth/login', rv.headers['Location'])


class BenchP2T2Case(CsvCase):
    def setUp(self):
        super().setUp()
        self.u = {}
        for name in ['john', 'zoe', 'adam', 'mike', 'beth', 'carl']:
            self.u[name] = User(username=name, email=f'{name}@example.com')
        db.session.add_all(self.u.values())
        db.session.commit()
        u = self.u
        u['zoe'].about_me = 'likes "quotes", and commas'
        u['adam'].about_me = 'plain'
        u['beth'].about_me = 'line one\nline two'
        for follower in ('zoe', 'adam', 'beth'):
            u[follower].follow(u['john'])
        u['john'].follow(u['mike'])  # john follows mike: not his follower
        u['carl'].follow(u['mike'])
        for k in range(3):
            db.session.add(Post(body=f'zoe {k}', author=u['zoe']))
        db.session.add(Post(body='beth 0', author=u['beth']))
        db.session.add(Post(body='john 0', author=u['john']))
        db.session.commit()

    def test_followers_as_csv(self):
        self.login(self.u['mike'])
        self.assertEqual(self.get_csv('/user/john/followers.csv'), [
            ['username', 'about_me', 'posts'],
            ['adam', 'plain', '0'],
            ['beth', 'line one\nline two', '1'],
            ['zoe', 'likes "quotes", and commas', '3'],
        ])

    def test_other_user_and_no_followers(self):
        self.login(self.u['john'])
        self.assertEqual(self.get_csv('/user/mike/followers.csv'), [
            ['username', 'about_me', 'posts'],
            ['carl', '', '0'],
            ['john', '', '1'],
        ])
        self.assertEqual(self.get_csv('/user/zoe/followers.csv'),
                         [['username', 'about_me', 'posts']])

    def test_all_followers_no_paging(self):
        for k in range(30):
            f = User(username=f'fan{k:02d}', email=f'fan{k}@example.com')
            db.session.add(f)
            f.follow(self.u['carl'])
        db.session.commit()
        self.login(self.u['carl'])
        records = self.get_csv('/user/carl/followers.csv')
        self.assertEqual(len(records), 31)
        self.assertEqual([r[0] for r in records[1:]],
                         [f'fan{k:02d}' for k in range(30)])

    def test_unknown_user_is_404(self):
        self.login(self.u['john'])
        rv = self.client.get('/user/nobody/followers.csv')
        self.assertEqual(rv.status_code, 404)

    def test_login_required(self):
        self.assert_login_required('/user/john/followers.csv')

    def test_existing_pages_still_work(self):
        self.login(self.u['mike'])
        rv = self.client.get('/user/john')
        self.assertEqual(rv.status_code, 200)
        self.assertIn(b'john 0', rv.data)
        self.assertEqual(self.client.get('/user/john/popup').status_code,
                         200)


if __name__ == '__main__':
    unittest.main(verbosity=2)
