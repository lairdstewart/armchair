package armchair.entity;

public enum BookCategory {
    LIKED("liked"),
    OK("ok"),
    DISLIKED("disliked"),
    WANT_TO_READ("want_to_read");

    private final String value;

    BookCategory(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static BookCategory fromString(String value) {
        for (BookCategory category : BookCategory.values()) {
            if (category.value.equalsIgnoreCase(value)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown book category: " + value);
    }
}
