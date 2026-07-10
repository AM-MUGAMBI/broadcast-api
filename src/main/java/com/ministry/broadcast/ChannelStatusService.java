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

    @Value("${youtube.api.key:}")
    private String apiKey;

    // Simple in-memory cache so many visitors don't each trigger a fresh
    // check. Live-status is cached briefly; the "latest upload" fallback
    // (which costs quota) is cached longer.
    private final Map<String, CachedValue> liveCache = new ConcurrentHashMap<>();
    private final Map<String, CachedValue> latestCache = new ConcurrentHashMap<>();

    // Remembers the last time we CONFIRMED a channel live. If a later check
    // comes back negative (network hiccup, YouTube markup mismatch, transient
    // block) within this grace window, we keep reporting live rather than
    // flickering off — a real end-of-stream will simply fail to reconfirm
    // for the whole window and then correctly flip to not-live.
    private final Map<String, LiveRecord> lastConfirmedLive = new ConcurrentHashMap<>();
    private static final Duration LIVE_GRACE_WINDOW = Duration.ofMinutes(6);

    private record LiveRecord(String videoId, Instant seenAt) {}
    private record CachedValue(String videoId, Instant expiresAt) {}

    private static final Pattern CANONICAL_WATCH = Pattern.compile(
        "<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([a-zA-Z0-9_-]{6,})\""
    );
    private static final Pattern IS_LIVE_MARKER = Pattern.compile("\"isLiveNow\":true|\"isLive\":true");

    public ChannelStatus getStatus(String key) {
        ChannelRegistry.ChannelInfo info = ChannelRegistry.get(key);
        if (info == null) {
            return new ChannelStatus(key, null, false, Instant.now().toString(), "unknown_channel_key");
        }
        return getStatus(info);
    }

    /**
     * Same live-check + fallback logic, but for a channel that isn't in the
     * static registry — used for visitor-added "My Channels".
     */
    public ChannelStatus getStatus(ChannelRegistry.ChannelInfo info) {
        String liveVideoId = checkLiveCached(info);
        if (liveVideoId != null) {
            lastConfirmedLive.put(info.key(), new LiveRecord(liveVideoId, Instant.now()));
            return new ChannelStatus(info.key(), liveVideoId, true, Instant.now().toString(), null);
        }

        // This particular check came back negative — but if we confirmed
        // live recently, treat this as a probable blip, not a real end.
        LiveRecord lastLive = lastConfirmedLive.get(info.key());
        if (lastLive != null && Duration.between(lastLive.seenAt(), Instant.now()).compareTo(LIVE_GRACE_WINDOW) < 0) {
            return new ChannelStatus(info.key(), lastLive.videoId(), true, Instant.now().toString(),
                "held over from last confirmed live check " + Duration.between(lastLive.seenAt(), Instant.now()).toSeconds() + "s ago");
        }
        lastConfirmedLive.remove(info.key());

        if (apiKey == null || apiKey.isBlank()) {
            return new ChannelStatus(info.key(), null, false, Instant.now().toString(),
                "no_api_key: YOUTUBE_API_KEY is not set (or not being read) on the server");
        }

        FallbackResult fallback = latestPublicVideoCached(info);
        return new ChannelStatus(info.key(), fallback.videoId(), false, Instant.now().toString(), fallback.note());
    }

    /**
     * Resolves a visitor-typed channel handle (e.g. "@somechannel", a full
     * channel URL, or a raw UC... channel ID) into a real channel ID, so a
     * "My Channels" card can be built and polled going forward.
     */
    public ResolvedChannel resolveHandle(String rawInput) {
        String input = rawInput.trim();

        // Already a raw channel ID (e.g. from a /channel/UC... URL)
        Matcher channelIdInUrl = Pattern.compile("channel/(UC[a-zA-Z0-9_-]{20,})").matcher(input);
        if (channelIdInUrl.find()) {
            return fetchChannelTitleAndHandle(channelIdInUrl.group(1));
        }
        if (input.matches("UC[a-zA-Z0-9_-]{20,}")) {
            return fetchChannelTitleAndHandle(input);
        }

        // Otherwise treat it as a handle — pull the @name out of a URL if one was pasted
        String handle = input;
        Matcher handleInUrl = Pattern.compile("youtube\\.com/@([a-zA-Z0-9_.-]+)").matcher(input);
        if (handleInUrl.find()) {
            handle = handleInUrl.group(1);
        } else if (handle.startsWith("@")) {
            handle = handle.substring(1);
        }

        if (apiKey == null || apiKey.isBlank()) {
            return new ResolvedChannel(null, null, null, "no_api_key");
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

    if (apiKey == null || apiKey.isBlank()) {
        return null;
    }

    try {

        String url =
                "https://www.googleapis.com/youtube/v3/search"
                        + "?part=snippet"
                        + "&channelId=" + info.channelId()
                        + "&eventType=live"
                        + "&type=video"
                        + "&maxResults=1"
                        + "&key=" + apiKey;

        JsonNode root = getJson(url);

        JsonNode items = root.path("items");

        if (items.isEmpty()) {
            return null;
        }

        String videoId =
                items.get(0)
                        .path("id")
                        .path("videoId")
                        .asText(null);

        return videoId;

    } catch (Exception e) {
        return null;
    }
}

    // ---------- Fallback: latest public upload via YouTube Data API ----------

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
