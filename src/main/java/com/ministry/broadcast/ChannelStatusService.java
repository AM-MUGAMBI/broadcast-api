package com.ministry.broadcast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChannelStatusService {

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${youtube.api.key:${YOUTUBE_API_KEY:}}")
    private String apiKey;

    private final Map<String, CachedStatus> statusCache = new ConcurrentHashMap<>();
    private final Map<String, LiveRecord> lastConfirmedLive = new ConcurrentHashMap<>();
    private static final Duration LIVE_GRACE_WINDOW = Duration.ofMinutes(6);

    private record LiveRecord(String videoId, Instant seenAt) {}
    private record CachedStatus(ChannelStatus status, Instant expiresAt) {}

    public ChannelStatus getStatus(String key) {
        ChannelRegistry.ChannelInfo info = ChannelRegistry.get(key);
        if (info == null) {
            return new ChannelStatus(key, null, false, false, Instant.now().toString(), "unknown_channel_key");
        }
        return getStatus(info);
    }

    public ChannelStatus getStatus(ChannelRegistry.ChannelInfo info) {
        CachedStatus cached = statusCache.get(info.key());
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.status();
        }

        ChannelStatus status = resolveStatus(info);
        // Reduced cache to 15 seconds for a more responsive UI state shift
        statusCache.put(info.key(), new CachedStatus(status, Instant.now().plusSeconds(15)));
        return status;
    }

    private ChannelStatus resolveStatus(ChannelRegistry.ChannelInfo info) {
        if (apiKey == null || apiKey.isBlank()) {
            return new ChannelStatus(info.key(), null, false, false, Instant.now().toString(),
                "no_api_key: YOUTUBE_API_KEY is missing on the server.");
        }

        try {
            // 1. Get recent video IDs from the channel's upload playlist feed
            List<String> recentVideoIds = fetchRecentVideoIds(info.uploadsPlaylistId(), 5);
            if (recentVideoIds.isEmpty()) {
                return new ChannelStatus(info.key(), null, false, false, Instant.now().toString(), "No videos found in feed");
            }

            // 2. Query details using the much faster, accurate videos endpoint
            String url = "https://www.googleapis.com/youtube/v3/videos"
                    + "?part=snippet,liveStreamingDetails"
                    + "&id=" + String.join(",", recentVideoIds)
                    + "&key=" + apiKey;

            JsonNode root = getJson(url);
            
            // Debug point requested to inspect live details response structure
            System.out.println("=== YOUTUBE VIDEOS API RESPONSE FOR " + info.key().toUpperCase() + " ===");
            System.out.println(root.toPrettyString());

            JsonNode items = root.path("items");
            
            String liveVideoId = null;
            String upcomingVideoId = null;
            String fallbackVideoId = null;

            // 3. Scan the real-time metadata of the top recent videos
            for (JsonNode item : items) {
                String vid = item.path("id").asText();
                String broadcastStatus = item.path("snippet").path("liveBroadcastContent").asText("");

                if ("live".equals(broadcastStatus)) {
                    liveVideoId = vid;
                    break; // Instant match, prioritize active streams
                } else if ("upcoming".equals(broadcastStatus) && upcomingVideoId == null) {
                    upcomingVideoId = vid; // Take the newest scheduled stream
                } else if ("none".equals(broadcastStatus) && fallbackVideoId == null) {
                    // Make sure it's public before choosing it as fallback
                    fallbackVideoId = vid;
                }
            }

            // 4. Return matching operational state
            if (liveVideoId != null) {
                lastConfirmedLive.put(info.key(), new LiveRecord(liveVideoId, Instant.now()));
                return new ChannelStatus(info.key(), liveVideoId, true, false, Instant.now().toString(), null);
            }

            // Handle temporary network stream blips / grace window
            LiveRecord lastLive = lastConfirmedLive.get(info.key());
            if (lastLive != null && Duration.between(lastLive.seenAt(), Instant.now()).compareTo(LIVE_GRACE_WINDOW) < 0) {
                return new ChannelStatus(info.key(), lastLive.videoId(), true, false, Instant.now().toString(),
                    "Held over within grace window");
            }
            lastConfirmedLive.remove(info.key());

            if (upcomingVideoId != null) {
                return new ChannelStatus(info.key(), upcomingVideoId, false, true, Instant.now().toString(), "Upcoming broadcast scheduled");
            }

            return new ChannelStatus(info.key(), fallbackVideoId, false, false, Instant.now().toString(), "Standard upload fallback");

        } catch (Exception e) {
            return new ChannelStatus(info.key(), null, false, false, Instant.now().toString(), "api_error: " + e.getMessage());
        }
    }

    private List<String> fetchRecentVideoIds(String playlistId, int max) throws Exception {
        String url = "https://www.googleapis.com/youtube/v3/playlistItems"
            + "?part=contentDetails&maxResults=" + max
            + "&playlistId=" + playlistId
            + "&key=" + apiKey;

        JsonNode root = getJson(url);
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode item : root.path("items")) {
            String vid = item.path("contentDetails").path("videoId").asText(null);
            if (vid != null) ids.add(vid);
        }
        return ids;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(response.body());
    }

    // ---------- Keep Handle Resolution Logic Intact ----------

    public ResolvedChannel resolveHandle(String rawInput) {
        String input = rawInput.trim();
        Matcher channelIdInUrl = Pattern.compile("channel/(UC[a-zA-Z0-9_-]{20,})").matcher(input);
        if (channelIdInUrl.find()) {
            return fetchChannelTitleAndHandle(channelIdInUrl.group(1));
        }
        if (input.matches("UC[a-zA-Z0-9_-]{20,}")) {
            return fetchChannelTitleAndHandle(input);
        }

        String handle = input;
        Matcher handleInUrl = Pattern.compile("youtube\\.com/@([a-zA-Z0-9_.-]+)").matcher(input);
        if (handleInUrl.find()) {
            handle = handleInUrl.group(1);
        } else if (handle.startsWith("@")) {
            handle = handle.substring(1);
        }

        if (apiKey == null || apiKey.isBlank()) {
            return new ResolvedChannel(null, null, handle, "no_api_key");
        }

        try {
            String url = "https://www.googleapis.com/youtube/v3/channels"
                + "?part=id,snippet&forHandle=" + handle + "&key=" + apiKey;
            JsonNode root = getJson(url);
            JsonNode items = root.path("items");
            if (items.isEmpty()) {
                return new ResolvedChannel(null, null, handle, "channel_not_found");
            }
            JsonNode first = items.get(0);
            String channelId = first.path("id").asText();
            String title = first.path("snippet").path("title").asText(handle);
            return new ResolvedChannel(channelId, title, handle, null);
        } catch (Exception e) {
            return new ResolvedChannel(null, null, handle, "api_error: " + e.getMessage());
        }
    }

    private ResolvedChannel fetchChannelTitleAndHandle(String channelId) {
        if (apiKey == null || apiKey.isBlank()) {
            return new ResolvedChannel(channelId, null, null, "no_api_key");
        }
        try {
            String url = "https://www.googleapis.com/youtube/v3/channels"
                + "?part=snippet&id=" + channelId + "&key=" + apiKey;
            JsonNode root = getJson(url);
            JsonNode items = root.path("items");
            if (items.isEmpty()) {
                return new ResolvedChannel(channelId, null, null, "channel_not_found");
            }
            JsonNode snippet = items.get(0).path("snippet");
            String title = snippet.path("title").asText(channelId);
            String customUrl = snippet.path("customUrl").asText(null);
            return new ResolvedChannel(channelId, title, customUrl, null);
        } catch (Exception e) {
            return new ResolvedChannel(null, null, null, "api_error: " + e.getMessage());
        }
    }

    public record ResolvedChannel(String channelId, String title, String handle, String error) {}
}