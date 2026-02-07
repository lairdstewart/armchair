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
    Optional<Book> findByWorkOlid(String workOlid);

    @Query(value = "SELECT * FROM books WHERE work_olid IS NOT NULL ORDER BY RANDOM() LIMIT 5", nativeQuery = true)
    List<Book> findRandomBooks();

    @Query("SELECT b FROM Book b WHERE LOWER(TRIM(b.title)) = LOWER(TRIM(:title)) AND LOWER(TRIM(b.author)) = LOWER(TRIM(:author))")
    List<Book> findByTitleAndAuthorIgnoreCase(@Param("title") String title, @Param("author") String author);
}
