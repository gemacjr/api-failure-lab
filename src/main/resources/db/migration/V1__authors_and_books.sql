create table author (
    id   bigserial primary key,
    name varchar(255) not null
);

create table book (
    id        bigserial primary key,
    title     varchar(255) not null,
    author_id bigint not null references author (id)
);

create index book_author_id_idx on book (author_id);

-- 200 authors x 10 books = 2,000 books
insert into author (name)
select 'Author ' || g from generate_series(1, 200) as g;

insert into book (title, author_id)
select 'Book ' || a.id || '-' || n, a.id
from author a
cross join generate_series(1, 10) as n;
