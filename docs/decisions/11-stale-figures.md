# 11: The age of every figure and the stale mark

Built from issue #11 on top of `docs/decisions/7-accounts-and-manual-balances.md` and `10-bank-sync.md`. Nothing new is stored: no
migration, the facts come from what the bank sync already keeps.

## Where things are

- `finances.money.Freshness`: the pure rules (no clock, no database). `bank(...)` and `byHand(...)` return the one reason a figure is
  stale, or nothing. `Freshness.Limits` holds the limits; `FreshnessConfig` reads them from configuration.
- `finances.sync.FreshnessService`: gathers the facts per account (consent, recorded runs, newest balance) and calls the rules. It is in
  `sync` because it needs the bank facts and `accounts` does not depend on `sync`. Read only, nothing logged.
- `finances.web.AccountViews` (`StaleView`, the time of day in the age), `OverviewController` (the line at the top, the consents that end
  soon), `fragments.html` (`stale`, `balance`), `app.css`.

## Decisions

- **One reason, in this order**: consent expired, then sync failed, then sync too old (bank); not updated for N days (manual and CSV).
  A consent that ended is the most useful thing to say because the owner can act on it (*Renew*); the failed run comes next because it is
  the newest news; an old sync is what is left when nothing says why.
- **"Older than"**: a limit exactly reached is still fresh; one second (bank) or one day (by hand) more is stale. Tested on both sides.
- **A bank account is judged by its sync, not by its balance's own timestamp.** The moment *shown* is the bank's own timestamp ("today
  07:10") when the bank gave one; a date-only or "fetched" balance keeps the notes of #10. The stale test uses the last successful run
  (`sync_run`, outcome ok), falling back to `bank_account.last_success_at` and then to when the balance was stored; "last sync failed" is
  the newest run of the account.
- **A consent has ended** when its status says so, its date has passed (found without waiting for the next sync, so a machine with sync
  switched off still shows it), or its session file is not in the data folder (a restore on another machine: `10-bank-sync.md`). The
  session file is only checked for existing, never read.
- **Manual and CSV**: the moment is the date of the newest balance (for a CSV account with a balance column that is the date the import
  stored, otherwise the date typed). Limits per kind. Only the newest balance is marked; older entries in the history are history.
- **No balance yet** is not stale: the page says "No balance yet". A bank account without a balance still gets the mark when its consent
  ended or its last sync failed, because that is true and the owner can act on it.
- **Archived accounts** are not counted in the line at the top and show no mark (they are not synced and cannot be updated).
- **The mark** is a `<details>` with the word *Stale* and a sign, so it opens on tap and from the keyboard without script and does not
  rely on colour. A failed sync names its reason from the short code of the run (`bank.reason.*`), never a text from the bank.
- **The line at the top** is one line that names the accounts as links. A consent that ends within 14 days (`FreshnessService.
  CONSENT_WARNING_DAYS`, a constant, not a property) is announced in its own box with *Renew* (the existing POST), only when it still
  feeds an account that is not archived.
- **Sync now in place**: it still redirects to the overview with the result of the run on top (as in #10); the overview it lands on
  is computed after the run, so the accounts that failed carry their reason right there.
- **Limits are public property names** (hard rule 4), in the README: `finances.freshness.bank-sync-hours` (36), `current-days` (14),
  `savings-days` (45), `investment-days` (100), `debt-days` (100). A value below 1 stops the start with a message; the alternative
  (everything stale) hides the real problem.
- Times: stored in UTC, shown in the clock's zone; "today" and "yesterday" follow that zone. Tests build their data from the clock.

## Not done / known limits

- A bank balance the bank itself dated long ago while the sync is fine is not marked stale (the spec judges a bank account by its sync).
- The overview still shows no totals or headline numbers (not in this issue).
- The after-tax source follows the same pattern in M2.
