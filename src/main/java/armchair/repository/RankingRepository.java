package armchair.repository;

import armchair.entity.BookCategory;
import armchair.entity.BookType;
import armchair.entity.Ranking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface RankingRepository extends JpaRepository<Ranking, Long> {
    List<Ranking> findByUserIdAndTypeAndCategoryOrderByPositionAsc(Long userId, BookType type, BookCategory category);
    List<Ranking> findByUserIdAndTypeOrderByPositionAsc(Long userId, BookType type);
    List<Ranking> findByUserIdAndCategoryOrderByPositionAsc(Long userId, BookCategory category);
    boolean existsByUserIdAndBookGoogleBooksId(Long userId, String googleBooksId);
    boolean existsByUserIdAndBookId(Long userId, Long bookId);
    @Modifying
    @Transactional
    void deleteByUserId(Long userId);
}
