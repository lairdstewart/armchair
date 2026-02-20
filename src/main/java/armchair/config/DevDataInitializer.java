package armchair.config;

import armchair.entity.User;
import armchair.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Profile("dev")
public class DevDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    private final UserRepository userRepository;

    public DevDataInitializer(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) {
        if (userRepository.findByOauthSubjectAndOauthProvider("dev-user-subject", "google").isEmpty()) {
            // Check if a user with username "dev" already exists (e.g. from a previous run)
            User existing = userRepository.findByUsername("dev").orElse(null);
            if (existing != null) {
                existing.setOauthSubject("dev-user-subject");
                existing.setOauthProvider("google");
                userRepository.save(existing);
                log.info("Dev mode: linked existing 'dev' user to dev-user-subject");
            } else {
                User devUser = new User("dev", "dev-user-subject", "google");
                devUser.setSignupDate(LocalDateTime.now());
                devUser.setSignupNumber(0L);
                userRepository.save(devUser);
                log.info("Dev mode: created dev user");
            }
        }
        log.info("Dev mode: auto-authenticated as user 'dev'");
    }
}
