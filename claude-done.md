# Completed Tasks

- [x] Store ISBN-13 from Google Books API; Goodreads import uses CSV data directly (no API calls). Books with Exclusive Shelf "to-read"/"currently-reading" go to Want to Read; others go to Unranked.
- [x] ISBN-based hyperlinks: books without googleBooksId link to Google Books ISBN search. Added bookUrl() method to BookInfo and BookResult for cleaner template logic.
- [x] Ensure author, title, isbn_13 always set: added ISBN-10 to ISBN-13 conversion, Goodreads import falls back to ISBN column, nullable=false constraints on Book entity.
- [x] Cache Google Books API results in books table (checked by isbn13, saved after each search).
- [x] Fixed unranked books showing "[#0]" in search results — now shows "[unranked]" instead.
- [x] Added unique constraint on isbn_13 in books table.
- [x] Added ISBN-13 column to CSV export.
- [x] Fixed varchar(255) overflow bug on ranking_state and books — increased review column to 5000, title/author to 1000. The 429 API error was already handled correctly (logged and returns empty list).
- [x] Added "rank all" button to Unranked page — sequentially ranks all unranked books, shows remaining count, redirects to correct type when done.
- [x] After ranking a book, redirect to Fiction or Non-Fiction based on the book's type instead of always defaulting to Fiction.
- [x] Username validation: uniqueness check was already in place, added 50-character length limit with server-side and client-side enforcement.
- [x] Added change username button in profile — [change] link next to username navigates to a form with the same validation (unique, < 50 chars).
- [x] Strip subtitles from Goodreads import: if title contains a colon, remove it and everything after it.
