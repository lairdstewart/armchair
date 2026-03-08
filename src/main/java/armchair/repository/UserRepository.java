package armchair.repository;

import armchair.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByOauthSubject(String oauthSubject);
    Optional<User> findByOauthSubjectAndOauthProvider(String oauthSubject, String oauthProvider);
    boolean existsByUsername(String username);
    List<User> findByIsCurated(boolean isCurated);
    long countByIsCurated(boolean isCurated);

    @Query("SELECT u FROM User u WHERE u.isCurated = false AND u.publishLists = true AND LOWER(u.username) LIKE LOWER(CONCAT('%', :username, '%'))")
    List<User> searchPublicProfiles(@Param("username") String username);

    @Query("SELECT u FROM User u WHERE u.isCurated = false AND u.publishLists = true ORDER BY u.signupDate DESC LIMIT 10")
    List<User> findRecentPublicProfiles();

    @Query("SELECT u FROM User u WHERE u.isCurated = false AND u.publishLists = true ORDER BY u.signupDate DESC")
    List<User> findAllPublicProfiles();

    @Query("SELECT COUNT(u) FROM User u WHERE u.isCurated = false AND u.publishLists = true")
    long countPublicProfiles();

    @Query("SELECT u FROM User u WHERE u.isCurated = true AND LOWER(u.username) LIKE LOWER(CONCAT('%', :username, '%'))")
    List<User> searchCuratedProfiles(@Param("username") String username);

    @Query("SELECT u FROM User u WHERE u.isCurated = false AND u.publishLists = true AND LOWER(u.username) LIKE LOWER(CONCAT('%', :username, '%')) AND u.id <> :excludeId")
    List<User> searchPublicProfilesExcluding(@Param("username") String username, @Param("excludeId") Long excludeId);

    @Query("SELECT u FROM User u WHERE u.isCurated = false AND u.publishLists = true AND u.id <> :excludeId ORDER BY u.signupDate DESC LIMIT 10")
    List<User> findRecentPublicProfilesExcluding(@Param("excludeId") Long excludeId);

    @Query("SELECT u FROM User u WHERE u.isCurated = false AND u.publishLists = true AND u.id <> :excludeId ORDER BY u.signupDate DESC")
    List<User> findAllPublicProfilesExcluding(@Param("excludeId") Long excludeId);

    @Query("SELECT COUNT(u) FROM User u WHERE u.isCurated = false AND u.publishLists = true AND u.id <> :excludeId")
    long countPublicProfilesExcluding(@Param("excludeId") Long excludeId);
}
