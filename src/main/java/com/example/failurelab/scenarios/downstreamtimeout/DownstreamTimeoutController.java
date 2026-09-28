package com.example.failurelab.scenarios.downstreamtimeout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
        } catch (HttpClientErrorException ex) {
            // A 4xx means OUR request is wrong - a fallback would hide that bug. Let it surface as a 500
            // via GlobalExceptionHandler.
            throw ex;
        } catch (RestClientException ex) {
            // Everything else means the DOWNSTREAM is unhealthy: connect/read timeout, reset, refused, 5xx,
            // or a timeout that fires while the body is still arriving (Spring reports that one as a plain
            // RestClientException, not ResourceAccessException). Degrade instead of failing.
            log.warn("Downstream unavailable ({}); serving fallback", ex.getClass().getSimpleName());
            return ResponseEntity.ok()
                    .header("X-Degraded", "true")
                    .body(new DownstreamResult("fallback", FALLBACK_VALUE));
        }
    }
}
