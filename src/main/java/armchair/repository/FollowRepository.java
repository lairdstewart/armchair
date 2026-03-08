package armchair.repository;

import armchair.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FollowRepository extends JpaRepository<Follow, Long> {
    // Check if a follow relationship exists
    boolean existsByFollowerIdAndFollowedId(Long followerId, Long followedId);

    // Find a specific follow relationship (for deletion)
    Optional<Follow> findByFollowerIdAndFollowedId(Long followerId, Long followedId);

    // Get all users that a user follows, eagerly fetching the followed user
    @Query("SELECT f FROM Follow f JOIN FETCH f.followed WHERE f.follower.id = :followerId")
    List<Follow> findByFollowerIdWithFollowed(@Param("followerId") Long followerId);

    // Get all users that follow a user, eagerly fetching the follower user
    @Query("SELECT f FROM Follow f JOIN FETCH f.follower WHERE f.followed.id = :followedId")
    List<Follow> findByFollowedIdWithFollower(@Param("followedId") Long followedId);

    // Get all users that a user follows (for queries that don't need the User entity)
    List<Follow> findByFollowerId(Long followerId);

    // Get all users that follow a user
    List<Follow> findByFollowedId(Long followedId);

    // Delete all follows for a user (when user is deleted)
    void deleteByFollowerId(Long followerId);
    void deleteByFollowedId(Long followedId);
}
