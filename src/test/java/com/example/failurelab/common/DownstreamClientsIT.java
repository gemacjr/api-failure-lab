package com.example.failurelab.common;

import com.example.failurelab.support.AbstractLabIT;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownstreamClientsIT extends AbstractLabIT {

    @Autowired
    @Qualifier("untimedDownstream")
    RestClient untimed;

    @Autowired
    @Qualifier("timedDownstream")
    RestClient timed;

    @Test
    void untimedClientWaitsForAsLongAsTheDownstreamTakes() {
        DOWNSTREAM.stubFor(WireMock.get("/delayed").willReturn(okJson("{\"value\":\"late\"}").withFixedDelay(3000)));

        long start = System.nanoTime();
        String body = untimed.get().uri("/delayed").retrieve().body(String.class);

        assertThat(body).contains("late");
        assertThat(elapsedMs(start)).isGreaterThanOrEqualTo(2900);
    }

    @Test
    void timedClientGivesUpAfterTheReadTimeout() {
        DOWNSTREAM.stubFor(WireMock.get("/delayed").willReturn(okJson("{\"value\":\"late\"}").withFixedDelay(3000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> timed.get().uri("/delayed").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(elapsedMs(start)).isBetween(900L, 2000L);
    }
}
