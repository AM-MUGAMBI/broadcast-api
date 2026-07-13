package com.ministry.broadcast;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*") // Allow the static frontend (any host) to call this endpoint
public class ChannelStatusController {

    private final ChannelStatusService service;

    public ChannelStatusController(ChannelStatusService service) {
        this.service = service;
    }

    @GetMapping("/api/channel-status")
    public ChannelStatus channelStatus(@RequestParam String channel) {
        return service.getStatus(channel);
    }

    @GetMapping("/api/resolve-channel")
    public ChannelStatusService.ResolvedChannel resolveChannel(@RequestParam String input) {
        return service.resolveHandle(input);
    }

    @GetMapping("/api/channel-status-custom")
    public ChannelStatus customChannelStatus(
            @RequestParam String channelId, 
            @RequestParam(required = false) String handle) {
        // Creates a transient channel info profile on the fly to process and fetch
        ChannelRegistry.ChannelInfo info = new ChannelRegistry.ChannelInfo(channelId, channelId, handle);
        return service.getStatus(info);
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}