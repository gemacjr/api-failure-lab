package com.example.failurelab.scenarios.threadpool;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkheadTest {

    @Test
    void runsTheCallWhenAPermitIsFree() {
        Bulkhead bulkhead = new Bulkhead(1, Duration.ofMillis(50));

        assertThat(bulkhead.tryCall(() -> "ok")).contains("ok");
        assertThat(bulkhead.availablePermits()).isEqualTo(1);
    }

    @Test
    void rejectsQuicklyWhenFullAndRecoversAfterRelease() throws Exception {
        Bulkhead bulkhead = new Bulkhead(1, Duration.ofMillis(50));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> bulkhead.tryCall(() -> {
            inside.countDown();
            awaitQuietly(release);
            return "held";
        }));
        holder.start();
        inside.await();

        long start = System.nanoTime();
        Optional<String> rejected = bulkhead.tryCall(() -> "should not run");
        long waitedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(rejected).isEmpty();
        assertThat(waitedMs).isLessThan(500);

        release.countDown();
        holder.join();
        assertThat(bulkhead.tryCall(() -> "after")).contains("after");
    }

    @Test
    void releasesThePermitWhenTheCallThrows() {
        Bulkhead bulkhead = new Bulkhead(1, Duration.ofMillis(50));

        assertThatThrownBy(() -> bulkhead.tryCall(() -> {
            throw new IllegalStateException("downstream blew up");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(bulkhead.availablePermits()).isEqualTo(1);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
