package com.example.failurelab.scenarios.nplusone;

import com.example.failurelab.support.AbstractLabIT;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class NPlusOneScenarioIT extends AbstractLabIT {

    @Test
    void brokenIssuesOneQueryForAuthorsPlusOnePerAuthor() {
        HttpResponse<String> response = get("/scenarios/n-plus-one/broken");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Query-Count")).hasValue("201");
        assertThat(jsonLong(response.body(), "authors")).isEqualTo(200);
        assertThat(jsonLong(response.body(), "books")).isEqualTo(2000);
    }

    @Test
    void fixedLoadsEverythingInOneJoinQuery() {
        HttpResponse<String> response = get("/scenarios/n-plus-one/fixed");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Query-Count")).hasValue("1");
        assertThat(jsonLong(response.body(), "authors")).isEqualTo(200);
        assertThat(jsonLong(response.body(), "books")).isEqualTo(2000);
    }
}
