# Python holdout suite notes (py-holdout.json)

A held-out suite in a second language and stack. It has the same shape as holdout.json (read-only JSON API, CSV
export) but runs on a Python/Flask app. No lever was tuned on it, and no agent benchmark was run while it was being
written.

## Repository choice

Repo: `microblog` = https://github.com/miguelgrinberg/microblog @ `a975ef64864354867c88e0ed3a17ba7d17dca752`
(2025-04-06, MIT). It is the application from Miguel Grinberg's Flask Mega-Tutorial: Flask 3, SQLAlchemy 2
(typed `Mapped` models, `WriteOnlyMapped` relationships), Flask-Login, Flask-HTTPAuth (token API), Flask-WTF and
Flask-Babel, with an app factory (`create_app(config_class)`).

Why this repo:
- It is a small-to-medium web application, not a library. It has models with relationships (`User`, `Post`,
  `Message`, `Notification`, `Task`, followers), HTML routes (`app/main`, `app/auth`) and a token-authenticated JSON
  API (`app/api`). A JSON-API family and a CSV-export family both fit naturally.
- Its tests (`tests.py`, `unittest`, `sqlite://` in-memory) run offline with no services. Elasticsearch is disabled
  by `ELASTICSEARCH_URL = None`, and the Redis client in `create_app` is lazy and never connects. 4 tests take about
  0.5 s of wall time.
- The pitfalls are real and project-specific (see below), so knowledge from task 1 of a family pays off in tasks 2
  and 3.

Candidates not taken: the Flask tutorial (flaskr) is too small, with one model. Most FastAPI/Django sample apps with
real models need Postgres (e.g. full-stack-fastapi-template) or are much larger (healthchecks). microblog has no
lint/format gate of its own, so `qualityCommand` is `python -m compileall -q app`, as the suite spec prescribes for
that case.

## Setup

`bash bench/setup/microblog.sh` is idempotent. It:
1. Clones the repo into `bench/.work/repos/microblog` if it is missing, then checks out the SHA (detached).
2. Creates `bench/.work/venvs/microblog` with the first of `$PYTHON`, `python3.12`, `python3.11`, `python3` (validated
   with CPython 3.12.12).
3. Installs the repo's own `requirements.txt` pins, minus packages that neither the app's import graph nor
   `tests.py` needs: gunicorn, httpie, psycopg2-binary, aiosmtpd and their dependencies. `multidict==6.0.4`, pulled
   in by aiosmtpd, has no wheel for current Pythons and fails to build. The script ends with `pip check`.

Suite fields (commands run with cwd = worktree and `bench/.work/venvs/microblog/bin` first on PATH):

| Field | Value |
|---|---|
| `qualityCommand` | `python -m compileall -q app` |
| `ownTestCommand` | `python -m unittest -v tests` |
| `hiddenTestCommand` | `python -m unittest -v {hidden}` (e.g. `python -m unittest -v test_bench_p1t1.py`) |
| `hiddenTarget` | `.` (the repo root, next to `tests.py`) |

Chain semantics are the same as holdout.json. `reset_code()` returns the worktree to the SHA before every task, and
each reference patch is a `git diff` against the SHA that applies on its own.

Hidden tests follow `tests.py`. Each is a `unittest.TestCase` with its own `TestConfig(Config)` (`TESTING`,
`sqlite://`, `ELASTICSEARCH_URL = None`), `create_app(TestConfig)`, a pushed app context, `db.create_all()` /
`drop_all()` and the Flask test client. The test builds its data with the models. Authentication is done without
going through implementation details:
- API: `user.get_token()` plus `Authorization: Bearer <token>`.
- Web pages: Flask-Login's session key (`sess['_user_id']`) is set through `client.session_transaction()`.

There is one login per test method. Because the test keeps one app context, Flask-Login caches `current_user` in
`g`, so a second login in the same method would not take effect.

## P1: Read-only JSON API (token-authenticated)

| Task | Endpoint | What the hidden test checks |
|---|---|---|
| p1t1 | `GET /api/posts/<id>` | 200 with `application/json`. `id` is an int. `body`, `language` (`'en'`/`'es'`, and `null` for a post without one) are checked. `timestamp` ends with `+00:00`, and `fromisoformat` equals the stored UTC time. `author == {'id', 'username'}` exactly. Posts of 2 different authors are checked. Unknown id: 404 with JSON and an `error` key. No token and an invalid token: 401. `/api/users/<id>` and `/api/users` still work. |
| p1t2 | `GET /api/users/<id>/posts` | 12 posts of john, inserted out of time order, with one timestamp tie, plus 1 post of susan. Page 1 defaults: the exact id order (newest first, tie by higher id), `_meta == {page 1, per_page 10, total_pages 2, total_items 12}`, `next` is a string and `prev` is null. Following `_links.next` as-is returns the rest. `page=2`, `page=2&per_page=5` (exact slice and meta) and `per_page=500` (capped to 100, all 12) are checked. Per item: timestamp, author and language. Only that user's posts are returned, and a user without posts gets `items == []`. 404 JSON with `error`, 401 and the existing API are checked. |
| p1t3 | `GET /api/messages` | 11 messages to john from susan/mary, out of order, with a tie, plus 2 messages not received by john. Checked: the exact order, `_meta`, links, following `next`, `page=3&per_page=4` and the per_page cap. `sender == {'id', 'username'}` exactly, and the timestamps. Another token user without messages gets `[]`. After two reads, john's `last_message_read_time` is still `None`, `unread_message_count()` is still 11 and the message count is unchanged. 401 and the existing API are checked. |

Pitfalls an agent will likely hit, and can learn in p1t1:
1. **Token auth lives in `app/api/auth.py`** (`token_auth.login_required`). A route without it serves anonymous
   requests (the 401 check fails). Using Flask-Login's `login_required` gives a 302 redirect, not a 401.
2. **SQLite returns naive datetimes.** `post.timestamp.isoformat()` has no offset. `app/tasks.py` (the existing JSON
   export) appends `'Z'` instead. Both fail the `+00:00` requirement. The repo's own convention, in `User.to_dict`, is
   `.replace(tzinfo=timezone.utc).isoformat()`.
3. **API errors are JSON only inside the `api` blueprint** (`app/api/errors.py` `@bp.errorhandler(HTTPException)`).
   `db.get_or_404` / `abort(404)` in an `api` route gives `{"error": "Not Found"}`. A route registered on another
   blueprint relies on the app-wide handler in `app/errors/handlers.py`. That handler content-negotiates: it returns
   JSON when the client does not prefer HTML (which includes requests without an `Accept` header) and HTML
   otherwise. A route that returns `{}`/`None` with status 200 instead of a 404 fails the test.
4. **New API modules must be imported** in `app/api/__init__.py` (`from app.api import users, errors, tokens`),
   otherwise the routes never register.
5. `User.posts` / `messages_received` are `WriteOnlyMapped`. They cannot be iterated or `len()`-ed, so the agent
   needs `.select()` plus `db.session.scalars` or `db.paginate`.
6. p1t2 and p1t3: the paginated format already exists as `PaginatedAPIMixin.to_collection_dict`, which calls
   `item.to_dict()` (`Post` and `Message` have none) and builds `_links` with `url_for(endpoint, **kwargs)`. The
   `per_page` cap of 100 is in the existing user routes.
7. p1t3: the existing `/messages` HTML view marks messages as read (`last_message_read_time`, notification).
   Copying it breaks the read-only requirement. The messages are `current_user.messages_received`, with the
   current user being `token_auth.current_user()`, not Flask-Login's `current_user`.

## P2: CSV export (logged-in web pages)

| Task | Endpoint | What the hidden test checks |
|---|---|---|
| p2t1 | `GET /export/posts.csv` | 200, mimetype `text/csv`, and `Content-Disposition` starting with `attachment` and containing `posts.csv`. Body parsed by a strict RFC 4180 parser inside the test. It accepts CRLF or LF and quoting of any field. It rejects a bare quote in an unquoted field, junk after a closing quote, a bare CR, an unterminated quote and a last record without a line break. Exact records: header `id,timestamp,body`; john's 5 posts in time order with a tie broken by id ascending; values with a comma, quotes and a newline; timestamps `YYYY-MM-DD HH:MM:SS`; no posts of other users. No paging (40 posts give 41 records + header). A user without posts gets the header only. Anonymous: 302 to `/auth/login`. `/user/john` and `/explore` still work. |
| p2t2 | `GET /user/<username>/followers.csv` | Exact records `username,about_me,posts`: followers sorted by username, `about_me` with quotes/commas/newline, an empty `about_me`, and post counts. The users john follows are not listed. A user without followers gets the header only. 30 followers: all of them, sorted. Unknown username gives 404, anonymous gives 302. `/user/john` and `/user/john/popup` still work. |
| p2t3 | `GET /messages.csv` | Exact records `timestamp,from,to,body` for sent and received messages: time order with a tie broken by id ascending, quoting, no messages between other users. Checked for john and mary separately. A user without messages gets the header only. 35 messages are not paged. The export leaves `last_message_read_time` as `None` and `unread_message_count()` at 3. Anonymous gives 302. `/messages` still works and still marks the messages as read. |

Pitfalls an agent will likely hit, and can learn in p2t1:
1. **Login is Flask-Login** (`@login_required` from `flask_login`, `login.login_view = 'auth.login'`). Using the
   API's `token_auth` instead gives 401 rather than the required 302.
2. **A CSV response needs an explicit `Response(..., mimetype='text/csv')`** (or `make_response` plus a header).
   Returning a string gives `text/html`, and returning a dict gives JSON. Python's `csv` module provides correct RFC
   4180 quoting for free, while `','.join(...)` does not.
3. **Naive datetimes again**, plus the explicit `strftime('%Y-%m-%d %H:%M:%S')` format. `isoformat()` gives a `T`
   separator and may add microseconds.
4. **The existing `/export_posts`** is something different. It is an rq/Redis background job that e-mails JSON
   (`app/tasks.py`). Reusing `launch_task` needs Redis and does not meet the spec.
5. `WriteOnlyMapped` relationships (`user.posts`, `user.followers`) need `.select()`. Follower post counts can use
   `User.posts_count()`.
6. p2t2: `followers` and `following` are easy to swap. The 404 for an unknown username comes from
   `db.first_or_404(...)`, as in `/user/<username>`.
7. p2t3: sent and received messages are two relationships (`messages_sent`, `messages_received`), so the agent
   needs an `or_` query on `Message` or a merge. The existing `/messages` view writes `last_message_read_time`, and
   reusing it breaks the "not marked as read" requirement.
8. `@bp.before_app_request` commits `last_seen` for the logged-in user on every request. This is harmless, but it
   means every page request writes to the database.

## Reference patches

| Patch | Changes |
|---|---|
| p1t1 | new `app/api/posts.py` (`post_to_dict`, `get_post`), import added in `app/api/__init__.py` |
| p1t2 | new `app/api/posts.py` (`post_to_dict`, `get_user_posts` with explicit pagination), import added |
| p1t3 | new `app/api/messages.py` (`message_to_dict`, `get_messages`), import added |
| p2t1 | `app/main/routes.py`: `csv_response` helper (`csv.writer`, `text/csv`, attachment) + `export_posts_csv` |
| p2t2 | `app/main/routes.py`: `csv_response` + `followers_csv` |
| p2t3 | `app/main/routes.py`: `csv_response` + `messages_csv` (`or_` of sender/recipient) |

## Validation (2026-10-11, scratch worktrees under /tmp/pyval, never in the run.py worktrees)

Validation imports `bench/run.py`, sets `SUITE_SHA` and `SUITE_PATH` as `main()` does, and calls
`run.score(task, suite, wt)`, so the judging chain matches a real run. Per task:
1. `git worktree add --detach` of `bench/.work/repos/microblog` at the SHA.
2. `score()` on the clean SHA.
3. `git apply --check` of `bench/reference/<taskId>.patch`, then `git apply`.
4. `score()` again: quality without the hidden file, then the hidden file copied to `.`, the hidden command, the
   hidden file removed, and the own tests.
5. A check that the hidden file is gone, and `git status`.

| Task | Base: success / quality / hidden / own | Base hidden failures | `apply --check` | Reference: success / quality / hidden / own | Hidden file removed |
|---|---|---|---|---|---|
| p1t1 | False / True / False / True | 3 of 5 (endpoint missing, so 404 instead of 401/200) | ok | True / True / True / True | yes |
| p1t2 | False / True / False / True | 4 of 6 | ok | True / True / True / True | yes |
| p1t3 | False / True / False / True | 5 of 6 | ok | True / True / True / True | yes |
| p2t1 | False / True / False / True | 4 of 5 | ok | True / True / True / True | yes |
| p2t2 | False / True / False / True | 4 of 6 | ok | True / True / True / True | yes |
| p2t3 | False / True / False / True | 6 of 7 | ok | True / True / True / True | yes |

The script exited 0. `score()` on a reference took about 0.85 s. The own test suite takes about 0.5 s of wall time
(4 tests).

Wrong-turn checks: each variant was applied on top of the reference, and only the hidden test was run.

| Variant | Result |
|---|---|
| p1t1 `timestamp.isoformat()` (naive) | FAILED (2) |
| p1t1 `isoformat() + 'Z'` (the `app/tasks.py` convention) | FAILED (2) |
| p1t1 without `@token_auth.login_required` | FAILED (1) |
| p1t2 `per_page` not capped | FAILED (1) |
| p1t2 and p1t3, tie broken by id ascending | FAILED (2) / FAILED (2) |
| p1t3 without an id tie-break | FAILED (2) |
| p1t3 marks messages as read | FAILED (1) |
| p2t1 `','.join` without quoting | FAILED (1) |
| p2t1 without `@login_required` | FAILED (error) |
| p2t1 `isoformat()` timestamps | FAILED (2) |
| p2t1 and p2t3, tie broken by id descending | FAILED (1) / FAILED (2) |
| p2t3 without an id tie-break | FAILED (1) |
| p2t2 `following` instead of `followers` | FAILED (3) |
| p2t2 `str(about_me)` (writes `None`) | FAILED (1) |
| p2t3 received messages only | FAILED (2) |
| p2t3 export marks messages as read | FAILED (1) |

## UNVERIFIED

- Whether real agents hit or learn the pitfalls. No agent benchmark was run on this suite.
- **Missing tie-breaks in p1t2 and p2t1 are not detected.** For these queries (`WHERE user_id = ?` plus a temp
  B-tree sort) SQLite happens to return equal timestamps in the specified id order. An implementation without the
  explicit id tie-break passes there. p1t3 and p2t3 do detect it. A wrong-direction tie-break is detected
  everywhere.
- Python versions other than 3.12.12. The pins are from 2023, so 3.13+ may lack wheels (e.g. greenlet 3.0.1).
- Determinism across many runs. Each base and each reference was scored once through `score()`, and each hidden test
  also passed once on its own during authoring. The tests do not depend on JSON key order or on the order of
  unordered data.
