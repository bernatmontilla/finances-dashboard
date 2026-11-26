-- The after-tax available amount of the business side, read from an external source under
-- docs/contracts/after-tax-source.md (issue #18). Moments are UTC text; amounts are integer euro cents.
-- The token is a file in the data folder (secrets/), never in this database and never in a backup.

-- Every distinct value received, newest last: the history that later milestones can chart. The current value is the
-- most recently received row. `as_of` is when the source calculated it. `mismatch` is 1 when the source sent a gross
-- balance and reserved lines that do not add up to the available amount.
create table after_tax_value (
    id integer primary key autoincrement,
    received_at text not null,
    as_of text not null,
    available_cents integer not null,
    gross_balance_cents integer,
    mismatch integer not null default 0 check (mismatch in (0, 1))
);

create index after_tax_value_by_receipt on after_tax_value (id desc);

-- The reserved lines of one value, in the order the source sent them.
create table after_tax_reserved (
    value_id integer not null references after_tax_value (id),
    position integer not null,
    label text not null,
    amount_cents integer not null,
    primary key (value_id, position)
);

-- One row per fetch: when, how it was started and how it ended. `reason` is a short code, never a message from the
-- source; `http_status` is set only when the source answered with a status other than 200. Kept to the newest 100.
create table after_tax_fetch (
    id integer primary key autoincrement,
    started_at text not null,
    finished_at text not null,
    -- scheduled: the daily run; user: "Sync now"; test: the Test button.
    trigger text not null check (trigger in ('scheduled', 'user', 'test')),
    outcome text not null check (outcome in ('ok', 'failed')),
    reason text,
    http_status integer
);
