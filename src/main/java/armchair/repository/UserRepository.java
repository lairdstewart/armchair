package armchair.repository;

import armchair.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByOauthSubject(String oauthSubject);
    boolean existsByUsername(String username);
    List<User> findByIsGuest(boolean isGuest);
    long countByIsGuest(boolean isGuest);
    List<User> findByIsCurated(boolean isCurated);
    long countByIsGuestAndIsCurated(boolean isGuest, boolean isCurated);
    List<User> findByIsGuestFalseAndIsCuratedFalseAndPublishListsTrueAndUsernameContainingIgnoreCase(String username);
    List<User> findTop10ByIsGuestFalseAndIsCuratedFalseAndPublishListsTrueOrderBySignupDateDesc();
    long countByIsGuestFalseAndIsCuratedFalseAndPublishListsTrue();
    List<User> findByIsCuratedTrueAndUsernameContainingIgnoreCase(String username);
}
