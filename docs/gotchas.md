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

## The target machine

- Low memory, possibly shared with other services: the heap cap is real. Stream large result sets.
- arm64: a dependency with native code (sqlite-jdbc) must ship an aarch64 Linux binary.

## Tests and CI

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
