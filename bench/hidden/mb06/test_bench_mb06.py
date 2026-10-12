#!/usr/bin/env python
# Hidden test for v2-microblog task mb06 (R): app/models.py split into the package app/models.
# API shape (where each name lives, old import path still works) + a behavior smoke test.
from datetime import datetime, timezone, timedelta
import importlib
import os
import unittest
import sqlalchemy as sa
from app import create_app, db, login
import app.models as models
from config import Config


class TestConfig(Config):
    TESTING = True
    SQLALCHEMY_DATABASE_URI = 'sqlite://'
    ELASTICSEARCH_URL = None


LAYOUT = {
    'app.models.mixins': ['SearchableMixin', 'PaginatedAPIMixin'],
    'app.models.user': ['followers', 'User', 'load_user'],
    'app.models.post': ['Post'],
    'app.models.message': ['Message'],
    'app.models.notification': ['Notification'],
    'app.models.task': ['Task'],
}


class BenchMB06ShapeCase(unittest.TestCase):
    def test_models_is_a_package(self):
        self.assertTrue(hasattr(models, '__path__'), 'app.models must be a package')
        self.assertEqual(os.path.basename(models.__file__), '__init__.py')
        root = os.path.dirname(os.path.dirname(models.__file__))
        self.assertFalse(os.path.exists(os.path.join(root, 'models.py')),
                         'app/models.py must be gone')

    def test_each_name_lives_in_its_module_and_is_reexported(self):
        for module_name, names in LAYOUT.items():
            module = importlib.import_module(module_name)
            for name in names:
                self.assertTrue(hasattr(module, name), f'{module_name}.{name}')
                obj = getattr(module, name)
                self.assertIs(getattr(models, name), obj,
                              f'app.models.{name} is not {module_name}.{name}')
                if isinstance(obj, type) or callable(obj) and hasattr(obj, '__module__'):
                    self.assertEqual(obj.__module__, module_name, name)

    def test_old_style_imports_still_work(self):
        ns = {}
        exec('from app.models import User, Post, Message, Notification, Task, '
             'SearchableMixin, PaginatedAPIMixin, followers, load_user', ns)
        self.assertIs(ns['User'], models.User)

    def test_hooks_still_registered(self):
        m = models
        self.assertTrue(sa.event.contains(db.session, 'before_commit',
                                          m.SearchableMixin.before_commit))
        self.assertTrue(sa.event.contains(db.session, 'after_commit',
                                          m.SearchableMixin.after_commit))
        self.assertIs(login._user_callback, m.load_user)
        self.assertTrue(issubclass(m.Post, m.SearchableMixin))
        self.assertTrue(issubclass(m.User, m.PaginatedAPIMixin))


class BenchMB06BehaviorCase(unittest.TestCase):
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

    def test_models_work_together(self):
        User, Post, Message = models.User, models.Post, models.Message
        john = User(username='john', email='john@example.com')
        susan = User(username='susan', email='susan@example.com')
        db.session.add_all([john, susan])
        db.session.commit()
        now = datetime.now(timezone.utc)
        db.session.add_all([
            Post(body='a', author=john, timestamp=now - timedelta(seconds=2)),
            Post(body='b', author=susan, timestamp=now - timedelta(seconds=1)),
            Message(author=susan, recipient=john, body='hi'),
        ])
        john.follow(susan)
        n = john.add_notification('unread_message_count', 1)
        db.session.commit()
        self.assertEqual([p.body for p in db.session.scalars(john.following_posts())], ['b', 'a'])
        self.assertEqual(john.unread_message_count(), 1)
        self.assertEqual(n.get_data(), 1)
        self.assertEqual(susan.followers_count(), 1)
        self.assertEqual(john.posts_count(), 1)
        self.assertEqual(db.session.scalar(sa.select(models.Task).where(
            models.Task.user_id == john.id)), None)
        # web page first: Flask-Login caches the user of the first request in this app context
        with self.client.session_transaction() as sess:
            sess['_user_id'] = str(john.id)
            sess['_fresh'] = True
        rv = self.client.get('/index')
        self.assertEqual(rv.status_code, 200)
        self.assertIn('john', rv.get_data(as_text=True))
        token = john.get_token()
        db.session.commit()
        rv = self.client.get('/api/users', headers={'Authorization': 'Bearer ' + token})
        self.assertEqual(rv.status_code, 200)
        self.assertEqual(rv.get_json()['_meta']['total_items'], 2)


if __name__ == '__main__':
    unittest.main(verbosity=2)
