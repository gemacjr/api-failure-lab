package com.example.failurelab.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gives every request a correlation id (response header + logging MDC) and tags scenario requests
 * with X-Scenario / X-Variant so load-test results and logs are easy to filter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScenarioHeaderFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID = "correlationId";

    private static final Pattern SCENARIO_PATH = Pattern.compile("^/scenarios/([^/]+)/(broken|fixed)(/.*)?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = UUID.randomUUID().toString();
        MDC.put(CORRELATION_ID, correlationId);
        response.setHeader("X-Correlation-Id", correlationId);

        Matcher scenario = SCENARIO_PATH.matcher(request.getRequestURI());
        if (scenario.matches()) {
            response.setHeader("X-Scenario", scenario.group(1));
            response.setHeader("X-Variant", scenario.group(2));
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID);
        }
    }
}
