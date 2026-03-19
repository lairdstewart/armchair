# Database

- **PostgreSQL** is the primary database
- Database credentials stored in `application-secret.properties` (not committed to git)
- Schema auto-created via `spring.jpa.hibernate.ddl-auto=update`
- Manual migration steps in `src/main/resources/migration.sql` (run manually; `ddl-auto=update` only adds columns/tables, never drops them)
- `autosave=conservative` set on HikariCP data source to handle PostgreSQL "cached plan must not change result type" errors caused by Hibernate 6's DDL at startup invalidating prepared statement plans
- PostgreSQL database. Schema auto-created via Hibernate `ddl-auto=update`.

## Tables

### users

User accounts (OAuth).

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGINT | PK, auto-generated | |
| username | VARCHAR | UNIQUE | Display name |
| oauth_subject | VARCHAR | | Google's unique user ID (OpenID "sub" claim) |
| oauth_provider | VARCHAR | | OAuth provider name (e.g., "google", "github") |
| signup_number | BIGINT | | Which number signup (1, 2, 3...) |
| signup_date | TIMESTAMP | | When user signed up |
| publish_lists | BOOLEAN | default false | True if user's lists are visible in Explore |

### curated_lists

Curated/imported book lists (e.g., NYT Best Books). Separate from users — no OAuth or signup data.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGINT | PK, auto-generated | |
| username | VARCHAR | UNIQUE | Display name for the curated list |

### books

Deduplicated book metadata shared across all users.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGINT | PK, auto-generated | |
| work_olid | VARCHAR | UNIQUE | Open Library work ID (e.g., "OL123W"). Primary dedup key. |
| edition_olid | VARCHAR | | Open Library edition ID (e.g., "OL456M") |
| isbn13 | VARCHAR | | ISBN-13 if known |
| cover_id | INTEGER | | Open Library cover image ID (from `cover_i` in Search API) |
| title | VARCHAR(1000) | NOT NULL | Book title |
| author | VARCHAR(1000) | NOT NULL | Author name |
| first_publish_year | INTEGER | | Year first published |

**Notes:**
- Books with `work_olid = NULL` are "unverified" (e.g., from Goodreads import)
- Unverified books are excluded from global search results
- `cover_id` is the reliable way to know if a cover exists; `edition_olid` alone doesn't guarantee a cover

### rankings

User-specific book rankings. One row per user-book relationship.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGINT | PK, auto-generated | |
| user_id | BIGINT | FK -> users | |
| book_id | BIGINT | FK -> books | |
| bookshelf | ENUM | | FICTION, NONFICTION, WANT_TO_READ, UNRANKED |
| category | ENUM | | LIKED, OK, DISLIKED, UNRANKED |
| position | INTEGER | | Rank within bookshelf+category (0 = best) |
| review | VARCHAR(5000) | | User's review text |

### curated_rankings

Book rankings for curated lists. One row per curated-list-book relationship.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGINT | PK, auto-generated | |
| curated_list_id | BIGINT | FK -> curated_lists | |
| book_id | BIGINT | FK -> books | UNIQUE with curated_list_id |
| bookshelf | ENUM | | FICTION, NONFICTION |
| category | ENUM | | LIKED, UNRANKED |
| position | INTEGER | | Rank within bookshelf+category (0 = best) |
| review | VARCHAR(5000) | | Review text |

### ranking_state (REMOVED)

**This table is no longer used.** Ranking state was moved from the database to
`HttpSession` to avoid unnecessary DB writes for ephemeral UI interaction state.
The `ranking_state` table can be manually dropped from production:

```sql
DROP TABLE IF EXISTS ranking_state;
```

### follows

User follow relationships.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGINT | PK, auto-generated | |
| follower_id | BIGINT | FK -> users | The user who is following |
| followed_id | BIGINT | FK -> users | The user being followed |
| followed_at | TIMESTAMP | | When the follow occurred |

## Enums

### Bookshelf
- `FICTION`
- `NONFICTION`
- `WANT_TO_READ`
- `UNRANKED`

### BookCategory
- `LIKED`
- `OK`
- `DISLIKED`
- `UNRANKED`

## Key Relationships

```
users 1--* rankings (user_id)
books 1--* rankings (book_id)
curated_lists 1--* curated_rankings (curated_list_id)
books 1--* curated_rankings (book_id)
users 1--* follows (follower_id)
users 1--* follows (followed_id)
```
