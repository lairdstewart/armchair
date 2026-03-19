package armchair.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String username;

    private String oauthSubject; // Provider's unique user ID (Google sub, GitHub id)

    private String oauthProvider; // "google" or "github"

    private Long signupNumber; // Which number signup this user was (1, 2, 3, ...)

    private LocalDateTime signupDate; // When the user signed up

    public User() {}

    public User(String username) {
        this.username = username;
    }

    public User(String username, String oauthSubject) {
        this.username = username;
        this.oauthSubject = oauthSubject;
    }

    public User(String username, String oauthSubject, String oauthProvider) {
        this.username = username;
        this.oauthSubject = oauthSubject;
        this.oauthProvider = oauthProvider;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getOauthSubject() {
        return oauthSubject;
    }

    public void setOauthSubject(String oauthSubject) {
        this.oauthSubject = oauthSubject;
    }

    public String getOauthProvider() {
        return oauthProvider;
    }

    public void setOauthProvider(String oauthProvider) {
        this.oauthProvider = oauthProvider;
    }

    public Long getSignupNumber() {
        return signupNumber;
    }

    public void setSignupNumber(Long signupNumber) {
        this.signupNumber = signupNumber;
    }

    public LocalDateTime getSignupDate() {
        return signupDate;
    }

    public void setSignupDate(LocalDateTime signupDate) {
        this.signupDate = signupDate;
    }

}
