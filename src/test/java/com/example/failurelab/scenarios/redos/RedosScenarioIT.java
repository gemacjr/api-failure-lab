package com.example.failurelab.scenarios.redos;

import com.example.failurelab.support.AbstractLabIT;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RedosScenarioIT extends AbstractLabIT {

    @Test
    void fixedHandlesTheAttackPayloadInstantly() {
        long start = System.nanoTime();
        HttpResponse<String> response = get("/scenarios/redos/fixed?input=" + CsvRecordValidatorTest.NEAR_MISS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"valid\":false");
        assertThat(elapsedMs(start)).isLessThan(200);
    }

    @Test
    void fixedAcceptsInputExactlyAtTheLimit() {
        String atLimit = "a".repeat(CsvRecordValidator.MAX_INPUT_LENGTH);

        assertThat(get("/scenarios/redos/fixed?input=" + atLimit).statusCode()).isEqualTo(200);
    }

    @Test
    void fixedRejectsInputOverTheLimitBeforeRunningTheRegex() {
        String overLimit = "a".repeat(CsvRecordValidator.MAX_INPUT_LENGTH + 1);

        HttpResponse<String> response = get("/scenarios/redos/fixed?input=" + overLimit);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("200 characters");
    }

    @Test
    void missingInputIsABadRequest() {
        assertThat(get("/scenarios/redos/fixed").statusCode()).isEqualTo(400);
        assertThat(get("/scenarios/redos/broken").statusCode()).isEqualTo(400);
    }

    @Test
    void brokenReportsHowLongTheRegexTook() {
        HttpResponse<String> response = get("/scenarios/redos/broken?input=a,b,c,d,e,f,g,h,i,j,42");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"valid\":true");
        assertThat(jsonLong(response.body(), "elapsedMs")).isGreaterThanOrEqualTo(0);
    }
}
