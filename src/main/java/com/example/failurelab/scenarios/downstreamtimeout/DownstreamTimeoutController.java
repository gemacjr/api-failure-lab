package com.example.failurelab.scenarios.downstreamtimeout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Scenario 04. Both variants call the downstream /fast endpoint. Break it for real with
 * `make toxic-latency TARGET=downstream MS=30000`.
 * broken: no timeout, so each request waits 30s, threads pile up, and the whole app stalls (see 01).
 * fixed:  1s read timeout plus a degraded-but-useful fallback response.
 */
@RestController
@RequestMapping("/scenarios/downstream-timeout")
class DownstreamTimeoutController {

    private static final Logger log = LoggerFactory.getLogger(DownstreamTimeoutController.class);

    /** What we serve when the downstream is unavailable: last-known-good or a safe default. */
    static final String FALLBACK_VALUE = "{\"value\":\"default\"}";

    private final RestClient untimed;
    private final RestClient timed;

    DownstreamTimeoutController(@Qualifier("untimedDownstream") RestClient untimed,
                                @Qualifier("timedDownstream") RestClient timed) {
        this.untimed = untimed;
        this.timed = timed;
    }

    @GetMapping("/broken")
    DownstreamResult broken() {
        return new DownstreamResult("downstream", untimed.get().uri("/fast").retrieve().body(String.class));
    }

    @GetMapping("/fixed")
    ResponseEntity<DownstreamResult> fixed() {
        try {
            String body = timed.get().uri("/fast").retrieve().body(String.class);
            return ResponseEntity.ok(new DownstreamResult("downstream", body));
        } catch (ResourceAccessException | HttpServerErrorException ex) {
            // The DOWNSTREAM is unhealthy (timeout, reset, refused, 5xx): degrade instead of failing.
            // A 4xx (HttpClientErrorException) is deliberately NOT caught - it means our request is wrong,
            // and a fallback would hide that bug. It surfaces as a 500 via GlobalExceptionHandler.
            log.warn("Downstream unavailable ({}); serving fallback", ex.getClass().getSimpleName());
            return ResponseEntity.ok()
                    .header("X-Degraded", "true")
                    .body(new DownstreamResult("fallback", FALLBACK_VALUE));
        }
    }
}
