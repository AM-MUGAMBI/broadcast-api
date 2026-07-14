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
    private static final Duration CACHE_DURATION = Duration.ofSeconds(15);

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
        statusCache.put(info.key(), new CachedStatus(status, Instant.now().plus(CACHE_DURATION)));
        return status;
    }

    private ChannelStatus resolveStatus(ChannelRegistry.ChannelInfo info) {
        if (apiKey == null || apiKey.isBlank()) {
            return new ChannelStatus(info.key(), null, false, false, Instant.now().toString(), "no_api_key");
        }

        try {
            List<String> recentVideoIds = fetchRecentVideoIds(info.uploadsPlaylistId(), 5);
            if (!recentVideoIds.isEmpty()) {
                String liveId = checkLiveViaVideosApi(recentVideoIds);
                if (liveId != null) {
                    return new ChannelStatus(info.key(), liveId, true, false, Instant.now().toString(), null);
                }
            }

            String upcomingId = fetchStreamByEvent(info.channelId(), "upcoming");
            if (upcomingId != null) {
                return new ChannelStatus(info.key(), upcomingId, false, true, Instant.now().toString(), "Upcoming broadcast");
            }

            if (!recentVideoIds.isEmpty()) {
                return new ChannelStatus(info.key(), recentVideoIds.get(0), false, false, Instant.now().toString(), "Latest upload");
            }

            return new ChannelStatus(info.key(), null, false, false, Instant.now().toString(), "No content found");

        } catch (Exception e) {
            return new ChannelStatus(info.key(), null, false, false, Instant.now().toString(), "api_error: " + e.getMessage());
        }
    }

    private String checkLiveViaVideosApi(List<String> videoIds) throws Exception {
        String url = "https://www.googleapis.com/youtube/v3/videos?part=snippet&id="
                     + String.join(",", videoIds) + "&key=" + apiKey;
        JsonNode root = getJson(url);

        for (JsonNode item : root.path("items")) {
            if ("live".equals(item.path("snippet").path("liveBroadcastContent").asText())) {
                return item.path("id").asText();
            }
        }
        return null;
    }

    private String fetchStreamByEvent(String channelId, String eventType) {
        try {
            String url = "https://www.googleapis.com/youtube/v3/search?part=snippet&channelId=" + channelId
                         + "&eventType=" + eventType + "&type=video&maxResults=1&key=" + apiKey;
            JsonNode root = getJson(url);
            JsonNode items = root.path("items");
            return items.isEmpty() ? null : items.get(0).path("id").path("videoId").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> fetchRecentVideoIds(String playlistId, int max) throws Exception {
        String url = "https://www.googleapis.com/youtube/v3/playlistItems?part=contentDetails&maxResults=" + max
                     + "&playlistId=" + playlistId + "&key=" + apiKey;
        JsonNode root = getJson(url);
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode item : root.path("items")) {
            String vid = item.path("contentDetails").path("videoId").asText(null);
            if (vid != null) ids.add(vid);
        }
        return ids;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(response.body());
    }

    public ResolvedChannel resolveHandle(String rawInput) {
        String input = rawInput.trim();

        if (apiKey == null || apiKey.isBlank()) {
            return new ResolvedChannel(null, null, null, "no_api_key");
        }

        Matcher channelIdInUrl = Pattern.compile("channel/(UC[a-zA-Z0-9_-]{20,})").matcher(input);
        if (channelIdInUrl.find()) return fetchChannelTitleAndHandle(channelIdInUrl.group(1));
        if (input.matches("UC[a-zA-Z0-9_-]{20,}")) return fetchChannelTitleAndHandle(input);

        String handle = input.startsWith("@") ? input.substring(1) : input;
        Matcher handleInUrl = Pattern.compile("youtube\\.com/@([a-zA-Z0-9_.-]+)").matcher(input);
        if (handleInUrl.find()) {
            handle = handleInUrl.group(1);
        }

        try {
            String url = "https://www.googleapis.com/youtube/v3/channels?part=id,snippet&forHandle=" + handle + "&key=" + apiKey;
            JsonNode root = getJson(url);
            JsonNode items = root.path("items");
            if (items.isEmpty()) return new ResolvedChannel(null, null, handle, "channel_not_found");
            return new ResolvedChannel(items.get(0).path("id").asText(), items.get(0).path("snippet").path("title").asText(handle), handle, null);
        } catch (Exception e) {
            return new ResolvedChannel(null, null, handle, "api_error: " + e.getMessage());
        }
    }

    private ResolvedChannel fetchChannelTitleAndHandle(String channelId) {
        if (apiKey == null || apiKey.isBlank()) {
            return new ResolvedChannel(channelId, null, null, "no_api_key");
        }
        try {
            String url = "https://www.googleapis.com/youtube/v3/channels?part=snippet&id=" + channelId + "&key=" + apiKey;
            JsonNode root = getJson(url);
            JsonNode items = root.path("items");
            if (items.isEmpty()) return new ResolvedChannel(channelId, null, null, "channel_not_found");
            return new ResolvedChannel(channelId, items.get(0).path("snippet").path("title").asText(channelId), null, null);
        } catch (Exception e) {
            return new ResolvedChannel(channelId, null, null, "api_error: " + e.getMessage());
        }
    }

    public record ResolvedChannel(String channelId, String title, String handle, String error) {}
}
