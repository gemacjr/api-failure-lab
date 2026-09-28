package com.example.failurelab.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Two clients for the same downstream service, differing ONLY in timeouts.
 *
 * - untimedDownstream: the JDK HttpClient default - no read timeout at all. A slow downstream holds
 *   the calling thread for as long as it likes. Used by broken variants, and by scenarios 01/03 where
 *   the 2s /slow call is the point of the lesson.
 * - timedDownstream: connect 500ms, read 1s. Used by the fixed variant of scenario 04.
 *
 * Both are built from Boot's RestClient.Builder so they record http.client.requests metrics.
 */
@Configuration
public class DownstreamClients {

    public static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    @Bean
    RestClient untimedDownstream(RestClient.Builder builder, @Value("${lab.downstream.base-url}") String baseUrl) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return builder.baseUrl(baseUrl).requestFactory(new JdkClientHttpRequestFactory(http)).build();
    }

    @Bean
    RestClient timedDownstream(RestClient.Builder builder, @Value("${lab.downstream.base-url}") String baseUrl) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
