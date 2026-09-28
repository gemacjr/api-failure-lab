package com.example.failurelab.scenarios.memoryleak;

import com.example.failurelab.support.AbstractLabIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryLeakScenarioIT extends AbstractLabIT {

    private static final int UNIQUE_KEYS = 600;

    @BeforeEach
    void clearLeakyCache() {
        assertThat(delete("/scenarios/memory-leak/broken/cache").statusCode()).isEqualTo(204);
    }

    @Test
    void brokenKeepsEveryEntryForever() {
        HttpResponse<String> last = fill("broken", "leak");

        assertThat(jsonLong(last.body(), "entries")).isEqualTo(UNIQUE_KEYS);
    }

    @Test
    void brokenDoesNotGrowForARepeatedKey() {
        get("/scenarios/memory-leak/broken?key=same");
        HttpResponse<String> second = get("/scenarios/memory-leak/broken?key=same");

        assertThat(jsonLong(second.body(), "entries")).isEqualTo(1);
    }

    @Test
    void fixedStaysWithinItsMaximumSize() {
        HttpResponse<String> last = fill("fixed", "bounded");

        assertThat(jsonLong(last.body(), "entries")).isLessThanOrEqualTo(500);
    }

    @Test
    void missingKeyIsABadRequestNotAServerError() {
        assertThat(get("/scenarios/memory-leak/broken").statusCode()).isEqualTo(400);
        assertThat(get("/scenarios/memory-leak/fixed").statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> fill(String variant, String prefix) {
        HttpResponse<String> last = null;
        for (int i = 0; i < UNIQUE_KEYS; i++) {
            last = get("/scenarios/memory-leak/" + variant + "?key=" + prefix + "-" + i);
            assertThat(last.statusCode()).isEqualTo(200);
        }
        return last;
    }
}
