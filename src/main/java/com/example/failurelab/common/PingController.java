package com.example.failurelab.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The canary. It does no work at all, so if its latency rises, the app itself is starved
 * (threads, CPU, GC) - not this endpoint.
 */
@RestController
class PingController {

    @GetMapping("/api/ping")
    Map<String, String> ping() {
        return Map.of("status", "pong");
    }
}
