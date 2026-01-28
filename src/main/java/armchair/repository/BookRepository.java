package armchair.repository;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.BookType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookRepository extends JpaRepository<Book, Long> {
    List<Book> findByUserIdAndTypeAndCategoryOrderByPositionAsc(Long userId, BookType type, BookCategory category);
    List<Book> findByUserIdAndTypeOrderByPositionAsc(Long userId, BookType type);

    @Query(value = "SELECT DISTINCT ON (google_books_id) * FROM books WHERE google_books_id IS NOT NULL ORDER BY google_books_id, RANDOM() LIMIT 10", nativeQuery = true)
    List<Book> findRandom10UniqueBooks();
}
