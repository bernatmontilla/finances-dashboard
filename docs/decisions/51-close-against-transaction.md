# 51: Close a reservation against a transaction

Second slice of #20 (see `20-reservations.md`). Also fixes the two review points of pull request 52.

## A column, not a table

`reservation.transaction_id` (migration V9) is a nullable column that references `transactions (id)`. This replaces the sentence in
`20-reservations.md` and the comment in V8 that say the link would get its own table (V8 is merged and not edited). It is null while the
reservation is open and when it was closed without a payment. Several reservations may point at one transaction (a combined payment): there
is no unique index, and nothing is adjusted, so nothing counts twice.

## Which payments can be linked

`ReservationService.close(id, transactionId)` refuses, in this order: an unknown reservation, a closed one (`ALREADY_CLOSED`), an unknown
transaction, a pending one (`TRANSACTION_NOT_BOOKED`), one that is not spending (`TRANSACTION_NOT_SPENDING`), one of an account of the other
scope (`TRANSACTION_OUT_OF_SCOPE`). Booked only, because `TransactionService.deliverFromBank` deletes pending bank rows without a note: a
link to one would break (a test delivers a complete fetch while a booked row is linked). Archived accounts of the scope count: their history
is kept. The time window is not a rule of `close`, only of what the picker offers.

## The picker

`GET /reservations/{id}/close` lists `ReservationService.paymentCandidates`: booked spending of the scope from 60 days before the earlier of
today and the due date, up to today, at most 100 (`TransactionService.bookedSpending`). A candidate is **likely** when its amount is within
10 % of the reserved amount (integer cents, `10 * |paid - reserved| <= reserved`) and its booking date within 14 days of the due date. Likely
ones come first (closest amount, then closest date, then lowest id), the rest newest first. The existing Close button stays and closes
without a payment, exactly as before; the picker is a separate page with plain radio buttons and no script. A blank `transaction` on the POST
closes without one.

Known limit: the window ends today and the list holds the 100 newest rows, so for a reservation that fell due months ago a payment
near the due date may not be offered; closing without a payment still works. Widening it (a search) is issue #59.

## The difference

`paid - reserved`, worked out in `ReservationService.closedWithPayments` (`ClosedReservation`); the view only formats it and picks the text
(more, less, exactly). Nothing else uses it and nothing is adjusted. The link reads the row as it is now: a bank may later change a
booked row's amount, and if that turns it into money received, the page shows no payment and no difference for it.

## Reopening, removing, setting reserved so far

- Reopen clears `transaction_id` in the same update as `closed_at`.
- `ReservationService.remove` refuses a closed reservation (`CLOSED`), so the rule holds for any caller and a link to a payment is never one
  tap from gone. The controller shows the message with status 400. A consequence: a next round can no longer outlive the round it follows
  through `remove`. The #20 test `removingAReservationThatANextRoundFollowsLeavesTheRoundUnlinked` asserted the old behaviour and is replaced
  by one that asserts the refusal (the spec changes that behaviour; `follows_id ... on delete set null` stays for safety).
- `setReserved` is refused from the due date on (`DUE_NOT_SETTABLE`), because the full amount counts as reserved then and the figure would
  change nothing. The edit page shows the reason instead of the form (the same condition as "no per-month figure"). One #20 test set the
  figure on the due day; its assertions are unchanged but the setting now happens before that day.

## Compatibility

Migration V9 only adds a nullable column; V1 to V8 are untouched. The backup is the whole database: an older backup restores and migrates
(the schema version in two tests moves from 8 to 9). No configuration property, health endpoint, release asset or data-folder layout changes.
`ReservationService` takes a `TransactionService` (no cycle).
