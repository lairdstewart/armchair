package armchair.controller;

import armchair.service.PageAssemblyService;
import armchair.service.RankingService;
import armchair.service.SessionStateManager;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class MyBooksController extends BaseController {

    @Autowired
    private RankingService rankingService;

    @Autowired
    private SessionStateManager sessionState;

    @Autowired
    private PageAssemblyService pageAssemblyService;

    @PostMapping("/my-books")
    public String goToMyBooks(HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId != null) {
            rankingService.restoreAbandonedBook(userId, sessionState.getRankingState(session));
            sessionState.clearRankingState(session);
            sessionState.clearSearchAndResolveState(session);
            sessionState.clearDuplicateResolveSession(session);
        }
        return "redirect:/my-books";
    }

    @GetMapping("/my-books")
    public String showPage(Model model, HttpSession session,
                           @RequestParam(required = false) String selectedBookshelf,
                           @RequestParam(required = false) String resolveQuery) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        addNavigationAttributes(model, "list");

        String redirect = pageAssemblyService.assemblePage(userId, model, session, selectedBookshelf, resolveQuery);
        if (redirect != null) {
            return redirect;
        }

        return "index";
    }
}
