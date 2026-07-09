package com.ministry.broadcast;

public record ChannelStatus(
    String channel,
    String videoId,
    boolean live,
    String checkedAt,
    String note
) {}
