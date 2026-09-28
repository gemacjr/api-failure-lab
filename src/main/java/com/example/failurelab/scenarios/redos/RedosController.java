package com.example.failurelab.scenarios.redos;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Scenario 06. Each attack request burns one CPU core for seconds, and keeps burning even after the
 * client gives up, because regex matching cannot be interrupted.
 */
@RestController
@RequestMapping("/scenarios/redos")
class RedosController {

    @GetMapping("/broken")
    ValidationResult broken(@RequestParam String input) {
        long start = System.nanoTime();
        boolean valid = CsvRecordValidator.brokenMatches(input);
        return new ValidationResult(valid, elapsedMs(start));
    }

    @GetMapping("/fixed")
    ValidationResult fixed(@RequestParam String input) {
        // Defence in depth: even a linear regex should not be fed unbounded input.
        if (input.length() > CsvRecordValidator.MAX_INPUT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "input exceeds " + CsvRecordValidator.MAX_INPUT_LENGTH + " characters");
        }
        long start = System.nanoTime();
        boolean valid = CsvRecordValidator.fixedMatches(input);
        return new ValidationResult(valid, elapsedMs(start));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    record ValidationResult(boolean valid, long elapsedMs) {
    }
}
