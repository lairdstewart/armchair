package armchair.service;

import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Book;
import armchair.entity.Ranking;
import armchair.repository.BookRepository;
import armchair.repository.RankingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ImportExportService {

    private static final Logger log = LoggerFactory.getLogger(ImportExportService.class);
    private static final int MAX_REVIEW_LENGTH = 5000;
    private static final int MAX_IMPORT_ROWS = 10000;

    @Autowired
    private BookService bookService;

    @Autowired
    private RankingService rankingService;

    @Autowired
    private RankingRepository rankingRepository;

    @Autowired
    private BookRepository bookRepository;

    public record ImportResult(int imported, int skipped, int failed) {}

    @Transactional
    public ImportResult importGoodreads(InputStream inputStream, Long userId) {
        int imported = 0;
        int skipped = 0;
        int failed = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return new ImportResult(0, 0, 0);
            }
            List<String> headers = parseCsvLine(headerLine);
            int titleIndex = -1;
            int authorIndex = -1;
            int reviewIndex = -1;
            for (int i = 0; i < headers.size(); i++) {
                String h = headers.get(i).trim();
                if ("Title".equalsIgnoreCase(h)) titleIndex = i;
                else if ("Author".equalsIgnoreCase(h)) authorIndex = i;
                else if ("My Review".equalsIgnoreCase(h)) reviewIndex = i;
            }
            if (titleIndex == -1 || authorIndex == -1) {
                return new ImportResult(0, 0, 0);
            }

            Set<String> existingBookKeys = rankingRepository.findByUserId(userId).stream()
                .map(r -> r.getBook().getTitle().toLowerCase().trim() + "\0" + r.getBook().getAuthor().toLowerCase().trim())
                .collect(Collectors.toSet());

            List<Ranking> existingUnranked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.UNRANKED, BookCategory.UNRANKED);
            int nextUnrankedPosition = existingUnranked.size();

            String line;
            int rowCount = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (++rowCount > MAX_IMPORT_ROWS) break;
                List<String> fields = parseCsvLine(line);
                if (fields.size() <= Math.max(titleIndex, Math.max(authorIndex, reviewIndex >= 0 ? reviewIndex : 0))) {
                    continue;
                }

                try {
                    String title = fields.get(titleIndex).trim();
                    int colonIndex = title.indexOf(':');
                    if (colonIndex >= 0) {
                        title = title.substring(0, colonIndex).trim();
                    }
                    title = title.replaceAll("\\s*\\([^)]*#\\d+\\)\\s*$", "").trim();
                    String author = fields.get(authorIndex).trim();
                    String review = reviewIndex >= 0 && reviewIndex < fields.size() ? fields.get(reviewIndex).trim() : "";
                    if (title.isEmpty() || author.isEmpty()) continue;

                    String importKey = title.toLowerCase().trim() + "\0" + author.toLowerCase().trim();
                    if (existingBookKeys.contains(importKey)) {
                        skipped++;
                        continue;
                    }

                    Book book = bookService.findOrCreateBook(null, null, title, author, null, null);

                    if (!title.equals(book.getTitle())) {
                        book.setTitle(title);
                        bookRepository.save(book);
                    }

                    if (rankingRepository.existsByUserIdAndBookId(userId, book.getId())) {
                        skipped++;
                    } else {
                        String trimmedReview = trimReview(review);
                        Ranking newRanking = new Ranking(userId, book, Bookshelf.UNRANKED, BookCategory.UNRANKED, nextUnrankedPosition);
                        nextUnrankedPosition++;
                        newRanking.setReview(trimmedReview);
                        rankingRepository.save(newRanking);
                        imported++;
                    }
                } catch (Exception e) {
                    log.error("Error importing Goodreads row {}: {}", rowCount, e.getMessage());
                    failed++;
                }
            }
        } catch (IOException e) {
            log.error("Error reading Goodreads CSV: {}", e.getMessage());
        }

        return new ImportResult(imported, skipped, failed);
    }

    public String generateCsv(Long userId) {
        StringBuilder csv = new StringBuilder();
        csv.append("Rank,Title,Author,Category,Bookshelf,Review\n");

        int rank = 1;

        Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = rankingService.fetchAllRankingsGrouped(userId);
        for (Bookshelf bookshelf : Bookshelf.values()) {
            for (BookCategory category : BookCategory.values()) {
                for (Ranking ranking : rankingService.getRankings(grouped, bookshelf, category)) {
                    csv.append(rank++).append(",");
                    csv.append("\"").append(escapeCsv(ranking.getBook().getTitle())).append("\",");
                    csv.append("\"").append(escapeCsv(ranking.getBook().getAuthor())).append("\",");
                    csv.append("\"").append(category.getValue()).append("\",");
                    csv.append("\"").append(bookshelf.getValue()).append("\",");
                    csv.append("\"").append(escapeCsv(ranking.getReview())).append("\"\n");
                }
            }
        }

        return csv.toString();
    }

    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private String escapeCsv(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace("\"", "\"\"");
    }

    private static String trimReview(String review) {
        if (review == null || review.isBlank()) return null;
        String trimmed = review.trim();
        return trimmed.length() > MAX_REVIEW_LENGTH ? trimmed.substring(0, MAX_REVIEW_LENGTH) : trimmed;
    }
}
