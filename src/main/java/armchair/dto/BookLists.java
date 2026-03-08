package armchair.dto;

import java.util.ArrayList;
import java.util.List;

public record BookLists(List<BookInfo> liked, List<BookInfo> ok, List<BookInfo> disliked, List<BookInfo> unranked) {
    public List<RankedBookInfo> toRankedList() {
        List<RankedBookInfo> result = new ArrayList<>();
        int rank = 1;
        for (BookInfo b : liked) result.add(new RankedBookInfo(b, String.valueOf(rank++), "liked"));
        for (BookInfo b : ok) result.add(new RankedBookInfo(b, String.valueOf(rank++), "ok"));
        for (BookInfo b : disliked) result.add(new RankedBookInfo(b, String.valueOf(rank++), "disliked"));
        for (BookInfo b : unranked) result.add(new RankedBookInfo(b, "?", "unranked"));
        return result;
    }
}
