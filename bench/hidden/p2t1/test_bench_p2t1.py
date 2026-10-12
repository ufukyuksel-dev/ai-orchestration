#!/usr/bin/env python
# Hidden test for py-holdout task p2t1: GET /export/posts.csv.
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


class BenchP2T1Case(CsvCase):
    def setUp(self):
        super().setUp()
        self.john = User(username='john', email='john@example.com')
        self.susan = User(username='susan', email='susan@example.com')
        self.mary = User(username='mary', email='mary@example.com')
        db.session.add_all([self.john, self.susan, self.mary])
        db.session.commit()
        posts = [
            Post(body='second', author=self.john,
                 timestamp=utc(2024, 2, 1, 9, 5, 7)),
            Post(body='first, with a comma', author=self.john,
                 timestamp=utc(2023, 11, 30, 23, 0, 1)),
            Post(body='tie b', author=self.john,
                 timestamp=utc(2024, 3, 3, 3, 3, 3)),
            Post(body='tie a "quoted"', author=self.john,
                 timestamp=utc(2024, 3, 3, 3, 3, 3)),
            Post(body='two\nlines', author=self.john,
                 timestamp=utc(2025, 1, 1, 0, 0, 0)),
            Post(body='not mine', author=self.susan,
                 timestamp=utc(2024, 1, 1, 0, 0, 0)),
        ]
        db.session.add_all(posts)
        db.session.commit()
        self.ids = [p.id for p in posts]

    def test_own_posts_as_csv(self):
        self.login(self.john)
        rv = self.client.get('/export/posts.csv')
        self.assertEqual(rv.status_code, 200)
        self.assertEqual(rv.mimetype, 'text/csv')
        disposition = rv.headers.get('Content-Disposition', '')
        self.assertTrue(disposition.startswith('attachment'), disposition)
        self.assertIn('posts.csv', disposition)
        records = parse_csv(rv.get_data(as_text=True))
        i = self.ids
        self.assertEqual(records, [
            ['id', 'timestamp', 'body'],
            [str(i[1]), '2023-11-30 23:00:01', 'first, with a comma'],
            [str(i[0]), '2024-02-01 09:05:07', 'second'],
            [str(i[2]), '2024-03-03 03:03:03', 'tie b'],
            [str(i[3]), '2024-03-03 03:03:03', 'tie a "quoted"'],
            [str(i[4]), '2025-01-01 00:00:00', 'two\nlines'],
        ])

    def test_all_posts_no_paging(self):
        for k in range(40):
            db.session.add(Post(body=f'bulk {k}', author=self.susan,
                                timestamp=utc(2020, 1, 1) + timedelta(
                                    minutes=k)))
        db.session.commit()
        self.login(self.susan)
        records = self.get_csv('/export/posts.csv')
        self.assertEqual(len(records), 42)
        self.assertEqual(records[1][1:], ['2020-01-01 00:00:00', 'bulk 0'])
        self.assertEqual(records[40][2], 'bulk 39')
        self.assertEqual(records[41][2], 'not mine')

    def test_user_without_posts_gets_only_the_header(self):
        self.login(self.mary)
        self.assertEqual(self.get_csv('/export/posts.csv'),
                         [['id', 'timestamp', 'body']])

    def test_login_required(self):
        self.assert_login_required('/export/posts.csv')

    def test_existing_pages_still_work(self):
        self.login(self.john)
        rv = self.client.get('/user/john')
        self.assertEqual(rv.status_code, 200)
        self.assertIn(b'second', rv.data)
        self.assertEqual(self.client.get('/explore').status_code, 200)


if __name__ == '__main__':
    unittest.main(verbosity=2)
