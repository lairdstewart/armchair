package armchair.service;

import armchair.entity.RankingState;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SessionStateManager {

    public static final String SESSION_BOOK_SEARCH_RESULTS = "bookSearchResults";
    public static final String SESSION_BOOK_SEARCH_QUERY = "bookSearchQuery";
    public static final String SESSION_UNIFIED_BOOK_SEARCH_RESULTS = "unifiedBookSearchResults";
    public static final String SESSION_UNIFIED_BOOK_SEARCH_QUERY = "unifiedBookSearchQuery";
    public static final String SESSION_SKIP_RESOLVE = "skipResolve";
    public static final String SESSION_CACHED_EDITIONS = "cachedEditions";
    public static final String SESSION_EDITION_PAGE = "editionPage";
    public static final String SESSION_RESOLVE_WARNING = "resolveWarning";
    public static final String SESSION_DUPLICATE_RESOLVE_TITLE = "duplicateResolveTitle";
    public static final String SESSION_DUPLICATE_RESOLVE_WORK_OLID = "duplicateResolveWorkOlid";
    public static final String SESSION_DUPLICATE_RESOLVE_BOOK_ID = "duplicateResolveBookId";
    public static final String SESSION_BROWSE_EDITIONS_PREFIX = "browseEditions_";
    public static final String SESSION_RANKING_STATE = "rankingState";


    public RankingState getRankingState(HttpSession session) {
        return (RankingState) session.getAttribute(SESSION_RANKING_STATE);
    }

    public void saveRankingState(HttpSession session, RankingState state) {
        session.setAttribute(SESSION_RANKING_STATE, state);
    }

    public void clearRankingState(HttpSession session) {
        session.removeAttribute(SESSION_RANKING_STATE);
    }

    public void clearDuplicateResolveSession(HttpSession session) {
        session.removeAttribute(SESSION_DUPLICATE_RESOLVE_TITLE);
        session.removeAttribute(SESSION_DUPLICATE_RESOLVE_WORK_OLID);
        session.removeAttribute(SESSION_DUPLICATE_RESOLVE_BOOK_ID);
    }

    public void clearSearchAndResolveState(HttpSession session) {
        session.removeAttribute(SESSION_BOOK_SEARCH_RESULTS);
        session.removeAttribute(SESSION_SKIP_RESOLVE);
    }

    public void clearEditionCache(HttpSession session) {
        session.removeAttribute(SESSION_CACHED_EDITIONS);
        session.removeAttribute(SESSION_EDITION_PAGE);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getCachedEditions(HttpSession session) {
        return (List<T>) session.getAttribute(SESSION_CACHED_EDITIONS);
    }

    public <T> void setCachedEditions(HttpSession session, List<T> editions) {
        session.setAttribute(SESSION_CACHED_EDITIONS, editions);
    }

    public Integer getEditionPage(HttpSession session) {
        return (Integer) session.getAttribute(SESSION_EDITION_PAGE);
    }

    public String getResolveWarning(HttpSession session) {
        String warning = (String) session.getAttribute(SESSION_RESOLVE_WARNING);
        if (warning != null) {
            session.removeAttribute(SESSION_RESOLVE_WARNING);
        }
        return warning;
    }

    public void setResolveWarning(HttpSession session, String warning) {
        session.setAttribute(SESSION_RESOLVE_WARNING, warning);
    }

    public String getDuplicateResolveTitle(HttpSession session) {
        return (String) session.getAttribute(SESSION_DUPLICATE_RESOLVE_TITLE);
    }

    public String getDuplicateResolveWorkOlid(HttpSession session) {
        return (String) session.getAttribute(SESSION_DUPLICATE_RESOLVE_WORK_OLID);
    }

    public Long getDuplicateResolveBookId(HttpSession session) {
        return (Long) session.getAttribute(SESSION_DUPLICATE_RESOLVE_BOOK_ID);
    }

    public void setDuplicateResolveState(HttpSession session, String title, String workOlid, Long bookId) {
        session.setAttribute(SESSION_DUPLICATE_RESOLVE_TITLE, title);
        session.setAttribute(SESSION_DUPLICATE_RESOLVE_WORK_OLID, workOlid);
        session.setAttribute(SESSION_DUPLICATE_RESOLVE_BOOK_ID, bookId);
    }

}
