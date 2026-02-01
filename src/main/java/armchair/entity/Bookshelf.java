package armchair.entity;

public enum Bookshelf {
    FICTION("fiction"),
    NONFICTION("nonfiction"),
    WANT_TO_READ("want_to_read"),
    UNRANKED("unranked");

    private final String value;

    Bookshelf(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static Bookshelf fromString(String value) {
        for (Bookshelf bookshelf : Bookshelf.values()) {
            if (bookshelf.value.equalsIgnoreCase(value)) {
                return bookshelf;
            }
        }
        throw new IllegalArgumentException("Unknown bookshelf: " + value);
    }
}
