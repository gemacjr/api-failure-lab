package com.example.failurelab.common;

import com.example.failurelab.support.AbstractLabIT;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class FoundationIT extends AbstractLabIT {

    @Test
    void pingAnswersPong() {
        HttpResponse<String> response = get("/api/ping");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"pong\"");
    }

    @Test
    void catalogListsAllSixScenarios() {
        HttpResponse<String> response = get("/scenarios");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"slug\":\"thread-pool\"", "\"slug\":\"n-plus-one\"",
                "\"slug\":\"pool-exhaustion\"", "\"slug\":\"downstream-timeout\"",
                "\"slug\":\"memory-leak\"", "\"slug\":\"redos\"");
    }

    @Test
    void unhandledExceptionsBecomeProblemDetailsWithoutLeakingDetails() {
        HttpResponse<String> response = get("/test/boom");

        String correlationId = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body()).contains("\"correlationId\":\"" + correlationId + "\"");
        assertThat(response.body()).doesNotContain("hunter2").doesNotContain("at com.example");
    }

    @Test
    void diagnosticActuatorEndpointsTheDocsUseAreAvailable() {
        HttpResponse<String> response = get("/actuator");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("/actuator/heapdump", "/actuator/threaddump", "/actuator/prometheus");
    }

    @Test
    void prometheusEndpointIsExposed() {
        HttpResponse<String> response = get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes");
    }
}
