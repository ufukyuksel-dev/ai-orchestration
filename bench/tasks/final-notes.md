# Final suite notes (final.json)

Repo: spring-petclinic @ a6efbed773f61a271c071461326940786998722e. Hidden tests live in the root test package
(`hiddenTarget` = `src/test/java/org/springframework/samples/petclinic`, one target per suite; run.py only supports a
suite-level target). Every hidden test is a single `@SpringBootTest` + `@AutoConfigureMockMvc` class with a unique
`bench.hidden=<taskId>` property, so it gets its own Spring context and therefore its own in-memory H2 database. No
Docker is needed. `tests` for every task = `!MySqlIntegrationTests,!PostgresIntegrationTests`, the full suite, because
the likely wrong turns break the existing WebMvc/DataJpa tests. Reference solutions:
`bench/reference/<taskId>.patch` (`git diff` against the SHA; each one applies cleanly on its own).

## Dropped candidate: cached JSON endpoints

The suspected gotcha does not exist. `javax.cache:cache-api` is on the classpath, but no JCache provider is (only
`com.github.ben-manes.caffeine:caffeine`). So Spring Boot picks `CaffeineCacheManager`, which creates caches on
demand, and the `JCacheManagerCustomizer` in `system/CacheConfiguration` never runs. Checked: a plain
`@Cacheable("specialties")` with no registration served `GET /specialties` with 200. `getCacheNames()` returned
`[specialties]` and the manager class was `CaffeineCacheManager`.

## F1: Deleting parts of the owner aggregate

The lesson from T1:
1. Pets and visits are persisted only through `OwnerRepository.save(owner)`. `Owner.pets` and `Pet.visits` are
   unidirectional `@OneToMany @JoinColumn` with `cascade = ALL` but no `orphanRemoval`, and `pets.owner_id` and
   `visits.pet_id` are nullable. Removing a child from the collection and saving therefore only sets the foreign key to
   NULL. The row stays in the database, no error is raised, and the UI looks correct.
2. `VisitController`'s `@ModelAttribute("visit") loadPetWithVisit` runs before every handler in that controller. It
   adds a new blank `Visit` to the model's pet. If a new handler saves the model's owner, that phantom visit is
   persisted, and Bean Validation fails at persist time (`ConstraintViolationException`: description must not be
   blank), which gives a 500.

| Task | Gotcha exercised | Checked failure without it |
|---|---|---|
| f1t1 delete one visit | 1 (Pet.visits) + 2 | Handler using `@ModelAttribute Owner`: 500 ConstraintViolationException. Fresh `findById` but no `orphanRemoval`: `[deleted visit row] expected 0 but was 1`. |
| f1t2 delete pet + its visits | 1 (Owner.pets) | `removeIf` + save, no `orphanRemoval`: `[deleted pet row] expected 0 but was 1`. |
| f1t3 purge visits before a date | 1 (Pet.visits) + 2 | Model owner: 500 ConstraintViolationException. Fresh owner but no `orphanRemoval`: `[purged visit row] expected 0 but was 1`. |

The references add `orphanRemoval = true` on the relevant collection and load the owner fresh in the handler.

## F2: List filters that survive pagination

The lesson from T1:
1. The pagination links in `owners/ownersList.html` and `vets/vetList.html` are hard-coded strings, for example
   `'/owners?page=' + i` (5 places per template). They drop every filter, including the existing `lastName`.
2. `GET /owners` binds the query onto an `Owner` form object (model attribute `owner`, so `${owner.lastName}` and
   `${owner.city}` are available to the list template). `findOwners.html` is a `th:object="${owner}"` form, so
   `th:field` works only for real `Owner` properties.
3. The search form submits empty parameters (`city=`), which must mean "no filter". A single match redirects and
   zero matches re-render the find form.
4. The repository signature matters: `OwnerControllerTests`, `ClinicServiceTests` and `VetControllerTests` mock or
   call `findByLastNameStartingWith` and `findAll(Pageable)`.

| Task | Gotcha exercised | Checked failure without it |
|---|---|---|
| f2t1 owners by `city` | 1, 3 (2: `th:field="*{city}"` is fine) | Filter implemented but links untouched: `links to page 2 ... ["/owners?page=2", ...] did not match` (needs `city=Testville`). |
| f2t2 vets by `specialty` | 1 (vetList.html) | Filter implemented but links untouched: `["/vets.html?page=2", "/vets.html?page=2", "/vets.html?page=2"] did not match`. |
| f2t3 owners by `petType` | 1, 2, 3 + distinct join | `th:field="*{petType}"` on the find form: `TemplateProcessingException ... NotReadablePropertyException: Invalid property 'petType' of bean class Owner`. Non-distinct derived query: `[rows on page 1] expected 5 but was 4`. |

## Verification (commands run in a scratch worktree at the SHA, reset between tasks)

- Clean SHA plus the hidden test (`./mvnw -q -Dspring-javaformat.skip=true -Dcheckstyle.skip test -Dtest=<Bench…Test>`):
  all 6 fail. f1t1, f1t2 and f1t3 get 404 on the POST. f2t1: `rows on page 1 expected 5 but was 0`. f2t2 and f2t3:
  `rows on page 2 expected 2/3 but was 5`.
- Reference applied: `./mvnw -q -DskipTests validate` was run without the hidden file, as run.py does, and exits 0.
  Then the hidden test was copied in and
  `./mvnw -q -Dspring-javaformat.skip=true -Dcheckstyle.skip test -Dtest='!MySqlIntegrationTests,!PostgresIntegrationTests' -Dsurefire.failIfNoSpecifiedTests=false`
  exits 0: 14 classes, 43 tests, 0 failures, 0 errors, for each of the 6 tasks.
- Checked a second time through `run.score()` from bench/run.py against final.json. For every task, the clean SHA gives
  `success=False` (qualityOk=True, testsOk=False) and the reference patch gives `success=True`.
- `git apply --check` passes for all 6 patches on a clean checkout.

## UNVERIFIED

- Whether real agents discover the F1 orphan-removal gotcha during T1. It fails silently: the UI looks right, and only
  a database check such as the hidden test reveals it. The phantom-visit gotcha is loud (500).
- Hidden-test determinism across many runs: each task was run 2-3 times, always with the same result. Unsorted paging
  (f2t2 and f2t3) relies on H2's stable row order, but the assertions only count rows per page and check the union.
- The MySQL/Postgres behavior of the reference patches was not exercised (Docker integration tests are excluded).

## Revision (review r3)

- f2t3: the unfiltered `lastName=Hamsterson` check no longer requires Gus on page 2 (the task specifies no order). The two pages together must list each of the seven Hamstersons exactly once. Re-verified with `run.score()`: clean SHA `success=False`, reference patch `success=True`.
