-- Fixed cost proposals and a kept price that knows its amount (issue #71).

-- `fixed_cost_price_kept` said only which transaction the owner accepted at its price. A bank can correct that transaction's
-- amount later, so the row now also holds the amount that was kept (positive cents); a kept row counts only while it equals the
-- transaction's current amount. Rows from V12 are filled in from their transaction.
alter table fixed_cost_price_kept add column amount_cents integer;
update fixed_cost_price_kept set amount_cents = (select -t.amount_cents from transactions t where t.id = fixed_cost_price_kept.transaction_id);
