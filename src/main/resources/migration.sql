-- Migration: Split books table into books (metadata) + rankings (user-specific data)
-- Run this manually against the database before deploying the new code.

-- 1. Create new books table (deduplicated metadata)
CREATE TABLE books_new (
    id BIGSERIAL PRIMARY KEY,
    isbn_13 VARCHAR(255),
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

-- ============================================================================
-- Migration: Book deduplication — create book_isbns join table
-- Run this manually against the database before deploying the new code.
-- ============================================================================

-- 10. Create book_isbns table
CREATE TABLE book_isbns (
    id BIGSERIAL PRIMARY KEY,
    book_id BIGINT NOT NULL REFERENCES books(id),
    isbn_13 VARCHAR(255) NOT NULL,
    UNIQUE (book_id, isbn_13)
);

-- 11. Populate book_isbns from existing books.isbn_13
INSERT INTO book_isbns (book_id, isbn_13)
SELECT id, isbn_13 FROM books WHERE isbn_13 IS NOT NULL AND isbn_13 != '';

-- 12. Drop unique constraint and not-null constraint on books.isbn_13
ALTER TABLE books ALTER COLUMN isbn_13 DROP NOT NULL;
ALTER TABLE books DROP CONSTRAINT IF EXISTS books_isbn_13_key;
ALTER TABLE books DROP CONSTRAINT IF EXISTS uk_isbn_13;
-- Hibernate-generated constraint names vary; drop by scanning pg_constraint if needed:
DO $$
DECLARE
    cname TEXT;
BEGIN
    SELECT conname INTO cname
    FROM pg_constraint
    WHERE conrelid = 'books'::regclass
      AND contype = 'u'
      AND array_length(conkey, 1) = 1
      AND conkey[1] = (SELECT attnum FROM pg_attribute WHERE attrelid = 'books'::regclass AND attname = 'isbn_13');
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE books DROP CONSTRAINT %I', cname);
    END IF;
END $$;

-- ============================================================================
-- Migration: Drop isbn_13 column from books table
-- ISBNs are now stored exclusively in the book_isbns table.
-- Run this manually against the database before deploying the new code.
-- ============================================================================

-- 13. Drop isbn_13 column from books
ALTER TABLE books DROP COLUMN IF EXISTS isbn_13;

-- 14. Drop user_uploaded column from books (replaced by google_books_id IS NULL check)
ALTER TABLE books DROP COLUMN IF EXISTS user_uploaded;

-- ============================================================================
-- Migration: Rename type → bookshelf, move WANT_TO_READ to Bookshelf enum
-- Run this manually against the database BEFORE deploying the new code.
-- ============================================================================

-- 15. Drop Hibernate-generated check constraints on type/category columns
--     These restrict values to the old enum sets and block new values like WANT_TO_READ in bookshelf.
ALTER TABLE rankings DROP CONSTRAINT IF EXISTS rankings_type_check;
ALTER TABLE rankings DROP CONSTRAINT IF EXISTS rankings_category_check;
ALTER TABLE ranking_state DROP CONSTRAINT IF EXISTS ranking_state_type_check;
ALTER TABLE ranking_state DROP CONSTRAINT IF EXISTS ranking_state_category_check;

-- 16. Rename type → bookshelf in rankings
ALTER TABLE rankings RENAME COLUMN type TO bookshelf;

-- 17. Rename type → bookshelf in ranking_state
ALTER TABLE ranking_state RENAME COLUMN type TO bookshelf;

-- 18. Migrate want-to-read data in rankings
UPDATE rankings SET bookshelf = 'WANT_TO_READ', category = 'UNRANKED' WHERE category = 'WANT_TO_READ';

-- 19. Migrate want-to-read data in ranking_state
UPDATE ranking_state SET bookshelf = 'WANT_TO_READ', category = 'UNRANKED' WHERE category = 'WANT_TO_READ';

-- ============================================================================
-- Migration: Replace Google Books API with Open Library API
-- Rename google_books_id → work_olid, add cover_edition_olid + first_publish_year,
-- drop book_isbns table, drop isbn13_being_ranked from ranking_state.
-- Run this manually against the database BEFORE deploying the new code.
-- ============================================================================

-- 20. Rename google_books_id → work_olid in books
ALTER TABLE books RENAME COLUMN google_books_id TO work_olid;

-- 21. Add new columns to books
ALTER TABLE books ADD COLUMN IF NOT EXISTS cover_edition_olid VARCHAR(255);
ALTER TABLE books ADD COLUMN IF NOT EXISTS first_publish_year INTEGER;

-- 22. Rename google_books_id_being_ranked → work_olid_being_ranked in ranking_state
ALTER TABLE ranking_state RENAME COLUMN google_books_id_being_ranked TO work_olid_being_ranked;

-- 23. Drop isbn13_being_ranked from ranking_state
ALTER TABLE ranking_state DROP COLUMN IF EXISTS isbn13_being_ranked;

-- 24. Drop book_isbns table
DROP TABLE IF EXISTS book_isbns;

-- ============================================================================
-- Migration: Edition selection feature
-- Rename cover_edition_olid → edition_olid, add isbn_13 column to books,
-- add edition tracking columns to ranking_state.
-- Run this manually against the database BEFORE deploying the new code.
-- ============================================================================

-- 25. Rename cover_edition_olid → edition_olid in books
ALTER TABLE books RENAME COLUMN cover_edition_olid TO edition_olid;

-- 26. Add isbn_13 column to books
ALTER TABLE books ADD COLUMN IF NOT EXISTS isbn_13 VARCHAR(255);

-- 27. Add edition tracking columns to ranking_state
ALTER TABLE ranking_state ADD COLUMN IF NOT EXISTS edition_olid_being_ranked VARCHAR(255);
ALTER TABLE ranking_state ADD COLUMN IF NOT EXISTS isbn13_being_ranked VARCHAR(255);
ALTER TABLE ranking_state ADD COLUMN IF NOT EXISTS edition_selected BOOLEAN DEFAULT FALSE;

-- ============================================================================
-- Migration: Re-rank restoration fix
-- Add columns to track original position so abandoned re-ranks can be restored.
-- Run this manually against the database BEFORE deploying the new code.
-- ============================================================================

-- 28. Add re-rank restoration columns to ranking_state
ALTER TABLE ranking_state ADD COLUMN IF NOT EXISTS original_category VARCHAR(255);
ALTER TABLE ranking_state ADD COLUMN IF NOT EXISTS original_position INTEGER;
