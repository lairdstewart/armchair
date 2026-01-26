package armchair.service;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.BookType;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Service
public class CuratedListImportService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private GoogleBooksService googleBooksService;

    @PostConstruct
    public void importCuratedLists() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:lists/*.yaml");

            for (Resource resource : resources) {
                importFromYaml(resource);
            }
        } catch (Exception e) {
            System.err.println("Error scanning for curated lists: " + e.getMessage());
        }
    }

    private void importFromYaml(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(inputStream);

            String username = (String) data.get("username");
            if (username == null || username.isBlank()) {
                System.err.println("Skipping " + resource.getFilename() + ": no username found");
                return;
            }

            // Check if user already exists
            if (userRepository.existsByUsername(username)) {
                System.out.println("Skipping " + username + ": already exists");
                return;
            }

            System.out.println("Importing curated list: " + username);

            // Create curated user
            User user = new User(username);
            user.setCurated(true);
            user.setGuest(false);
            userRepository.save(user);

            // Import fiction books
            @SuppressWarnings("unchecked")
            List<String> fictionTitles = (List<String>) data.get("fiction");
            if (fictionTitles != null) {
                importBooks(user.getId(), fictionTitles, BookType.FICTION);
            }

            // Import non-fiction books
            @SuppressWarnings("unchecked")
            List<String> nonfictionTitles = (List<String>) data.get("non-fiction");
            if (nonfictionTitles != null) {
                importBooks(user.getId(), nonfictionTitles, BookType.NONFICTION);
            }

            System.out.println("Finished importing: " + username);

        } catch (Exception e) {
            System.err.println("Error importing " + resource.getFilename() + ": " + e.getMessage());
        }
    }

    private void importBooks(Long userId, List<String> titles, BookType type) {
        int position = 0;
        for (String title : titles) {
            // Look up book via Google Books API
            List<GoogleBooksService.BookResult> results = googleBooksService.searchBooks(title);

            String googleBooksId = null;
            String author = null;

            if (!results.isEmpty()) {
                GoogleBooksService.BookResult firstResult = results.get(0);
                googleBooksId = firstResult.googleBooksId();
                author = firstResult.author();
            }

            Book book = new Book(userId, googleBooksId, title, author, type, BookCategory.LIKED, position);
            bookRepository.save(book);

            position++;

            // Small delay to avoid rate limiting on Google Books API
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
