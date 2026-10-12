# v2 petclinic suites: notes (v2-petclinic.json, v2-petclinic-pilot.json)

Repo: spring-petclinic @ a6efbed773f61a271c071461326940786998722e (Spring Boot 4, Hibernate 7, Thymeleaf, H2 seed
data). Rules: `bench/v2-authoring.md`. Design: `output/plan-harness-benchmark-v2.md` §4, §4.1, §6.3.
No agent was run on these tasks.

Conventions shared with the v1 holdout suite (`holdout.json`):
- `hiddenTarget` = `src/test/java/org/springframework/samples/petclinic`; hidden tests are copied there by `run.score()`.
- `qualityCommand` = `./mvnw -q -DskipTests validate` (spring-javaformat + checkstyle/nohttp), run without the hidden
  files.
- Hidden command = `run.MAVEN_HIDDEN` with `tests` = `!MySqlIntegrationTests,!PostgresIntegrationTests`. That is the whole
  suite (existing tests plus the hidden class), so "existing tests stay green" is part of success. Docker is not
  needed: the two Testcontainers/compose classes are excluded, as in v1. The rest of the suite runs on in-memory H2
  in about 10 s.
- Every hidden class is a `@SpringBootTest` + `@AutoConfigureMockMvc` with a unique `bench.hidden=<id>` property, so it
  gets its own context and its own H2 database.
- Every position starts from a parentless snapshot of sha + `baseline.patch` (`run.reset_code`). Injected bugs are
  therefore not visible in git history.

## Scored suite v2-petclinic (7 independent tasks, one family each, no chains)

| id | type | summary | checkSeconds |
|---|---|---|---|
| pc01 | B | Listing all owners shows 4 owners and one page; the cause is a JPQL join in the repository | 13.2 |
| pc02 | B | Telephone error shows raw `{telephone.invalid}`; the cause is a validator bean without a MessageSource | 12.1 |
| pc03 | I | What happens on the Add-Visit page for another owner's pet (status, exception, thrower, lookup) | n/a |
| pc04 | I | What `GET /owners?lastName=Davis&page=2` renders (view, error code, field) | n/a |
| pc05 | R | Extract `OwnerService` used by three controllers (7 files in the reference) | 11.1 |
| pc06 | T | Unit tests for `Owner` pet lookup and adding pets and visits; 5 mutants | 59.8 |
| pc07 | F | Reject a visit dated before the pet's birth date; i18n convention probe | 17.8 |

`checkSeconds` is from `verify_task.py`: one `score()` of the reference tree on a warm `~/.m2`. All values are under
the 3-minute limit.

### pc01 (B): owner search lists too few owners
- **Injection** (`baselines/pc01.patch`): `OwnerRepository.findByLastNameStartingWith` gets
  `@Query("SELECT owner FROM Owner owner LEFT JOIN owner.pets pet WHERE owner.lastName LIKE :lastName%")`.
  The SQL returns one row per pet. With LIMIT 5, page 1 holds 5 rows but only 4 distinct owners, because Hibernate
  de-duplicates the root entities. Spring Data then sees a short first page (4 < 5) and skips the count query, so
  the total becomes 4 and the page count 1. Owners 5 to 10 can no longer be reached. Searching a single owner, even
  one with 2 pets, still redirects correctly, so the bug only shows when listing.
- **Symptom vs cause**: the symptom is in the search page and controller paging; the cause is in the repository
  annotation.
- **Reference**: remove the `@Query` (back to the derived query). Adding `DISTINCT` also passes.
- **Hidden test** `BenchPc01Test`: single matches (Coleman, Estaban, Franklin) redirect. An empty search shows
  `totalItems == 10` and `totalPages == 2`, pages 1 and 2 each hold 5 owners, and together they hold ids 1..10 exactly
  once. `Davis` lists ids {2,4} with total 2, `Es` lists {8,10}, and `Nobody` renders `owners/findOwners`. Baseline:
  1 of 3 hidden tests fails (total 4). The existing suite stays green on the baseline.
- **Pitfalls**: the controller looks responsible (it does the paging). De-duplicating in Java or raising the page size
  in the controller does not fix the total. `ClinicServiceTests` only tests `Davis`, where each owner has one pet, so
  it stays green.
- **Objective**: exact counts, ids and view names on fixed seed data. The checks do not depend on order: id sets are
  compared, not sequences.

### pc02 (B): telephone message shown raw
- **Injection** (`baselines/pc02.patch`): a new `system/ValidationConfiguration` declares
  `@Bean static LocalValidatorFactoryBean validator()` with no MessageSource. Its Javadoc gives a plausible reason
  (one validator shared by forms and method validation). Boot's `defaultValidator` backs off
  (`@ConditionalOnMissingBean`), and Spring MVC uses this bean. Hibernate Validator then interpolates
  `{telephone.invalid}` from `ValidationMessages.properties`, where the key does not exist, so the literal key is
  shown. Default messages such as "must not be blank" are unaffected, which hides the cause.
- **Symptom vs cause**: the symptom is in the owner form. The obvious places, `Owner` (`@Pattern(message=...)`) and
  `messages.properties` (key present), are both correct. The cause is a configuration class in another package.
- **Reference**: delete the class. Also verified: keeping the bean and calling
  `setValidationMessageSource(messageSource)` passes too.
- **Hidden test** `BenchPc02Test`: invalid telephones (`12345`, `608555102x`) on new owners, and `608555102311` on an
  edit, show `Telephone must be a 10-digit number`, never `{telephone.invalid}`, and nothing is saved. After
  `GET /?lang=de` in the same session, the German message from `messages_de.properties` is shown. The blank-address
  error still reads `must not be blank`, and a valid owner is still created. Baseline: 3 of 4 fail; the existing
  suite stays green.
- **Pitfalls**: hard-coding the English text in `@Pattern(message=...)` passes EN but fails DE. Changing the template
  to print `#{telephone.invalid}` instead of `th:errors` breaks the other field errors and the language check.
- **Objective**: exact message strings taken from the repository's own message files.

### pc03 (I): Add-Visit page for another owner's pet
- **Answer** (`answers/pc03.json`): `status` = 500, `exception` = IllegalArgumentException,
  `thrownIn` = VisitController.loadPetWithVisit, `lookup` = Owner.getPet.
- **Chain**: `@ModelAttribute("visit") loadPetWithVisit` runs before `initNewVisitForm`. `Owner#getPet(Integer)`
  returns null for a foreign pet, and `loadPetWithVisit` throws an IAE. No handler maps it, so Boot's error page
  returns 500. This was verified against a running server (RANDOM_PORT): `/owners/1/pets/7/visits/new` gives 500 and
  `/owners/6/pets/7/visits/new` gives 200.
- **Contradictions** (any one fails the answer): 404, 200, 3xx, PetController, OwnerController, NullPointer,
  addVisit, processNewVisitForm, initNewVisitForm. These are the tempting wrong answers: guessing 404, blaming the
  handler method, or blaming `Owner#addVisit`'s `Assert.notNull`, which also throws an IAE but only on POST.
- **Objective**: one value per key. Aliases cover FQCN, `#`, `()` and "500 Internal Server Error". Robustness was
  checked with `run.score_answer`: correct variants pass, and wrong status, thrower or lookup fails.

### pc04 (I): second page of the Davis search
- **Answer** (`answers/pc04.json`): `view` = owners/findOwners, `errorcode` = notFound, `field` = lastName.
- **Chain**: `processFindForm` calls `PageRequest.of(1, 5)`. Page 2 of the 2 Davis owners is empty, and
  `Page#isEmpty()` checks the page content, not the total, so the controller calls
  `rejectValue("lastName", "notFound", ...)`. Verified over HTTP: the find form renders with "has not been found".
- **Trap**: "2 matches ⇒ list view" (`owners/ownersList`). The `ownersList`, `redirect`, `404`, `500` and
  `ownerDetails` contradictions catch it.
- **Objective**: a fixed control-flow result on fixed seed data.

### pc05 (R): owner service layer
- **Required shape** (stated in the prompt, because the API-shape test needs names): public
  `owner.OwnerService` with `@Service`, a public constructor `(OwnerRepository)`, and the methods
  `Owner findOwner(int)` (IAE for unknown ids), `Page<Owner> findOwnersByLastName(String, int)` (1-based, 5 per page)
  and `Owner save(Owner)`. The three controllers must have no field or constructor parameter of type
  `OwnerRepository`, and each must have an `OwnerService` field.
- **Reference** (7 files): the new `OwnerService`; `OwnerController`, `PetController` and `VisitController`; plus
  `@Import(OwnerService.class)` on the three `@WebMvcTest` classes. They keep mocking `OwnerRepository`, so the real
  service runs over the mock.
- **Hidden test** `BenchPc05Test`: reflection checks the shape and the dependencies. The service is checked on H2
  (find, unknown → IAE, page sizes, totals and page numbers taken from a DB count, save assigns an id). Pages are
  checked end to end: search redirect, list, notFound, details, edit forms, IAE root cause for unknown owners and
  foreign pets. Writes are checked end to end: create and edit an owner, add a pet, duplicate name, edit a pet, add a
  visit, missing description. On the baseline it does not compile (no `OwnerService`), so the task fails.
- **Pitfalls**: `@WebMvcTest` does not load `@Service` beans, so the three existing controller test classes fail
  with a missing bean until they import or mock the service. Deleting those tests instead is caught only by the
  report-only diff-scope review. The hidden test covers their behavior anyway. The three controllers each threw the
  IAE with slightly different messages; only the type is asserted.
- **Objective**: reflection plus the full existing and hidden behavior on fixed data. Counts are derived from the DB,
  so test order inside the class does not matter.

### pc06 (T): tests for an owner's pets
- `allowedPaths` = `src/test/java/*`. `testCommand` is the full suite (same command as the hidden check).
- **Mutants** (`mutants/pc06/`, all in `Owner.java`):
  1. `getPet(name, ignoreNew)` compares with `equals` instead of `equalsIgnoreCase`.
  2. `getPet(name, ignoreNew)` ignores `ignoreNew`, so unsaved pets are always returned.
  3. `getPet(name)` delegates with `ignoreNew = true`, so the default lookup skips unsaved pets.
  4. `addPet` adds saved pets too (the `isNew()` guard is removed).
  5. `addVisit` without `Assert.notNull(pet)`: an unknown pet id gives a NullPointerException instead of an IAE.
- **Verified**: every mutant survives the existing suite (baseline `mutantsKilled` = 0, also run one by one), and the
  reference `OwnerPetsTests` (6 tests) kills all 5. None of the mutants is equivalent: each changes a return value or
  exception that a plain unit test observes.
- **Pitfalls**: testing only the happy path of `getPet(name)` misses 2 and 3. Tests must create unsaved pets (id
  null) next to saved ones. `assertThrows(Exception.class, …)` does not kill 5.
- **Objective**: kill counts are deterministic (pure unit-level behavior, no time or order dependence). Scope: no
  production change is allowed.

### pc07 (F): visit date before birth date
- **Behavior** (`BenchPc07Test`, counts toward success): Samantha (born 2012-09-04) with date 2012-09-03 or
  2001-01-01 gives the form again, a field error on `date`, the exact English message, and nothing saved. Max on his
  birth date, or later, is booked (redirect, +2 rows). Leo with a missing description still errors on `description`.
  With a missing description and a date before birth, both errors are reported.
- **Convention K** (visible only in the code): user-visible texts are message keys present in `messages.properties`
  and in every translated `messages_xx.properties` (`messages_en` stays empty, as its comment says). They are
  resolved through the MessageSource via error codes (`rejectValue(field, code)`, as `PetController` does with
  `typeMismatch.birthDate` and `duplicate`) and are never literals in templates. `I18nPropertiesSyncTest` enforces
  the key sync, so it is already part of success. The probe adds the MessageSource resolution.
- **Probe** (`BenchPc07ProbeTest`, `probe`/`probeTests`, report-only `probeOk`): a key in `messages.properties` has
  the English text; every `messages_xx.properties` except `en` defines it non-blank; no template contains the literal
  text; after `?lang=de`, the page shows the `messages_de` value of that key. Checked: a variant using
  `rejectValue("date", "beforeBirth", "<English text>")` with no message keys passes the behavior test and fails the
  probe (2 of 3).
- **Reference**: `VisitController.processNewVisitForm` rejects `date` with code `visitDate.beforeBirthDate`; the key
  is added to all 8 message files (7 translated).
- **Pitfalls**: `loadPetWithVisit` adds a phantom `Visit` to the model's pet. The check must use the submitted visit's
  date against `pet.getBirthDate()`, and must not iterate the pet's visits.
- **Objective**: exact strings, field-error presence and DB row counts. The probe reads the repository's own files.

## Pilot suite v2-petclinic-pilot (disposable, never scored)

| id | type | summary | checkSeconds |
|---|---|---|---|
| pcp1 | B | Duplicate pet names that differ only in letter case are accepted (injected `equals` in `Owner#getPet(String, boolean)`) | 13.6 |
| pcp2 | I | Owner search page size (5) and the vet cache name (`vets`) | n/a |

pcp1's hidden `BenchPcp1Test` posts `Leo`/`LEO`/`leo` for owner 1 and expects the `duplicate` error code and an
unchanged pet count; a new name is still accepted. The injection is the same code change as pc06 mutant 1. That is
harmless, because pilot cells are never scored and their memory is cleared.

## Verification (2026-10-11)

```
python3 bench/verify_task.py --suite v2-petclinic-pilot
pcp1: OK {'baselineFails': True, 'referencePasses': True, 'checkSeconds': 13.6}
pcp2: OK {'referencePasses': True, 'emptyFails': True}
python3 bench/verify_task.py --suite v2-petclinic
pc01: OK {'baselineFails': True, 'referencePasses': True, 'checkSeconds': 13.2}
pc02: OK {'baselineFails': True, 'referencePasses': True, 'checkSeconds': 12.1}
pc03: OK {'referencePasses': True, 'emptyFails': True}
pc04: OK {'referencePasses': True, 'emptyFails': True}
pc05: OK {'baselineFails': True, 'referencePasses': True, 'checkSeconds': 11.1}
pc06: OK {'baselineFails': True, 'referencePasses': True, 'checkSeconds': 59.8}
pc07: OK {'baselineFails': True, 'referencePasses': True, 'checkSeconds': 17.8}
```

On every B/R/F baseline: `qualityOk` is true and only the hidden tests fail. pc07's baseline also has
`probeOk = false`, and its reference has `probeOk = true`.

Runner quirk: answer-key names in `bench/answers/*.json` are lowercase (`pagesize`, `thrownin`, `errorcode`).
`run.answer_block()` lowercases the agent's keys, but `score_answer()` looks up the key file's names unchanged, so a
camelCase name there could never match. The prompts still show camelCase, which is fine because the agent's keys are
lowercased.

## Not verified

- Agent behavior: no agent was run.
- Determinism over many runs: each check ran once in `verify_task.py` plus once or twice during authoring.
- MySQL/Postgres behavior: excluded, as in v1.
