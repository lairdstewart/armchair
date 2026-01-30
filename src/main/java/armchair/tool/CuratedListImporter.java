package armchair.tool;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.BookType;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.UserRepository;
import armchair.service.GoogleBooksService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.domain.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@SpringBootApplication(
    scanBasePackages = {"armchair.service", "armchair.tool"},
    exclude = {SecurityAutoConfiguration.class, OAuth2ClientAutoConfiguration.class}
)
@EntityScan("armchair.entity")
@EnableJpaRepositories("armchair.repository")
public class CuratedListImporter {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: CuratedListImporter <absolute-path-to-yaml-file>");
            System.exit(1);
        }

        String yamlPath = args[0];

        SpringApplication app = new SpringApplication(CuratedListImporter.class);
        app.setWebApplicationType(WebApplicationType.NONE);

        try (ConfigurableApplicationContext context = app.run(args)) {
            UserRepository userRepository = context.getBean(UserRepository.class);
            BookRepository bookRepository = context.getBean(BookRepository.class);
            GoogleBooksService googleBooksService = context.getBean(GoogleBooksService.class);

            importFromYaml(yamlPath, userRepository, bookRepository, googleBooksService);
        }
    }

    private static void importFromYaml(String path, UserRepository userRepository,
                                        BookRepository bookRepository, GoogleBooksService googleBooksService) {
        try (InputStream inputStream = new FileInputStream(path)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(inputStream);

            String username = (String) data.get("username");
            if (username == null || username.isBlank()) {
                System.err.println("Error: no username found in " + path);
                System.exit(1);
            }

            if (userRepository.existsByUsername(username)) {
                System.out.println("Skipping " + username + ": already exists");
                return;
            }

            System.out.println("Importing curated list: " + username);

            User user = new User(username);
            user.setCurated(true);
            user.setGuest(false);
            userRepository.save(user);

            @SuppressWarnings("unchecked")
            List<String> fictionTitles = (List<String>) data.get("fiction");
            if (fictionTitles != null) {
                importBooks(user.getId(), fictionTitles, BookType.FICTION, BookCategory.LIKED,
                            bookRepository, googleBooksService);
            }

            @SuppressWarnings("unchecked")
            List<String> fictionUnrankedTitles = (List<String>) data.get("fiction-unranked");
            if (fictionUnrankedTitles != null) {
                importBooks(user.getId(), fictionUnrankedTitles, BookType.FICTION, BookCategory.UNRANKED,
                            bookRepository, googleBooksService);
            }

            @SuppressWarnings("unchecked")
            List<String> nonfictionTitles = (List<String>) data.get("non-fiction");
            if (nonfictionTitles != null) {
                importBooks(user.getId(), nonfictionTitles, BookType.NONFICTION, BookCategory.LIKED,
                            bookRepository, googleBooksService);
            }

            @SuppressWarnings("unchecked")
            List<String> nonfictionUnrankedTitles = (List<String>) data.get("non-fiction-unranked");
            if (nonfictionUnrankedTitles != null) {
                importBooks(user.getId(), nonfictionUnrankedTitles, BookType.NONFICTION, BookCategory.UNRANKED,
                            bookRepository, googleBooksService);
            }

            System.out.println("Finished importing: " + username);

        } catch (Exception e) {
            System.err.println("Error importing " + path + ": " + e.getMessage());
            System.exit(1);
        }
    }

    private static void importBooks(Long userId, List<String> titles, BookType type, BookCategory category,
                                     BookRepository bookRepository, GoogleBooksService googleBooksService) {
        int position = 0;
        for (String title : titles) {
            List<GoogleBooksService.BookResult> results = googleBooksService.searchBooks(title);

            String googleBooksId = null;
            String author = null;

            if (!results.isEmpty()) {
                GoogleBooksService.BookResult firstResult = results.get(0);
                googleBooksId = firstResult.googleBooksId();
                author = firstResult.author();
            }

            Book book = new Book(userId, googleBooksId, title, author, type, category, position);
            bookRepository.save(book);

            System.out.println("  " + (position + 1) + ". " + title + (author != null ? " by " + author : ""));
            position++;

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
