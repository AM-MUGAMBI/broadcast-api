package com.ministry.broadcast;

import java.util.Map;

/**
 * Known channels. To add or change a channel, edit this map only —
 * nothing else in the codebase needs to know about specific channels.
 */
public class ChannelRegistry {

    public record ChannelInfo(String key, String channelId, String handle) {
        public String uploadsPlaylistId() {
            // YouTube convention: the "uploads" playlist ID is the channel ID
            // with its "UC" prefix swapped for "UU".
            return "UU" + channelId.substring(2);
        }
    }

    private static final Map<String, ChannelInfo> CHANNELS = Map.of(
        "rptw", new ChannelInfo("rptw", "UCqdgi-yU4fVlOhKZLrz24rw", "repentpreparetheway"),
        "crown", new ChannelInfo("crown", "UC3DgiGIrnmfMbBjDQP0oM-w", "CrownTvkeOfficial")
    );

    public static ChannelInfo get(String key) {
        return CHANNELS.get(key);
    }
}
