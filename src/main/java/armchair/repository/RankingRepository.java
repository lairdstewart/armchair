package armchair.repository;

import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface RankingRepository extends JpaRepository<Ranking, Long> {
    List<Ranking> findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(Long userId, Bookshelf bookshelf, BookCategory category);
    List<Ranking> findByUserIdAndBookshelfOrderByPositionAsc(Long userId, Bookshelf bookshelf);
    boolean existsByUserIdAndBookWorkOlid(Long userId, String workOlid);
    Ranking findByUserIdAndBookWorkOlid(Long userId, String workOlid);
    boolean existsByUserIdAndBookId(Long userId, Long bookId);
    boolean existsByBookId(Long bookId);
    List<Ranking> findByUserId(Long userId);
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Ranking r SET r.position = r.position - 1 WHERE r.userId = :userId AND r.bookshelf = :bookshelf AND r.category = :category AND r.position > :removedPosition")
    void decrementPositionsAbove(@Param("userId") Long userId, @Param("bookshelf") Bookshelf bookshelf, @Param("category") BookCategory category, @Param("removedPosition") int removedPosition);

    @Modifying
    @Transactional
    void deleteByUserId(Long userId);
}
