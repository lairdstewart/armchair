package armchair.controller;

import armchair.dto.BookInfo;
import armchair.dto.ProfileDisplay;
import armchair.dto.RankedBookInfo;
import armchair.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class HomeController extends BaseController {

    private final UserService userService;

    public HomeController(UserService userService) {
        this.userService = userService;
    }

    private static final List<RankedBookInfo> EXAMPLE_RANKED_BOOKS = List.of(
        new RankedBookInfo(
            new BookInfo(null, "OL1168007W", null, "Animal Farm", "George Orwell",
                "A biting political allegory where farm animals overthrow their human master, only to watch their revolutionary ideals corrupt into the very tyranny they sought to escape.",
                1945, 11431279),
            "1", "liked"),
        new RankedBookInfo(
            new BookInfo(null, "OL4397048W", null, "To Kill a Mockingbird", "Harper Lee",
                null, 1960, 8228691),
            "2", "ok"),
        new RankedBookInfo(
            new BookInfo(null, "OL362427W", null, "Romeo and Juliet", "William Shakespeare",
                "The classic tale of star-crossed lovers. Shakespeare's language is beautiful but the plot moves fast — perhaps too fast for the weight of its tragedy.",
                1597, 8257991),
            "3", "ok"),
        new RankedBookInfo(
            new BookInfo(null, "OL468431W", null, "The Great Gatsby", "F. Scott Fitzgerald",
                null, 1925, 15144216),
            "4", "disliked")
    );

    private static final List<RankedBookInfo> EXAMPLE_RECS = List.of(
        new RankedBookInfo(
            new BookInfo(null, "OL1168083W", null, "1984", "George Orwell",
                null, 1949, 14416004),
            "1", "liked"),
        new RankedBookInfo(
            new BookInfo(null, "OL64228W", null, "Brave New World", "Aldous Huxley",
                null, 1932, 4022957),
            "2", "liked")
    );

    @GetMapping("/")
    public String showWelcome(Model model) {
        addNavigationAttributes(model, "about");
        model.addAttribute("exampleRankedBooks", EXAMPLE_RANKED_BOOKS);
        model.addAttribute("exampleRecs", EXAMPLE_RECS);

        List<ProfileDisplay> profiles = userRepository.findRecentPublicProfiles().stream()
            .limit(2)
            .map(userService::createProfileDisplay)
            .toList();
        model.addAttribute("exampleProfiles", profiles);

        return "welcome";
    }

    @GetMapping("/login")
    public String showLogin(Model model) {
        addNavigationAttributes(model, "login");
        return "login";
    }
}
