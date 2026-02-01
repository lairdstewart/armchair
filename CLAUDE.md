# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Golden Rule

**Never add features or change behavior the user didn't ask for or approve.** Do exactly what is requested, nothing more. No extra features, no unrequested behavior changes. If something seems like it should also be changed, ask first. Behavior-preserving refactors are fine and encouraged — keep the code clean as you go.

## Todo Workflow

This is the default interaction pattern:
- **If a todo is a question, answer it first and wait** — do not write code until the user has responded
- Read tasks from `/claude-todo.md` and work through them top to bottom
- Continue working unless there's a question or the user stops you
- When finished with a task, add it to `/claude-done.md`
- **Never write to `/claude-todo.md`** - the user edits that file and we want to avoid conflicts
- Only write to `/claude-done.md` to record completed tasks
- **Each completed todo must have its own git commit**. Do not combine multiple todos into the same commit.
- **Only work on the "claude" branch** - do not edit any other branch
- **After committing a todo, re-read `/claude-todo.md`** - it may have changed while working

## Project Overview

Armchair is a Spring Boot web application for managing ranked book lists. Users can add books and rank them through pairwise comparisons using a binary search insertion algorithm. Think "Beli for books."

**Tech Stack:**
- Spring Boot 3.2.0 with Spring Web MVC
- Thymeleaf for server-side templating
- Java 17
- Maven for build management
- PostgreSQL database (Spring Data JPA with Hibernate)
- OAuth2 authentication via Google
- Spring Boot DevTools for hot reload during development

**Architectural Preference:**
- Prefer server-side processing in Java over client-side JavaScript
- Keep JavaScript minimal - only for browser APIs (like clipboard access, form auto-submit on radio change)
- Generate all data transformations, formatting, and business logic in the controller and pass to Thymeleaf templates

**Development Workflow:**
- Do not compile or run the code - the developer will handle building and testing

## Build and Run Commands

**Build the project:**
```bash
mvn clean install
```

**Run the application:**
```bash
mvn spring-boot:run
```

The application will be available at `http://localhost:8080`

**Build without running tests:**
```bash
mvn clean install -DskipTests
```

## Architecture

### Database

- **PostgreSQL** is the primary database
- Database credentials stored in `application-secret.properties` (not committed to git)
- Schema auto-created via `spring.jpa.hibernate.ddl-auto=update`
- Manual migration steps in `src/main/resources/migration.sql` (run manually; `ddl-auto=update` only adds columns/tables, never drops them)

**Schema:**
- `users` — id, username (unique), oauth_subject, signup_number, signup_date, is_guest, is_curated, publish_lists
- `books` — id, google_books_id, title (VARCHAR 1000, NOT NULL), author (VARCHAR 1000, NOT NULL). Deduplicated metadata shared across all users. No ISBN column — ISBNs live in `book_isbns`.
- `book_isbns` — id, book_id (FK → books), isbn_13 (NOT NULL). Unique on (book_id, isbn_13). Stores all known ISBNs per book for cross-edition deduplication.
- `rankings` — id, user_id, book_id (FK → books), type (enum), category (enum), position, review (VARCHAR 5000). One row per user-book relationship.
- `ranking_state` — user_id (PK), google_books_id_being_ranked, title_being_ranked, author_being_ranked, isbn13_being_ranked, review_being_ranked, type, category, compare_to_index, low_index, high_index, re_rank, remove, review, rank_all, book_id_being_reviewed. At most one row per user, tracks in-progress ranking/review operations.
- `follows` — id, follower_id, followed_id, followed_at

### Authentication

- **OAuth2 via Google** - only stores Google's unique "sub" claim, not email
- **Guest mode** - temporary users can use the app without authentication
- Guest data migrates to user account when guest signs up
- Guest accounts are cleaned up on app startup

### Navigation Bar

Order: **Armchair** | Search | Library | Recs | ... | Profile/Login

- "Library" links to `/my-books` (the main book list interface)
- "Recs" links to `/recs` (placeholder page, coming soon)

### Application Flow

The application operates in these modes (defined in `BookController`):

1. **LIST Mode** - Default state showing book lists with radio button selector for Fiction/Non-Fiction/Want to Read
   - All three radio buttons always visible and enabled
   - Each book has inline [review] [re-rank] [remove] action links
   - Books with reviews show [+] toggle that expands to show review text
   - Empty lists show "Add books via the search tab."
   - Want to Read list shows [rank] [remove] links (rank moves book to categorization)
2. **CATEGORIZE Mode** - After selecting a book from Search, user selects type (fiction/non-fiction) AND category (liked/ok/disliked), plus optional review text
3. **RANK Mode** - Pairwise comparisons to rank the book within its category using binary search
4. **REVIEW Mode** - User edits a book's review (shows disabled type/category, editable review text)

The flow is: Search → CATEGORIZE → RANK → LIST

### Key Pages

- `/` - Welcome/About page (includes About, Privacy, and Support sections)
- `/my-books` - Main book list interface (LIST/CATEGORIZE/RANK modes), called "Library" in navbar
- `/search` - Unified search for books, profiles, curated lists, following, and followers (default: books)
- `/recs` - Recommendations page (placeholder, "coming soon ...")
- `/my-profile` - User profile with stats and settings
- `/user/{username}` - View another user's book list (only if they've published)
- `/setup-username` - First-time OAuth user setup

### Key Components

**BookController** (`src/main/java/armchair/controller/BookController.java`)
- Main controller handling all endpoints
- Uses `RankingState` entity to track binary search state during ranking
- Key endpoints:
  - `/search` (GET) - Unified search page for books/profiles/curated/following/followers
  - `/select-book` (POST) - Select a book from search results, creates RankingState
  - `/add-to-reading-list` (POST) - Add book directly to Want to Read list
  - `/categorize` (POST) - Receives type, category, and optional review; starts ranking or adds directly
  - `/choose` (POST) - Processes pairwise comparisons during ranking
  - `/direct-review`, `/direct-rerank`, `/direct-remove` (POST) - Direct inline actions for books
  - `/mark-as-read` (POST) - Move book from Want to Read to categorization
  - `/remove-from-reading-list` (POST) - Remove book from Want to Read list
  - `/save-review` (POST) - Save book review
  - `/follow`, `/unfollow` (POST) - Follow/unfollow users
  - `/toggle-publish-lists` (POST) - Toggle profile visibility
  - `/recs` (GET) - Recommendations placeholder page

**Entities** (`src/main/java/armchair/entity/`)
- `User` - username, oauthSubject, signupNumber, signupDate, isGuest, isCurated, publishLists
- `Book` - googleBooksId, title, author (deduplicated metadata shared across users; ISBNs stored in `book_isbns`)
- `BookIsbn` - bookId, isbn13 (join table storing multiple ISBNs per book for cross-edition deduplication)
- `Ranking` - userId, book (FK), type, category, position, review (VARCHAR 5000) (user-specific ranking data)
- `RankingState` - tracks binary search state (userId, title/author/googleBooksId/isbn13/review being ranked, type, category, compareToIndex, lowIndex, highIndex, reRank, remove, review, rankAll flags, bookIdBeingReviewed)
- `Follow` - followerId, followedId, followedAt
- `BookType` - enum: FICTION, NONFICTION, UNRANKED
- `BookCategory` - enum: LIKED, OK, DISLIKED, WANT_TO_READ, UNRANKED

**Templates** (`src/main/resources/templates/`)
- `index.html` - Main app interface with conditional modes (LIST/CATEGORIZE/RANK/REVIEW)
- `search.html` - Unified search with radio buttons for books/profiles/curated
- `recs.html` - Recommendations placeholder page
- `profile.html` - User profile with publish toggle
- `welcome.html` - Home/about page
- `view-user.html` - Display another user's book lists
- `fragments.html` - Reusable navbar and book list display fragments

**Services**
- `GoogleBooksService` - Integration with Google Books API for book search

**Security** (`SecurityConfig.java`)
- Permits public access to: `/`, `/my-books`, `/search`, `/select-book`, `/categorize`, `/choose`, `/user/**`, `/recs`, etc.
- OAuth2 login with Google, redirects to `/my-profile` on success

### Privacy Features

- `publishLists` boolean on User entity (defaults to false)
- Only users with `publishLists=true` appear in profile search
- Access to `/user/{username}` blocked if user hasn't published (curated lists always visible)

### Search Features

- Unified search page with radio buttons: Books (default), Curated Lists, Profiles, Following, Followers
- **Books search:**
  - Shows 10 random books from database when search is empty (excludes user's existing books)
  - Shows "... 40 million more books" at the bottom
  - Each book shows [rank] and [want to read] action links
  - If already ranked, shows [#N] (non-clickable); if already in want-to-read, shows [want to read] (non-clickable)
- **Profiles search:** Shows users who have `publishLists=true` with [follow]/[unfollow] links
- **Following/Followers:** Shows user's following/followers with [follow]/[unfollow] links
- **Curated lists:** Shows all curated lists, searchable by username

### Book Deduplication

Books are deduplicated so that different editions (different ISBNs) of the same title+author resolve to a single `Book` row. The `book_isbns` join table stores all known ISBNs for each book.

**Resolution order in `findOrCreateBook()`:**
1. Look up ISBN in `book_isbns` table — exact match, fast
2. Case-insensitive `LOWER(TRIM(...))` match on title+author
3. If neither matches, create a new `Book` row
4. On match from step 2, record the new ISBN in `book_isbns`
5. Enrich existing book with `googleBooksId` if it was previously missing

This logic is used in `BookController.findOrCreateBook()` and duplicated as a static method in `CuratedListImporter`.

### Unverified Books

Books without a `googleBooksId` are considered unverified (e.g., from Goodreads CSV imports that didn't match an existing book). These are excluded from global search results (`searchByTitleOrAuthor` and `findRandom10Books` both filter on `google_books_id IS NOT NULL`) to prevent user-supplied content from appearing in other users' searches.

When an unverified book enters `categorizeBook` (via rank, rank-all, or mark-as-read), a Google Books API lookup is attempted (by ISBN if available, else by title+author). On success, the book's metadata is updated with canonical title, author, and `googleBooksId`, making it visible in global search. On failure (rate limited, not found), the book proceeds with existing data and remains hidden from global search but functional in the user's personal lists. The helper `getBookIsbn13(bookId)` looks up ISBNs from the `book_isbns` table since `Book` has no ISBN field.

### Ranking Algorithm

Books are organized by type (fiction/non-fiction) and category (liked/ok/disliked). There's also a separate "Want to Read" list for unread books.

**Adding a Book to Ranked List:**
1. User searches for book in Search tab, clicks [rank] link
2. `/select-book` creates RankingState with book info, redirects to `/my-books`
3. CATEGORIZE mode: user selects type (fiction/non-fiction) AND category (liked/ok/disliked)
4. `/categorize` receives both, gets the appropriate list for that type+category
5. If category list is empty, book is added directly at position 0
6. If category list has books, enters RANK mode with binary search
7. `/choose` processes pairwise comparisons:
   - "New book is better" → search lower indices (highIndex = compareToIndex - 1)
   - "Existing book is better" → search higher indices (lowIndex = compareToIndex + 1)
8. When lowIndex > highIndex, book is inserted at that position
9. Returns to LIST mode

**Adding a Book to Want to Read:**
1. User searches for book in Search tab, clicks [want to read] link
2. `/add-to-reading-list` adds book directly to Want to Read list (no categorization needed)
3. Later, user can click [rank] on a Want to Read book to move it through categorization

### Curated List Importer CLI

`CuratedListImporter` (`src/main/java/armchair/tool/CuratedListImporter.java`) is a standalone Spring Boot CLI tool for importing curated book lists from JSON files into the database.

**Usage:**
```bash
mvn exec:java -Dexec.mainClass="armchair.tool.CuratedListImporter" -Dexec.args="/absolute/path/to/file.json"
```

**JSON format:**
```json
{
  "username": "List Name",
  "books": [
    {"rank": "1", "title": "Title", "author": "Author", "category": "fiction", "review": ""},
    {"rank": "", "title": "Unranked Book", "author": "Author", "category": "non-fiction", "review": "A review"}
  ]
}
```

**Import behavior:**
- All fields (`rank`, `title`, `author`, `review`) are required per book; the tool fails fast on any missing or malformed field
- `author` must be non-empty (fail fast if blank)
- `category` is optional and defaults to `"fiction"` if absent; if present must be `"fiction"` or `"non-fiction"`
- `rank`: non-empty = LIKED category, empty string `""` = UNRANKED category
- Ranked books are sorted by rank number within each type; positions are assigned 0, 1, 2... per type+category (works for both global and per-category ranking schemes)
- Google Books API is searched with `"title, author"` to get the `googleBooksId` and canonical author
- Reviews are stored if non-empty
- If the username already exists, all existing books are deleted and reimported fresh
- The entire file is parsed and validated in memory before any database writes

**List files** are stored in `src/main/resources/lists/`
