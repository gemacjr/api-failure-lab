package com.example.failurelab.scenarios.nplusone;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/** Scenario 02. Same report, 201 queries (broken) vs 1 query (fixed). See the X-Query-Count header. */
@RestController
@RequestMapping("/scenarios/n-plus-one")
class NPlusOneController {

    private final AuthorReportService reports;

    NPlusOneController(AuthorReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/broken")
    ResponseEntity<AuthorReport> broken() {
        return counted(reports::brokenReport);
    }

    @GetMapping("/fixed")
    ResponseEntity<AuthorReport> fixed() {
        return counted(reports::fixedReport);
    }

    private static ResponseEntity<AuthorReport> counted(Supplier<AuthorReport> report) {
        QueryCounter.reset();
        AuthorReport result = report.get();
        return ResponseEntity.ok()
                .header("X-Query-Count", String.valueOf(QueryCounter.count()))
                .body(result);
    }
}
