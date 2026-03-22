package armchair.recommendation;

import armchair.entity.Book;

public record ScoredBook(Book book, double score) {}
