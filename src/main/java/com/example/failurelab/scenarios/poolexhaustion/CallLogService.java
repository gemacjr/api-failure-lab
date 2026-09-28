package com.example.failurelab.scenarios.poolexhaustion;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Scenario 03. Both variants: read from the DB, call a 2s downstream, write the result to the DB.
 * The ONLY difference is where the transaction boundary is, and so how long a pooled connection is held.
 */
@Service
class CallLogService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RestClient downstream;

    CallLogService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                   @Qualifier("untimedDownstream") RestClient downstream) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.downstream = downstream;
    }

    /** BROKEN: the transaction (and its connection) stays open for the whole 2s remote call. */
    @Transactional
    public CallLogResult brokenCallAndRecord() {
        int previousCalls = countCalls();
        String response = callSlowDownstream();   // <- holding a pooled connection, doing nothing with it
        record(response);
        return new CallLogResult(response, previousCalls);
    }

    /** FIXED: no connection is held during the remote call; the write gets its own short transaction. */
    public CallLogResult fixedCallAndRecord() {
        int previousCalls = countCalls();          // auto-commit: connection returned immediately
        String response = callSlowDownstream();    // no connection held
        tx.executeWithoutResult(status -> record(response));
        return new CallLogResult(response, previousCalls);
    }

    private int countCalls() {
        Integer count = jdbc.queryForObject("select count(*) from downstream_call_log", Integer.class);
        return count == null ? 0 : count;
    }

    private void record(String response) {
        jdbc.update("insert into downstream_call_log (response) values (?)", response);
    }

    private String callSlowDownstream() {
        return downstream.get().uri("/slow").retrieve().body(String.class);
    }
}
