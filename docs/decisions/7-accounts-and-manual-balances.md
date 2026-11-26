# #7 Accounts with scope and kind, and manual balances with history

- **Money** (`finances.money.Money`): a record of `long` cents and a currency; the constructor
  refuses any currency but `EUR` (`UnsupportedCurrencyException`) and `Long.MIN_VALUE`. Sums use
  `Math.addExact`, so an overflow throws instead of wrapping. No `double`, `float` or `BigDecimal`.
- **Typed amounts** (`Money.parse`): an optional sign, digits and `,` / `.`. When both separators
  appear the last one is the decimal separator (`1.234,56`, `1,234.56`). The same separator twice
  is grouping (`1.234.567`). **One separator followed by exactly three digits, with at most three
  digits before it (`1,234`, `1.234`), is refused as `AMBIGUOUS`**; after a leading zero (`0,500`)
  it can only be decimals, so it is `TOO_MANY_DECIMALS`. More than two decimals, other characters
  (`12 EUR`, spaces, `1e5`) and amounts that overflow are refused. `AmountFormatException` names
  the reason only, never the typed text (hard rule 11). Formatting is `€1,234.56` / `-€12.34`.
- **Schema** (`V2__accounts.sql`): `account` and `balance`. `currency` has `check (currency = 'EUR')`
  on both. `account.iban` has a partial unique index, so two accounts never share an IBAN, **an
  archived account included**; names are free. A balance is `(account, amount_cents, currency,
  balance_date, entered_at)`; the date is a plain ISO date, `entered_at` is UTC.
- **IBAN** is checked by format only (two letters, two digits, 10 to 30 letters or digits), spaces
  and case ignored, stored compact and upper case. No check digits: the fictional IBANs of
  `AGENTS.md` are not valid ones.
- **Latest balance** = the entry with the newest date; on the same date the last one typed.
  The page shows the balance with `as of <date>` and its age in days. There is **no stale limit for a
  manual balance yet**: the date and age are shown, and marking something stale comes with the age
  work of the sync and CSV issues (plan #14, row #11).
- **Nothing is deleted** from the accounts page: there is no delete for an account, only archive and
  restore. An archived account keeps its balances, is listed under *Archived accounts*, and refuses
  new, corrected or removed balances until it is restored. A single balance entry can be removed,
  from its own correction page (a second step, so it is not an accident).
- **Debt**: the entered amount is the amount owed and is shown as `Owes €…`, never as an available
  balance. A negative amount owed is refused. **An account that has any balance cannot change
  between a debt and another kind** (`KIND_CHANGE_HAS_BALANCES`): the saved amounts would silently
  change meaning, which matters once totals exist. An account without balances can change freely;
  otherwise archive it and add a new one. (Assumptions: the spec only says "entered as the amount owed".)
- **Removing a balance** is a real delete of that one entry, as the spec says ("corrected or
  removed"); a correction replaces the amount and date in place. Accounts are never deleted.
- **Dates and zones**: "today" is the date in the machine's time zone (`Clock.systemDefaultZone()`).
  If the machine runs in UTC and the owner is elsewhere, a balance dated "today" can be refused as
  future shortly after local midnight. A configurable zone is a new property and needs the owner.
- **The same-site check is a mitigation, not full CSRF protection**: a request without the header
  (curl, very old browsers) passes.
- **Unknown pages** (`/accounts/999`) show `error.html` with a way back instead of raw JSON.
- **For the lead**: `src/*/java/finances/web/**` is not in `.github/labeler.yml`, so a later PR that
  only touches `SameOriginFilter` or `AccountsController` would get no review in CI. Consider adding it.
- **Only manual accounts get typed balances**; `csv` and `bank` accounts refuse them in the domain
  (`NOT_MANUAL`), so a later sync owns its own balances. `Source` already has all three values and the
  schema accepts them.
- **Clock**: one `Clock` bean (`finances.data.ClockConfig`, system default zone). "Today" for the
  future-date rule is the owner's calendar day; tests use a fixed clock.
- **Refused forms** are shown again, filled in, with status 400 and the reason as a message key
  (`error.account.*`, `error.amount.*`); a test checks every reason has a message. A balance entry
  that belongs to another account is `404`, not edited.
- **No login, so a same-site check** (`SameOriginFilter`): a request that changes something and
  carries `Sec-Fetch-Site` other than `same-origin` or `none` gets 403. Without it, any page in the
  owner's browser could post a form to the dashboard. (Since #46 a request without that header is checked by `Origin`: `46-error-page-and-origin-check.md`.)
  Not a new dependency.
- **Templates** take texts from `messages.properties`; scope and kind labels come as message keys
  from `AccountViews`, because the guard test would flag a literal `value="private"`.
- **Not done on purpose**: totals and headline numbers, transactions, CSV and bank (other issues).
- **Backwards compatibility**: only an added migration (V2); `V1` and the health endpoint are untouched.
