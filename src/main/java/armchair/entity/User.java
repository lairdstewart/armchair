package armchair.entity;

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

    private String username;

    private String oauthSubject; // Google's unique user ID from OpenID

    private Long signupNumber; // Which number signup this user was (1, 2, 3, ...)

    private LocalDateTime signupDate; // When the user signed up

    private boolean isGuest; // True if this is a temporary guest user

    private boolean isCurated = false; // True if this is a curated/imported list (e.g., NYT Best Books)

    private boolean publishLists = false; // True if user wants their lists visible in Explore Profiles

    public User() {}

    public User(String username) {
        this.username = username;
    }

    public User(String username, String oauthSubject) {
        this.username = username;
        this.oauthSubject = oauthSubject;
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

    public boolean isGuest() {
        return isGuest;
    }

    public void setGuest(boolean guest) {
        isGuest = guest;
    }

    public boolean isCurated() {
        return isCurated;
    }

    public void setCurated(boolean curated) {
        isCurated = curated;
    }

    public boolean isPublishLists() {
        return publishLists;
    }

    public void setPublishLists(boolean publishLists) {
        this.publishLists = publishLists;
    }
}
