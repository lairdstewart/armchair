package armchair.controller;

import armchair.service.ImportExportService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class ControllerUtils {

    private ControllerUtils() {}

    public static final int EDITION_PAGE_SIZE = 5;
    public static final int SEARCH_RESULTS_DEFAULT = 3;
    public static final int SEARCH_RESULTS_EXPANDED = 10;
    public static final int MANUAL_SEARCH_RESULTS = 10;
    public static final int BOOK_SEARCH_PAGE_SIZE = 5;
    public static final int BOOK_SEARCH_FETCH_LIMIT = 25;
    public static final int BOOKSHELF_PAGE_SIZE = 10;
    public static final int RECOMMENDATIONS_LIMIT = 10;
    public static final int MIN_RANKED_BOOKS_FOR_RECS = 10;

    public record PaginationResult<T>(List<T> pageItems, int page, int totalPages, int totalCount) {
        public static <T> PaginationResult<T> of(List<T> allItems, int page, int pageSize) {
            int total = allItems.size();
            int pages = Math.max(1, (total + pageSize - 1) / pageSize);
            int safePage = Math.max(0, Math.min(page, pages - 1));
            int start = safePage * pageSize;
            int end = Math.min(start + pageSize, total);
            List<T> items = start < total ? allItems.subList(start, end) : List.of();
            return new PaginationResult<>(items, safePage, pages, total);
        }
    }

    public static <T> List<T> moveMatchingToFront(List<T> items, Predicate<T> matcher) {
        for (int i = 1; i < items.size(); i++) {
            if (matcher.test(items.get(i))) {
                List<T> reordered = new ArrayList<>(items);
                T match = reordered.remove(i);
                reordered.add(0, match);
                return reordered;
            }
        }
        return items;
    }

    public static String trimReview(String review) {
        if (review == null || review.isBlank()) return null;
        String trimmed = review.trim();
        return trimmed.length() > ImportExportService.MAX_REVIEW_LENGTH
            ? trimmed.substring(0, ImportExportService.MAX_REVIEW_LENGTH) : trimmed;
    }
}
