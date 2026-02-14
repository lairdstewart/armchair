# Database

- **PostgreSQL** is the primary database
- Database credentials stored in `application-secret.properties` (not committed to git)
- Schema auto-created via `spring.jpa.hibernate.ddl-auto=update`
- Manual migration steps in `src/main/resources/migration.sql` (run manually; `ddl-auto=update` only adds columns/tables, never drops them)
- `autosave=conservative` set on HikariCP data source to handle PostgreSQL "cached plan must not change result type" errors caused by Hibernate 6's DDL at startup invalidating prepared statement plans
- PostgreSQL database. Schema auto-created via Hibernate `ddl-auto=update`.

## Tables

### users

User accounts (both OAuth and guest).

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGINT | PK, auto-generated | |
| username | VARCHAR | UNIQUE | Display name |
| oauth_subject | VARCHAR | | Google's unique user ID (OpenID "sub" claim) |
| signup_number | BIGINT | | Which number signup (1, 2, 3...) |
| signup_date | TIMESTAMP | | When user signed up |
| is_guest | BOOLEAN | | True for temporary guest users |
| is_curated | BOOLEAN | default false | True for curated/imported lists (e.g., NYT Best Books) |
| publish_lists | BOOLEAN | default false | True if user's lists are visible in Explore |

### books

Deduplicated book metadata shared across all users.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGINT | PK, auto-generated | |
| work_olid | VARCHAR | | Open Library work ID (e.g., "OL123W"). Primary dedup key. |
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

### ranking_state

Tracks in-progress ranking/review operations. At most one row per user.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| user_id | BIGINT | PK | FK -> users |
| work_olid_being_ranked | VARCHAR | | |
| edition_olid_being_ranked | VARCHAR | | |
| isbn13_being_ranked | VARCHAR | | |
| edition_selected | BOOLEAN | | Whether user has selected an edition |
| title_being_ranked | VARCHAR(1000) | | |
| author_being_ranked | VARCHAR(1000) | | |
| review_being_ranked | VARCHAR(5000) | | |
| bookshelf | ENUM | | Target bookshelf |
| category | ENUM | | Target category |
| compare_to_index | INTEGER | | Binary search: current comparison index |
| low_index | INTEGER | | Binary search: low bound |
| high_index | INTEGER | | Binary search: high bound |
| re_rank | BOOLEAN | | True if re-ranking existing book |
| remove | BOOLEAN | | True if removing book |
| review | BOOLEAN | | True if editing review |
| rank_all | BOOLEAN | | True if in "rank all unranked" mode |
| book_id_being_reviewed | BIGINT | | FK -> books (for review mode) |
| original_category | ENUM | | For re-rank restoration |
| original_position | INTEGER | | For re-rank restoration |

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
users 1--1 ranking_state (user_id)
users 1--* follows (follower_id)
users 1--* follows (followed_id)
```
