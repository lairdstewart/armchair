# UI Test Plan

Manual test procedures for verifying Armchair's UI before a release. Each
section covers a user flow with numbered steps and expected results.

## Prerequisites

- Server running with `make run-no-auth` (dev profile, mock auth, ephemeral
  port)
- Note the assigned port from startup logs ("Tomcat started on port")
- Base URL: `http://localhost:<port>`
- Fresh session (no prior state) — clear cookies or use incognito if needed

---

## 1. Welcome Page

1. Navigate to `/`
2. Confirm the page displays "About", "Privacy", and "Support" sections
3. Confirm the navbar shows: Armchair | Search | Library | Recs | Profile
4. Click each navbar link and confirm it navigates to the correct page

---

## 2. Library — Empty State

1. Navigate to `/my-books`
2. Confirm the Fiction tab is selected by default
3. Confirm empty state message: "Add books via the search tab or import from
   Goodreads."
4. Click the Non-Fiction tab — confirm same empty message
5. Click Want to Read tab — confirm it's empty
6. Confirm there is no Uncategorized tab (no unranked books yet)

---

## 3. Search and Add a Book

1. Navigate to `/search`
2. Confirm the "books" radio tab is selected by default
3. Type "The Great Gatsby" in the search box and submit
4. Confirm results appear with titles, authors, and cover images (or "?"
   placeholders)
5. Click "browse N editions" on the first result
6. Confirm the editions page loads with a list of editions showing title,
   publisher, date, and covers
7. Click "rank" on one edition
8. Confirm the Categorize mode appears with Fiction/Non-Fiction radio buttons
   and category (liked/ok/disliked) radio buttons
9. Select "Fiction" and "liked it"
10. Optionally type a review in the textarea
11. Click "Continue"
12. Confirm the library page shows the book at rank #1 under Fiction
13. If a review was entered, confirm the review toggle expands to show it

---

## 4. Ranking via Pairwise Comparison

1. Search for and add a second fiction book (e.g., "1984")
2. After categorizing, confirm the Rank mode appears: "Which was better?"
3. Confirm two books are displayed with covers and titles
4. Click the arrow for one of them
5. If more comparisons are needed, repeat choosing
6. Once ranking completes, confirm both books appear in the Fiction list with
   correct rank numbers (#1, #2)
7. Confirm the book chosen as "better" in comparisons has a higher rank

---

## 5. Add a Want-to-Read Book

1. Search for a book (e.g., "Dune")
2. On the editions page, click "want to read"
3. Navigate to `/my-books` and click the "Want to Read" tab
4. Confirm the book appears in the list
5. Click "rank" on the Want to Read book
6. Confirm the Categorize mode appears — categorize and rank it
7. Confirm it moves from Want to Read to the appropriate Fiction/Non-Fiction
   list

---

## 6. Re-rank a Book

1. In the library, find a ranked book
2. Click "re-rank" on it
3. Confirm the Rank mode starts with pairwise comparisons
4. Complete the comparisons
5. Confirm the book's rank may have changed in the list

---

## 7. Review a Book

1. In the library, click "review" on a ranked book
2. Confirm the Review mode appears showing the book's title, category, and
   fiction/non-fiction
3. Type or edit a review in the textarea
4. Click "Done"
5. Confirm the library shows the review toggle for that book
6. Click the toggle and confirm the review text is displayed

---

## 8. Remove a Book

1. In the library, click "remove" on a ranked book
2. Confirm a browser confirmation dialog appears
3. Accept the confirmation
4. Confirm the book is no longer in the list
5. Confirm remaining books are re-numbered correctly

---

## 9. Remove a Want-to-Read Book

1. Add a book to Want to Read (via search)
2. On the Want to Read tab, click "remove"
3. Accept the confirmation
4. Confirm the book is gone from the list

---

## 10. Import from Goodreads

1. Navigate to `/my-profile`
2. Click "Import from Goodreads"
3. Confirm the import page loads with instructions
4. Confirm the "Import Books" button is disabled before selecting a file
5. Select the test CSV file (`src/test/resources/goodreads_library_export.csv`)
6. Confirm the button becomes enabled
7. Click "Import Books"
8. Confirm a success message appears (e.g., "N books imported successfully")
9. Navigate to `/my-books`
10. Confirm imported books appear in the Uncategorized tab (if any were
    unmatched) or Want to Read tab

---

## 11. Categorize All (Uncategorized Books)

1. Ensure there are uncategorized books (from Goodreads import or otherwise)
2. Click the Uncategorized tab
3. Confirm "categorize all" button is visible
4. Click "categorize all"
5. Confirm the flow walks through each book: resolve → select edition →
   categorize → rank
6. After completing all, confirm the Uncategorized tab disappears or is empty

---

## 12. Search — Profiles Tab

1. Navigate to `/search`
2. Select the "profiles" radio tab
3. Search for a username
4. Confirm results show usernames with book counts
5. Confirm follow/unfollow buttons appear as appropriate

---

## 13. Search — Curated Lists Tab

1. Select the "curated" radio tab
2. Search for a term
3. Confirm results show usernames linked to their public profiles

---

## 14. View Another User's Profile

1. From profile search results, click a username
2. Confirm their public book list loads at `/user/<username>`
3. Confirm Fiction and/or Non-Fiction tabs appear with ranked books
4. Confirm "want to read" buttons appear on books not in your library
5. Click "want to read" on a book and confirm it's added to your Want to Read

---

## 15. Recommendations

1. Navigate to `/recs`
2. If fewer than 3 fiction books ranked, confirm the threshold message appears
3. After ranking 3+ fiction books, confirm recommendations appear (may require
   other users in the system for collaborative filtering)
4. Click "want to read" on a recommendation and confirm it's added

---

## 16. Profile Settings

1. Navigate to `/my-profile`
2. Confirm username, "user since" date, user number, and book counts display
3. Click "change" next to username
4. Change the username and submit
5. Confirm redirect back to profile with new username displayed
6. Toggle "Publish my lists" checkbox
7. Confirm the toggle persists on page reload

---

## 17. Export CSV

1. Navigate to `/my-profile` (must have books)
2. Confirm "Export Book Lists to .csv" button is visible
3. Click it
4. Confirm a CSV file downloads

---

## 18. Duplicate Book Detection

1. Search for and add a book that's already in your library
2. Confirm the Duplicate Resolve mode appears: "This book is already in your
   library"
3. Click "Skip" and confirm you return to the library unchanged
4. Repeat and click "Re-rank" — confirm the ranking flow starts

---

## 19. Navbar Navigation

1. From any page, click each navbar link in sequence:
   - "Armchair" → `/` (welcome page)
   - "Search" → `/search`
   - "Library" → `/my-books`
   - "Recs" → `/recs`
   - "Profile" → `/my-profile`
2. Confirm each page loads correctly with no errors

---

## 20. Edge Cases

1. **Empty search**: Submit the search form with no query — confirm graceful
   handling (empty results or validation message)
2. **Long review text**: Enter a review near the 5000-character limit — confirm
   it saves and displays correctly
3. **Book with no cover**: Confirm the "?" placeholder renders correctly
4. **Pagination**: Search for a common term (e.g., "the") — confirm pagination
   links appear and work
5. **Back/cancel buttons**: During any modal flow (categorize, rank, review),
   click "back" or "cancel" — confirm return to previous state without data
   corruption
