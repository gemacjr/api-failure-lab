package com.example.failurelab.scenarios.nplusone;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate calls inspect() for every SQL statement it prepares. Counting per thread gives
 * "queries issued by this request", because a request runs on one thread from start to finish.
 * Registered in application.yml (hibernate.session_factory.statement_inspector).
 */
public class QueryCounter implements StatementInspector {

    private static final ThreadLocal<long[]> COUNT = ThreadLocal.withInitial(() -> new long[1]);

    public static void reset() {
        COUNT.get()[0] = 0;
    }

    public static long count() {
        return COUNT.get()[0];
    }

    @Override
    public String inspect(String sql) {
        COUNT.get()[0]++;
        return sql;
    }
}
