-- Accounts and their manual balances (issue #7). Amounts are whole cents; booking and balance
-- dates are plain ISO dates; moments are UTC. Only the euro exists (hard rule 8).
create table account (
    id integer primary key autoincrement,
    name text not null check (length(trim(name)) > 0),
    scope text not null check (scope in ('private', 'business')),
    kind text not null check (kind in ('current', 'savings', 'investment', 'debt')),
    source text not null check (source in ('manual', 'csv', 'bank')),
    iban text,
    currency text not null default 'EUR' check (currency = 'EUR'),
    archived integer not null default 0 check (archived in (0, 1)),
    created_at text not null
);

-- Two accounts never share an IBAN (an archived one still counts); the name is free.
create unique index account_iban_unique on account (iban) where iban is not null;

-- One row per balance the owner typed in. Nothing in here is ever overwritten by anything but the
-- owner; for a debt the amount is the amount owed.
create table balance (
    id integer primary key autoincrement,
    account_id integer not null references account (id),
    amount_cents integer not null,
    currency text not null default 'EUR' check (currency = 'EUR'),
    balance_date text not null,
    entered_at text not null
);

create index balance_by_account on balance (account_id, balance_date desc, id desc);
