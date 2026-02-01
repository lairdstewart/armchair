package armchair.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "books")
public class Book {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String googleBooksId;
    @Column(nullable = false, length = 1000)
    private String title;
    @Column(nullable = false, length = 1000)
    private String author;

    @org.hibernate.annotations.ColumnDefault("false")
    private boolean userUploaded;

    public Book() {}

    public Book(String googleBooksId, String title, String author) {
        this.googleBooksId = googleBooksId;
        this.title = title;
        this.author = author;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getGoogleBooksId() {
        return googleBooksId;
    }

    public void setGoogleBooksId(String googleBooksId) {
        this.googleBooksId = googleBooksId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public boolean isUserUploaded() {
        return userUploaded;
    }

    public void setUserUploaded(boolean userUploaded) {
        this.userUploaded = userUploaded;
    }
}
