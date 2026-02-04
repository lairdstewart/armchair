package armchair.repository;

import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface RankingRepository extends JpaRepository<Ranking, Long> {
    List<Ranking> findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(Long userId, Bookshelf bookshelf, BookCategory category);
    List<Ranking> findByUserIdAndBookshelfOrderByPositionAsc(Long userId, Bookshelf bookshelf);
    boolean existsByUserIdAndBookWorkOlid(Long userId, String workOlid);
    boolean existsByUserIdAndBookId(Long userId, Long bookId);
    List<Ranking> findByUserId(Long userId);
    @Modifying
    @Transactional
    void deleteByUserId(Long userId);
}
