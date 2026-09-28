package com.example.failurelab.scenarios.poolexhaustion;

import com.example.failurelab.support.AbstractLabIT;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 12 concurrent requests, a pool of 5 connections, a 3s connection timeout, and a 2s downstream call.
 * Broken holds a connection during the call: 5 finish at ~2s, 5 more at ~4s, and the rest time out at 3s.
 */
class PoolExhaustionScenarioIT extends AbstractLabIT {

    private static final int CONCURRENT_CALLS = 12;

    @Test
    void brokenRunsOutOfConnectionsAndFails() {
        List<HttpResponse<String>> responses = fireAndWait("/scenarios/pool-exhaustion/broken");

        long failures = responses.stream().filter(r -> r.statusCode() == 500).count();
        assertThat(failures).as("requests that timed out waiting for a connection").isGreaterThanOrEqualTo(1);
        assertThat(responses).filteredOn(r -> r.statusCode() == 500)
                .allSatisfy(r -> assertThat(r.body()).contains("correlationId"));
    }

    @Test
    void fixedServesTheSameConcurrencyWithoutErrors() {
        List<HttpResponse<String>> responses = fireAndWait("/scenarios/pool-exhaustion/fixed");

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(responses.get(0).body()).contains("\"downstream\":");
    }

    private List<HttpResponse<String>> fireAndWait(String path) {
        List<CompletableFuture<HttpResponse<String>>> calls =
                IntStream.range(0, CONCURRENT_CALLS).mapToObj(i -> getAsync(path)).toList();
        return calls.stream().map(CompletableFuture::join).toList();
    }
}
