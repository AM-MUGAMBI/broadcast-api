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

    // Live-status cache (short) + grace window (keeps "live" from flickering
    // off on a transient scrape miss) + latest-upload cache (longer, since
    // it costs real API quota).
    private final Map<String, CachedValue> liveCache = new ConcurrentHashMap<>();
    private final Map<String, CachedValue> latestCache = new ConcurrentHashMap<>();
    private final Map<String, LiveRecord> lastConfirmedLive = new ConcurrentHashMap<>();
    private static final Duration LIVE_GRACE_WINDOW = Duration.ofMinutes(6);

    private record LiveRecord(String videoId, Instant seenAt) {}
    private record CachedValue(String videoId, Instant expiresAt) {}
    private record FallbackResult(String videoId, String note) {}

    private static final Pattern CANONICAL_WATCH = Pattern.compile(
        "<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([a-zA-Z0-9_-]{6,})\""
    );
    private static final Pattern IS_LIVE_MARKER = Pattern.compile("\"isLiveNow\":true|\"isLive\":true");

    /**
     * Entry point for /api/channel-status. Works for both a known registry
     * key ("rptw", "crown") and an arbitrary visitor-added channel — a raw
     * handle like "@ntvkenyaonline", a full URL, or a channel ID — resolving
     * it on the fly if it's not already in the registry.
     */
    public ChannelStatus getStatusForAnyChannel(String key) {
        ChannelRegistry.ChannelInfo info = ChannelRegistry.get(key);
        if (info != null) {
            return getStatus(info);
        }

        ResolvedChannel resolved = resolveHandle(key);
        if (resolved.channelId() == null) {
            return new ChannelStatus(key, null, false, false, Instant.now().toString(),
                resolved.error() != null ? resolved.error() : "channel_not_found");
        }
        String handle = resolved.handle() != null ? resolved.handle()
            : (key.startsWith("@") ? key.substring(1) : null);
        ChannelRegistry.ChannelInfo adhoc = new ChannelRegistry.ChannelInfo(key, resolved.channelId(), handle);
        return getStatus(adhoc);
    }

    /** Same logic, for a channel that already has full info (built-in or ad-hoc). */
    public ChannelStatus getStatus(ChannelRegistry.ChannelInfo info) {
        String liveVideoId = checkLiveCached(info);
        if (liveVideoId != null) {
            lastConfirmedLive.put(info.key(), new LiveRecord(liveVideoId, Instant.now()));
            return new ChannelStatus(info.key(), liveVideoId, true, false, Instant.now().toString(), null);
        }

        String scrapeDebug = lastLiveCheckDebug.get(info.key());

        LiveRecord lastLive = lastConfirmedLive.get(info.key());
        if (lastLive != null && Duration.between(lastLive.seenAt(), Instant.now()).compareTo(LIVE_GRACE_WINDOW) < 0) {
            return new ChannelStatus(info.key(), lastLive.videoId(), true, false, Instant.now().toString(),
                "held over from last confirmed live check " + Duration.between(lastLive.seenAt(), Instant.now()).toSeconds() + "s ago");
        }
        lastConfirmedLive.remove(info.key());

        if (apiKey == null || apiKey.isBlank()) {
            return new ChannelStatus(info.key(), null, false, false, Instant.now().toString(),
                "no_api_key | " + scrapeDebug);
        }

        FallbackResult fallback = latestPublicVideoCached(info);
        String combinedNote = (scrapeDebug != null ? scrapeDebug + " || " : "") + fallback.note();
        return new ChannelStatus(info.key(), fallback.videoId(), false, false, Instant.now().toString(), combinedNote);
    }

    // ---------- Live check: free, scrapes the public /live page (no API quota) ----------

    private record LiveCheckResult(String videoId, String debugNote) {}

    private String checkLiveCached(ChannelRegistry.ChannelInfo info) {
        CachedValue cached = liveCache.get(info.key());
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.videoId();
        }
        LiveCheckResult result = checkLive(info);
        liveCache.put(info.key(), new CachedValue(result.videoId(), Instant.now().plusSeconds(45)));
        lastLiveCheckDebug.put(info.key(), result.debugNote());
        return result.videoId();
    }

    private final Map<String, String> lastLiveCheckDebug = new ConcurrentHashMap<>();

    private LiveCheckResult checkLive(ChannelRegistry.ChannelInfo info) {
        try {
            String url = (info.handle() != null && !info.handle().isBlank())
                ? "https://www.youtube.com/@" + info.handle() + "/live"
                : "https://www.youtube.com/channel/" + info.channelId() + "/live";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (compatible; ministry-broadcast-bot/1.0)")
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            int status = response.statusCode();

            Matcher canonical = CANONICAL_WATCH.matcher(body);
            boolean canonicalFound = canonical.find();
            String canonicalVideoId = canonicalFound ? canonical.group(1) : null;
            boolean liveMarkerFound = IS_LIVE_MARKER.matcher(body).find();

            if (canonicalFound && liveMarkerFound) {
                return new LiveCheckResult(canonicalVideoId, null);
            }
            return new LiveCheckResult(null, "scrape_debug: url=" + url + " status=" + status
                + " bodyLen=" + body.length() + " canonicalFound=" + canonicalFound
                + " liveMarkerFound=" + liveMarkerFound
                + (canonicalFound ? " canonicalVideoId=" + canonicalVideoId : ""));
        } catch (Exception e) {
            return new LiveCheckResult(null, "scrape_error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    // ---------- Fallback: latest public upload via YouTube Data API (cheap: 2 units) ----------

    private FallbackResult latestPublicVideoCached(ChannelRegistry.ChannelInfo info) {
        CachedValue cached = latestCache.get(info.key());
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return new FallbackResult(cached.videoId(), cached.videoId() != null ? "Latest upload" : "cached_null_result");
        }
        FallbackResult result = latestPublicVideo(info);
        latestCache.put(info.key(), new CachedValue(result.videoId(), Instant.now().plusSeconds(300)));
        return result;
    }

    private FallbackResult latestPublicVideo(ChannelRegistry.ChannelInfo info) {
        try {
            List<String> candidateIds = fetchRecentVideoIds(info.uploadsPlaylistId(), 10);
            if (candidateIds.isEmpty()) {
                return new FallbackResult(null, "playlistItems returned zero videos");
            }
            String videoId = firstPublicVideo(candidateIds);
            if (videoId == null) {
                return new FallbackResult(null, "found " + candidateIds.size() + " recent videos, but none were public");
            }
            return new FallbackResult(videoId, "Latest upload");
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
            statusByVideoId.put(item.path("id").asText(), item.path("status").path("privacyStatus").asText(""));
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

    // ---------- Resolving a visitor-typed handle/URL/ID into a real channel ----------

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
