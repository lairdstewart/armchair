package armchair.service;

import armchair.dto.ProfileDisplay;
import armchair.dto.ProfileDisplayWithFollow;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.repository.FollowRepository;
import armchair.repository.RankingRepository;
import armchair.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final int MAX_USERNAME_LENGTH = 50;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private RankingRepository rankingRepository;

    @Autowired
    private RankingService rankingService;

    public String validateUsername(String username) {
        if (username == null || username.isBlank()) {
            return "Username cannot be empty";
        }
        if (username.trim().length() > MAX_USERNAME_LENGTH) {
            return "Username must be fewer than 50 characters";
        }
        if (!USERNAME_PATTERN.matcher(username.trim()).matches()) {
            return "Username can only contain letters, numbers, hyphens, and underscores";
        }
        if (userRepository.existsByUsername(username.trim())) {
            return "Username already taken";
        }
        return null;
    }

    public ProfileDisplay createProfileDisplay(User user) {
        Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = rankingService.fetchAllRankingsGrouped(user.getId());
        return new ProfileDisplay(user.getUsername(), formatBookStats(grouped));
    }

    public ProfileDisplayWithFollow createProfileDisplayWithFollow(User user, Long currentUserId) {
        boolean isFollowing = currentUserId != null && followRepository.existsByFollowerIdAndFollowedId(currentUserId, user.getId());
        Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = rankingService.fetchAllRankingsGrouped(user.getId());
        return new ProfileDisplayWithFollow(user.getUsername(), formatBookStats(grouped), user.getId(), isFollowing);
    }

    public String formatBookStats(Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped) {
        return String.format(" | %d fiction | %d non-fiction",
            rankingService.countBooksByBookshelf(grouped, Bookshelf.FICTION),
            rankingService.countBooksByBookshelf(grouped, Bookshelf.NONFICTION));
    }

    @Transactional
    public void deleteUserAndData(User user) {
        Long userId = user.getId();
        rankingRepository.deleteByUserId(userId);
        followRepository.findByFollowerId(userId).forEach(followRepository::delete);
        followRepository.findByFollowedId(userId).forEach(followRepository::delete);
        userRepository.delete(user);
    }

}
