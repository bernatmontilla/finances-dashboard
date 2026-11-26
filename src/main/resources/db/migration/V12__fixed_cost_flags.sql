-- Flags on fixed costs: a price change and a missing charge (issue #22).
--
-- Both flags are worked out when a page is read and never stored. What is stored is what the owner said about them (hard rule 10),
-- and nothing a sync, an import, a restore of the same data or matching ever deletes:
--
-- `fixed_cost_skipped` is "skip this period": the occurrence of that month (`YYYY-MM`, the month of the expected date) is neither paid
-- nor still to come and is never flagged as missing. A tick of that month deletes the row, as a tick deletes an untick.
--
-- `fixed_cost_price_kept` is "keep the stored amount": the charge (a booked transaction) is accepted at its price, so it is not
-- flagged again. A later charge with another price raises a new flag. The transaction has no `on delete` action, as in V11: only
-- booked rows are looked at, and the one delete of a transaction removes pending bank rows only.
create table fixed_cost_skipped (
    fixed_cost_id integer not null references fixed_cost (id) on delete cascade,
    month text not null check (month glob '[0-9][0-9][0-9][0-9]-[0-9][0-9]'),
    skipped_at text not null,
    primary key (fixed_cost_id, month)
);

create table fixed_cost_price_kept (
    fixed_cost_id integer not null references fixed_cost (id) on delete cascade,
    transaction_id integer not null references transactions (id),
    kept_at text not null,
    primary key (fixed_cost_id, transaction_id)
);
