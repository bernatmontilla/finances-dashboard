# 63: Fixed costs marked paid by a matching transaction

Second slice of #21 (first slice: `21-fixed-costs.md`). A fixed cost that has a counterparty no longer has to be ticked by hand:
when a booked payment that matches it arrives, the month counts as paid and the page shows the transaction.

## Storage (migration V11)

- `fixed_cost_match (fixed_cost_id, month, transaction_id, matched_at)`: one row per occurrence, keyed by the month of the **expected**
  date (a charge booked on 29 Nov for the 1 Dec occurrence links month `2026-12`). A unique index on `transaction_id` makes "one
  transaction marks at most one fixed cost" a database rule, not only an algorithm. It goes with the fixed cost (`on delete cascade`).
  The transaction has no `on delete` action, as in V9: only booked rows are linked, and the one delete of a transaction
  (`TransactionService.deliverFromBank`) removes pending bank rows only.
- `fixed_cost_unpaid (fixed_cost_id, month, unticked_at)`: the owner's "not paid after all" for a matched month. It is owner data
  (hard rule 10): matching and `update` never delete it; only a tick or removing the fixed cost does.
- The link is stored, not worked out when a page is read. A stored link holds across the month boundary, is the same on every read, and a
  GET stays read-only (SQLite has one writer).

## What matches

Only a fixed cost with a counterparty IBAN or name is matched; one without either stays by hand. A transaction matches an occurrence
when it is **booked** (a bank sync can delete a pending row), is spending, is on an account of the fixed cost's scope (archived accounts
included, as #51; its own account when it has one), has the same counterparty (the IBAN when the fixed cost has one, whatever the name;
otherwise the names must be equal after `TransactionService.normalise`, not merely similar), has an amount within a tenth of the fixed
cost's in integer cents (`ReservationService.amountGap`/`amountClose`, the same rule as the reservation candidates) and is booked within
7 days of the expected date, both ends included. Only running fixed costs, and only the occurrences of this month and the next once their
window has begun (`expected - 7 <= today`). Two occurrences of one fixed cost are at least 28 days apart, so their windows never overlap.

`matchPayments()` collects the pairs, sorts them by amount gap, then date gap, then transaction id, then fixed cost id, and assigns them
greedily. It runs at the end of a bank sync run in which an account succeeded, after a CSV import that stored or updated rows, and after
`create`, `update` and `resume`; `update` first drops that fixed cost's links of this month and later (earlier months stay), because the
old terms may no longer fit. Pausing or ending keeps the links. An existing link is never moved, and running it twice stores nothing twice.
A failure inside it is caught after a sync or an import (the rows are stored), logged by class name only (hard rule 11), and the next run
tries again.

## The status of an occurrence

First rule that applies: a tick means paid by hand (`byHand`); an untick (`fixed_cost_unpaid`) means still to come; a link means paid by
a match (`payment`); otherwise still to come. A match never overrides a hand tick, and a hand untick wins over a match. Untick keeps the
link (and the transaction is not offered to another fixed cost); without a link it writes nothing, so a payment that arrives later can still
mark the month.

## Age and stale mark of "still to come" (hard rule 9)

"Still to come" now rests on transactions. Per scope, over the fixed costs that are still to come or paid by a match this month and can be
matched, the accounts involved are the fixed cost's own account, otherwise every account in use of the scope. The page and the overview show
the delivery of the **oldest** of them (the least current source decides) or "No transactions delivered yet", and the stale mark when
one of them is stale (`FreshnessService`). The accounts and the oldest delivery are worked out in `FixedCostService.basis`; the view only
formats. A scope where nothing can be matched shows no age, as in #21: those figures are entered by hand.

## Reservations

A transaction that closed a reservation (#51) may also mark a fixed cost paid. A reservation builds up towards one payment and a fixed cost
repeats; they are different things, and neither changes the headline numbers through the other. `HeadlineService` and `BuildUp` are not
touched (a test checks the headline amount is the same with a matched fixed cost).

## Account links

No form or picker here changes an account link, so the rule of #21 (an edit keeps a link to an archived account) is unchanged.

## Backwards compatibility and tests

V1 to V10 are untouched. An older backup restores and migrates to V11 with no links. The backup format is unchanged; the manifest's
schema version and the restore page's "database version" now say 11 (`BackupServiceTest`, `SettingsBackupPagesTest`, the spec changes
that behaviour). `FixedCostService` takes a `TransactionService`, and `BankSyncService` and `CsvImportService` take the
`FixedCostService` (constructor changes only; their existing tests are unchanged apart from the wiring).

## Left out

Ticking next month's occurrence early (#67), choosing the matched transaction by hand (#68), matching past months (#69), showing a
different matched amount as a price change (#64).
