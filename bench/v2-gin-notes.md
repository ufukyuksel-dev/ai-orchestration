# v2 gin suites: notes (v2-gin, v2-gin-pilot)

Hidden from agents. Written before any agent run on these suites; no agent was run while they were authored.

## Repository and conventions

`gothinkster/golang-gin-realworld-example-app` @ `626c372d259472148d93303f74aa9b9a1cdcef24` (Go, gin 1.10, GORM v2,
SQLite through cgo). Packages `users`, `articles`, `common`, `main` (`hello.go`). Background and setup as in
`bench/go-holdout-notes.md` (`bench/setup/golang-gin-realworld-example-app.sh`).

Commands are the go-holdout ones:
- `qualityCommand` `go vet ./... && test -z "$(gofmt -l .)"` (runs before any hidden file is placed);
- `hiddenTestCommand` `go test -count=1 -run '^TestBenchGN' .` with `hiddenTarget` `.` (package `main`); gn07 overrides
  it with `go test -count=1 -run '^TestBenchGN07' ./users/` and places its file at `users/bench_gn07_test.go`;
- `ownTestCommand` `go test -count=1 ./...` (after the hidden file is removed).

Black-box hidden tests (gn01, gn02, gn04, gnp1) start the real `main()` on a free port with a throw-away `DB_PATH`,
create every row through the public HTTP API and check only the contract stated in the prompt. All helpers carry the
task prefix (`benchGN01...`) so they cannot clash with agent code in `main`. Each file has one `Test` function
(`main()` runs once per process).

Baselines: gn01, gn04 and gnp1 inject a bug through `baselines/<id>.patch`; `run.reset_code()` commits sha + patch as
one parentless snapshot, so the injection is not visible in git history. Reference patches are diffs against that
baseline.

## Pitfalls of the repository the tasks rely on

1. **Author/favoriter ids are `ArticleUserModel` ids** (this is K, below).
2. **Route groups vs. auth middleware order** (`hello.go`): a group created after `v1.Use(AuthMiddleware(true))`
   rejects anonymous callers before its handlers run.
3. **Import direction**: `articles` imports `users`; `users` cannot call into `articles`.
4. **Soft deletes**: every model except `UserModel` embeds `gorm.Model`; deletes only set `deleted_at`, unique
   indexes still see the row.
5. **GORM struct conditions drop zero fields**: `Where(FavoriteModel{FavoriteID: x, FavoriteByID: 0})` matches any
   favorite of `x`. Upstream consequence (present at the pinned sha, not injected): an anonymous caller gets
   `"favorited": true` from `GET /api/articles/{slug}` whenever anybody favorited the article, and
   `"following": true` in an author object whose user is missing. No hidden check depends on these upstream
   behaviors (gn01 compares single-article flags only for signed-in users).
6. Existing users tests send tokens of users that do not exist (`HeaderTokenMock(req, 4)` after mocking 3 users,
   and id 0) and expect a `422`, so "unknown user id -> 401" in the middleware breaks `TestWithoutAuth`.

## Memory chain GN-K (gn01 -> gn02 -> gn03)

**K:** articles, comments and favorites do not store `UserModel` ids. `ArticleModel.AuthorID`,
`CommentModel.AuthorID` and `FavoriteModel.FavoriteByID` point to `ArticleUserModel` rows (`article_user_models`),
a separate per-user row that `articles.GetArticleUserModel(user)` creates lazily (on the first article, comment,
favorite or authenticated list/serializer call), keyed by `user_model_id`. So the two id spaces diverge as soon as
users act in a different order than they registered. `FollowModel` uses `UserModel` ids. To act on "user U's
articles/comments/favorites", first map U to its `ArticleUserModel` (by `user_model_id`), then filter by that id.

| Pos | Task | Role | How it depends on K |
|---|---|---|---|
| 1 | gn01 (B) | discovery | The injected bug passes `myUserModel.ID` (a `UserModel` id) to `BatchGetFavoriteStatus`, which filters `favorite_by_id`. Finding and fixing it is discovering K: the fix maps the user through `GetArticleUserModel` first. No prompt or file name mentions the two id spaces. |
| 2 | gn02 (X) | dependent | Deleting a user's articles, comments and favorites must filter by the user's `ArticleUserModel` id. The seed makes the ids diverge (authors write in the order david, alice, erinx, carol, bobby; bobby is user 2 but author row 5, and author row 2 is alice). Deleting by `user.ID` deletes alice's data and keeps bobby's: verified, the test fails (`GET /api/articles/bobby-bulletin: status 200, want 404`). |
| 3 | gn03 (T) | control | Token parsing in the auth middleware (`users`); no `ArticleUserModel` involved. Measures the injection tax of carrying K into an unrelated task. |

Positions run from their own baselines (gn01: sha + `baselines/gn01.patch`; gn02, gn03: sha). gn02 also needs
pitfalls 2, 3 and 6, which it has to discover itself.

## Tasks

### gn01 (B, chain discovery): wrong `favorited` flags in lists

- Injection (`baselines/gn01.patch`, `articles/serializers.go`, `ArticlesSerializer.Response`): the list serializer
  passes `myUserModel.ID` instead of `GetArticleUserModel(myUserModel).ID` to `BatchGetFavoriteStatus`. The symptom
  shows in the list and feed endpoints; the single-article path (`ArticleSerializer.Response`) is correct. Existing
  tests stay green on the baseline (their user and author ids coincide).
- Hidden check (`bench_gn01_test.go`): 4 users; articles written in reverse registration order, so user id i maps to
  author row 5-i. Favorites (alice: carol's; david: bobby's and alice's; carol: david's; an unfavorite). For alice,
  bobby, carol and david, every article in `GET /api/articles?limit=50`, `?tag=news`, `?author=bobby` and in the feed
  must carry the expected `favorited` (equal to `GET /api/articles/{slug}` for that user) and the right
  `favoritesCount`; anonymous lists are all `false`. Baseline failure: `GET /api/articles?limit=50 as alice: article
  carol-corner favorited false, want true`.
- Reference: the reverse of the injection. Fixing inside `BatchGetFavoriteStatus` (map the user id there) passes too.
- Objective: exact boolean per (user, article, endpoint) derived from the seed; no timing, no ordering assumption.

### gn02 (X, chain dependent): account deletion (`users` + `articles` + `main` wiring)

- Contract: `DELETE /api/user` (anonymous -> 401, nothing deleted); afterwards profile 404, login 401, the old token
  -> 401 on `GET /api/user`; the user's articles 404 and absent from lists (`limit`, `tag`, `author`, `favorited`) and
  feeds; their comments absent everywhere; their favorites no longer counted; username and email re-registrable
  with an empty new account; other accounts unchanged (articles, comments, favorites, follows, profile). A second
  deletion of a user without any article data (never got an `ArticleUserModel`) must work.
- Why it spans both packages: the route belongs to the `/api/user` group of `users`, user and follow rows are owned
  by `users`, article/comment/favorite/`ArticleUserModel` rows by `articles`, and `users` cannot import `articles`.
  The reference adds `users/delete.go` (`UserDelete`, `DeleteUser`, `OnUserDelete` hook registry; transaction),
  `articles/account.go` (`DeleteUserData`), registers the hook in `hello.go`, adds the route to `UserRegister`, and
  makes `UserRetrieve` answer 401 when the token's user no longer exists. The hidden test is black-box (package
  `main`) and exercises both packages' endpoints; it does not enforce that both packages' files change (an agent may
  do all deletions from `articles` or `main`).
- Pitfalls: K (verified wrong turn above); pitfall 6: making the auth middleware reject unknown user ids breaks two
  `TestWithoutAuth` cases (verified), so the 401 for `GET /api/user` has to be narrower; pitfall 2 for route
  placement; soft-deleted articles keep their slug (not checked).
- Objective: exact slug/comment sets and counts after the deletion, exact status codes. SQLite ids use
  `AUTOINCREMENT` (verified DDL), so a re-registered user never reuses the old id; the "new account starts empty"
  checks hold deterministically.
- checkSeconds about 16 (bcrypt on 7 registrations dominates).

### gn03 (T, chain control): pin down request authentication

- `allowedPaths` `*_test.go` (fnmatch: also matches files in subdirectories); `testCommand` `go test -count=1 ./...`.
- Mutants (`mutants/gn03/*.patch`, all in `users/middlewares.go`; each one survives the existing test suite,
  verified, so only the agent's tests can kill them):
  1. `Token` prefix matched case-sensitively (`strings.HasPrefix(bearerToken, "Token ")`).
  2. `access_token` query parameter preferred over the header.
  3. An invalid token aborts with 401 even on anonymous endpoints (`auto401` ignored on parse errors).
  4. Expired tokens accepted (`jwt.WithoutClaimsValidation()`).
  5. Tokens with a wrong signature accepted (signature errors ignored, `token.Valid` not checked).
  Each maps to one sentence of the prompt; none is equivalent (each is killed by a different assertion of the
  reference tests: lowercase prefix, header-over-query, malformed token on `/anon`, expired token on `/anon`,
  wrong-secret token on `/anon`).
- Reference (`reference/gn03.patch`): `users/middlewares_test.go`, a router wired like `hello.go` (`/anon` after
  `AuthMiddleware(false)`, `/auth` after `AuthMiddleware(true)`), tokens signed with `common.JWTSecret`, another
  secret, and a past `exp`. Kills 5/5.
- Baseline fails by `scopeOk: false` (no test file changed). checkSeconds about 22 (6 full `go test ./...` runs).

### gn04 (B, independent): password lost after a profile update

- Injection (`baselines/gn04.patch`, `users/validators.go`, `UserModelValidator.Bind`): the guard
  `if self.User.Password != common.RandomPassword` is removed, so an update without a password hashes the sentinel
  that `NewUserModelValidatorFillWith` puts in the password field. The symptom shows at login, the cause is in the
  validator. Existing tests stay green (their only update-then-login case changes the password).
- Hidden check: bio-only and image-only updates keep the original password working; the sentinel constant, an empty
  and a wrong password are rejected; a password change works (new accepted, old 401) and survives a later bio-only
  update; another user's login, `GET /api/user`, the 401 for anonymous `PUT` and the 422 for a too short password are
  unchanged.
- Wrong turn verified: "fixing" `NewUserModelValidatorFillWith` (no sentinel) makes the bio-only update a 422
  (`Password: {key: required}`), which fails.
- Objective: login status codes only.

### gn05 (I, independent): anonymous `GET /api/articles/feed`

- Answer key (`answers/gn05.json`): `status` 401, `function` `AuthMiddleware` / `users.AuthMiddleware`,
  `reachesHandler` no. Contradictions: `ArticleFeed`, `reachesHandler: yes`, status 200/404.
- Why: the feed route is registered by `ArticlesRegister` on a group created after `v1.Use(AuthMiddleware(true))`;
  gin copies the group's handlers at creation, so the chain is `AuthMiddleware(false)`, `AuthMiddleware(true)`,
  `ArticleFeed`, and the second middleware aborts. The static `/feed` wins over the anonymous group's `/:slug`. The
  tempting answer is `ArticleFeed`, which has its own 401 branch that is never reached. Verified with curl: 401,
  empty body.

### gn06 (I, independent): re-creating the title of a deleted article

- Answer key (`answers/gn06.json`): `status` 422, `errorKey` database, `rowKept` yes. Contradictions: status
  201/200/409/500, `rowKept: no`, `errorKey: none`.
- Why: soft delete keeps the row; `Slug` has a plain unique index that includes soft-deleted rows; `SaveOne` fails
  with `UNIQUE constraint failed: article_models.slug`, reported by `ArticleCreate` as
  `common.NewError("database", err)` with 422. Verified with curl on a fresh database.

### gn07 (R, independent): one accessor for the signed-in user

- Required structure: `users.CurrentUser(c *gin.Context) UserModel`; the literals `"my_user_model"` and
  `"my_user_id"` in exactly one non-test `.go` file each; no `Get`/`MustGet` on a gin context in non-test code
  outside `users`; context keys unchanged (existing tests read `my_user_id` and set up routers with the middleware).
- Hidden check (`users/bench_gn07_test.go`, package `users`, uses the package's `TestMain` test DB):
  `TestBenchGN07CurrentUser` compiles against `CurrentUser` and checks it returns the stored user for a valid token
  and the zero user anonymously; `TestBenchGN07SourceScope` walks the repository's non-test `.go` files and checks the
  two literal/`Get` criteria. Behavior is pinned by the existing tests (`ownTestCommand`).
- Reference touches 6 files (`users/{middlewares,routers,serializers}.go`, `articles/{routers,serializers,validators}.go`);
  `users.UsersRegistration`'s `c.Set` goes through an unexported `setCurrentUser`.
- Wrong turn verified: adding only `CurrentUser` fails the scope test (6 files hold the literal, 3 `articles` files
  call `MustGet`).
- The source-scope test is part of success, not a report-only probe: it checks acceptance criteria the prompt states
  explicitly (the definition of done of this refactor), not an unstated convention. If the coordinator prefers it
  report-only, move `TestBenchGN07SourceScope` into a second file listed under `"probe"`.

## Pilot (v2-gin-pilot, disposable)

One family `GNP`, no chain, two tasks that do not share knowledge (they still run in one cell, so arm B carries
memory from gnp1 to gnp2).
- gnp1 (B): `baselines/gnp1.patch` adds `db.Unscoped()` to the comment association query (`getComments`); deleted
  comments are listed again. Hidden test: ids after two deletions, empty list after deleting the only comment, a
  forbidden delete (403) changes nothing. Existing tests stay green on the baseline.
- gnp2 (I): page size of `GET /api/articles` without `limit` and with `limit=abc` (20 and 20), decided in
  `FindManyArticle` (`strconv.Atoi` fallback). Contradictions: `GetArticleFeed`, `ArticleList`.
Both areas (comments, paging) are disjoint from the scored tasks.

## Verification (2026-10-11, go1.27.2 darwin/arm64)

`python3 bench/verify_task.py --suite v2-gin` (twice, identical) and `--suite v2-gin-pilot`: every task OK.

| Task | Type | Role | baseline fails | reference passes | checkSeconds |
|---|---|---|---|---|---|
| gn01 | B | discovery | yes | yes | 5.0 |
| gn02 | X | dependent | yes | yes | 16.2 |
| gn03 | T | control | yes (scope) | yes, 5/5 mutants killed | 22.2 |
| gn04 | B | - | yes | yes | 6.0 |
| gn05 | I | - | empty answer fails | yes | - |
| gn06 | I | - | empty answer fails | yes | - |
| gn07 | R | - | yes | yes | 4.9 |
| gnp1 | B | pilot | yes | yes | 4.8 |
| gnp2 | I | pilot | empty answer fails | yes | - |

The answer keys were also run through `run.score_answer` with tempting wrong answers (`ArticleFeed`,
`reachesHandler: yes`, `AuthMiddleware or ArticleFeed`, status 201, `rowKept: no`): all fail; formatted variants
(`users.AuthMiddleware(true)`, `` `database` ``, `401 Unauthorized`, bullet/bold block) pass.

## UNVERIFIED

- Whether agents hit or learn K and the other pitfalls; no agent ran.
- gn02 does not enforce that both packages change (black-box check only).
- Linux/CI hosts (validated on macOS arm64 only); the hidden tests bind TCP ports on all interfaces as in go-holdout.
