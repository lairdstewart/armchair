# Completed Tasks

- [x] Store ISBN-13 from Google Books API; Goodreads import uses CSV data directly (no API calls). Books with Exclusive Shelf "to-read"/"currently-reading" go to Want to Read; others go to Unranked.
- [x] ISBN-based hyperlinks: books without googleBooksId link to Google Books ISBN search. Added bookUrl() method to BookInfo and BookResult for cleaner template logic.
- [x] Ensure author, title, isbn_13 always set: added ISBN-10 to ISBN-13 conversion, Goodreads import falls back to ISBN column, nullable=false constraints on Book entity.
- [x] Cache Google Books API results in books table (checked by isbn13, saved after each search).
- [x] Fixed unranked books showing "[#0]" in search results — now shows "[unranked]" instead.
