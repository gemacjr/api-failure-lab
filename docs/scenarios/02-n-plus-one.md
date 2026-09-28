# 02 — N+1 queries

> **Category:** database · **Endpoints:** `GET /scenarios/n-plus-one/broken`, `GET /scenarios/n-plus-one/fixed` · **Load script:** `k6/02-n-plus-one.js`

## Symptom

> "It's fast on my laptop and slow in production."

Latency grows as the data grows. On the database side you see thousands of tiny, identical queries, each
one fast, so none of them shows up as a "slow query". The app is slow and the database looks fine.

## Reproduce

```bash
make up && make run

curl -si localhost:8180/scenarios/n-plus-one/broken | grep -iE 'x-query-count|^\{'
curl -si localhost:8180/scenarios/n-plus-one/fixed  | grep -iE 'x-query-count|^\{'

make load S=02 V=broken
make load S=02 V=fixed
```

Both return the same body, `{"authors":200,"books":2000}`, but with `X-Query-Count: 201` vs `1`.

**The key demo:** make every database round trip cost 5 ms, the way it does when the database runs on
another host, and run both again:

```bash
make toxic-latency TARGET=postgres MS=5
time curl -s localhost:8180/scenarios/n-plus-one/broken    # ~201 x 5 ms = 1+ second
time curl -s localhost:8180/scenarios/n-plus-one/fixed     # barely changes
make toxic-reset
```

## What you'll see

The data is 200 authors × 10 books. The load is 10 users for 30 s.

| Metric | broken | fixed |
|---|---|---|
| SQL statements per request | 201 | 1 |
| p95 latency (local DB) | (measured in Task 11) | (measured in Task 11) |
| requests/s (local DB) | (measured in Task 11) | (measured in Task 11) |
| single request with +5 ms DB latency | (measured in Task 11) | (measured in Task 11) |

## Diagnose

1. **Count queries per request, not just latency.** In this lab, the `X-Query-Count` header does it for
   you. In a real app, turn on SQL logging temporarily:
   ```yaml
   logging.level.org.hibernate.SQL: debug
   ```
   You'll see one `select ... from author`, then the same statement 200 times:
   `select ... from book b1_0 where b1_0.author_id=?`. The same SQL repeated with a different parameter is the
   signature of N+1.
2. **Look from the database side.** Log every statement Postgres receives:
   ```bash
   docker compose exec postgres psql -U lab failurelab \
     -c "alter system set log_min_duration_statement = 0" -c "select pg_reload_conf()"
   curl -s localhost:8180/scenarios/n-plus-one/broken > /dev/null
   docker compose logs postgres --since 10s | grep -c 'from book'      # ~200
   docker compose exec postgres psql -U lab failurelab \
     -c "alter system reset log_min_duration_statement" -c "select pg_reload_conf()"
   ```
   In production, the `pg_stat_statements` extension gives you the same information in aggregate: look for
   statements with an enormous `calls` count and a tiny `mean_exec_time`.
3. **Prove it's round trips, not SQL.** Run the Toxiproxy demo above. Adding 5 ms per round trip makes
   broken slower by about 200 × 5 ms, while fixed barely moves. Latency that scales with *round trips* is an N+1 problem.

## Root cause

`Author.books` is a **lazy** collection, which is the correct default. The code loads 200 authors with one
query, then loops over them and calls `author.getBooks().size()`. Each call finds that the collection isn't
loaded yet and runs one query to load it:

```
1 query  (authors) + N queries (books of author 1, 2, ..., 200) = N+1 = 201
```

Each query is fast. The cost is the **round trips**: network latency, driver overhead and statement parsing, times 201. On a
laptop with a local database, a round trip costs ~0.1 ms, so the problem hides. In production, where the
database is across the network, it costs 0.5–5 ms, so the same code is 10–50× slower.

**The Open-Session-in-View (OSIV) trap:** Spring Boot enables `spring.jpa.open-in-view` **by default**. It keeps
the Hibernate session open for the whole HTTP request, so lazy loading works *anywhere*, including in a
controller or during JSON serialization, long after the service method has returned. That makes N+1 easy to write
and hard to see. The lab sets it to `false`, so lazy loading only works inside a `@Transactional` method.

## Fix

Load the books in the **same** query as the authors:

```java
// broken: findAll() then a lazy load per author
@Transactional(readOnly = true)
public AuthorReport brokenReport() {
    return summarize(authors.findAll());            // + author.getBooks().size() x 200
}

// fixed: one statement - authors LEFT JOIN books
@Query("select a from Author a left join fetch a.books")
List<Author> findAllWithBooks();
```

Other options, and when to choose each:

| Technique | Queries | When to use it |
|---|---|---|
| `JOIN FETCH` (this lab) | 1 | You always need the children for this use case |
| `@EntityGraph(attributePaths = "books")` | 1 | Same, declared on the repository method instead of in the JPQL |
| `hibernate.default_batch_fetch_size: 50` | N/50 + 1 | A global safety net: lazy loads are batched with `where author_id in (...)` |
| A DTO projection (`select new ...`) | 1 | Read-only views: fetch only the columns you need, and don't manage entities |

⚠️ Fetch-joining **two** collections at once (`join fetch a.books join fetch a.awards`) gives a cartesian
product, or a `MultipleBagFetchException`. Fetch one collection per query, or use batch fetching.

## Takeaway

**Count queries per request, not just latency: latency hides N+1 on a local database.**

In code review, look for a getter on a JPA relationship (`getBooks()`, `getOrders()`) inside a loop,
a `stream().map(...)`, or a serializer. It's a red flag if the repository method feeding the loop is a plain `findAll()`.

## Further reading

- [Hibernate ORM user guide: fetching](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching)
- Vlad Mihalcea, ["N+1 query problem with JPA and Hibernate"](https://vladmihalcea.com/n-plus-1-query-problem/)
- [Spring Boot: `spring.jpa.open-in-view`](https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.jpa-and-spring-data.open-entity-manager-in-view)
