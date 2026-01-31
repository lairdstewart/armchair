-- Migration: Split books table into books (metadata) + rankings (user-specific data)
-- Run this manually against the database before deploying the new code.

-- 1. Create new books table (deduplicated metadata)
CREATE TABLE books_new (
    id BIGSERIAL PRIMARY KEY,
    isbn VARCHAR(255),
    google_books_id VARCHAR(255),
    title VARCHAR(255),
    author VARCHAR(255)
);

-- 2. Insert unique books with non-null googleBooksId
INSERT INTO books_new (google_books_id, title, author)
SELECT DISTINCT ON (google_books_id) google_books_id, title, author
FROM books WHERE google_books_id IS NOT NULL ORDER BY google_books_id;

-- 3. Insert books with null googleBooksId (one per unique title+author)
INSERT INTO books_new (title, author)
SELECT DISTINCT title, author FROM books WHERE google_books_id IS NULL;

-- 4. Create rankings table
CREATE TABLE rankings (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    book_id BIGINT,
    type VARCHAR(255),
    category VARCHAR(255),
    position INTEGER,
    review VARCHAR(5000)
);

-- 5. Populate rankings (non-null googleBooksId)
INSERT INTO rankings (user_id, book_id, type, category, position, review)
SELECT b.user_id, bn.id, b.type, b.category, b.position, b.review
FROM books b JOIN books_new bn ON b.google_books_id = bn.google_books_id
WHERE b.google_books_id IS NOT NULL;

-- 6. Populate rankings (null googleBooksId — match by title+author)
INSERT INTO rankings (user_id, book_id, type, category, position, review)
SELECT b.user_id, bn.id, b.type, b.category, b.position, b.review
FROM books b JOIN books_new bn ON b.title = bn.title AND b.author = bn.author AND bn.google_books_id IS NULL
WHERE b.google_books_id IS NULL;

-- 7. Clear ranking_state (temporary data, safe to clear)
TRUNCATE ranking_state;

-- 8. Drop old books table and rename new one
DROP TABLE books;
ALTER TABLE books_new RENAME TO books;

-- 9. Add FK constraint
ALTER TABLE rankings ADD CONSTRAINT fk_rankings_book FOREIGN KEY (book_id) REFERENCES books(id);
