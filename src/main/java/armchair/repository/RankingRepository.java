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
    @Query("SELECT r FROM Ranking r JOIN FETCH r.book WHERE r.userId = :userId AND r.bookshelf = :bookshelf AND r.category = :category ORDER BY r.position ASC")
    List<Ranking> findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(@Param("userId") Long userId, @Param("bookshelf") Bookshelf bookshelf, @Param("category") BookCategory category);

    @Query("SELECT r FROM Ranking r JOIN FETCH r.book WHERE r.userId = :userId AND r.bookshelf = :bookshelf ORDER BY r.position ASC")
    List<Ranking> findByUserIdAndBookshelfOrderByPositionAsc(@Param("userId") Long userId, @Param("bookshelf") Bookshelf bookshelf);
    boolean existsByUserIdAndBookWorkOlid(Long userId, String workOlid);
    Ranking findByUserIdAndBookWorkOlid(Long userId, String workOlid);
    boolean existsByUserIdAndBookId(Long userId, Long bookId);
    boolean existsByBookId(Long bookId);
    @Query("SELECT r FROM Ranking r JOIN FETCH r.book WHERE r.userId = :userId")
    List<Ranking> findByUserId(@Param("userId") Long userId);
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Ranking r SET r.position = r.position - 1 WHERE r.userId = :userId AND r.bookshelf = :bookshelf AND r.category = :category AND r.position > :removedPosition")
    void decrementPositionsAbove(@Param("userId") Long userId, @Param("bookshelf") Bookshelf bookshelf, @Param("category") BookCategory category, @Param("removedPosition") int removedPosition);

    long countByUserIdAndBookshelfAndCategoryIn(Long userId, Bookshelf bookshelf, List<BookCategory> categories);

    @Query("SELECT r.book.id FROM Ranking r WHERE r.userId = :userId")
    List<Long> findBookIdsByUserId(@Param("userId") Long userId);

    List<Ranking> findByBookshelfOrderByUserIdAscPositionAsc(Bookshelf bookshelf);

    List<Ranking> findByUserIdInAndCategoryAndBookshelfOrderByPositionAsc(List<Long> userIds, BookCategory category, Bookshelf bookshelf);

    @Modifying
    @Transactional
    void deleteByUserId(Long userId);
}
