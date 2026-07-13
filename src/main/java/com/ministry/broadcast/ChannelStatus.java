package com.ministry.broadcast;

public record ChannelStatus(
    String channel,
    String videoId,
    boolean live,
    boolean upcoming, // Must be present
    String checkedAt,
    String note
) {}