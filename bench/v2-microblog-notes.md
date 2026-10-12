# v2 microblog suites: notes (hidden from agents)

Suites: `bench/tasks/v2-microblog.json` (scored, 7 independent tasks) and `bench/tasks/v2-microblog-pilot.json`
(disposable pilot, 2 independent tasks). Repository `microblog` = miguelgrinberg/microblog @
`a975ef64864354867c88e0ed3a17ba7d17dca752`, prepared by `bash bench/setup/microblog.sh` (clone + venv; see
`py-holdout-notes.md` for the venv and the test idioms). Commands are the py-holdout ones: `pathPrepend` = the venv,
`qualityCommand` = `python -m compileall -q app`, `ownTestCommand` = `python -m unittest -v tests`,
`hiddenTestCommand` = `python -m unittest -v {hidden}`, `hiddenTarget` = `.`.

No memory chains (independent tasks, one per family), so there is no K to carry; the pitfalls below are what a
B-arm memory card could legitimately hold after an earlier task on this repository.

Every hidden test builds its own app with `create_app(TestConfig)` on `sqlite://`, creates data through the models,
authenticates with `user.get_token()` (API) or Flask-Login's session key (web pages, one login per test method,
because Flask-Login caches the user in `g` of the single app context the test keeps). No network, no Redis, no
Elasticsearch, no wall-clock thresholds beyond "within 2 minutes of now".

## Task list

| Id | Type | Summary | Check |
|---|---|---|---|
| mb01 | B | API collections (`/api/users`, followers, following) drop the partial last page | 1.2 s |
| mb02 | B | `last_seen` shifted by the server's UTC offset | 1.2 s |
| mb03 | I | How a new post reaches the search index, and what turns indexing off | answer only |
| mb04 | I | Where the unread-messages badge number comes from and how it resets/updates | answer only |
| mb05 | T | Pin down API token behavior (5 mutants) | 13.9 s |
| mb06 | R | Split `app/models.py` into the package `app/models/` (8 files) | 1.0 s |
| mb07 | F | `POST /api/messages` + convention probe | 1.0 s |
| mbp1 | B (pilot) | Follower count shows the following count | 1.0 s |
| mbp2 | I (pilot) | 404 outside `/api` without an `Accept` header | answer only |

(check = `checkSeconds` of `verify_task.py`: one `run.score()` of the reference tree.)

## mb01 (B): API collections lose the partial last page

- **Injection** (`baselines/mb01.patch`, `app/models.py`): `PaginatedAPIMixin.to_collection_dict` computes
  `total_pages = resources.total // per_page` (floor instead of ceiling) and uses it for `_meta.total_pages` and for
  `next` (`if page < total_pages`). Symptom is seen at the API routes (`app/api/users.py`), the cause is in the
  shared mixin in the models module, which the routes only call.
- **Reference**: reverse of the injection (`resources.pages`, `resources.has_next`). Any correct ceiling also passes.
- **Hidden test** `test_bench_mb01.py`: 12 users; walks `next` links from `/api/users?per_page=5` (all 12, 3 pages,
  `total_pages` 3 on every page, last page has 2 items, `next` null, `prev` set); default page size (2 pages);
  followers 7/`per_page=3` (3 pages); following 6/`per_page=3` (exact multiple: 2 pages, no extra empty page);
  `per_page=100` (1 page) and an empty collection (no links).
- **Pitfalls**: patching each route instead of the mixin; "fixing" with `// per_page + 1` (adds an empty page for
  exact multiples: caught); fixing `total_pages` but leaving `next` on the wrong condition (caught).
- **Objective**: link walking and `_meta` values are exact; the existing tests do not touch the API, so they pass
  on baseline and after the fix.

## mb02 (B): `last_seen` shifted by the server's UTC offset

- **Injection** (`baselines/mb02.patch`, `app/main/routes.py`): the `before_app_request` hook writes
  `current_user.last_seen = datetime.now().astimezone()` (aware *local* time). SQLite stores the local wall-clock
  fields without the offset; `User.to_dict` then labels them `+00:00`. Symptom shows at `GET /api/users/<id>` and
  the profile page; the cause is the request hook of the main blueprint, not the API or the model.
- **Reference**: `datetime.now(timezone.utc)` again.
- **Hidden test** `test_bench_mb02.py`: sets `TZ=Asia/Kolkata` (UTC+05:30, no DST) with `time.tzset()` in `setUp`
  (restored in `tearDown`), so the symptom is reproducible on a UTC machine too. Checks: a stored UTC value is
  reported unchanged (`2024-05-01T10:30:00+00:00`); after a logged-in request to `/explore` or `/user/susan`, the
  API's `last_seen` and the stored column are within 2 minutes of the real UTC now.
- **Pitfalls**: the injected line looks correct ("it is timezone-aware"); fixing the read side
  (`astimezone(timezone.utc)` in `to_dict`) breaks the stored-UTC case and the stored column check (verified: 3
  failures); the bug is invisible on a UTC machine, so an agent must reason or set `TZ` to reproduce.
- **Objective**: fixed offset zone, tolerance 2 minutes vs. a 5.5-hour error.

## mb03 (I): search indexing

Keys and accepted answers (`answers/mb03.json`):
`listener` = `SearchableMixin.after_commit`; `event` = `after_commit`; `indexer` = `add_to_index`
(`app.search.add_to_index`); `fields` = `Post.__searchable__` / `__searchable__`; `switch` = `ELASTICSEARCH_URL`.
Contradictions: `before_commit` (it only records `session.new/dirty/deleted`), `reindex`, `remove_from_index`,
`query_index`, `MS_TRANSLATOR_KEY`, `REDIS_URL`.
Needs: `db.event.listen` calls at module level in `app/models.py`, `app/search.py`, `create_app` (`app.elasticsearch =
None` when the URL is empty) and `config.py`. One correct answer per key (the prompt pins "after a successful commit"
and "writes one object").

## mb04 (I): unread-messages badge

`count` = `User.unread_message_count`; `cutoff` = `User.last_message_read_time`; `fallback` = `1900-01-01`
(`datetime(1900, 1, 1)`, asked as `YYYY-MM-DD` so the value has no `, `); `reset` = `main.messages` (the view sets
`last_message_read_time` and an `unread_message_count` notification of 0); `poll` = `main.notifications`
(`base.html` script, `setInterval` fetch of `/notifications?since=`). Contradictions: `last_seen`, `send_message`,
`1970-01-01`, `0001-01-01`.
Needs: `base.html` (template + script), `app/models.py`, `app/main/routes.py`.

## mb05 (T): pin down API token behavior

- `allowedPaths` = `tests.py`, `test_*.py` (repository root; the prompt says so because a `tests/` package would
  shadow `tests.py`). `testCommand` = `python -B -X pycache_prefix=.nopyc -m unittest discover -s . -t . -p
  'test*.py'` (runs `tests.py` and every root `test_*.py`). `-B` writes no bytecode and `-X pycache_prefix` points
  bytecode *reads* at a directory that never exists, so the `__pycache__` written by the quality gate's
  `compileall` can never shadow a mutated source. Every mutant also changes the file size.
- **Mutants** (`mutants/mb05/`, production code only), each a behavior the prompt states:
  1. `get_token` reuses a token until it expires (no one-minute margin);
  2. `check_token` ignores the expiry;
  3. `revoke_token` sets the expiry 60 s in the future;
  4. default lifetime one day instead of one hour;
  5. Basic auth accepts any password of an existing user.
- **Reference** (`reference/mb05.patch`): new `test_api_tokens.py`, 8 tests; kills 5/5 (verified by the scorer);
  the existing `tests.py` alone kills 0/5 (checked by hand), so an agent must write real tests.
- **Pitfalls**: the token is cached on the user while it has > 60 s left, so "request a token twice and expect a new
  one" fails; testing expiry needs setting `token_expiration` (naive after a reload from SQLite); Basic auth needs a
  user with a password (`set_password`).
- **Objective**: mutant killing is deterministic; no wall-clock waits (expiry is set, not awaited). No equivalent
  mutants: each changes a response status or a returned token in a case the prompt names.
- Baseline "fails" only through `scopeOk` (no test file added): for T tasks the meaningful check is the reference
  killing every mutant.

## mb06 (R): split `app/models.py` into a package

- The prompt fixes the layout (mixins, user + `followers` + `load_user`, post, message, notification, task,
  `__init__` re-exports; `app/models.py` gone). 8 files change (1 deleted, 7 added).
- **Hidden test** `test_bench_mb06.py`: API shape (package, `__module__` of every class/function equals its new
  module, `app.models.X is app.models.<mod>.X`, old-style imports) + wiring kept (`before_commit`/`after_commit`
  listeners still registered on `db.session`, `login._user_callback is load_user`, class bases) + a behavior smoke
  test (following posts, unread count, notifications, follower/post counts, a logged-in web page, an API collection).
  Plus the existing `tests.py`.
- **Pitfalls**: circular imports (`Post` needs `User.id` for its foreign key, `User` methods use `Post`, `Message`,
  `Notification`, `Task`): solved by importing the dependents at the bottom of `user.py` (reference) or inside
  methods; losing the `db.event.listen` registrations or the `@login.user_loader` when moving code; `app/tasks.py`
  and `app/__init__.py` (`from app import models`) must keep importing.
- **Objective**: import/identity checks; no behavior change is verified by the smoke test and the existing tests.
  Diff scope (only `app/models*`) is report-only (`outOfScope` is empty for the reference; `allowedPaths` is not set
  so an agent touching other files is reported, not failed).

## mb07 (F): `POST /api/messages` + convention probe

- **Behavior** (`test_bench_mb07.py`): 201 with exact `sender`/`recipient` objects, `+00:00` timestamp near now,
  the stored message, the recipient's unread count; sender = token owner; 140 chars accepted, 141 rejected; 400 with
  a JSON `error` for missing fields, wrong types, empty text, empty object and a non-JSON body; 404 JSON for an
  unknown recipient; 401 without/with an invalid token; nothing stored on rejection.
- **Convention probe** (`test_bench_mb07_convention.py`), conventions visible only in the code:
  1. API validation errors use `bad_request(message)` from `app/api/errors.py`: body
     `{"error": "Bad Request", "message": "..."}`. `abort(400)` inside the `api` blueprint gives only `error`
     (caught).
  2. Sending a message refreshes the recipient's `unread_message_count` notification (the web view
     `main.send_message` calls `user.add_notification('unread_message_count', user.unread_message_count())`), which
     drives the live badge. The prompt states only "the same effects for the recipient as sending from the web
     interface". After two API messages: exactly one such notification with data 2, none for the sender.
- Verified: a variant with `abort(400)` and without the notification passes the behavior file and fails both probe
  tests.
- **Other pitfalls**: the `Message` relationship is `author`, the JSON field is `sender`; naive SQLite timestamps
  (`.replace(tzinfo=timezone.utc)` is the repo convention); the new module must be imported in
  `app/api/__init__.py`; `request.get_json()` raises 415 on a non-JSON body (use `silent=True`).
- **Objective**: exact JSON/status assertions and a database query.

## Pilot (disposable)

- **mbp1 (B)**: `baselines/mbp1.patch` makes `User.followers_count` count `self.following`. Hidden test checks
  `follower_count`/`following_count` of three users through the API and the "3 followers" / "1 following" text of
  the profile page. Note: the existing `tests.py::test_follow` already fails on this baseline (allowed by the
  authoring rules), which makes the pilot B easier than the scored ones; it exists to prove the setup, the
  per-position baseline patch and scoring, not to be hard.
- **mbp2 (I)**: unknown URL outside `/api` without `Accept`: `handler` = `app.errors.handlers.not_found_error`,
  `decider` = `wants_json_response`, `format` = `json` (both `accept_mimetypes` qualities are 0 without the header, so
  `>=` is true; checked with the test client: `404 application/json {"error":"Not Found"}`), `status` = `404`.
  Contradictions: `handle_exception` (the `api` blueprint handler does not apply to an unmatched URL), `html`,
  `text/html`, `errors/404.html`.

## Verification (2026-10-11)

`python3 bench/verify_task.py --suite v2-microblog` and `--suite v2-microblog-pilot`: every task OK (code/tests:
baseline fails, reference passes; investigation: reference answer passes, empty answer fails). mb05: 5/5 mutants
killed by the reference. Extra checks by hand: wrong I answers (`before_commit`, two candidates, `last_seen`,
`send_message`, `html`, `handle_exception`, a missing key) fail; decorated answer blocks (bold header, bullets,
backticks, `#`, trailing `()`) pass; the mb02 read-side fix and the mb01 `+ 1` fix fail.

## UNVERIFIED

- No agent was run on these tasks; whether prompts are hard enough (B localization, R circular imports) is unknown
  until the pilot.
- `time.tzset()` (mb02) is POSIX-only; fine on macOS/Linux, not on Windows.
- Determinism over many runs: each check was scored once by `verify_task.py` plus once or twice by hand.
