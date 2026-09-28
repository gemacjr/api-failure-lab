package com.example.failurelab.scenarios.redos;

import java.util.regex.Pattern;

/**
 * Validates "11 comma-separated fields, the last one numeric", e.g. {@code a,b,c,d,e,f,g,h,i,j,42}.
 *
 * Why not the textbook {@code ^(a+)+$}? Since JDK 9, java.util.regex memoizes simple greedy loops, so the
 * classic exponential examples run in ~0 ms on JDK 17. Bounded repetition like {@code {10}} is NOT
 * memoized, so this realistic-looking pattern backtracks polynomially: about 0.9 s at 31 chars and about
 * 14 s at 41 chars (measured on the lab machine).
 */
public final class CsvRecordValidator {

    public static final int MAX_INPUT_LENGTH = 200;

    // BROKEN: `.*` can also swallow commas, so there are a huge number of ways to carve the input into
    // 10 "field," chunks. On a near-miss the engine tries every one of them before giving up.
    private static final Pattern BROKEN = Pattern.compile("^(.*,){10}\\d+$");

    // FIXED: `[^,]*` cannot match a comma, so there is exactly ONE way to split the input. Linear time.
    private static final Pattern FIXED = Pattern.compile("^([^,]*,){10}\\d+$");

    private CsvRecordValidator() {
    }

    public static boolean brokenMatches(String line) {
        return BROKEN.matcher(line).matches();
    }

    public static boolean fixedMatches(String line) {
        return FIXED.matcher(line).matches();
    }
}
