create table downstream_call_log (
    id        bigserial primary key,
    called_at timestamptz not null default now(),
    response  text        not null
);
