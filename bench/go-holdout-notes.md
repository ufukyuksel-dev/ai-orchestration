# Go holdout suite notes (go-holdout.json)

Authored before any agent run on it. No lever was tuned on it, and no agent benchmark was run while it was written.

## Repository

`gothinkster/golang-gin-realworld-example-app` @ `626c372d259472148d93303f74aa9b9a1cdcef24` (MIT,
https://github.com/gothinkster/golang-gin-realworld-example-app). This is the RealWorld ("Conduit") API in Go:
gin 1.10, GORM v2 and SQLite through `mattn/go-sqlite3` (cgo), with JWT auth. It has three packages (`users`,
`articles`, `common`), about 1.6k lines of non-test code, and models for users, follows, articles, tags,
favorites and comments.

Why this repo:
- It is a real application (`hello.go` is `main`), not a library. It is small enough for 5-15 minute tasks and has
  enough relations to give project pitfalls.
- `go test ./...` takes about 4 s (6 s through `score()` with vet). The tests use a SQLite file per package and need
  no external services. With a warm module cache everything runs offline: the validation ran with `GOPROXY=off`.
- It builds, vets and tests cleanly with today's Go (go1.27.2). `go.mod` says `go 1.21` and has no toolchain
  directive, so no toolchain download happens.
- It has a quality gate: CI runs gofmt (`scripts/gofmt.sh`) and golangci-lint v1.61 (`.golangci.yml`: staticcheck,
  unused, misspell). golangci-lint is NOT part of `qualityCommand`. It is not installed here, and a v1.61 binary is
  not expected to load go1.27 export data. The gate is `go vet ./... && test -z "$(gofmt -l .)"`.
- Rejected: `bxcodec/go-clean-arch` (last commit 2024, needs MySQL to run, and the tests are almost only mocks with
  no real router or relations to learn).

The repo ships an `AGENTS.md` (structure, commands). Both arms see it.

## Setup

`bench/setup/golang-gin-realworld-example-app.sh` (idempotent): clone to `bench/.work/repos/golang-gin-realworld-example-app`,
check out the SHA, `go mod download`, `go build ./...`. It needs `go` on PATH (`brew install go`), a C compiler for
cgo and network once. `bench/fetch.sh` does not know this repo. The suite file carries `sha`, so `run.py` pins it
through `SUITE_SHA`.

## Suite layout

- `hiddenTarget` = `.`: the hidden tests are in package `main` at the repo root. The repo has no root tests, so
  nothing collides. All helpers in a hidden file carry the task prefix (`benchG1T1...`) so they cannot clash with
  code an agent adds to `main`.
- `hiddenTestCommand` = `go test -count=1 -run '^TestBenchG' .`, `ownTestCommand` = `go test -count=1 ./...`
  (run after the hidden file is removed), `qualityCommand` as above (run before the hidden file is placed).
- Hidden tests are black-box. Each starts the real `main()` in a goroutine on a free port (`PORT`) with a throw-away
  SQLite file (`DB_PATH` in `t.TempDir()`). It creates all data through the existing public API (register, articles,
  comments, follow, favorite, deletes) and checks only the HTTP contract of the prompt. It never touches models,
  registration functions or file names. A route the agent wires anywhere `main` serves it is found. A route that
  only a test router knows is not.
- The seed order is chosen so that `ArticleUserModel` ids (the author/favoriter id used by articles, comments and
  favorites) differ from `UserModel` ids. Mixing them up gives wrong results instead of passing by luck.
- Each task starts from the clean SHA (`reset_code()`), and every reference patch is a `git diff` against the SHA.
  The patches are independent, not stacked.

## G1: Read-only JSON statistics endpoints

| Task | Endpoint | What the hidden test checks |
|---|---|---|
| g1t1 | `GET /api/profiles/{username}/stats` | 5 users. Anonymous and with a token: 200, `application/json`, `stats.username`, and `articlesCount`/`commentsCount`/`followersCount`/`followingCount` as JSON integers. Exact values: alice 2/2/2/1, bobby 1/1/1/1, carol 1/0/0/2, david 0/0/1/0, erinx 0/0/0/0. The seed includes a deleted article, a deleted comment and an unfollow. `nobody` gives 404. `/api/profiles/alice` still gives 200, an anonymous follow still gives 401, and an article page still gives 200. |
| g1t2 | `GET /api/articles/{slug}/stats` | Anonymous and with a token. The story has `commentsCount` 3, `commenters` `["alice","bobby"]` (david's comment deleted, alice twice) and `favoritesCount` 3 with `favoritedBy` `["bobby","david","erinx"]` (alice unfavorited). An untouched article gives 0/0/`[]`/`[]` (null fails). A deleted article and an unknown slug give 404. The article and comments endpoints still work, and an anonymous comment still gives 401. |
| g1t3 | `GET /api/tags/stats` | Without articles: `{"tags": []}`. After 7 articles (1 deleted) the result is exactly `go=3, db=2, web=2, aa=1` (tie broken by tag, and `zz`, carried only by the deleted article, omitted), anonymous and with a token. `/api/tags` still lists all 5 tags, and `?tag=go` still gives 200. |

Pitfalls an agent is likely to hit, and can reuse in T2 and T3:
1. **Middleware order decides auth.** In `hello.go`, `v1.Use(AuthMiddleware(false))` and later
   `v1.Use(AuthMiddleware(true))` apply only to groups and routes created after each call. A GET added to
   `ArticlesRegister`/`ProfileRegister` (or wired after `Use(true)`) gives 401 to anonymous callers. Without any auth
   middleware, code that calls `c.MustGet("my_user_model")` (every existing serializer) panics.
2. **Import direction.** `articles` imports `users`. A profile endpoint that needs article, comment or favorite
   counts cannot live in `users` (import cycle). It goes in `articles` with its own register function wired in
   `hello.go`, or it uses raw tables.
3. **Author ids are `ArticleUserModel` ids.** `ArticleModel.AuthorID`, `CommentModel.AuthorID` and
   `FavoriteModel.FavoriteByID` point to `article_user_models`, which are created lazily (first article, comment,
   favorite or authed list call). `FollowModel` uses `UserModel` ids. Querying `author_id = user.ID` gives wrong
   counts (verified: alice gets 1 article instead of 2).
4. **Soft deletes.** Every model embeds `gorm.Model`, so deletes, unfollows and unfavorites only set `deleted_at`.
   GORM model queries filter that out. `db.Table(...)`/raw SQL and `article_tags` join rows do not, and deleted
   articles keep their tag links.
5. **Go JSON:** a nil `[]string` encodes as `null`. Start with `[]string{}`.
6. Route trees: `/:slug/...` siblings are fine in gin 1.10. A second wildcard name at the same position (e.g.
   `/:id/...` next to `/:slug`) panics at startup.

## G2: CSV export

All three tasks use the same CSV contract as the Java holdout: header, a line break after every line, RFC 4180
quoting. The hidden test parses the body strictly: CRLF or LF, quoting of any field allowed; it rejects bare quotes,
junk after a closing quote, bare CR, unterminated quotes and a missing final line break. It requires
`mime.ParseMediaType(Content-Type) == text/csv`.

| Task | Endpoint | What the hidden test checks |
|---|---|---|
| g2t1 | `GET /api/articles.csv` | 24 articles by 3 authors, 1 deleted: exactly 23 records after the header, in creation order. Titles `Hello, "World"` and `Two\nLines Title` and tags `a,b`+`go` (giving `a,b;go`) must round-trip. Tags are sorted and joined with `;`. `favoritesCount` is 2/1/0 (with an unfavorite). `createdAt` must equal the `createdAt` of `GET /api/articles/{slug}` (format `2006-01-02T15:04:05.999Z`, trailing zeros trimmed). Checked anonymous and with a token. The deleted article gives 404, `/api/articles` still gives 200, and an anonymous POST still gives 401. |
| g2t2 | `GET /api/articles/{slug}/comments.csv` | Records equal `id`/author/body/`createdAt` as reported by `GET /api/articles/{slug}/comments`, in creation order (bobby's `a, "quoted"\nsecond line`, then alice, then alice). A deleted comment and other articles' comments are excluded. Checked anonymous and with a token. An empty article gives only the header. Unknown and deleted articles give 404. |
| g2t3 | `GET /api/profiles/{username}/favorites.csv` | alice favorites all 24 articles (12 by bobby, 12 by carol, one titled `Commas, "Quotes" and More`), unfavorites 1, and 1 is deleted: 22 records sorted by slug. david has 2 records, and bobby only the header. Checked anonymous and with a token. `nobody` gives 404. Profile, `?favorited=` and follow-auth behavior are unchanged. |

Pitfalls (on top of G1's 1-4):
1. `/api/articles.csv` cannot come from the `/articles` group (`Group("/articles").GET(".csv")` becomes
   `/api/articles/.csv`). It must be registered on `v1` or the engine, at the right position relative to the two
   `Use` calls.
2. **`FindManyArticle` silently pages.** Reusing it (all articles, or `favorited=`) with an empty limit gives at
   most 20 rows (verified: 21 instead of 24 records in g2t1, 20 instead of 23 in g2t3).
3. **The API time format** is `2006-01-02T15:04:05.999Z` in UTC, which trims trailing zeros. RFC 3339 fails
   (verified). A `.000` variant fails only when a timestamp has trailing zeros, so it is caught in most runs, not
   all.
4. `encoding/csv` already does RFC 4180 quoting (hand-written `strings.Join` fails, verified). Use
   `text/csv` as the content type (`text/plain` fails, verified), with `c.Data` rather than `c.String`/`c.JSON`.
5. g2t3 has the favoriter id trap (`FavoriteByID` is an `ArticleUserModel` id). Using `user.ID` gives the wrong
   user's favorites (verified: header only).

## Reference patches

Each patch is minimal and formatted with gofmt. Handlers go in new files in `articles`. Wiring is a one-line change
in `articles/routers.go` or `hello.go`. G2 patches each carry their own `articles/csv.go` (`writeCSV` with
`encoding/csv` + `c.Data(200, "text/csv; charset=utf-8", ...)`).

| Patch | Adds |
|---|---|
| g1t1 | `articles/profile_stats.go` (`ProfileStatsAnonymousRegister`, `ProfileStats`), wired in `hello.go` after `users.ProfileRetrieveRegister` |
| g1t2 | `articles/article_stats.go` (`ArticleStats`), route in `ArticlesAnonymousRegister` |
| g1t3 | `articles/tag_stats.go` (`TagStats`, join with `article_models.deleted_at IS NULL`), route in `TagsAnonymousRegister` |
| g2t1 | `articles/csv.go` + `articles/articles_csv.go`; `v1.GET("/articles.csv", ...)` right after `v1.Use(users.AuthMiddleware(false))` |
| g2t2 | `articles/csv.go` + `articles/comments_csv.go`; route in `ArticlesAnonymousRegister` |
| g2t3 | `articles/csv.go` + `articles/favorites_csv.go` (`ProfileFavoritesAnonymousRegister`), wired in `hello.go` |

## Validation (2026-10-11, go1.27.2 darwin/arm64)

Scratch script (not committed) importing `bench/run.py` and calling only `run.score(task, suite, wt)`, so the
judging chain matches a real run: quality without the hidden file, then the hidden file copied into `hiddenTarget`,
then `hiddenTestCommand`, then the hidden file removed, then `ownTestCommand`. It ran with `GOPROXY=off
GOFLAGS=-mod=readonly`. Per task: `git worktree add --detach /tmp/.../<id> <sha>` from
`bench/.work/repos/golang-gin-realworld-example-app` → `score()` (base) → `git apply --check` + `git apply
bench/reference/<id>.patch` → `score()` (reference) → `git worktree remove --force`.

| Task | Base: quality / hidden / own | Base failure | `apply --check` | Reference: quality / hidden / own | Hidden file left | Reference 5x repeat |
|---|---|---|---|---|---|---|
| g1t1 | ok / FAIL / ok | `GET /api/profiles/alice/stats: status 404` | ok | ok / ok / ok | no | 5/5 pass |
| g1t2 | ok / FAIL / ok | `GET /api/articles/carol-big-story/stats: status 404` | ok | ok / ok / ok | no | 5/5 pass |
| g1t3 | ok / FAIL / ok | `GET /api/tags/stats: status 404` | ok | ok / ok / ok | no | 5/5 pass |
| g2t1 | ok / FAIL / ok | `GET /api/articles.csv: status 404` | ok | ok / ok / ok | no | 5/5 pass |
| g2t2 | ok / FAIL / ok | `.../talk-of-the-town/comments.csv: status 404` | ok | ok / ok / ok | no | 5/5 pass |
| g2t3 | ok / FAIL / ok | `GET /api/profiles/alice/favorites.csv: status 404` | ok | ok / ok / ok | no | 5/5 pass |

`score()` takes about 5-7 s per call. `go test -count=1 ./...` alone takes about 4 s wall.

Wrong-turn checks (reference mutated, hidden test run alone). Every variant fails except the one marked:

| Variant | Result |
|---|---|
| g1t1: stats route in the authed group (after `Use(true)`) | 401 instead of 200 |
| g1t1: `author_id = user.ID` | `articlesCount` 1, want 2 |
| g1t1: followers counted with `db.Table("follow_models")` (no soft-delete scope) | `followersCount` 3, want 2 |
| g1t1: route registered before any auth middleware | **passes** (legitimate: this handler needs no user context) |
| g1t2: nil slice for an empty list | `commenters must be a JSON array, got <nil>` |
| g1t2: favoriter resolved as `UserModel{ID: FavoriteByID}` | `favoritedBy` `[bobby carol erinx]` |
| g1t2: route in `ArticlesRegister` (authed) | 401 |
| g1t3: join without `deleted_at IS NULL` | `web=3`, `zz=1` listed |
| g1t3: no tie-break on tag | `web` before `db` |
| g2t1: `FindManyArticle("", "", "", "", "")` | 21 records, want 24 |
| g2t1: no quoting (`strings.Join`) | parser: bare double quote |
| g2t1: `time.RFC3339` | createdAt mismatch |
| g2t1: `text/plain` | content type rejected |
| g2t1: route not wired | 404 |
| g2t2: newest first | order mismatch |
| g2t3: `favorite_by_id = user.ID` | 1 record (header), want 23 |
| g2t3: `FindManyArticle(favorited=...)` | 20 records, want 23 |

## UNVERIFIED

- Whether real agents hit or learn the pitfalls. No agent benchmark was run on this suite.
- Linux and CI hosts: validated only on macOS arm64 with Apple clang (cgo). The hidden tests bind a TCP port on all
  interfaces (`r.Run(":"+PORT)` in `main`). The macOS application firewall was off; with it on, the test binary
  may trigger an "accept incoming connections" prompt.
- golangci-lint (configured in the repo's CI) is not part of the gate.
- Task duration (target 5-15 minutes) is estimated from the reference sizes (50-90 line patches), not measured.
