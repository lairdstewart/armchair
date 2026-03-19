package armchair.repository;

import armchair.entity.Bookshelf;
import armchair.entity.CuratedRanking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface CuratedRankingRepository extends JpaRepository<CuratedRanking, Long> {
    @Query("SELECT r FROM CuratedRanking r JOIN FETCH r.book WHERE r.curatedList.id = :curatedListId")
    List<CuratedRanking> findByCuratedListId(@Param("curatedListId") Long curatedListId);

    @Modifying
    @Transactional
    void deleteByCuratedListId(Long curatedListId);

    @Query("SELECT r FROM CuratedRanking r JOIN FETCH r.book WHERE r.curatedList.id = :curatedListId AND r.bookshelf = :bookshelf ORDER BY r.position ASC")
    List<CuratedRanking> findByCuratedListIdAndBookshelfOrderByPositionAsc(@Param("curatedListId") Long curatedListId, @Param("bookshelf") Bookshelf bookshelf);

    @Query("SELECT r FROM CuratedRanking r JOIN FETCH r.book WHERE r.bookshelf = :bookshelf ORDER BY r.curatedList.id ASC, r.position ASC")
    List<CuratedRanking> findByBookshelfOrderByCuratedListIdAscPositionAsc(@Param("bookshelf") Bookshelf bookshelf);

    @Query("SELECT r.book.id FROM CuratedRanking r WHERE r.curatedList.id = :curatedListId")
    List<Long> findBookIdsByCuratedListId(@Param("curatedListId") Long curatedListId);
}
