package armchair.entity;

public enum BookType {
    FICTION("fiction"),
    NONFICTION("nonfiction"),
    UNRANKED("unranked");

    private final String value;

    BookType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static BookType fromString(String value) {
        for (BookType type : BookType.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown book type: " + value);
    }
}
