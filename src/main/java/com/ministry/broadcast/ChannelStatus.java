package com.ministry.broadcast;

public record ChannelStatus(
    String channel,
    String videoId,
    boolean live,
    boolean upcoming, // Added to track scheduled streams
    String checkedAt,
    String note
) {}