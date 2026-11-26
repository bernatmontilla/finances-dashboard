# 18: The after-tax source

Built from issue #18 on top of `docs/contracts/after-tax-source.md` (version 1, not changed except the clarification below),
`10-bank-sync.md` (one short transaction after the fetch) and `11-stale-figures.md` (the age and the stale mark).

## Where things are

- `finances.outbound`: `AfterTaxClient` (the only code that calls the source), `AfterTaxToken` (the token file), `AfterTaxException`
  (a short reason code and a status number, never a text from the source).
- `finances.sync`: `AfterTaxMapping` (pure: body to value or one refusal code), `AfterTaxStore` (the SQL), `AfterTaxService`
  (starts and runs a fetch, stores, tells the pages), `AfterTaxValue`, `AfterTaxFetchRow`, `AfterTaxReason` (the stored codes).
- `finances.money.Freshness.afterTax(...)` and the limit `finances.freshness.after-tax-hours` (default 48) in `Freshness.Limits`.
- `finances.web`: `AfterTaxViews`, the section in `settings.html`, `POST /settings/after-tax/test`; `AccountViews.stale` maps the two new
  reasons.
- Migration `V7__after_tax.sql`: `after_tax_value`, `after_tax_reserved`, `after_tax_fetch`.

## Decisions

- **Configuration.** `finances.outbound.after-tax.url`, empty means no source. A set value must be an absolute `http` or `https`
  address with a host and no user info, query or fragment, else the start stops with a message naming the property and never the value.
  Any host the installer configures is allowed (hard rule 7: the installer's configuration is what allows it); a token over plain `http`
  to a host that is not this machine only makes the section warn. The page never shows the address (a path can carry a key).
- **Token.** `<data>/secrets/after-tax-token`, read on every fetch and stripped. Missing or empty: no `Authorization` header. Anything
  but visible ASCII inside (`0x21` to `0x7E`): no request, reason `token_unreadable`. Never in a backup (`secrets/` is not in it).
- **The request.** `GET`, the configured address exactly, no body, no query, `Accept: application/json`, no redirect followed (a 3xx is
  `status`). 10 seconds in total from connect to the last byte (a trickling body is cut off by closing the stream), 64 KiB of body at most.
  The timeout is a constant as the contract fixes it; the constructor takes a shorter one for tests.
- **Valid answer.** Status 200 only; the `Content-Type` is not checked (a static file server labels `.json` loosely). The body must be a
  JSON object; a duplicate key, trailing text or an unreadable number is `not_json` (which value would win is a guess). `contract_version`
  the integer `1` (`version`); `currency` exactly `"EUR"` (`currency`); `as_of` ISO 8601 with offset or `Z` (`invalid_field` otherwise);
  every amount a JSON integer literal within +-(2^53 - 1) (`not_an_integer`: a decimal, an exponent, a string, a boolean, too large).
  A required field missing or `null` is `missing_field`. An optional field that is `null` is absent; one that is present and invalid
  refuses the whole answer (hard rule 9: refuse rather than guess). A reserved line needs a non-blank string label and an amount of zero or
  more, else `invalid_field`. Unknown fields are ignored.
- **Mismatch.** When gross and reserved are both present and gross minus the sum differs from the available amount (or the sum overflows,
  `Math.addExact`) the value is stored with `mismatch = 1` and the section warns. The available amount is shown as the source sent it;
  nothing is calculated with the lines.
- **`as_of` in the future** (the contract is silent): up to 5 minutes ahead is accepted and counts as age zero; more is refused as
  `as_of_in_future`. Proposed as a marked clarification in the contract, version stays 1. The owner decides on it at merge.
- **A fetch** runs on its own single daemon thread (`after-tax-fetch`) behind its own flag: it never shares the bank sync's lock and never
  waits for it. The daily run and *Sync now* only start it (skipped when one is running) and are started before the bank sync, so
  they also work with no Enable Banking application. *Test* runs one fetch on the same thread, waits for it (bounded by the cap) and
  redirects with `ok`, `failed` or `busy`; a test stores a valid answer like any other. No GET handler ever calls the source.
- **Storage.** The request is made with no transaction open; then one short transaction writes the value (when valid) and the fetch
  record. A valid answer equal to the newest stored one (same `as_of`, amounts, lines) stores no new value row, only the fetch record
  (hard rule 10). The current value is the most recently received valid answer, even when its `as_of` is older than the previous one
  (the contract: a valid answer replaces the stored value). Value history is never pruned (a candidate issue); fetch records keep the
  newest 100. When storing fails the record says `store_failed` and nothing is half stored.
- **Freshness.** `Freshness.afterTax(now, asOf, lastFailure, limit)`: a failed newest fetch (`SOURCE_FAILED`, with its code) wins over an old
  value (`SOURCE_OLD`); "older than": exactly 48 h is fresh, 48 h and a second is stale. No value yet is not stale; the section says so.
  The age shown counts from the value's own `as_of` and uses the same words as a balance (`age.*`, the `stale` fragment).
- **Logging.** One INFO line per fetch with the outcome code. No address, host, token, amount or label in any log or exception message; the
  exception classes drop the cause. `AfterTaxViews` is under the guard that forbids exception objects in log calls.
- **Package choice.** The code lives in `outbound`, `sync` and `money`, all under the `risk:*` globs of `.github/labeler.yml`; a new package
  would not be, and a worker may not edit that file.

## Differences from the spec, and limits

- `Freshness.Limits` got a sixth component; a second constructor with the five of #11 keeps every caller and test as it was.
- `SyncScheduler.runOnce` is package-private (a test calls it) and no longer fails when the scheduler was not started.
- *Test* runs the fetch on the fetch thread and waits for it, so *Test* during a running fetch says `busy` instead of queueing.
- `BankHarness` (test) exposes its transaction manager so a test can build the after-tax service on the same database.
- The migration is `V7`: `V6` was taken by #41 (issue #39), which merged first.
  `BackupServiceTest` and `SettingsBackupPagesTest`.
- Known limit: a source that changes to another one keeps the old history (a candidate issue).

## Contract clarification

`docs/contracts/after-tax-source.md` gets a section marked as a clarification of version 1: the future `as_of`, the size and time caps, the
redirect rule and the duplicate key. Version 1's meaning is unchanged.
