#!/usr/bin/env python
# Hidden test for py-holdout task p2t3: GET /messages.csv.
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


class BenchP2T3Case(CsvCase):
    def setUp(self):
        super().setUp()
        self.john = User(username='john', email='john@example.com')
        self.susan = User(username='susan', email='susan@example.com')
        self.mary = User(username='mary', email='mary@example.com')
        self.david = User(username='david', email='david@example.com')
        db.session.add_all([self.john, self.susan, self.mary, self.david])
        db.session.commit()
        db.session.add_all([
            Message(author=self.susan, recipient=self.john, body='hi john',
                    timestamp=utc(2024, 4, 2, 10, 0, 0)),
            Message(author=self.john, recipient=self.susan,
                    body='hi susan, how are you?',
                    timestamp=utc(2024, 4, 1, 9, 30, 15)),
            Message(author=self.mary, recipient=self.john,
                    body='tie 2 "late"', timestamp=utc(2024, 4, 3, 7, 7, 7)),
            Message(author=self.john, recipient=self.mary,
                    body='tie 1', timestamp=utc(2024, 4, 3, 7, 7, 7)),
            Message(author=self.mary, recipient=self.john,
                    body='multi\nline', timestamp=utc(2025, 2, 28, 23, 59, 59)),
            Message(author=self.susan, recipient=self.mary,
                    body='not johns', timestamp=utc(2024, 4, 2, 0, 0, 0)),
        ])
        db.session.commit()

    def test_sent_and_received_messages_as_csv(self):
        self.login(self.john)
        self.assertEqual(self.get_csv('/messages.csv'), [
            ['timestamp', 'from', 'to', 'body'],
            ['2024-04-01 09:30:15', 'john', 'susan', 'hi susan, how are you?'],
            ['2024-04-02 10:00:00', 'susan', 'john', 'hi john'],
            ['2024-04-03 07:07:07', 'mary', 'john', 'tie 2 "late"'],
            ['2024-04-03 07:07:07', 'john', 'mary', 'tie 1'],
            ['2025-02-28 23:59:59', 'mary', 'john', 'multi\nline'],
        ])

    def test_other_users(self):
        self.login(self.mary)
        self.assertEqual(self.get_csv('/messages.csv'), [
            ['timestamp', 'from', 'to', 'body'],
            ['2024-04-02 00:00:00', 'susan', 'mary', 'not johns'],
            ['2024-04-03 07:07:07', 'mary', 'john', 'tie 2 "late"'],
            ['2024-04-03 07:07:07', 'john', 'mary', 'tie 1'],
            ['2025-02-28 23:59:59', 'mary', 'john', 'multi\nline'],
        ])

    def test_user_without_messages_gets_only_the_header(self):
        # one login per test: the test keeps one app context, and Flask-Login
        # caches the current user in it
        self.login(self.david)
        self.assertEqual(self.get_csv('/messages.csv'),
                         [['timestamp', 'from', 'to', 'body']])

    def test_all_messages_no_paging(self):
        for k in range(35):
            db.session.add(Message(author=self.susan, recipient=self.david,
                                   body=f'bulk {k}',
                                   timestamp=utc(2021, 1, 1) + timedelta(
                                       seconds=k)))
        db.session.commit()
        self.login(self.david)
        records = self.get_csv('/messages.csv')
        self.assertEqual(len(records), 36)
        self.assertEqual([r[3] for r in records[1:]],
                         [f'bulk {k}' for k in range(35)])

    def test_export_does_not_mark_messages_as_read(self):
        self.assertEqual(self.john.unread_message_count(), 3)
        self.login(self.john)
        self.get_csv('/messages.csv')
        db.session.expire_all()
        john = db.session.get(User, self.john.id)
        self.assertIsNone(john.last_message_read_time)
        self.assertEqual(john.unread_message_count(), 3)

    def test_login_required(self):
        self.assert_login_required('/messages.csv')

    def test_existing_pages_still_work(self):
        self.login(self.john)
        rv = self.client.get('/messages')
        self.assertEqual(rv.status_code, 200)
        self.assertIn(b'hi john', rv.data)
        db.session.expire_all()
        john = db.session.get(User, self.john.id)
        self.assertEqual(john.unread_message_count(), 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
