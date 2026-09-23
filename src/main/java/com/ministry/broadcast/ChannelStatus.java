package com.ministry.broadcast;

public record ChannelStatus(
    String channel,
    String videoId,
    boolean live,
    boolean upcoming,
    String checkedAt,
    String note
) {}
