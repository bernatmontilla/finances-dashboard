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
  oldest pending one **without a reference and with the same fingerprint** and becomes it (status and
  reference are set; one row, counted as updated). Without a reference the pending one is found by
  fingerprint directly. A booked row never goes back to pending, whatever a late delivery says.
- **Known limit, on purpose**: the fingerprint includes the booking date, so a pending transaction whose
  bank later books it **under another date, without a reference both times**, is stored twice. Matching
  across dates would guess. A pending item that is delivered again after its booked twin was stored
  under a reference (a stale re-delivery) is stored as a new pending one; real syncs do not return
  pending items that are booked.
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
