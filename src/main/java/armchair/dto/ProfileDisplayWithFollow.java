package armchair.dto;

public record ProfileDisplayWithFollow(String username, String stats, Long userId, boolean isFollowing) {}
