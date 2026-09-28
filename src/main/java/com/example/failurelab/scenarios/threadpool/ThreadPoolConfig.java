package com.example.failurelab.scenarios.threadpool;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
class ThreadPoolConfig {

    /** At most 8 of Tomcat's 20 threads may be inside the slow call; rejected callers still wait up to MAX_WAIT_FOR_PERMIT. */
    static final int MAX_CONCURRENT_SLOW_CALLS = 8;
    static final Duration MAX_WAIT_FOR_PERMIT = Duration.ofMillis(100);

    @Bean
    Bulkhead slowDownstreamBulkhead(MeterRegistry registry) {
        Bulkhead bulkhead = new Bulkhead(MAX_CONCURRENT_SLOW_CALLS, MAX_WAIT_FOR_PERMIT);
        Gauge.builder("lab.bulkhead.available", bulkhead, Bulkhead::availablePermits)
                .description("Free bulkhead permits for the slow downstream")
                .register(registry);
        return bulkhead;
    }
}
