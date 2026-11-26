# 20: Reservations for upcoming expenses

First slice of issue #20. Closing a reservation against a transaction is issue #51; it adds its own table that points at
`reservation`, so nothing here has to be migrated for it.

## What is stored, and what is not

`reservation` (migration V8) keeps the amount, the due date and an **anchor**: a day and the amount that was reserved on it. "Reserved so
far" is never stored; it is worked out for any day by `finances.money.Reserving` (integer cents, rounded up, `multiplyExact`):
`A` from the due date on, `R0` up to the anchor day, `R0 + ceil((A - R0) * days(S, d) / days(S, D))` between. Rounding up means the
build-up is never below the even share and never shows reserved money as spendable. The limits (at most €100,000,000.00, due at most 50 years
ahead) keep the product far from overflowing.

## The rule for a running reservation

- Create: anchor is today at the typed "already reserved" amount (or 0).
- Changing the amount or the due date re-anchors at today at `min(reserved now, new amount)`. What is reserved stays reserved (never taken
  back on its own) and the rest spreads over the time left. Lowering the amount below what is reserved caps it at the new amount; moving the
  due date later lowers the per-month figure and leaves reserved so far alone.
- Changing only the name, the scope or the yearly flag keeps the anchor.
- Setting reserved so far by hand uses the same anchor. A value below 0 or above the amount is refused.
- A due date is checked only when it is set or changed: an overdue reservation can still be renamed. A due date before today is refused on
  create and change; a reservation is past due only because time passed, and it then stays fully reserved and flagged (never dropped).
- Per month: what is missing over the whole months left (rounded up, at least 1); none once due.

## Closing, reopening, removing

- Close sets `closed_at`. A closed reservation reduces nothing. A yearly one starts next year's round in the same transaction (same name,
  amount, scope; due `D.plusYears(1)`, so 29 Feb becomes 28 Feb; anchor the closing day at 0; `follows_id` the closed one). The round is
  made as the issue says even when the closed one was long overdue, in which case the new round is overdue too; the owner can edit its date.
- Reopen clears `closed_at`. The next round that closing started is removed when untouched (open, `changed_at` null); if it was edited or
  closed, reopening is refused (`NEXT_ROUND_CHANGED`) and nothing changes: two open rounds would reserve twice and an edited round is the owner's
  data. Closing and reopening do not set `changed_at`, so they never make a round count as touched.
- A closed reservation is not edited (`CLOSED`), it is reopened first.
- Remove is the owner's own POST (a button on the edit page; a closed one is reopened first, so history is never one tap from gone). A round that followed it stays; `follows_id` becomes null
  (`on delete set null`).

## Where things live

- `Reserving` (money, pure) has the build-up rule. `ReservationService` (accounts) is the only writer of `reservation` and the only place
  that works out what a reservation stands at today (`ReservationStanding`, `ReservationSummary`), so views never add or compare amounts.
- `HeadlineService.openReservations` returns every open reservation of the scope as a `BuildUp.Reservation` (overdue and €0.00 ones
  included, earliest due date first). `BuildUp` is unchanged. A scope's reserved total can make the number negative; nothing is capped.
- Business reservations can be created and listed. With no after-tax source the business number stays `NO_SOURCE`, and the reservations
  page and the overview say there is no business number to reduce (`HeadlineService.businessSourceConfigured()`).
- Unknown scope: the form's scope code goes through `Scope.parse`, which refuses it with the existing `invalid_scope` message; the service
  also refuses a null scope with its own reason.
- The overview shows, per scope with an open reservation, the reserved total and the next 3 due (overdue first), with a link to the page.
  The overdue flag is on the reservations page and the overview, not in the build-up, whose lines link to the page.

## Compatibility

New table only; V1 to V7 are untouched. The backup is the whole database, so it carries reservations, and an older backup restores with none
(the schema version in two tests moves from 7 to 8). No configuration property, endpoint of the health check, release asset or data-folder
layout changes.
