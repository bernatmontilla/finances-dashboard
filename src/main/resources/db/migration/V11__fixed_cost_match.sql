-- Fixed costs marked paid by a matching transaction (issue #63).
--
-- `fixed_cost_match` links one occurrence of a fixed cost (a fixed cost and the month of its expected charge date, `YYYY-MM`) to the
-- booked transaction that paid it. The unique index says one transaction marks at most one fixed cost paid. It is written only by
-- FixedCostService.matchPayments and goes with the fixed cost (`on delete cascade`); the link is never moved by a later run.
--
-- `fixed_cost_unpaid` is the owner's word that a matched month was not paid after all. It wins over a match for that occurrence, so
-- matching skips it and only a tick or removing the fixed cost deletes it (hard rule 10).
--
-- Only a booked transaction is ever linked (FixedCostService checks). The one delete of a transaction in the application is
-- TransactionService.deliverFromBank, and it removes pending bank rows only, so it can never reach a linked row; no `on delete`
-- action is declared on the transaction (as in V9), and a delete of a linked row would be refused by the foreign key rather than
-- silently break the link.
create table fixed_cost_match (
    fixed_cost_id integer not null references fixed_cost (id) on delete cascade,
    month text not null check (month glob '[0-9][0-9][0-9][0-9]-[0-9][0-9]'),
    transaction_id integer not null references transactions (id),
    matched_at text not null,
    primary key (fixed_cost_id, month)
);

create unique index fixed_cost_match_by_transaction on fixed_cost_match (transaction_id);

create table fixed_cost_unpaid (
    fixed_cost_id integer not null references fixed_cost (id) on delete cascade,
    month text not null check (month glob '[0-9][0-9][0-9][0-9]-[0-9][0-9]'),
    unticked_at text not null,
    primary key (fixed_cost_id, month)
);
