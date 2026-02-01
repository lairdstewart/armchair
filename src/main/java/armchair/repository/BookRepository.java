package armchair.repository;

import armchair.entity.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookRepository extends JpaRepository<Book, Long> {
    Optional<Book> findByGoogleBooksId(String googleBooksId);
    Optional<Book> findByIsbn13(String isbn13);

    @Query(value = "SELECT * FROM books WHERE google_books_id IS NOT NULL AND user_uploaded = false ORDER BY RANDOM() LIMIT 10", nativeQuery = true)
    List<Book> findRandom10Books();

    @Query("SELECT b FROM Book b WHERE b.userUploaded = false AND (LOWER(b.title) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(b.author) LIKE LOWER(CONCAT('%', :term, '%')))")
    List<Book> searchByTitleOrAuthor(@Param("term") String term);

    @Query("SELECT b FROM Book b WHERE LOWER(TRIM(b.title)) = LOWER(TRIM(:title)) AND LOWER(TRIM(b.author)) = LOWER(TRIM(:author))")
    List<Book> findByNormalizedTitleAndAuthor(@Param("title") String title, @Param("author") String author);
}
