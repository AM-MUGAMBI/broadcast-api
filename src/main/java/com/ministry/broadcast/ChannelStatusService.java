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
        .build();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${youtube.api.key:}")
    private String apiKey;

    // Simple in-memory cache so many visitors don't each trigger a fresh
    // check. Live-status is cached briefly; the "latest upload" fallback
    // (which costs quota) is cached longer.
    private final Map<String, CachedValue> liveCache = new ConcurrentHashMap<>();
    private final Map<String, CachedValue> latestCache = new ConcurrentHashMap<>();

    private record CachedValue(String videoId, Instant expiresAt) {}

    private static final Pattern CANONICAL_WATCH = Pattern.compile(
        "<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([a-zA-Z0-9_-]{6,})\""
    );
    private static final Pattern IS_LIVE_MARKER = Pattern.compile("\"isLiveNow\":true|\"isLive\":true");

    public ChannelStatus getStatus(String key) {
        ChannelRegistry.ChannelInfo info = ChannelRegistry.get(key);
        if (info == null) {
            return new ChannelStatus(key, null, false, Instant.now().toString());
        }

        String liveVideoId = checkLiveCached(info);
        if (liveVideoId != null) {
            return new ChannelStatus(key, liveVideoId, true, Instant.now().toString());
        }

        String latestVideoId = latestPublicVideoCached(info);
        return new ChannelStatus(key, latestVideoId, false, Instant.now().toString());
    }

    // ---------- Live check: free, scrapes the public /live page ----------

    private String checkLiveCached(ChannelRegistry.ChannelInfo info) {
        CachedValue cached = liveCache.get(info.key());
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.videoId();
        }
        String result = checkLive(info);
        liveCache.put(info.key(), new CachedValue(result, Instant.now().plusSeconds(45)));
        return result;
    }

    private String checkLive(ChannelRegistry.ChannelInfo info) {
        try {
            String url = "https://www.youtube.com/@" + info.handle() + "/live";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (compatible; ministry-broadcast-bot/1.0)")
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            Matcher canonical = CANONICAL_WATCH.matcher(body);
            if (canonical.find() && IS_LIVE_MARKER.matcher(body).find()) {
                return canonical.group(1);
            }
        } catch (Exception e) {
            // Network hiccup or YouTube changed their markup — fail safe to "not live"
            // rather than breaking the whole response.
        }
        return null;
    }

    // ---------- Fallback: latest public upload via YouTube Data API ----------

    private String latestPublicVideoCached(ChannelRegistry.ChannelInfo info) {
        CachedValue cached = latestCache.get(info.key());
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.videoId();
        }
        String result = latestPublicVideo(info);
        latestCache.put(info.key(), new CachedValue(result, Instant.now().plusSeconds(300)));
        return result;
    }

    private String latestPublicVideo(ChannelRegistry.ChannelInfo info) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        try {
            List<String> candidateIds = fetchRecentVideoIds(info.uploadsPlaylistId(), 10);
            if (candidateIds.isEmpty()) {
                return null;
            }
            return firstPublicVideo(candidateIds);
        } catch (Exception e) {
            return null;
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
        // videos.list preserves no particular order guarantee across ids,
        // so re-walk candidateIds in their original (newest-first) order
        // and match against whichever came back as public.
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
