# Architecture: where bank data comes from

Decided in planning round 1.

- **Three sources per account: `bank` (Enable Banking), `csv`, `manual`.** The app is fully usable
  with `csv` and `manual` alone, so nobody needs a bank API account to run it.
- **Enable Banking** for automatic, read-only (account information only) access. The app registers
  no payment scope and contains no code path that could start a payment.
- **To verify first in M1, on the owner's machine** (not checked in the planning round): that the
  owner's banks are covered, that an individual may link his own accounts free of charge, how
  long a consent lasts per bank, how far back transactions go, and the request limits per day.
  If any of these fails, M1 continues with `csv` and `manual` and the bank source is replanned.
- **Sync is idempotent.** A transaction is stored once, keyed by account plus the bank's own
  reference, or by a hash of date, amount, counterparty and description when there is none. The
  bank's raw record is kept next to the normalised one. A sync only adds or updates bank fields;
  it never touches what the owner entered (PRODUCT invariant 5).
- **Pending transactions** are stored as pending and replaced when booked, never counted twice.
- **Consent expiry is a normal state**, not an error: the account stays, with its history, marked
  stale, until the owner renews.
- **Secrets** (the Enable Banking application key, session ids, the after-tax token) are files in
  the data folder, readable by the service user only, never logged.
