package com.example.failurelab.scenarios.threadpool;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A bulkhead caps how many threads may be inside a slow dependency at once. When it is full, callers
 * are turned away quickly instead of joining the pile-up, so the rest of the thread pool stays free
 * for other endpoints. (Named after the watertight compartments in a ship's hull.)
 */
public class Bulkhead {

    private final Semaphore permits;
    private final Duration maxWait;

    public Bulkhead(int maxConcurrentCalls, Duration maxWait) {
        this.permits = new Semaphore(maxConcurrentCalls);
        this.maxWait = maxWait;
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    /**
     * Runs {@code call} if a permit becomes available within {@code maxWait}; otherwise returns
     * {@link Optional#empty()} WITHOUT running it. {@code call} must not return {@code null}: an empty
     * result would be indistinguishable from a rejection.
     */
    public <T> Optional<T> tryCall(Supplier<T> call) {
        boolean acquired;
        try {
            // Wait briefly: absorbs a momentary burst, but never lets callers pile up for long.
            acquired = permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore the flag so callers can see the interrupt
            return Optional.empty();
        }
        if (!acquired) {
            return Optional.empty(); // full: reject WITHOUT releasing - we never took a permit
        }
        try {
            return Optional.ofNullable(call.get());
        } finally {
            permits.release(); // always give the permit back, even if the call throws
        }
    }
}
