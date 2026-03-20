package armchair.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentLogger implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentLogger.class);

    private final Environment env;

    public EnvironmentLogger(Environment env) {
        this.env = env;
    }

    @Override
    public void run(String... args) {
        String googleClientId = resolve("spring.security.oauth2.client.registration.google.client-id");
        String googleClientSecret = resolve("spring.security.oauth2.client.registration.google.client-secret");
        String githubClientId = resolve("spring.security.oauth2.client.registration.github.client-id");
        String githubClientSecret = resolve("spring.security.oauth2.client.registration.github.client-secret");
        String microsoftClientId = resolve("spring.security.oauth2.client.registration.microsoft.client-id");
        String microsoftClientSecret = resolve("spring.security.oauth2.client.registration.microsoft.client-secret");
        String datasourceUrl = resolve("spring.datasource.url");

        log.info("Configured credentials: GOOGLE_CLIENT_ID={}, GOOGLE_CLIENT_SECRET={}, "
                + "GITHUB_CLIENT_ID={}, GITHUB_CLIENT_SECRET={}, "
                + "MICROSOFT_CLIENT_ID={}, MICROSOFT_CLIENT_SECRET={}, "
                + "DATABASE_URL={}",
                preview(googleClientId),
                previewEnd(googleClientSecret),
                preview(githubClientId),
                preview(githubClientSecret),
                preview(microsoftClientId),
                preview(microsoftClientSecret),
                preview(datasourceUrl));
    }

    private String resolve(String key) {
        try {
            return env.getProperty(key);
        } catch (Exception e) {
            log.debug("Could not resolve property {}: {}", key, e.getMessage());
            return null;
        }
    }

    private String preview(String value) {
        if (value == null || value.isEmpty()) {
            return "<not set>";
        }
        return value.substring(0, Math.min(3, value.length())) + "...";
    }

    private String previewEnd(String value) {
        if (value == null || value.isEmpty()) {
            return "<not set>";
        }
        return "..." + value.substring(Math.max(0, value.length() - 3));
    }
}
