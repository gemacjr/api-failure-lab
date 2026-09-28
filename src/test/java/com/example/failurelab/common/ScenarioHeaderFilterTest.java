package com.example.failurelab.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioHeaderFilterTest {

    private final ScenarioHeaderFilter filter = new ScenarioHeaderFilter();

    @Test
    void tagsScenarioRequests() throws Exception {
        MockHttpServletResponse response = run("/scenarios/redos/fixed");

        assertThat(response.getHeader("X-Scenario")).isEqualTo("redos");
        assertThat(response.getHeader("X-Variant")).isEqualTo("fixed");
        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void tagsSubPathsOfAVariant() throws Exception {
        MockHttpServletResponse response = run("/scenarios/memory-leak/broken/cache");

        assertThat(response.getHeader("X-Scenario")).isEqualTo("memory-leak");
        assertThat(response.getHeader("X-Variant")).isEqualTo("broken");
    }

    @Test
    void leavesOtherRequestsUntaggedButCorrelated() throws Exception {
        MockHttpServletResponse response = run("/api/ping");

        assertThat(response.getHeader("X-Scenario")).isNull();
        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
    }

    private MockHttpServletResponse run(String uri) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", uri), response, new MockFilterChain());
        return response;
    }
}
