package com.example.failurelab.scenarios.threadpool;

import com.example.failurelab.support.AbstractLabIT;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 25 concurrent calls to a 2s downstream against a 20-thread Tomcat, then one canary ping.
 */
class ThreadPoolScenarioIT extends AbstractLabIT {

    private static final int CONCURRENT_CALLS = 25;

    @Test
    void brokenBlocksEveryThreadSoTheCanaryQueues() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> load = fire("/scenarios/thread-pool/broken");
        Thread.sleep(300);

        long start = System.nanoTime();
        HttpResponse<String> ping = get("/api/ping");
        long pingMs = elapsedMs(start);
        CompletableFuture.allOf(load.toArray(CompletableFuture[]::new)).join();

        assertThat(ping.statusCode()).isEqualTo(200);
        assertThat(pingMs).as("canary latency while all 20 Tomcat threads are blocked").isGreaterThan(1000);
        assertThat(load).allSatisfy(f -> assertThat(f.join().statusCode()).isEqualTo(200));
    }

    @Test
    void fixedShedsExcessLoadAndTheCanaryStaysFast() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> load = fire("/scenarios/thread-pool/fixed");
        Thread.sleep(300);

        long start = System.nanoTime();
        HttpResponse<String> ping = get("/api/ping");
        long pingMs = elapsedMs(start);
        CompletableFuture.allOf(load.toArray(CompletableFuture[]::new)).join();

        List<HttpResponse<String>> responses = load.stream().map(CompletableFuture::join).toList();
        long ok = responses.stream().filter(r -> r.statusCode() == 200).count();
        List<HttpResponse<String>> shed = responses.stream().filter(r -> r.statusCode() == 503).toList();

        assertThat(pingMs).as("canary latency with the bulkhead in place").isLessThan(500);
        assertThat(ok).as("calls admitted by the 8-permit bulkhead").isEqualTo(8);
        assertThat(shed).hasSize(CONCURRENT_CALLS - 8)
                .allSatisfy(r -> assertThat(r.headers().firstValue("Retry-After")).hasValue("1"));
    }

    private List<CompletableFuture<HttpResponse<String>>> fire(String path) {
        return IntStream.range(0, CONCURRENT_CALLS).mapToObj(i -> getAsync(path)).toList();
    }
}
