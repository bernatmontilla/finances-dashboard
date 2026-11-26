# 51: Close a reservation against a transaction

Second slice of #20; read [20-reservations.md](20-reservations.md) first.

## What changed

- **A column, not a table.** Migration V9 adds a nullable `reservation.transaction_id` referencing `transactions (id)`. Decision 20
  and the comment in V8 expected a separate table; one closed reservation has at most one payment, so a column is enough. V8 is not
  edited; V1 to V8 are untouched, and a backup made at database version 8 restores (the column is null for every row).
- **Booked transactions only.** `TransactionService.deliverFromBank` deletes pending bank rows that have no note, and foreign keys are
  enforced: a link to such a row would make a sync fail. A booked row is never deleted and never goes back to pending.
- **Checks when closing**, in this order: the reservation is open (`ALREADY_CLOSED`), the transaction exists, is booked, belongs to an
  account of the reservation's scope (checked at closing time only: an account's scope may change later and the link must not be
  lost), and is spending (amount below zero). A transaction outside the picker's window is accepted if it passes these; the window
  only limits the list. One transaction may close several reservations: nothing is adjusted, so nothing is counted twice.
- **The picker** lists booked spending of the scope's accounts (archived ones included, their history is kept), booked from 90 days
  before today up to today, at most `TransactionService.PAGE_SIZE` (50). A *likely match* is a payment whose amount is within a tenth
  of the reserved amount (`10 * |paid - reserved| <= reserved`, integer cents) and whose booking date is within 14 days of the due
  date. Likely ones come first (closest amount, then closest date), the rest newest first. `ReservationService.rank` is pure.
- **Paid and difference.** Paid is the transaction's amount negated; the difference is paid minus the reservation's amount (not
  "reserved so far", which is never stored). Positive: paid more; zero: as reserved. Worked out in `ReservationService.payment`, the
  view only formats it.
- **Closing and reopening.** Closing a yearly reservation against a transaction starts next year's round exactly like a plain close.
  Reopening clears `closed_at` and `transaction_id` in one update; a reopening refused with `NEXT_ROUND_CHANGED` changes nothing and
  keeps the link. Neither sets `changed_at`.

## The two rules from the review of #52

- `ReservationService.remove` refuses a closed reservation (`CLOSED`), so the rule "reopen first" holds whatever calls it; the
  controller shows the list again with status 400. Reopening removes or refuses the next round, so an open reservation never has a
  following round: the old test `removingAReservationThatANextRoundFollowsLeavesTheRoundUnlinked` tested a path that can no longer be
  reached and is replaced by one that asserts the refusal. This is a spec-driven change of an existing assertion (hard rule 3).
  (`follows_id ... on delete set null` stays as a safety net.)
- `ReservationService.setReserved` refuses `ALREADY_DUE` from the due date on, because the full amount counts from then on and the
  anchor would have no effect. The edit page does not offer the form for a due reservation.

## Compatibility

Added only: one migration, one nullable column, new reasons. No configuration property, endpoint, release asset or data-folder layout
changes. Nothing new is logged.
