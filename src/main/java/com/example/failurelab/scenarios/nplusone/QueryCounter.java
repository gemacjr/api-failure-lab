package com.example.failurelab.scenarios.nplusone;

import org.hibernate.resource.jdbc.spi.StatementInspector;

public class QueryCounter implements StatementInspector {

    @Override
    public String inspect(String sql) {
        return sql;
    }
}
