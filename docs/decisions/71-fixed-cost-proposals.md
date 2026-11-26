# 71: Proposing fixed costs from repeating transactions

Second slice of #22 (the flags are `22-fixed-cost-flags.md`). It builds on `63-fixed-costs-matched.md`. After each sync and import the
app looks for booked spending that repeats like a fixed cost and lists it as a proposal. A proposal never changes a number or a flag
until the owner adds it through the ordinary add form; a rejection is the owner's data (hard rule 10).

## The rule (`RepeatingCharges`, pure code)

Booked spending of one counterparty and scope, oldest first. For each rhythm, monthly to yearly, the run starts at the newest charge and
walks back one charge at a time, never skipping one. The next older charge joins when

- it is booked within `FixedCostService.MATCH_DAYS` (7) days, both ends included, of the date one rhythm before the charge just taken, and
- its amount is within `ReservationService.amountClose` of the newest amount.

The walk stops at the first charge that does not join. A rhythm fits when the run has at least 3 charges (2 for half-yearly and yearly)
and the group still runs: the newest charge plus one rhythm plus 7 days is not before today. The first rhythm that fits wins.

**Thresholds chosen (the issue suggested rhythm ± 5 days and the median).**

- *7 days, not 5.* The matching of a fixed cost accepts a charge within 7 days of its expected date. A charge that detection accepts is
  one that the confirmed fixed cost then matches; with 5 a charge on day 6 would be "off" for detection and "on" for matching. One
  constant for "about the date".
- *The newest amount, not the median.* Matching and the price-change flag compare with the stored amount. If the proposal held the
  median, a just-confirmed proposal could flag its own newest charge. The tolerance itself is the one of #22 (a tenth in integer
  cents): no second threshold for "the same amount".
- *Nothing more than once in a gap* follows from walking without skipping: a charge in between breaks the run (weekly groceries never
  qualify). Two equal charges within one window are therefore not a run either.
- *Still running.* A group whose next charge plus the window is already past is not proposed: it stopped.

## What is read

Booked spending (`amount_cents < 0`) on every account, archived ones included, from today minus 25 months (enough for two yearly
charges that still run, and a bound on the work). The rows are streamed in `booking_date, id` order through a row callback, never
loaded as a list of `Transaction`. Left out: a payment whose counterparty IBAN (compacted, upper case) is one of the installation's own
`account.iban` (a transfer, archived accounts included), and a payment with neither an IBAN nor a name. A group is scope plus the IBAN,
or without one the normalised name (`TransactionService.normalise`); the same counterparty on a private and a business account is two
groups.

## Storage (V13)

- `fixed_cost_proposal` (one row per group, `group_key` unique so an id stays the same from run to run), and
  `fixed_cost_proposal_charge` (the run's transaction ids). Derived data: `detect()` replaces them. It reads first and then writes in one
  short transaction that takes the single writer with a no-op update (as `matchPayments`). It writes no owner table; running it twice
  stores the same rows.
- `fixed_cost_rejection`: scope, counterparty (IBAN, name key), name and the rejected amount. Only the owner's undo deletes a row.
- `fixed_cost_price_kept.amount_cents`, filled in from the transaction for existing rows (review fix 2 of #72).

## What is never proposed

A group is not proposed (checked in `detect()` and again when the proposals are read, so a fixed cost created a second ago hides its
proposal at once) when

- a fixed cost of the same scope, running, paused or ended, has its counterparty: the same IBAN, or, when the fixed cost has no IBAN, a
  name equal after normalising (on read, the newest charge's name); an ended one blocks too, so the owner resumes it instead of getting a
  duplicate;
- a rejection of the same scope and counterparty has an amount within `amountClose` of the proposal's. This is the "amount range" of the
  issue without a second threshold and without storing a range: a price change beyond a tenth proposes the counterparty again.

Removing a fixed cost brings its proposal back at the next detection, not at once.

## The pages

`GET /fixed-costs/new?proposal={id}` fills the add form (name, scope, amount, rhythm, first charge, counterparty, IBAN; the account stays
blank); posting it is the unchanged `create`, with its checks and `matchPayments()`. A proposal that is no longer open gives the empty
form, status 400 and `error.fixedcost.proposal_not_open`. Reject and undo are POST actions (undo runs `detect()` so the proposal is back
at once). The list shows each proposal with the booking date of its latest charge (hard rule 9; a past fact is never stale). The
overview line counts open flags plus open proposals.

## Review fixes of #72

1. The missing flag also needs, for every account it rests on, a booked transaction dated after the window closed
   (`Context.newestBooked`), besides the delivery time: a late import of old transactions no longer raises a false flag.
2. **Keep** stores the kept amount; a kept row counts only while it equals the transaction's current amount.
3. The price-change flag carries the booking date of the charge and the text shows it. The text changed, so the assertions that matched
   "The latest charge was" now match "The latest charge, booked <date>, was" (the spec changes that behaviour).

## Changed tests

`BackupServiceTest` (`schemaVersion=13`) and `SettingsBackupPagesTest` ("database version 13"): a migration was added. An older backup
(V12) restores and migrates to V13 with no proposals and no rejections (`MigrationV13Test`).

## Left out

Two fixed costs of one counterparty at different amounts (#22's known limit), proposing the account, listing the proposal's charges
(#73, #74), income detection and forecasts (M4), categories (M3) and notifications (M6).
