-- Fixed costs (issue #21): what repeats whether one looks or not (rent, subscriptions, insurance), kept by hand. No money
-- moves and nothing here is subtracted from the headline numbers. Amounts are whole cents; `first_due` and `ended_on` are plain
-- ISO dates; moments are UTC. Only the euro exists (hard rule 8).
--
-- `first_due` is the first charge: it sets the day of the month and, with the rhythm, the months. Every later charge is counted
-- from it (never chained), so a 31st falls on the last day of a short month and comes back to the 31st afterwards.
-- `paused_at` is null while the fixed cost runs; `ended_on` is null until the owner ends it (a plain date, the last day it may
-- be charged). Only the owner changes this table (hard rule 10): no sync, import or restore touches it.
--
-- A tick says "this fixed cost was paid in this month". It is keyed by month (not by date) so that editing the day or the rhythm
-- never loses a tick, and it goes with the fixed cost when the owner removes it. No rhythm charges twice in one month.
create table fixed_cost (
    id integer primary key autoincrement,
    name text not null check (length(trim(name)) > 0),
    scope text not null check (scope in ('private', 'business')),
    amount_cents integer not null check (amount_cents > 0),
    currency text not null default 'EUR' check (currency = 'EUR'),
    rhythm text not null check (rhythm in ('monthly', 'two_monthly', 'quarterly', 'half_yearly', 'yearly')),
    first_due text not null,
    counterparty_name text,
    counterparty_iban text,
    account_id integer references account (id),
    paused_at text,
    ended_on text,
    created_at text not null,
    changed_at text
);

create index fixed_cost_by_scope on fixed_cost (scope, ended_on);

create table fixed_cost_paid (
    fixed_cost_id integer not null references fixed_cost (id) on delete cascade,
    month text not null check (month glob '[0-9][0-9][0-9][0-9]-[0-9][0-9]'),
    ticked_at text not null,
    primary key (fixed_cost_id, month)
);
