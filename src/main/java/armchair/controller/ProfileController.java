package armchair.controller;

import armchair.dto.BookLists;
import armchair.dto.ProfileDisplay;
import armchair.dto.ProfileDisplayWithFollow;
import armchair.dto.UserBookRank;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.CuratedList;
import armchair.entity.CuratedRanking;
import armchair.entity.Follow;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.repository.CuratedListRepository;
import armchair.repository.FollowRepository;
import armchair.service.RankingService;
import armchair.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Controller
public class ProfileController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private CuratedListRepository curatedListRepository;

    @Autowired
    private RankingService rankingService;

    @Autowired
    private UserService userService;

    @GetMapping("/user/{username}")
    public String viewUser(@PathVariable String username, Model model, HttpSession session) {
        addNavigationAttributes(model, "search");

        User user = userRepository.findByUsername(username).orElse(null);
        boolean isCurated = false;
        BookLists fictionBooks;
        BookLists nonfictionBooks;

        if (user != null) {
            Map<Bookshelf, Map<BookCategory, List<Ranking>>> viewedUserRankings = rankingService.fetchAllRankingsGrouped(user.getId());
            fictionBooks = rankingService.getBookLists(Bookshelf.FICTION, viewedUserRankings);
            nonfictionBooks = rankingService.getBookLists(Bookshelf.NONFICTION, viewedUserRankings);
        } else {
            CuratedList curatedList = curatedListRepository.findByUsername(username).orElse(null);
            if (curatedList == null) {
                return "redirect:/search?type=profiles&query=" + UriUtils.encode(username, StandardCharsets.UTF_8);
            }
            isCurated = true;
            Map<Bookshelf, Map<BookCategory, List<CuratedRanking>>> viewedRankings = rankingService.fetchAllCuratedRankingsGrouped(curatedList.getId());
            fictionBooks = rankingService.getBookLists(Bookshelf.FICTION, viewedRankings);
            nonfictionBooks = rankingService.getBookLists(Bookshelf.NONFICTION, viewedRankings);
        }

        boolean hasFiction = !fictionBooks.liked().isEmpty() || !fictionBooks.ok().isEmpty() || !fictionBooks.disliked().isEmpty() || !fictionBooks.unranked().isEmpty();
        boolean hasNonfiction = !nonfictionBooks.liked().isEmpty() || !nonfictionBooks.ok().isEmpty() || !nonfictionBooks.disliked().isEmpty() || !nonfictionBooks.unranked().isEmpty();

        Long currentUserId = getCurrentUserId();
        Map<Bookshelf, Map<BookCategory, List<Ranking>>> currentUserRankings = currentUserId != null ? rankingService.fetchAllRankingsGrouped(currentUserId) : Map.of();
        Map<String, UserBookRank> userBooks = currentUserId != null ? rankingService.buildUserBooksMap(currentUserRankings) : Map.of();

        model.addAttribute("viewUsername", username);
        model.addAttribute("fictionRankedBooks", fictionBooks.toRankedList());
        model.addAttribute("nonfictionRankedBooks", nonfictionBooks.toRankedList());
        model.addAttribute("hasFiction", hasFiction);
        model.addAttribute("hasNonfiction", hasNonfiction);
        model.addAttribute("isCurated", isCurated);
        model.addAttribute("userBooks", userBooks);

        return "view-user";
    }

    @GetMapping("/my-profile")
    public String showProfile(Model model, HttpSession session, HttpServletRequest request) {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity != null) {
            User existingUser = userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider()).orElse(null);
            if (existingUser == null) {
                return "redirect:/setup-username";
            }
        }

        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/";
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return "redirect:/";
        }

        addNavigationAttributes(model, "profile");

        Map<Bookshelf, Map<BookCategory, List<Ranking>>> profileRankings = rankingService.fetchAllRankingsGrouped(userId);
        long fictionCount = rankingService.countBooksByBookshelf(profileRankings, Bookshelf.FICTION);
        long nonfictionCount = rankingService.countBooksByBookshelf(profileRankings, Bookshelf.NONFICTION);

        model.addAttribute("username", user.getUsername());
        model.addAttribute("signupDate", user.getSignupDate());
        model.addAttribute("signupNumber", user.getSignupNumber());
        model.addAttribute("fictionCount", fictionCount);
        model.addAttribute("nonfictionCount", nonfictionCount);
        model.addAttribute("hasAnyBooks", fictionCount + nonfictionCount > 0);
        int port = request.getServerPort();
        String publicProfileUrl = request.getScheme() + "://" + request.getServerName()
                + (port == 80 || port == 443 ? "" : ":" + port)
                + "/user/" + user.getUsername();
        model.addAttribute("publicProfileUrl", publicProfileUrl);

        return "profile";
    }

    @GetMapping("/setup-username")
    public String showUsernameSetup(Model model) {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity == null) {
            return "redirect:/";
        }

        User existingUser = userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider()).orElse(null);
        if (existingUser != null) {
            return "redirect:/my-books";
        }

        addNavigationAttributes(model, "setup");
        return "setup-username";
    }

    @PostMapping("/setup-username")
    public String submitUsername(@RequestParam String username, Model model, HttpSession session) {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity == null) {
            return "redirect:/";
        }

        addNavigationAttributes(model, "setup");

        String validationError = userService.validateUsername(username);
        if (validationError != null) {
            model.addAttribute("error", validationError);
            if (username != null && !username.isBlank()) {
                model.addAttribute("username", username.trim());
            }
            return "setup-username";
        }
        username = username.trim();

        User newUser = new User(username, identity.subject(), identity.provider());

        long realUserCount = userRepository.count();
        newUser.setSignupNumber(realUserCount + 1);
        newUser.setSignupDate(LocalDateTime.now());

        log.info("Registering username '{}' for provider '{}'", username, identity.provider());
        try {
            userRepository.save(newUser);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            String message = e.getMessage() != null ? e.getMessage() : "";
            if (message.toLowerCase().contains("username")) {
                log.warn("Username constraint violation registering '{}' (provider={}): {}",
                        username, identity.provider(), message);
                model.addAttribute("error", "Username already taken");
                model.addAttribute("username", username);
                return "setup-username";
            } else {
                log.error("Unexpected constraint violation registering '{}' (provider={}): {}",
                        username, identity.provider(), message, e);
                return "redirect:/error";
            }
        }

        return "redirect:/my-profile";
    }

    @GetMapping("/change-username")
    public String showChangeUsername(Model model, HttpSession session) {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity == null) {
            return "redirect:/";
        }
        User user = userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider()).orElse(null);
        if (user == null) {
            return "redirect:/";
        }
        addNavigationAttributes(model, "profile");
        model.addAttribute("username", user.getUsername());
        return "change-username";
    }

    @PostMapping("/change-username")
    public String changeUsername(@RequestParam String username, Model model, HttpSession session) {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity == null) {
            return "redirect:/";
        }
        User user = userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider()).orElse(null);
        if (user == null) {
            return "redirect:/";
        }

        addNavigationAttributes(model, "profile");

        if (username != null && username.trim().equals(user.getUsername())) {
            return "redirect:/my-profile";
        }

        String validationError = userService.validateUsername(username);
        if (validationError != null) {
            model.addAttribute("error", validationError);
            model.addAttribute("username", username != null && !username.isBlank() ? username.trim() : user.getUsername());
            return "change-username";
        }
        username = username.trim();

        user.setUsername(username);
        try {
            userRepository.save(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            String message = e.getMessage() != null ? e.getMessage() : "";
            if (message.toLowerCase().contains("username")) {
                log.warn("Username constraint violation changing to '{}' (provider={}): {}",
                        username, identity.provider(), message);
                model.addAttribute("error", "Username already taken");
                model.addAttribute("username", username);
                return "change-username";
            } else {
                log.error("Unexpected constraint violation changing to '{}' (provider={}): {}",
                        username, identity.provider(), message, e);
                return "redirect:/error";
            }
        }

        return "redirect:/my-profile";
    }

    @PostMapping("/follow")
    public String followUser(@RequestParam Long userId, @RequestParam(required = false) String returnUrl, HttpSession session) {
        if (getOAuthIdentity() == null) {
            return "redirect:/search?type=profiles";
        }

        Long currentUserId = getCurrentUserId();
        User currentUser = currentUserId != null ? userRepository.findById(currentUserId).orElse(null) : null;
        if (currentUser == null) {
            return "redirect:/search?type=profiles";
        }

        if (currentUserId.equals(userId)) {
            return "redirect:/search?type=profiles";
        }

        User targetUser = userRepository.findById(userId).orElse(null);
        if (targetUser == null) {
            return "redirect:/search?type=profiles";
        }

        if (!followRepository.existsByFollowerIdAndFollowedId(currentUserId, userId)) {
            Follow follow = new Follow(currentUser, targetUser);
            followRepository.save(follow);
        }

        return isSafeRedirectUrl(returnUrl) ? "redirect:" + returnUrl : "redirect:/search?type=profiles";
    }

    @PostMapping("/unfollow")
    public String unfollowUser(@RequestParam Long userId, @RequestParam(required = false) String returnUrl, HttpSession session) {
        if (getOAuthIdentity() == null) {
            return "redirect:/search?type=profiles";
        }

        Long currentUserId = getCurrentUserId();
        User currentUser = currentUserId != null ? userRepository.findById(currentUserId).orElse(null) : null;
        if (currentUser == null) {
            return "redirect:/search?type=profiles";
        }

        followRepository.findByFollowerIdAndFollowedId(currentUserId, userId)
            .ifPresent(followRepository::delete);

        return isSafeRedirectUrl(returnUrl) ? "redirect:" + returnUrl : "redirect:/search?type=profiles";
    }

    @Transactional
    @PostMapping("/delete-profile")
    public String deleteProfile(HttpSession session) {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity == null) {
            return "redirect:/";
        }

        User user = userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider()).orElse(null);
        if (user == null) {
            return "redirect:/";
        }

        userService.deleteUserAndData(user);

        session.invalidate();

        return "redirect:/logout";
    }
}
