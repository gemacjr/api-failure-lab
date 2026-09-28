package com.example.failurelab.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Base class for integration tests: the real app on a random port, Postgres in Testcontainers,
 * and an in-JVM WireMock playing the downstream service. One Spring context is shared by all subclasses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class AbstractLabIT {

    protected static final WireMockServer DOWNSTREAM =
            new WireMockServer(options().dynamicPort().containerThreads(100));

    static {
        DOWNSTREAM.start();
    }

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("lab.downstream.base-url", DOWNSTREAM::baseUrl);
    }

    @Value("${local.server.port}")
    protected int port;

    protected final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void resetDownstream() {
        DOWNSTREAM.resetAll();
        DownstreamStubs.register(DOWNSTREAM);
    }

    protected HttpResponse<String> get(String path) {
        return send(request(path).GET().build());
    }

    protected HttpResponse<String> delete(String path) {
        return send(request(path).DELETE().build());
    }

    protected CompletableFuture<HttpResponse<String>> getAsync(String path) {
        return http.sendAsync(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Extracts a numeric field from a flat JSON body, e.g. jsonLong("{\"entries\":3}", "entries") == 3. */
    protected static long jsonLong(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(\\d+)").matcher(body);
        if (!m.find()) {
            throw new AssertionError("field '" + field + "' not found in " + body);
        }
        return Long.parseLong(m.group(1));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
