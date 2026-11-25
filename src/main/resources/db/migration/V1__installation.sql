-- The first migration: one row that says when this installation's database was created (UTC).
create table installation (
    id integer primary key check (id = 1),
    created_at text not null
);

insert into installation (id, created_at) values (1, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
