# 10: Linking banks through Enable Banking and syncing them

Built from `docs/decisions/6-enable-banking.md` and the lead's decisions on issue #10. Nothing here was tried against a real
account: the tests run against a fake Enable Banking server made from the fixtures of #6 (`src/test/java/finances/sync/
FakeEnableBanking.java`). Trying it with a real account is the owner's acceptance check (list at the end of #6).

## Where things are

- `finances.outbound`: `EnableBankingClient` (the only code that opens a connection; JDK `HttpClient`, one configured host,
  https unless loopback, redirects never followed, RS256 token made with the JDK: no new dependency), `ApplicationCredentials`
  (the two files, PKCS#8 and PKCS#1 keys), `BankApiException` (a reason and the API's text code, never a bank message).
- `finances.sync`: `BankLinkService` (link, confirm, renew, unlink), `BankSyncService` (a run), `SyncScheduler` (the daily run),
  `BankMapping` (JSON to values), `BankStore` (SQL), `SessionFiles` (secret session ids), `BankStatus` (what pages read).
- `finances.accounts`: `TransactionService.deliverFromBank` and the takeover rule, `AccountService.addFromBank / attachToBank /
  detachFromBank / addBankBalance / latestBankBalance`.
- Migration `V5__bank_link.sql` (V4 is in open pull request 31; if the numbers collide after a rebase, renumber this one).

## Decisions

- **Pending becomes booked** (lead's option A): one method, `TransactionService.pendingToTakeOver`, with its tests next to it. The
  7 days are `PENDING_TAKEOVER_DAYS`, tested on each side. The batch bug of the issue is fixed in the same place: rows the batch
  delivered or matched (`Tally.touched`) are never taken over. A pending item is dated by `transaction_date`, else `value_date`,
  else the day of the fetch.
- **Pending rows the bank stops reporting**: after a complete fetch, pending bank rows dated on or after the start of the fetched
  period (all of them for the first sync) that the fetch neither delivered nor took over are removed (no note) or kept and shown
  "no longer reported by the bank" (note). Column `transactions.no_longer_reported_at`, cleared when delivered again. A failed
  fetch calls nothing. Known limit: a pending row older than the fetched period (last success minus 7 days) that the bank drops
  later stays pending.
- **A fetch is all or nothing**: balances, then all pages of transactions (collected in memory), then one short database
  transaction: success time, balance, delivery, run record. Any unreadable item (signed amount, unknown direction, bad date, a
  never-ending continuation key) fails the whole fetch with `unexpected`, so nothing is guessed and nothing is removed.
- **No retry loop.** One attempt per account per run; rate limit is `rate_limited`, the next run tries again. One account's failure
  does not stop the others. When a consent has ended, its other accounts are not asked.
- **Two runs never overlap**: one lock. The daily run and "Sync now" answer *busy* at once; the first sync of the link flow waits up
  to two minutes (the history is only there for about an hour); unlinking waits up to 30 seconds.
- **Daily and user-started fetches**: the daily run sends no PSU headers (a background fetch). "Sync now" and the link flow send the
  user's own headers (`Psu-Ip-Address`, `Psu-User-Agent`, and the others the request has) when **all** headers the bank lists can be
  given, else none (a bank answers 422 to a partial set). Each run records which it was, and the settings page says so. Never logged.
  Unknown: whether a bank accepts the address of a client on a private network as `Psu-Ip-Address`.
- **The first sync runs when the owner confirms an account** (inside the link flow, before anything else is done), not at the next
  daily run, with `strategy=longest`. The confirmation page says to confirm within the hour. A renewal re-attaches and syncs at
  once. A first sync that failed is a first sync again (`last_success_at` stays empty).
- **Matching across consents**: by `identification_hash`, else IBAN when either side lacks a hash; never by `uid` or session id. Any
  authorisation that returns an account already linked moves it to the new consent (Renew or linking the same bank again); an
  old consent left without accounts is closed at the bank (`DELETE /sessions`, best effort) and forgotten.
- **Consent states**: `authorized`, `expired`, `revoked` (both look like "consent expired" with *Renew*). Found by the date, by a
  missing session file (a restore on another machine), by `GET /sessions/{id}` before a fetch, or by the error code during one. An
  ended consent's sync stops silently; the run that finds it records `consent_expired` for each account once.
- **Secrets**: `<data>/secrets/enable-banking-application-id`, `enable-banking-key.pem`, `enable-banking-session-<consent id>` (0600
  where the file system allows). Session ids are not in the database or a backup; a restored database shows its consents expired.
- **Redirect and state**: `finances.sync.redirect-url` (empty = linking is off, with a message). The `state` is random, kept in memory,
  single use, 30 minutes. An unknown state, an error or a missing code stores nothing. Nothing the bank sent as text
  (`error_description`) is read or shown.
- **An empty account list** after authorisation stores nothing, closes the session and shows one line saying where to link accounts.
- **Balances**: by `balance_type`, preferring CLBD, ITBD, OPBD, then the rest in a fixed order, euro only. Stored in `balance` with
  `balance_type`, `as_of`, `as_of_basis` (`bank_time` timestamp, `bank_date` reference date only, `fetched` the bank gave no age: shown as
  "fetched", never "correct as of"). A balance equal to the newest bank one (type, amount, bank time) is not stored again. A debt is
  stored as the amount owed, whatever sign the bank gave it. Manual balances are untouched. The stale marking is the next issue.
- **Accounts**: created only after confirmation (name, scope, kind) or attached to an existing account that is not archived and has no
  bank link (same IBAN proposed). Attaching sets the source to `bank`, keeping history and notes. Unlinking sets it back to
  `manual`. Only euro accounts (`XXX` and others are listed as not supported). A business account can be linked if the owner wants
  to; the page says the terms cover personal use, and the link always sends `psu_type=personal`.
- **Unlink**: the session is closed at the bank as far as it works; the local removal happens either way.
- **Logging**: counts and exception class names only; guard tests keep exceptions out of log calls and payment strings out of the
  code. A test captures the log at DEBUG during link, syncs and unlink and finds no token, session id, uid, IBAN, counterparty, name,
  amount, hash or header.
- **Scheduler**: a daemon thread, `finances.sync.time` (HH:mm, default 04:30, the owner's zone). A run missed because the application
  was off is not made up; the next fetch starts at the last success, so nothing is lost.

## Properties and files (all new, nothing existing changed)

`finances.outbound.enable-banking.base-url` (default the API host), `finances.outbound.timeout-seconds` (30), `finances.sync.redirect-url`,
`finances.sync.time`, `finances.sync.enabled`, `finances.sync.overlap-days` (7). Settings page: *Bank connection*; overview: *Sync now*.

## Not done / known limits

- No notification before a consent expires, no stale marking on the overview, no pasted-callback fallback if Enable Banking refuses a
  private redirect address.
- Business accounts: not prevented, not supported by the terms.
- The tests changed two numbers: the database version in `SettingsBackupPagesTest` and `BackupServiceTest` (3 to 5), because a
  migration was added. No other existing assertion changed.

- Attaching an account that holds CSV-imported transactions shows the overlap twice (the page says so).
- The overview and lists note a bank balance that is not a booked type (its type) or whose age the bank did not give ("fetched"); the stale marking itself is the next issue, so an expired consent is not yet marked stale there (the account page does say so).

## Found in review and fixed

- A balance may be negative (an overdraft, a loan: no credit/debit indicator); a transaction amount may not. Only balances parse a sign.
- A pending item with no date of any kind keeps the day it was first seen (`TransactionService.pendingBankDate`), else its fingerprint would change daily and the owner's note would stay on a ghost row.
- The store step re-checks the link (`markSuccess` only when consent, uid, account and last success are unchanged): a restore, unlink or renewal during a fetch stores nothing (`store_failed`).
- A second confirmation of the same offered account rolls back (no orphan account).
- Merge order: V5 skips V4 of pull request 31; merge 31 first, and renumber the later one if the order is ever reversed (Flyway refuses a lower version that was never applied).
