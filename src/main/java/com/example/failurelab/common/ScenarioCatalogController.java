package com.example.failurelab.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class ScenarioCatalogController {

    private static final List<ScenarioInfo> SCENARIOS = List.of(
            new ScenarioInfo("01", "thread-pool", "Tomcat thread-pool exhaustion", "threads",
                    "docs/scenarios/01-thread-pool-exhaustion.md"),
            new ScenarioInfo("02", "n-plus-one", "N+1 queries", "database",
                    "docs/scenarios/02-n-plus-one.md"),
            new ScenarioInfo("03", "pool-exhaustion", "Hikari connection-pool exhaustion", "database",
                    "docs/scenarios/03-connection-pool-exhaustion.md"),
            new ScenarioInfo("04", "downstream-timeout", "Downstream call with no timeout", "downstream",
                    "docs/scenarios/04-downstream-no-timeout.md"),
            new ScenarioInfo("05", "memory-leak", "Memory leak -> GC thrash -> OOM", "memory",
                    "docs/scenarios/05-memory-leak.md"),
            new ScenarioInfo("06", "redos", "Catastrophic regex backtracking (ReDoS)", "cpu",
                    "docs/scenarios/06-redos.md"));

    @GetMapping("/scenarios")
    List<ScenarioInfo> list() {
        return SCENARIOS;
    }
}
