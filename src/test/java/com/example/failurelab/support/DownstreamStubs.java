package com.example.failurelab.support;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;

/** The same stubs docker-compose loads from lab/wiremock/mappings, registered in-JVM for tests. */
public final class DownstreamStubs {

    public static final int SLOW_DELAY_MS = 2000;

    private DownstreamStubs() {
    }

    public static void register(WireMockServer server) {
        server.stubFor(get("/slow").willReturn(okJson("{\"value\":\"slow\"}").withFixedDelay(SLOW_DELAY_MS)));
        server.stubFor(get("/fast").willReturn(okJson("{\"value\":\"fast\"}")));
    }
}
