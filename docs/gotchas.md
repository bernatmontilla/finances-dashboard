# Gotchas

Things that cost a session time once. One line each, grouped by area, read only the part about
what you are changing. Add a line when you hit a new one (in the same PR). When the same gotcha
bites twice, turn it into a guard test that scans the code or the docs and names file and line, and
mention the test here.

## Money and bank data

- A bank's "balance" comes in several types (booked, available, expected); store which one, and
  show the booked one unless a spec says otherwise.
- The same transaction can arrive first as pending and later as booked with another reference.
- SQLite allows one writer: keep write transactions short, and never hold one across an HTTP call.

- Transactions are stored only through `TransactionService.deliver` (#8); it never writes the owner's
  note, so a new hand-entered field goes in its own table, not in `transactions`.
- CSV import (#9) reads a file with its own decimal separator and hands the amount to `Money.parse`; a
  row that cannot be read is refused with a reason and the row number, never repaired. An export in
  Windows-1252 must not sit in `src/test/resources` (the guard tests read every file there as UTF-8):
  build such bytes in `CsvFixtures`. A `>` inside a Thymeleaf attribute breaks the text guard: write `gt`.
- A pending item that the bank books under another date **and gives no reference for the booked one** is
  stored twice: without a reference the fingerprint includes the booking date. With a reference the booked
  one takes over the matching pending row (same amount, counterparty, description, at most 7 days earlier):
  `docs/decisions/8-transactions-store.md`, `10-bank-sync.md`.
- Only the bank sync deletes a transaction, and only a pending one of source bank that a complete fetch did not
  report (`TransactionService.deliverFromBank`); never call it after a failed or partial fetch.

- Backup and restore: `VACUUM INTO` is the consistent snapshot while the app writes (never copy `finances.db`
  while it runs: the WAL file holds committed data). A restore replaces the file, so every open connection
  must be closed first: all connections come from `GatedDataSource`; a new place that opens SQLite on its
  own must take the gate too (`docs/decisions/12-backup-and-restore.md`).
- Anything that holds the owner's data while it travels (an upload, a snapshot) goes in `<data>/tmp/`
  (`DataFolder.SCRATCH_FOLDER`), never in the system temporary folder.

## Enable Banking (documented, not yet tried against a real account: `docs/decisions/6-enable-banking.md`)

- Restricted mode (own accounts, free) returns only the accounts linked in the Control Panel; any
  other account is stripped and the result is an **empty list**, not an error. Whitelist first.
- The Terms limit production use to personal use; business accounts are only tolerated as
  "evaluation" and can be switched off with notice.
- Renewing a consent gives a new session id **and new account `uid`s**; match accounts by
  `identification_hash` (or IBAN), never by `uid`.
- Full history is available only about an hour after the authorisation, then often 90 days:
  run the first sync inside the link flow with `strategy=longest`.
- `entry_reference` is unique per account, often only on booked items, sometimes missing or
  duplicated; pending items usually have none. `transaction_id` is never an identity.
- Decide the balance by `balance_type` (`CLBD` = booked), never by its `name`: the reference's own
  example labels a `CLAV` balance "Booked balance".
- Amounts are unsigned decimal **strings**; the direction is `credit_debit_indicator`. Currency
  `XXX` means multi-currency or unknown: not supported.
- A scheduled fetch (no PSU headers) is a "background" fetch: many banks allow about 4 a day per
  account; 429 `ASPSP_RATE_LIMIT_EXCEEDED` means wait six hours. PSU headers are all-or-none for the
  headers the bank lists, else 422 `PSU_HEADER_NOT_PROVIDED`.
- Base logic on the `error` text code, not the HTTP status (a 401 has several causes); an empty
  transactions page can still carry a `continuation_key`, and the other parameters must stay equal.
- `POST /sessions` returns account details only once; store IBAN, name and currency then.
- The bank list returns logo URLs on another host; never load them in a page (hard rule 7).
- A user's address behind a reverse proxy is the proxy's (loopback): `Psu-Ip-Address` is left out for loopback, link-local and unspecified addresses, and a bank that requires it then gets a background fetch (`BankViews.personsAddress`, #39).
- Secrets are files in `<data>/secrets/` (application id, key, one session file per consent), never in the
  database or a backup. A consent row without its session file means "restored on another machine": it is
  treated as expired. A new secret file goes in the table of `docs/install.md`.
- A sync fetches everything first and stores in one short transaction: never a database transaction open
  during a request to the bank, never a partly stored fetch. A transaction that cannot be read fails the whole
  fetch (otherwise a pending row that was not delivered would be removed).
- The Enable Banking `state` of a link attempt is kept in memory only: a restart in the middle of a link
  abandons it (nothing was stored). The callback is the one GET that stores something.
- A migration changes "database version" in two existing tests (`SettingsBackupPagesTest`, `BackupServiceTest`);
  update the number there when you add one.

- Staleness (#11): the rules are `finances.money.Freshness`, the facts come from `finances.sync.FreshnessService`; never decide "stale" in a
  template or a view. A new place that shows a balance shows its age and the `stale` fragment. The overview's line at the top counts the
  accounts of `accounts.list(false)` only (archived ones are not marked). A test that depends on "now" builds its data from the clock.

- After-tax source (#18): the request is `finances.outbound.AfterTaxClient` only; the whole exchange (headers and body) has the 10 s cap
  because `HttpRequest.timeout` stops at the headers: the body is read on a second thread that is cut off by closing the stream. A fetch
  runs on its own thread and flag, never under `BankSyncService`'s lock. A message containing an apostrophe is HTML-escaped in a page, so
  a test that looks for the text of a message must avoid it.

- Headline numbers (#19): the definitions of Private free to spend and Business available are `finances.money.BuildUp` only; a page, a
  template or a view never adds, subtracts or compares amounts. The after-tax "current" value is picked by the `as_of` instant, never by
  the receipt order or by SQL text order. A record that has a method `isX()` and a component `x` shows the boolean to a template reading
  `.x`: name the method `hasX()`.

- Reservations (#20): "reserved so far" is never stored, it is `finances.money.Reserving` from an anchor (day plus amount); only
  `ReservationService` writes `reservation` and works out what a reservation stands at today, so a view never adds amounts. Anything that
  changes a reservation's amount or due date must re-anchor (`docs/decisions/20-reservations.md`).

## The target machine

- Low memory, possibly shared with other services: the heap cap is real. Stream large result sets.
- arm64: a dependency with native code (sqlite-jdbc) must ship an aarch64 Linux binary.

## Tests and CI

- Thymeleaf's `~{fragments :: nav}` also selects every other `<nav>` element in that file, not only the
  one with `th:fragment="nav"`: never put another `<nav>` in `fragments.html`. Methods read by a template on a package-private record must be `public`.

- On a CI failure, read the check-run annotations:
  `gh api repos/{owner}/{repo}/check-runs/<job id>/annotations`.
- A test that depends on today's date breaks on the first of the month: build the data relative to
  now, not a fixed date.

## Pull requests and labels

- A PR that conflicts with `main` gets no `CI` and no *Claude review*, not even when it is marked
  ready: GitHub starts no `pull_request` workflow for it. Check `mergeable` before marking ready.
  After the rebase the push starts `CI` but not the review (it starts only on opened, ready and
  labelled): add `re-check` (remove it first if it is there) once the PR merges cleanly.
- The labeler runs as `pull_request_target`, which uses `main`'s `labeler.yml`: a PR that adds a
  risky path labels only the PRs after it is merged, not itself.

## Git and GitHub from a Claude cloud session

- GraphQL is blocked: `gh pr create`, `gh pr edit`, `gh pr ready`, `gh issue view` fail. Use REST:
  - read an issue: `gh api repos/{owner}/{repo}/issues/N`
  - open a draft PR: `gh api repos/{owner}/{repo}/pulls -f title=… -f head=… -f base=main -F body=@file -F draft=true`
  - labels: `gh api repos/{owner}/{repo}/issues/N/labels -f 'labels[]=release:patch'`
    (remove: `gh api -X DELETE repos/{owner}/{repo}/issues/N/labels/<name>`)
  - ready for review: `gh api -X POST repos/{owner}/{repo}/pulls/N/ccr/ready_for_review`
- Shallow clone: `git fetch origin main` before pushing, else the push re-sends everything (HTTP 413).
  Rebasing a PR branch: read its old tip with `git ls-remote` and push with
  `--force-with-lease=<branch>:<old sha>`.
- Never `pkill -f` with a pattern contained in the command itself: kill by PID.

## Build environment

- The Maven wrapper downloads Maven with `wget`/`curl`; in a cloud session wget needs
  `ca_certificate=/root/.ccr/ca-bundle.crt` in `~/.wgetrc` (see `/root/.ccr/README.md`).
- A cloud image may have only JDK 21: `apt-get install openjdk-25-jdk-headless` and set `JAVA_HOME`.
