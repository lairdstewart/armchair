package armchair.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentLogger implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentLogger.class);

    @Value("${spring.security.oauth2.client.registration.google.client-id:}")
    private String googleClientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret:}")
    private String googleClientSecret;

    @Value("${spring.security.oauth2.client.registration.github.client-id:}")
    private String githubClientId;

    @Value("${spring.security.oauth2.client.registration.github.client-secret:}")
    private String githubClientSecret;

    @Value("${spring.security.oauth2.client.registration.microsoft.client-id:}")
    private String microsoftClientId;

    @Value("${spring.security.oauth2.client.registration.microsoft.client-secret:}")
    private String microsoftClientSecret;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Override
    public void run(String... args) {
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
