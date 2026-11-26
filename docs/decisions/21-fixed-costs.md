# 21: Fixed costs kept by hand

First slice of #21: a list of fixed costs per scope, the totals, "paid or still to come" for the current month (ticked by hand), and
the overview line. Matching against transactions is #63, detection and price changes #64, forecasts and budgets #65.

## Storage (migration V10)

`fixed_cost` holds the name, scope, amount (integer cents, euro), rhythm (`monthly`, `two_monthly`, `quarterly`, `half_yearly`,
`yearly`), `first_due`, an optional counterparty name and IBAN, an optional `account_id`, `paused_at` and `ended_on`. `fixed_cost_paid`
holds one row per fixed cost and month (`YYYY-MM`) that was ticked as paid; it goes with the fixed cost (`on delete cascade`). Only
`FixedCostService` writes either table, and nothing in a sync, an import or a restore touches them (hard rule 10). The whole database
goes into a backup, so both tables are part of it; an older backup restores and is migrated to V10 with no fixed costs
(`BackupServiceTest`). There is no transaction column: #63 adds its own link.

## One anchor date, not "a day" and "a date"

The issue asks for "the day or date it is normally charged". One field covers every rhythm: `first_due` is the first charge, and the
day of the month and (for a longer rhythm) the months follow from it. A cost that starts mid-month is simply a `first_due` in that
month. `ChargeSchedule` counts every charge from `first_due` (`first_due.plusMonths(k * months)`), never from the previous charge, so a
31st falls on the last day of a short month and returns to the 31st; a yearly 29 Feb falls on 28 Feb in other years.

## Ticks are per month

A tick means "paid in this month", not "paid on this date", so changing the day, the rhythm or the amount never loses it (hard rule 10),
and no rhythm charges twice in a month. Ticking and unticking apply to the current month only, and are refused for a fixed cost that is
paused, ended or has no charge this month. A tick for a month that passed stays in the table; the page does not show it.

## What "still to come" means without matching

A fixed cost with a charge this month and no tick is still to come, **also when its charge date has already passed**: without matching
nothing else says it was paid, and "did not arrive" belongs to #64. The page and the overview show the expected date and, once it has passed, the words "date passed, not marked as paid" (`FixedCostMonth.overdue`), so the total is not read as more certain than it is. A fixed cost with no charge this
month is neither paid nor to come; the page shows when it is next due (the first charge after this month).

## Totals

Per scope, over the running fixed costs (not paused, not ended): per year = the sum of `amount * (12 / months)`,
exact; per month = per year / 12 **rounded up** to whole cents, as `Reserving` rounds, so a cost is never shown lower than it is.
Paused and ended ones are listed but never added.

## Pause, end, resume, remove

- Pause sets `paused_at`; resume clears it. End sets `ended_on` (the given date, today by default; not before `first_due`).
- `ended_on` is the last day a charge may fall (so a charge on the end date itself is still expected and can be ticked). A fixed cost is
  **ended** once `ended_on` is before today (the spec said "on or before today"; changed after the risk check, because the two
  readings of the end date disagreed): it is listed under *Ended*, read-only (edit, pause, remove and tick are
  refused), and not counted. An end date still ahead leaves it running until then; a charge after the end date is never expected.
  Resume clears `paused_at`, and `ended_on` only when the fixed cost has ended (an end date still ahead stays). The end form has no date
  field: it ends today; the service and the POST take a date.
- Remove is only for a fixed cost that has not ended.
- Editing an account link: an account that was archived after it was linked stays linked; a new account must exist, be in use and be
  of the same scope.

## Overview

Per scope with at least one fixed cost still to come: a `<details>` block (no script) outside `#headlines`, after the reservation
groups. The summary reads "Fixed costs still to come this month, Private: €X"; inside, each one with its amount and expected date, and a
link to the page. `HeadlineService` and `BuildUp` are not touched: a test checks that the headline amount is the same with and without
fixed costs (PRODUCT.md: shown beside the headline numbers, not subtracted).

## Figures without an age

Hard rule 9 binds amounts that come from a source. A fixed cost is entered by hand, like a reservation, so its figures show no age.
The matching slice (#63) relies on transactions and must show their age.

## Reservation or fixed cost

Both pages carry the same one-line hint (a reservation builds up towards one payment; a fixed cost repeats) with a link to the other,
and the README explains it. Nothing links the two data-wise: a reservation and a fixed cost for the same expense are the owner's choice.
