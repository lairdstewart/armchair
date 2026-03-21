# Testing

## MockMvc integration tests

Integration tests use `@SpringBootTest` + `MockMvc` with an H2 in-memory database (`MODE=PostgreSQL` for compatibility). `OpenLibraryService` is the only `@MockBean` — everything else (controller, repositories, security) runs for real.

**Write tests for bug fixes:** When fixing a bug, write a test that would have caught it (if reasonable). Include the test in the same commit as the fix.

**Key conventions:**
- All test classes extend `BaseIntegrationTest` (`src/test/java/armchair/BaseIntegrationTest.java`), which provides `MockMvc`, autowired repositories, and helper methods
- `@Transactional` on the base class auto-rolls back each test — clean DB, no interference between tests
- `@ActiveProfiles("test")` loads `src/test/resources/application-test.properties` (H2 config, dummy OAuth registration)
- POST requests require `.with(csrf())` for Spring Security CSRF protection
- OAuth requests: use `.with(oauthUser("oauth-subject"))` and pre-create a `User` with `createOAuthUser(username, oauthSubject)`
- Helper methods: `createVerifiedBook()`, `createUnverifiedBook()`, `addRanking()`, `addRankingWithReview()`

**Test classes** (`src/test/java/armchair/`):
- `GoodreadsImportTest` — CSV import: parsing, shelf routing, title stripping, reviews, dedup, edge cases
- `BookDeduplicationTest` — `findOrCreateBook()` behavior: workOlid reuse, cover enrichment, title+author fallback
- `ResolveFlowTest` — RESOLVE state machine: auto-resolve, expand to 10, manual fallback, duplicate detection, abandon
- `RankingFlowTest` — ranking binary search, re-rank, remove, review, want-to-read flow, position shifting
- `BasicCrudTest` — add/remove books, CSV export, search, reading list, page loads

## Selenium browser tests

Selenium WebDriver tests drive a real headless Chrome browser against the running app. They catch bugs that MockMvc can't: multi-step flows, session persistence across redirects, JavaScript interactions, and form submissions through the real browser engine.

**When to use Selenium vs MockMvc:**
- **Selenium**: Navigation/state bugs, multi-step flows, back-button behavior, state leaking between flows, JavaScript-dependent UI
- **MockMvc**: Controller logic, HTTP-level behavior, request/response validation, single-endpoint testing

**Infrastructure:**
- All Selenium tests extend `BaseSeleniumTest` (`src/test/java/armchair/BaseSeleniumTest.java`)
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` runs the full app on a random port
- `@ActiveProfiles({"test", "dev"})` — `test` loads H2, `dev` activates `DevSecurityConfig` with mock OAuth
- `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)` gives each test a fresh app context and DB
- `@MockBean OpenLibraryService` — still works with `RANDOM_PORT`
- Headless Chrome managed by WebDriverManager (auto-downloads correct chromedriver)

**Key helpers in `BaseSeleniumTest`:**
- `login(username)` / `createUserAndLogin(username)` — uses the dev mock OAuth user
- `navigateTo(path)`, `assertOnPath(path)` — navigation and URL assertions
- `assertTextPresent(text)`, `assertTextNotPresent(text)` — body text assertions with explicit waits
- `clickBookAction(bookTitle, actionText)` — click action buttons on specific books
- `clickButton(text)`, `selectRadio(name, value)` — form interaction helpers
- `clickLibrary()`, `clickNavLink(text)` — navbar navigation
- `chooseInComparison(choice)`, `chooseNewUntilDone()` — pairwise ranking helpers
- `createVerifiedBook()`, `addRanking()` — direct DB setup (same as MockMvc tests)

**Test classes** (`src/test/java/armchair/selenium/`):
- `NavigationStateTest` — state cleared on navigation, state survives within workflow, abandoned rerank restoration, multi-flow sequencing, cancel/back behavior
- `RankingFlowTest` — end-to-end ranking from unranked, want-to-read → mark as read, rerank position changes, review save, rank-all batch processing, categorize with review
