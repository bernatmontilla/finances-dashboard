# 22: Fixed cost flags: price change and missing charge

First slice of #22 (the rest, proposing fixed costs from repeating transactions, is #71). It builds on `63-fixed-costs-matched.md`.
A fixed cost with a counterparty now says when its latest charge has another price, or when a charge did not arrive. Nothing
changes by itself: every answer is the owner's, and is stored as such (hard rule 10).

## One tolerance, one matcher

A charge is "the same price" when it is within a tenth of the stored amount in integer cents, `ReservationService.amountGap` /
`amountClose`, the rule #63 matches with (stored 10.00: 9.00 to 11.00 matches; 8.99 and 11.01 do not). A charge within it is a price that
varies a little (energy, phone); beyond it, a price change. There is no second threshold, so a charge is never both "matched" and "a
price change", and never neither.

Which transactions belong to a counterparty is decided in one place, `FixedCostService.windowCharges` (booked spending, on the accounts
of the scope or the fixed cost's own account, the IBAN when the fixed cost has one, otherwise the normalised name, in the window of
7 days around the expected date, linked to no fixed cost). `candidates` (matching) filters it by amount; the price change and the
missing charge read it as it is.

## Flags are read, not stored

`FixedCostService.flags()` works them out for every running fixed cost that has a counterparty (not paused, not ended; a fixed cost
ended today has no flags) from the facts around today, per fixed cost and limited to the windows. A GET stays read-only (one SQLite
writer), nothing is cached and nothing is logged.

- **Price change.** Over the occurrences of the previous, this and the next month whose window has begun, the charge is the linked
  transaction or, without a link, the newest unlinked charge in the window. The latest of those counts. It is flagged when it is beyond
  the tolerance and `fixed_cost_price_kept` has no row for that fixed cost and transaction.
- **Missing.** The latest occurrence of the previous or this month whose window has closed (`expected + 7 < today`) is missing when
  there is no tick, no skip, no link (or a link the owner unticked), no unlinked charge of the counterparty at any amount (that is a
  price change) and every account it rests on (its own, else every account in use of the scope) delivered transactions on a day after
  the window closed: before that "not arrived" is not known, and the age line already says so. Not flagged either: an occurrence whose
  window closed before the fixed cost was entered (an old first charge date is entered as it is, and would otherwise flag at once).

## The owner's answers

- **Mark paid** ticks the flagged month (which may be the previous month); any other month is refused (`NOT_FLAGGED`).
- **Skip** (`fixed_cost_skipped`, V12): that occurrence is neither paid nor still to come, not flagged, and matching leaves it alone. Only
  for the month of a missing flag or this month's unpaid charge. A tick of that month deletes the skip, as it deletes an untick;
  **undo skip** deletes it (`NOT_SKIPPED` when there is none). The status order of this month is tick, skip, untick, link, otherwise still
  to come. Monthly and yearly totals do not change.
- **End** is the existing end (today).
- **Take over** sets the amount to the flagged charge through `update`, so with its checks and its re-matching. **Keep**
  (`fixed_cost_price_kept`, V12) accepts that charge at its price; a later charge that differs raises a new flag. Both are refused
  (`NOT_FLAGGED`) unless that transaction is the fixed cost's current price-change flag, so a stale form changes nothing.

Nothing a sync, an import or `matchPayments()` runs deletes `fixed_cost_paid`, `fixed_cost_unpaid`, `fixed_cost_skipped` or
`fixed_cost_price_kept`.

## Changes to #63 (the review of pull request 70)

1. `matchPayments()` also looks at the **previous** month's occurrence (a charge due on 28 Nov and booked on 2 Dec links to `2026-11`,
   or it would show as missing). Older months stay out (#69).
2. `update` drops links only when a field matching uses changed (scope, amount, rhythm, first charge date, counterparty name or IBAN,
   account), and never the link of a month with an untick, so that payment cannot match another fixed cost.
3. The stale map of the fixed costs page and the overview includes archived accounts, so a fixed cost tied to one shows the stale mark
   (hard rule 9). The overview's top line and the headlines keep accounts in use only.
4. A bank correction of a linked booked transaction keeps the link (a link is never moved) and the month stays paid by a match; when the
   new amount is beyond the tolerance the price-change flag shows it.

## Backwards compatibility and tests

V1 to V11 are untouched; V12 adds two tables. An older backup restores and migrates with no skips or kept amounts. The backup format is
unchanged; "database version" is 12 in `BackupServiceTest` and `SettingsBackupPagesTest` (the spec changes that behaviour). The overview line
is outside `#headlines`; `HeadlineService` and `BuildUp` are not touched (a test checks the headline amount is the same with flags).

## Left out

Proposing fixed costs from repeating transactions (#71); categories (M3); notifications about flags (M6).
