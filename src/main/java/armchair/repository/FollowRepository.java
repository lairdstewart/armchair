package armchair.repository;

import armchair.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FollowRepository extends JpaRepository<Follow, Long> {
    // Check if a follow relationship exists
    boolean existsByFollowerIdAndFollowedId(Long followerId, Long followedId);

    // Find a specific follow relationship (for deletion)
    Optional<Follow> findByFollowerIdAndFollowedId(Long followerId, Long followedId);

    // Get all users that a user follows (for "following" tab)
    List<Follow> findByFollowerId(Long followerId);

    // Get all users that follow a user (for "followers" tab)
    List<Follow> findByFollowedId(Long followedId);

    // Delete all follows for a user (when user is deleted)
    void deleteByFollowerId(Long followerId);
    void deleteByFollowedId(Long followedId);
}
