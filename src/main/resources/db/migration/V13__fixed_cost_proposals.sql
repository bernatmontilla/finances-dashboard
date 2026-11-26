-- Fixed cost proposals and a kept price that knows its amount (issue #71).

-- `fixed_cost_price_kept` said only which transaction the owner accepted at its price. A bank can correct that transaction's
-- amount later, so the row now also holds the amount that was kept (positive cents); a kept row counts only while it equals the
-- transaction's current amount. Rows from V12 are filled in from their transaction.
alter table fixed_cost_price_kept add column amount_cents integer;
update fixed_cost_price_kept set amount_cents = (select -t.amount_cents from transactions t where t.id = fixed_cost_price_kept.transaction_id);

-- A proposal is derived data: a fixed cost that repeating booked spending suggests. `FixedCostProposalService.detect()` works them out
-- after a sync or an import and keeps the rows (one per counterparty and scope, `group_key`, so an id stays the same from one run to the
-- next). A proposal never changes a number or a flag until the owner confirms it through the add form. `name_key` is the counterparty
-- name as `TransactionService.normalise` writes it; `counterparty_iban` is null for a group that has no IBAN.
create table fixed_cost_proposal (
    id integer primary key autoincrement,
    group_key text not null unique,
    scope text not null check (scope in ('private', 'business')),
    name text not null,
    counterparty_name text,
    counterparty_iban text,
    name_key text not null,
    amount_cents integer not null check (amount_cents > 0),
    currency text not null default 'EUR' check (currency = 'EUR'),
    rhythm text not null check (rhythm in ('monthly', 'two_monthly', 'quarterly', 'half_yearly', 'yearly')),
    first_due text not null,
    latest_booked text not null,
    detected_at text not null
);

-- The charges a proposal rests on. The transaction has no `on delete` action, as in V11: only booked rows are read, and the one delete
-- of a transaction removes pending bank rows only.
create table fixed_cost_proposal_charge (
    proposal_id integer not null references fixed_cost_proposal (id) on delete cascade,
    transaction_id integer not null references transactions (id),
    primary key (proposal_id, transaction_id)
);

-- "Not a fixed cost": what the owner rejected is owner-entered data and survives a sync, an import, a restore and a migration (hard
-- rule 10); only the owner's undo deletes a row. A rejection holds the counterparty (the IBAN, else `name_key`) and the amount the
-- proposal had; a later proposal is hidden while its amount is within the matching tolerance of that amount, so a price change beyond
-- it is proposed again. No range is stored: the tolerance is `ReservationService.amountClose`, the one rule.
create table fixed_cost_rejection (
    id integer primary key autoincrement,
    scope text not null check (scope in ('private', 'business')),
    counterparty_iban text,
    name_key text not null,
    name text not null,
    amount_cents integer not null check (amount_cents > 0),
    rejected_at text not null
);
