# #8 Transactions store that never keeps the same transaction twice

- **Where**: `finances.accounts.TransactionService` (next to the accounts, so the `risk:high` glob
  `src/*/java/finances/accounts/**` already covers it). `V3__transactions.sql` adds the tables
  `transactions` and `transaction_note` and the column `account.transactions_delivered_at`; V1 and V2
  are untouched. Pages: the account page (`/accounts/{id}?page=N`) and `/transactions?page=N`.
- **One entry point**: `deliver(accountId, source, batch)` returns a `DeliveryReport(added, updated,
  known, refused)`. It takes a `Stream` (or a collection) and reads it one element at a time; the only
  thing kept is a count per fingerprint. One database transaction (`TransactionTemplate`, not an
  annotation, so a test on a plain data source gets the same rollback). The first statement is a write
  (the account's delivery time), so SQLite's single writer is taken up front instead of upgrading a
  read lock halfway, which fails at once under WAL.
- **Identity**:
  - With a reference: account + reference (partial unique index).
  - Without: account + fingerprint + `occurrence`, the position among identical fingerprints in the
    batch (partial unique index, only rows without a reference). Two identical transactions in a batch
    are both kept; the same batch again adds nothing; a bigger batch adds only the extra ones.
  - Fingerprint = SHA-256 of booking date, cents, counterparty and description, the two texts trimmed,
    white space collapsed and lower-cased. The counterparty IBAN is not part of it (spec).
- **Pending then booked**: a booked transaction with a reference that is not known first looks for the
  oldest pending one of the account **with the same amount, counterparty and description (normalised)**,
  dated on or before the booking date and at most `TransactionService.PENDING_TAKEOVER_DAYS` (7) days
  before it, **whether it has a reference of its own or not**, and becomes it (status, date and
  reference are set; one row, counted as updated). *Changed in #10* (`10-bank-sync.md`): this used to
  need the same fingerprint, i.e. the same booking date. A pending row that the same batch delivered or
  matched is never taken over, so an identical second purchase stays a second row. Without a reference
  the pending one is found by fingerprint directly (date exact). A booked row never goes back to
  pending, whatever a late delivery says. A row that was claimed this way keeps its place among the
  identical ones (`claimed_pending`), so pending deliveries of the siblings still left over line up
  with their own rows whatever the order of the batch (a test covers both orders).
- **Known limit, on purpose**: a booked transaction **without a reference** is matched by its
  fingerprint, which includes the booking date, so a pending one that is booked under another date
  **without a reference both times** is stored twice (the takeover above needs a reference on the
  booked one). *Changed in #10*: a booked delivery **with** a new reference no longer has this limit.
  A pending item that is delivered again after its booked twin was stored under a reference **with
  another reference than the pending one had** is stored as a new pending one; real syncs do not
  return pending items that are booked.
- **Pending rows the bank stops reporting (#10)**: `TransactionService.deliverFromBank`, used only by
  the bank sync, removes (no note) or keeps and marks (`no_longer_reported_at`, with a note) the
  pending bank rows a complete fetch did not mention. `deliver` still never deletes.
- **A pending delivery never touches a booked row** (it only bumps `last_seen_at`), and a delivery
  without a reference never erases the reference a row has.
- **One source per account**: a delivery without a reference matches rows without one (and claimed
  ones), not rows that have a reference of their own. Mixing a bank and a CSV import of the same
  transactions on one account is not deduplicated; an account has one source (`account.source`).
- **For the manual path (later)**: a delivery that fingerprint-matches a row with `source = 'manual'`
  would rewrite its source fields. No code enters transactions by hand yet; when one does, it must be
  excluded from source updates (the note is already safe).
- **The batch is read inside the write transaction**: a caller passes a file or memory, never a stream
  that waits on the network (a sync collects its pages first).
- **What a delivery writes**: only source fields (dates, amount, counterparty, description, status,
  source, reference, raw, last seen). The owner's note is in `transaction_note`, written only by
  `setNote`; category and split will join it there. `first_seen_at` is set once.
- **Refusals**: an archived account (`ARCHIVED`), an unknown one (`NOT_FOUND`); a currency other than
  EUR is counted as `refused` and the rest is stored. A transaction without a booking date or status
  cannot be built (`INCOMPLETE`), so a broken source fails the whole batch rather than half of it.
  The delivering `source` is recorded as given; it is not checked against `account.source`.
- **Amounts** are `long` cents; `DeliveredTransaction` carries the currency next to them because `Money`
  would refuse a foreign currency at construction, which would fail the batch instead of counting it.
- **Delivery time**: `account.transactions_delivered_at` is set by every accepted batch, even an empty
  one or one that held only known transactions: "last delivered" is when the source last answered.
  Pages show the day and its age. There is **no stale limit yet** (as for manual balances in #7): the
  date and age are shown; marking something stale comes with sync and import.
- **Listing**: `booking_date desc, id desc`, 50 per page, `limit 51` to know whether more follow; no
  total. Out-of-range or unreadable `page` values show the first page or an empty page, never an error.
  The all-transactions page includes archived accounts' history.
- **Logging**: nothing in the service logs. A test captures the log at DEBUG (Spring's JDBC and
  transaction logs included) during batches, refusals and rollbacks and checks no amount, IBAN,
  counterparty, description or reference appears. Exceptions name the reason only.
- **Not done**: CSV parsing, bank sync, categories, transfers, search, balances from transactions,
  a web form for the note (the field and its store exist; editing is for a later issue).
- **Backwards compatibility**: only an added migration; the data folder layout, backup format, health
  endpoint and property names are untouched. (Backup of the new tables belongs to the backup issue.)
