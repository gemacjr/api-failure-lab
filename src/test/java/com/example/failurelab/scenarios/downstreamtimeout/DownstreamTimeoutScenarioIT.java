package com.example.failurelab.scenarios.downstreamtimeout;

import com.example.failurelab.support.AbstractLabIT;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static org.assertj.core.api.Assertions.assertThat;

class DownstreamTimeoutScenarioIT extends AbstractLabIT {

    private static void makeFastEndpointHang(int delayMs) {
        DOWNSTREAM.stubFor(WireMock.get("/fast").willReturn(okJson("{\"value\":\"fast\"}").withFixedDelay(delayMs)));
    }

    @Test
    void healthyDownstreamIsServedNormally() {
        HttpResponse<String> response = get("/scenarios/downstream-timeout/fixed");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"source\":\"downstream\"");
        assertThat(response.headers().firstValue("X-Degraded")).isEmpty();
    }

    @Test
    void brokenWaitsAsLongAsTheDownstreamDoes() {
        makeFastEndpointHang(3000);

        long start = System.nanoTime();
        HttpResponse<String> response = get("/scenarios/downstream-timeout/broken");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(elapsedMs(start)).isGreaterThanOrEqualTo(2900);
    }

    @Test
    void fixedGivesUpAfterTheTimeoutAndServesTheFallback() {
        makeFastEndpointHang(3000);

        long start = System.nanoTime();
        HttpResponse<String> response = get("/scenarios/downstream-timeout/fixed");

        assertThat(elapsedMs(start)).isLessThan(1500);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Degraded")).hasValue("true");
        assertThat(response.body()).contains("\"source\":\"fallback\"");
    }

    @Test
    void fixedServesTheFallbackWhenTheDownstreamReturns5xx() {
        DOWNSTREAM.stubFor(WireMock.get("/fast").willReturn(serviceUnavailable()));

        HttpResponse<String> response = get("/scenarios/downstream-timeout/fixed");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Degraded")).hasValue("true");
    }

    @Test
    void fixedDoesNotMaskClientErrorsFromTheDownstream() {
        // A 4xx means WE sent a bad request - a fallback would hide our own bug, so it must surface.
        DOWNSTREAM.stubFor(WireMock.get("/fast").willReturn(aResponse().withStatus(400)));

        HttpResponse<String> response = get("/scenarios/downstream-timeout/fixed");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("X-Degraded")).isEmpty();
        assertThat(response.body()).contains("Unexpected BadRequest"); // HttpClientErrorException$BadRequest
    }

    @Test
    void fixedServesTheFallbackWhenTheConnectionIsReset() {
        DOWNSTREAM.stubFor(WireMock.get("/fast").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpResponse<String> response = get("/scenarios/downstream-timeout/fixed");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Degraded")).hasValue("true");
    }
}
