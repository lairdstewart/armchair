# Testing

Integration tests use `@SpringBootTest` + `MockMvc` with an H2 in-memory database (`MODE=PostgreSQL` for compatibility). `OpenLibraryService` is the only `@MockBean` — everything else (controller, repositories, security) runs for real.

**Write tests for bug fixes:** When fixing a bug, write a test that would have caught it (if reasonable). Include the test in the same commit as the fix.

**Key conventions:**
- All test classes extend `BaseIntegrationTest` (`src/test/java/armchair/BaseIntegrationTest.java`), which provides `MockMvc`, autowired repositories, and helper methods
- `@Transactional` on the base class auto-rolls back each test — clean DB, no interference between tests
- `@ActiveProfiles("test")` loads `src/test/resources/application-test.properties` (H2 config, dummy OAuth registration)
- POST requests require `.with(csrf())` for Spring Security CSRF protection
- Guest requests: use `guestSession()` to get a `MockHttpSession`, pass it to requests
- OAuth requests: use `.with(oauthUser("oauth-subject"))` and pre-create a `User` with `createOAuthUser(username, oauthSubject)`
- Helper methods: `createVerifiedBook()`, `createUnverifiedBook()`, `addRanking()`, `addRankingWithReview()`

**Test classes** (`src/test/java/armchair/`):
- `GoodreadsImportTest` — CSV import: parsing, shelf routing, title stripping, reviews, dedup, edge cases
- `GuestMigrationTest` — guest-to-OAuth migration of rankings, ranking state, and reviews
- `BookDeduplicationTest` — `findOrCreateBook()` behavior: workOlid reuse, cover enrichment, title+author fallback
- `ResolveFlowTest` — RESOLVE state machine: auto-resolve, expand to 10, manual fallback, duplicate detection, abandon
- `RankingFlowTest` — ranking binary search, re-rank, remove, review, want-to-read flow, position shifting
- `BasicCrudTest` — add/remove books, CSV export, search, reading list, page loads
