# Holdout suite notes (holdout.json)

This suite was authored after the final-suite tuning and before any run with the frozen product. No lever was tuned
on it, and no agent benchmark was run while it was being written.

Repo: spring-petclinic @ a6efbed773f61a271c071461326940786998722e (Spring Boot 4.0.3, Jackson 3). The layout matches
final.json:
- `hiddenTarget` = `src/test/java/org/springframework/samples/petclinic`.
- Hidden tests are in `bench/hidden/<taskId>/BenchHxTyTest.java`, which is where `run.score()` copies them from.
- Every hidden test is a `@SpringBootTest` + `@AutoConfigureMockMvc` class with a unique `bench.hidden=<taskId>`
  property. That gives each class its own context, in-memory H2 database and caches.
- `tests` = `!MySqlIntegrationTests,!PostgresIntegrationTests` (the full suite) for every task.
- `qualityCommand` = `./mvnw -q -DskipTests validate` (spring-javaformat + nohttp checkstyle).

Chain semantics mirror run.py exactly. `run_chain()` calls `reset_code()` (checkout/reset --hard/clean to the SHA)
before every task. Only arm B's memory carries over between tasks, not code. So every task starts from the clean SHA,
and every reference patch is a `git diff` against the SHA that applies on its own. They are not stacked.

Subsystems not used by dev.json (validation messages, new entity fields) or final.json (deletes, list filters and
pagination): read-only JSON endpoints and CSV export.

## H1: Read-only JSON API

| Task | Endpoint | What the hidden test checks |
|---|---|---|
| h1t1 | `GET /api/owners/{ownerId}` | Checks 200 and an `application/json`-compatible content type. Owner 10: `id` is numeric and `firstName`, `lastName`, `address`, `city` and `telephone` are exact strings. `pets` has exactly 2 entries, found by id, not by position: Lucky (12, `2010-06-24`, `type` = `"dog"`) and Sly (13, `2012-06-08`, `"cat"`). Owner 6 is spot-checked. A JDBC-inserted owner without pets gives `pets == []`. `/api/owners/9999` gives 404. `/owners/10` and `/owners?lastName=Davis` still return 200. |
| h1t2 | `GET /api/vets` | Checks 200, JSON content type and a top-level array of 7 vets (6 seeded + 1 inserted with specialties stored as surgery, radiology). Per vet, found by id: `firstName`, `lastName`, and `specialties` as a sorted array of name strings. `[]` for Carter and Jenkins, `["dentistry","surgery"]` for Douglas, `["radiology","surgery"]` for the inserted vet. `/vets.html` still renders, and `/vets` still returns `vetList`. Everything is in one test method, with data inserted before the first `findAll()`, so the `vets` cache can never be stale. |
| h1t3 | `GET /api/owners/{ownerId}/pets/{petId}/visits` | Checks 200, JSON content type and a top-level array. Pet 7 gets an extra, later-inserted visit dated 2012-12-25, so the exact `id`/`date`/`description` sequence is `[new, 1, 4]` / `2012-12-25, 2013-01-01, 2013-01-04` (sorted by date, not id). Pet 8 gives `[2, 3]`, and pet 1 gives `[]`. A repeated read still gives 3 visits and the DB still has 5 rows, so no phantom visit appears. 404 for owner 1/pet 7 (wrong owner), owner 9999 and pet 9999. The owner page and the new-visit form still return 200. |

Gotchas an agent can learn in T1 and reuse:
1. **Unknown ids surface as 500, not 404.** `OwnerController`, `PetController` and `VisitController` each have a
   `@ModelAttribute` method that runs before every handler in that controller. For an unknown id it throws
   `IllegalArgumentException`, which has no handler and gives a 500 (in MockMvc, a `ServletException`). A JSON
   handler added to one of these controllers cannot return 404 for unknown ids. It needs its own controller, or it
   must not depend on those model attributes.
2. **Entities are not the JSON shape.** Serializing `Owner`/`Pet` directly gives `type: {id, name, new}` and extra
   fields. The spec requires `type` as a plain name string. Dedicated DTOs (records) are the easy way.
3. **VisitController adds a phantom visit.** `loadPetWithVisit` adds a blank `new Visit()` (id null, date today) to
   the model's pet. A visits endpoint in `VisitController` that reads `pet.getVisits()` returns one visit too many.
   It also fails with a 500 for unknown or mismatched ids.
4. `/api/vets` must be a top-level array. The existing `/vets` resource wraps the vets in `{"vetList": [...]}`, and
   its specialties are objects.

## H2: CSV export

| Task | Endpoint | What the hidden test checks |
|---|---|---|
| h2t1 | `GET /owners.csv` | Checks 200 and a `text/csv`-compatible content type. The body is parsed with a strict RFC 4180 parser inside the test. It accepts CRLF or LF and quoting of any field. It rejects a bare quote in an unquoted field, junk after a closing quote, bare CR and unterminated quotes. Checks: exactly 13 records; the header is exactly `id,firstName,lastName,city,telephone`; the owners are ordered 1..12. Two JDBC-inserted owners, `Smith, Jr.` / `Port "Harbor"` and city `Two\nLines`, must round-trip, which requires quoting. `/owners?lastName=`, `/owners/1` and `/owners/find` still return 200. |
| h2t2 | `GET /vets.csv` | Checks 200, `text/csv`, 9 records and the exact header `id,firstName,lastName,specialties`. Vets are ordered by id. `specialties` is empty for Carter and Jenkins and `dentistry;surgery` for Douglas. An inserted vet with 3 specialties gives `dentistry;radiology;surgery`. An inserted vet `Comma, Quote` has the new specialty `x-ray, "dental"`, which gives the quoted field `radiology;x-ray, "dental"`. `/vets.html` and `/vets` still work. Everything is in one test method, before the first `findAll()`, because of the `vets` cache. |
| h2t3 | `GET /owners/{ownerId}/visits.csv` | Checks 200, `text/csv` and the exact header `pet,date,description`. Owner 6 has 3 inserted visits, and the full record sequence must be exact. The ordering is by date and then by pet name: on a tied date, Max (pet id 8) must come before Samantha (pet id 7), so sorting by pet id fails. One description, `said "hi"\nand left`, needs quoting. Owner 1, whose pet has no visits, gives only the header. `/owners/9999/visits.csv` gives 404. `/owners/6`, `/owners/6/edit` and the new-visit form still return 200. |

Gotchas an agent can learn in T1 and reuse:
1. A small reusable RFC 4180 writer (quote on `,` `"` CR/LF, double inner quotes) and a `text/csv` response. Handlers
   should use `produces = "text/csv"` or a `ResponseEntity` with an explicit content type. Returning a `String` from a
   `@Controller` method without `@ResponseBody` resolves a template name.
2. For h2t3: the same `OwnerController` `@ModelAttribute` trap as in H1. `/owners/{ownerId}/...` in `OwnerController`
   gives a 500 instead of a 404 for an unknown owner.
3. `VetRepository.findAll()` is `@Cacheable("vets")` and returns an unordered `Collection`. The export must sort by id
   itself.
4. All owners, no paging: `OwnerRepository` only has a paged last-name finder. `findAll(Sort.by("id"))` from
   `JpaRepository` is the direct route.

## Reference patches

Each patch is minimal and adds new classes only, with no changes to existing files:

| Patch | Adds |
|---|---|
| h1t1 | `owner/OwnerRestController` (`@RestController`, record DTOs, `ResponseStatusException(NOT_FOUND)`) |
| h1t2 | `vet/VetRestController` |
| h1t3 | `owner/VisitRestController` (explicit date sort, 404 for owner/pet mismatch) |
| h2t1 | `system/Csv` (RFC 4180 helper, CRLF) + `owner/OwnerCsvController` |
| h2t2 | `system/Csv` + `vet/VetCsvController` |
| h2t3 | `system/Csv` + `owner/OwnerCsvController` |

All patches are formatted with `./mvnw spring-javaformat:apply`.

## Validation (2026-09-26, scratch clones under /tmp/holdout-validate, never in bench/.work or the run.py worktrees)

Scratch base: `git clone` of `bench/.work/repos/spring-petclinic` (read-only use), checked out detached at the SHA.
The validation calls `run.score(task, suite, wt)` from bench/run.py (imported; nothing else in run.py is executed), so
the judging chain matches a real run exactly:
1. `./mvnw -q -DskipTests validate` without the hidden file.
2. Copy `bench/hidden/<taskId>/*` into `hiddenTarget`.
3. `./mvnw -q -Dspring-javaformat.skip=true -Dcheckstyle.skip test -Dtest='!MySqlIntegrationTests,!PostgresIntegrationTests' -Dsurefire.failIfNoSpecifiedTests=false`.
4. Remove the hidden file.

Online Maven (as run.py does; `~/.m2` was already warm).

Per task: fresh clone at the SHA → `score()` (base) → `git apply --check` + `git apply bench/reference/<taskId>.patch`
→ `score()` (reference). Each task starts from the clean SHA, as `reset_code()` does in a chain.

| Task | Fails on base | Base failure | `git apply --check` | Passes with reference (quality + full selection incl. hidden) | Suite on reference |
|---|---|---|---|---|---|
| h1t1 | yes (qualityOk=True, testsOk=False) | new endpoint 404 (`Status expected:<200> but was:<404>`), 2 of 4 hidden tests fail | yes | yes (success=True) | 14 classes, 46 tests, 0 failures/errors |
| h1t2 | yes (True/False) | `/api/vets` 404 | yes | yes | 14 classes, 43 tests, 0 failures/errors |
| h1t3 | yes (True/False) | endpoint 404 | yes | yes | 14 classes, 46 tests, 0 failures/errors |
| h2t1 | yes (True/False) | `/owners.csv` 404 | yes | yes | 14 classes, 44 tests, 0 failures/errors |
| h2t2 | yes (True/False) | `/vets.csv` 404 | yes | yes | 14 classes, 43 tests, 0 failures/errors |
| h2t3 | yes (True/False) | `/owners/6/visits.csv` 404 | yes | yes | 14 classes, 46 tests, 0 failures/errors |

Commands:
- `python3 /tmp/holdout-validate/validate.py 2` (scratch script, not committed). Results are in
  `/tmp/holdout-validate/validate-results.json`, and the script exited 0.
- An earlier pass ran each hidden test alone against its reference
  (`./mvnw -q -Dspring-javaformat.skip=true -Dcheckstyle.skip test -Dtest=BenchHxTyTest`) and exited 0 for all 6.
  The Spring `RuntimeException: Expected: controller used to showcase...` lines in the logs come from the existing
  `CrashController` tests and are expected.

Wrong-turn checks: plausible incorrect implementations, each hidden test run alone. All of them fail:

| Variant | Result |
|---|---|
| h1t1: serialize the `Owner` entity directly (404 handled) | Failure: `expected: "dog"` (type is an object) |
| h1t1: DTO endpoint inside `OwnerController` | Error: `ServletException ... IllegalArgumentException: Owner not found with id: 9999` (500, not 404) |
| h1t3: endpoint inside `VisitController`, reading the model's pet | 2 failures (extra phantom visit), 1 error (`IllegalArgumentException: Pet with id 7 not found for owner with id 1`) |
| h2t1: no RFC 4180 quoting | Failure: record mismatch for the comma/quote/newline owners |
| h2t3: inside `OwnerController`, tie broken by pet id | Failure: order on tied dates (Samantha before Max), plus Error: `IllegalArgumentException` for owner 9999 |

## UNVERIFIED

- Whether real agents hit or learn the gotchas. No agent benchmark was run on this suite.
- Determinism across many runs. Each reference passed twice: once alone, once in the full selection. Each base
  failed once through `score()`. The tests do not depend on JSON field order, JSON array order where none is
  specified, or H2 row order. CSV order is fully specified by the prompts.
- The behavior of the reference patches on MySQL/Postgres. The Docker integration tests are excluded, as in
  final.json.
