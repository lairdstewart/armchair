package armchair.repository;

import armchair.entity.BookIsbn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookIsbnRepository extends JpaRepository<BookIsbn, Long> {
    List<BookIsbn> findByBookId(Long bookId);
    List<BookIsbn> findByIsbn13(String isbn13);
    boolean existsByBookIdAndIsbn13(Long bookId, String isbn13);
}
