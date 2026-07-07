package com.ministry.broadcast;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*") // allow the static front-end (any host) to call this
public class ChannelStatusController {

    private final ChannelStatusService service;

    public ChannelStatusController(ChannelStatusService service) {
        this.service = service;
    }

    @GetMapping("/api/channel-status")
    public ChannelStatus channelStatus(@RequestParam String channel) {
        return service.getStatus(channel);
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
