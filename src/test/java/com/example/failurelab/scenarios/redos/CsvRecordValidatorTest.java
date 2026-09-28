package com.example.failurelab.scenarios.redos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvRecordValidatorTest {

    /** 33 commas then a non-digit: "almost" 11 fields, forcing the broken pattern to try every split. */
    static final String NEAR_MISS = ",".repeat(33) + "x";

    @Test
    void bothPatternsAcceptAValidElevenFieldRecord() {
        String record = "a,b,c,d,e,f,g,h,i,j,42";

        assertThat(CsvRecordValidator.brokenMatches(record)).isTrue();
        assertThat(CsvRecordValidator.fixedMatches(record)).isTrue();
    }

    @Test
    void brokenPatternBacktracksForHundredsOfMillisecondsOnANearMiss() {
        long start = System.nanoTime();
        boolean valid = CsvRecordValidator.brokenMatches(NEAR_MISS);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(valid).isFalse();
        assertThat(ms).as("broken regex on a %d-char input", NEAR_MISS.length()).isGreaterThan(500);
    }

    @Test
    void fixedPatternIsLinearEvenOnHugeInput() {
        String huge = ",".repeat(5000) + "x";

        long start = System.nanoTime();
        boolean valid = CsvRecordValidator.fixedMatches(huge);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(valid).isFalse();
        assertThat(ms).isLessThan(50);
    }

    @Test
    void brokenPatternWasAlsoWrongItAcceptsTwelveFields() {
        String twelveFields = "a,b,c,d,e,f,g,h,i,j,k,42";

        assertThat(CsvRecordValidator.brokenMatches(twelveFields)).isTrue();
        assertThat(CsvRecordValidator.fixedMatches(twelveFields)).isFalse();
    }
}
