-- Transactions (issue #8): the one place bank sync and CSV import store what they deliver.
-- Amounts are whole cents (spending negative); booking and value dates are plain ISO dates;
-- moments are UTC. Only the euro exists (hard rule 8).
--
-- A transaction is identified by its account plus the source's own reference; when the source has
-- none, by account plus a fingerprint of booking date, amount, counterparty and description, plus
-- its position among identical ones in the batch (`occurrence`), so two genuinely identical
-- transactions on one day are both kept and delivering the batch again adds nothing.
create table transactions (
    id integer primary key autoincrement,
    account_id integer not null references account (id),
    booking_date text not null,
    value_date text,
    amount_cents integer not null,
    currency text not null default 'EUR' check (currency = 'EUR'),
    counterparty_name text,
    counterparty_iban text,
    description text not null default '',
    status text not null check (status in ('pending', 'booked')),
    source text not null check (source in ('manual', 'csv', 'bank')),
    reference text,
    fingerprint text not null,
    occurrence integer not null default 1 check (occurrence >= 1),
    raw text not null default '',
    first_seen_at text not null,
    last_seen_at text not null
);

create unique index transactions_by_reference on transactions (account_id, reference) where reference is not null;
create unique index transactions_by_fingerprint on transactions (account_id, fingerprint, occurrence) where reference is null;
create index transactions_by_account_date on transactions (account_id, booking_date desc, id desc);
create index transactions_by_date on transactions (booking_date desc, id desc);

-- What the owner enters by hand lives apart from what a source delivers: the delivery entry point
-- never writes this table, so a sync or an import cannot lose it (hard rule 10). Category and split
-- come here later, as columns or tables of their own.
create table transaction_note (
    transaction_id integer primary key references transactions (id),
    note text not null check (length(note) > 0),
    updated_at text not null
);

-- When transactions were last delivered for the account (UTC), whatever the batch held.
alter table account add column transactions_delivered_at text;
