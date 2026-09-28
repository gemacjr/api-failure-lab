package com.example.failurelab.scenarios.threadpool;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Scenario 01. Both variants call the same 2-second downstream on the request thread.
 * broken: unlimited, so under load every Tomcat thread ends up waiting on the downstream.
 * fixed:  a bulkhead admits 8 at a time and sheds the rest with a fast 503.
 */
@RestController
@RequestMapping("/scenarios/thread-pool")
class ThreadPoolController {

    private final RestClient downstream;
    private final Bulkhead bulkhead;
    private final Counter rejected;

    ThreadPoolController(@Qualifier("untimedDownstream") RestClient downstream, Bulkhead slowDownstreamBulkhead,
                         MeterRegistry registry) {
        this.downstream = downstream;
        this.bulkhead = slowDownstreamBulkhead;
        this.rejected = Counter.builder("lab.bulkhead.rejected")
                .description("Calls turned away because the bulkhead was full")
                .register(registry);
    }

    @GetMapping("/broken")
    Map<String, String> broken() {
        return Map.of("downstream", callSlowDownstream());
    }

    @GetMapping("/fixed")
    ResponseEntity<?> fixed() {
        return bulkhead.tryCall(this::callSlowDownstream)
                .<ResponseEntity<?>>map(body -> ResponseEntity.ok(Map.of("downstream", body)))
                .orElseGet(this::shed);
    }

    private ResponseEntity<?> shed() {
        rejected.increment();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The slow dependency is at capacity; retry shortly");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    private String callSlowDownstream() {
        String body = downstream.get().uri("/slow").retrieve().body(String.class);
        return body == null ? "" : body; // never null: Bulkhead reports a null result as "rejected"
    }
}
