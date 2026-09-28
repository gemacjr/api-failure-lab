package com.example.failurelab.scenarios.memoryleak;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scenario 05. A "report cache" keyed by a request parameter. Each report is 100 KB.
 * broken: a static map that nothing ever removes from. Every distinct key lives until the JVM dies.
 * fixed:  Caffeine with a maximum size and a TTL, so memory use has a ceiling.
 */
@RestController
@RequestMapping("/scenarios/memory-leak")
class MemoryLeakController {

    static final int ENTRY_BYTES = 100 * 1024;
    static final int MAX_ENTRIES = 500;

    // BROKEN: static + unbounded + no eviction. With -Xmx256m, ~2,000 unique keys fill the heap.
    private static final Map<String, byte[]> LEAKY_CACHE = new ConcurrentHashMap<>();

    // FIXED: at most 500 x 100 KB = ~50 MB, and entries expire anyway.
    private final Cache<String, byte[]> boundedCache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    MemoryLeakController(MeterRegistry registry) {
        Gauge.builder("lab.cache.entries", LEAKY_CACHE, Map::size).tag("variant", "broken").register(registry);
        Gauge.builder("lab.cache.entries", boundedCache, Cache::estimatedSize).tag("variant", "fixed").register(registry);
    }

    @GetMapping("/broken")
    CacheReport broken(@RequestParam String key) {
        LEAKY_CACHE.computeIfAbsent(key, MemoryLeakController::renderReport);
        return CacheReport.of(LEAKY_CACHE.size());
    }

    @GetMapping("/fixed")
    CacheReport fixed(@RequestParam String key) {
        boundedCache.get(key, MemoryLeakController::renderReport);
        // Caffeine evicts in the background; cleanUp() makes the reported size deterministic for the lab.
        boundedCache.cleanUp();
        return CacheReport.of(boundedCache.estimatedSize());
    }

    /** Lets you recover from the leak without restarting the app. */
    @DeleteMapping("/broken/cache")
    ResponseEntity<Void> clearLeak() {
        LEAKY_CACHE.clear();
        return ResponseEntity.noContent().build();
    }

    /** Stands in for an expensive rendering we would want to cache. */
    private static byte[] renderReport(String key) {
        byte[] report = new byte[ENTRY_BYTES];
        Arrays.fill(report, (byte) key.hashCode());
        return report;
    }

    record CacheReport(long entries, long approxMegabytes) {
        static CacheReport of(long entries) {
            return new CacheReport(entries, entries * ENTRY_BYTES / (1024 * 1024));
        }
    }
}
