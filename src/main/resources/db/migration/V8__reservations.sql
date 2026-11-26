-- Reservations (issue #20): virtual pots for an upcoming expense. No money moves; what is reserved so far lowers
-- the headline number of the reservation's scope. Amounts are whole cents; the due date and the anchor date are plain ISO
-- dates; moments are UTC. Only the euro exists (hard rule 8).
--
-- What is reserved so far is not stored: it is worked out from the anchor (`anchor_date`, `anchor_cents`), which says how
-- much was reserved on which day, the amount and the due date. Creating a reservation, changing its amount or due date, and
-- setting what is reserved by hand all move the anchor to the day it happens. Only the owner changes this table (hard rule 10).
--
-- `follows_id` links a yearly reservation's next round to the round it follows; removing the earlier one keeps the later one.
-- `closed_at` is null while the reservation is open. Closing it against a transaction (issue #51) adds its own table that
-- points at `reservation`; nothing here has to change for that.
create table reservation (
    id integer primary key autoincrement,
    name text not null check (length(trim(name)) > 0),
    scope text not null check (scope in ('private', 'business')),
    amount_cents integer not null check (amount_cents > 0),
    currency text not null default 'EUR' check (currency = 'EUR'),
    due_date text not null,
    yearly integer not null default 0 check (yearly in (0, 1)),
    anchor_date text not null,
    anchor_cents integer not null check (anchor_cents >= 0 and anchor_cents <= amount_cents),
    follows_id integer references reservation (id) on delete set null,
    created_at text not null,
    changed_at text,
    closed_at text
);

create index reservation_by_scope on reservation (scope, closed_at, due_date);
