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

    // LOOKS FOR: 
    // 1st: application.properties value: youtube.api.key
    // 2nd: System Environment Variable: YOUTUBE_API_KEY
    @Value("${youtube.api.key:${YOUTUBE_API_KEY:}}")
    private String apiKey;

    private final Map<String, CachedStatus> statusCache = new ConcurrentHashMap<>();
    private final Map<String, CachedValue> latestCache = new ConcurrentHashMap<>();

    private final Map<String, LiveRecord> lastConfirmedLive = new ConcurrentHashMap<>();
    private static final Duration LIVE_GRACE_WINDOW = Duration.ofMinutes(6);

    private record LiveRecord(String videoId, Instant seenAt) {}
    private record CachedValue(String videoId, Instant expiresAt) {}
    private record CachedStatus(ChannelStatus status, Instant expiresAt) {}

    // Regex to extract the embedded player configuration JSON from the HTML source
    private static final Pattern YT_PLAYER_RESPONSE_PATTERN = Pattern.compile(
        "ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});"
    );

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
        statusCache.put(info.key(), new CachedStatus(status, Instant.now().plusSeconds(30)));
        return status;
    }

    private ChannelStatus resolveStatus(ChannelRegistry.ChannelInfo info) {
        // 1. Try to scrape the live URL (instant, handles active streams with 0 quota)
        String liveVideoId = checkLive(info);
        if (liveVideoId != null) {
            lastConfirmedLive.put(info.key(), new LiveRecord(liveVideoId, Instant.now()));
            return new ChannelStatus(info.key(), liveVideoId, true, false, Instant.now().toString(), null);
        }

        // 2. Apply grace window logic to avoid flickers
        LiveRecord lastLive = lastConfirmedLive.get(info.key());
        if (lastLive != null && Duration.between(lastLive.seenAt(), Instant.now()).compareTo(LIVE_GRACE_WINDOW) < 0) {
            return new ChannelStatus(info.key(), lastLive.videoId(), true, false, Instant.now().toString(),
                "held over from last confirmed live check " + Duration.between(lastLive.seenAt(), Instant.now()).toSeconds() + "s ago");
        }
        lastConfirmedLive.remove(info.key());

        // 3. Fallback: If not live, check for an upcoming scheduled broadcast
        String upcomingVideoId = fetchStreamByEvent(info.channelId(), "upcoming");
        if (upcomingVideoId != null) {
            return new ChannelStatus(info.key(), upcomingVideoId, false, true, Instant.now().toString(), "Upcoming broadcast scheduled");
        }

        // 4. Default: No live or upcoming streams, fetch the latest public uploaded video
        if (apiKey == null || apiKey.isBlank()) {
            return new ChannelStatus(info.key(), null, false, false, Instant.now().toString(),
                "no_api_key: YOUTUBE_API_KEY is not set (or not being read) on the server. API Key value: [" + apiKey + "]");
        }

        FallbackResult fallback = latestPublicVideoCached(info);
        return new ChannelStatus(info.key(), fallback.videoId(), false, false, Instant.now().toString(), fallback.note());
    }

    // ---------- Scrapes the public /live route using embedded JSON parsing ----------

    private String checkLive(ChannelRegistry.ChannelInfo info) {
        try {
            String liveUrl = "https://www.youtube.com/channel/" + info.channelId() + "/live";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(liveUrl))
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .GET()
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            // Find the JSON block containing the video player details
            Matcher matcher = YT_PLAYER_RESPONSE_PATTERN.matcher(body);
            if (matcher.find()) {
                String jsonStr = matcher.group(1);
                JsonNode root = mapper.readTree(jsonStr);
                
                // Inspect the videoDetails section
                JsonNode videoDetails = root.path("videoDetails");
                boolean isLive = videoDetails.path("isLive").asBoolean(false);
                String videoId = videoDetails.path("videoId").asText(null);

                if (isLive && videoId != null) {
                    return videoId;
                }
            }
        } catch (Exception e) {
            System.err.println("Error scraping live status for " + info.key() + ": " + e.getMessage());
        }
        return null;
    }

    // ---------- Safe API query for upcoming scheduled streams ----------

    private String fetchStreamByEvent(String channelId, String eventType) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        try {
            String url = "https://www.googleapis.com/youtube/v3/search"
                    + "?part=snippet"
                    + "&channelId=" + channelId
                    + "&eventType=" + eventType
                    + "&type=video"
                    + "&maxResults=1"
                    + "&key=" + apiKey;

            JsonNode root = getJson(url);
            JsonNode items = root.path("items");

            if (items.isEmpty()) {
                return null;
            }

            return items.get(0)
                    .path("id")
                    .path("videoId")
                    .asText(null);

        } catch (Exception e) {
            return null;
        }
    }

    // ---------- Rest of Channel Parsing & Fallback Logic ----------

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
            return new ResolvedChannel(channelId, null, null, "api_error: " + e.getMessage());
        }
    }

    public record ResolvedChannel(String channelId, String title, String handle, String error) {}

    private record FallbackResult(String videoId, String note) {}

    private FallbackResult latestPublicVideoCached(ChannelRegistry.ChannelInfo info) {
        CachedValue cached = latestCache.get(info.key());
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return new FallbackResult(cached.videoId(), cached.videoId() != null ? null : "cached_null_result");
        }
        FallbackResult result = latestPublicVideo(info);
        latestCache.put(info.key(), new CachedValue(result.videoId(), Instant.now().plusSeconds(300)));
        return result;
    }

    private FallbackResult latestPublicVideo(ChannelRegistry.ChannelInfo info) {
        try {
            List<String> candidateIds = fetchRecentVideoIds(info.uploadsPlaylistId(), 10);
            if (candidateIds.isEmpty()) {
                return new FallbackResult(null, "playlistItems returned zero videos — check the uploads playlist ID");
            }
            String videoId = firstPublicVideo(candidateIds);
            if (videoId == null) {
                return new FallbackResult(null, "found " + candidateIds.size() + " recent videos, but none were public");
            }
            return new FallbackResult(videoId, null);
        } catch (Exception e) {
            return new FallbackResult(null, "api_error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
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

    private String firstPublicVideo(List<String> candidateIds) throws Exception {
        String url = "https://www.googleapis.com/youtube/v3/videos"
            + "?part=status&id=" + String.join(",", candidateIds)
            + "&key=" + apiKey;

        JsonNode root = getJson(url);
        Map<String, String> statusByVideoId = new java.util.HashMap<>();
        for (JsonNode item : root.path("items")) {
            String vid = item.path("id").asText();
            String status = item.path("status").path("privacyStatus").asText("");
            statusByVideoId.put(vid, status);
        }
        for (String vid : candidateIds) {
            if ("public".equals(statusByVideoId.get(vid))) {
                return vid;
            }
        }
        return null;
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
}