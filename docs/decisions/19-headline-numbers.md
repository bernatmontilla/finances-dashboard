# 19: The two headline numbers and their build-up

Built from issue #19 on top of `docs/product/PRODUCT.md` (the two numbers, never added), `11-stale-figures.md` (the one place that
decides "stale") and `18-after-tax-source.md` (the source and its value).

## Where things are

- `finances.money.BuildUp`: the only place the two definitions exist. Pure: it takes plain `Line`s and a list of open `Reservation`s and
  returns a `Result` (a total, or no total and one `Hidden` reason). It knows nothing of accounts or the source, so `money` stays free of
  `accounts` and `sync`.
- `finances.sync.HeadlineService`: decides which accounts count (active, private, current) and whether a source is configured, takes
  "stale" from `FreshnessService` (accounts) and `AfterTaxService.standing` (the after-tax value), and calls `BuildUp`. It sits in `sync`,
  like `FreshnessService`, because it reads both. It is the one place reservations plug in (`openReservations`, an empty list until
  issue #20).
- `finances.web`: `HeadlineViews` (message keys and formatted text), the headline block on `overview.html` (the `headline` fragment) and
  `GET /build-up/private`, `GET /build-up/business` (`build-up.html`) in `OverviewController`.

## Decisions

- **What a number is.** Private free to spend = active private current accounts minus open private reservations. Business available =
  the current after-tax amount minus open business reservations. Archived accounts, savings, investment and debt accounts and every
  business account are in neither number. Nothing offers a way to add the two numbers, and no page shows their sum.
- **Unknown is never zero.** No active private current account, a counted account with no balance yet, no source configured, a source
  with no valid value yet, and a sum that does not fit are each a reason (`BuildUp.Hidden`) and a line on the page with a link, never an
  amount. A counted account with no balance hides the whole number rather than leaving that account out: a partial sum shown as the
  number is an unknown shown as a figure.
- **Negative stays negative.** An overdraft, a negative after-tax amount and reservations beyond the balance all count as they are and
  can make the total negative; it is shown as `-€...` with the words "Below zero". Nothing is capped.
- **Sums are exact.** `Math.addExact`/`subtractExact`; an overflow, or a total of `Long.MIN_VALUE` (which `Money` cannot show), is
  `TOO_LARGE`. Never wrapped around, never a 500.
- **Stale.** A stale part is still counted; the headline is then stale and lists every stale part with its own reason, from the same
  `Freshness` rules as the lines below. A headline's age is the age of its oldest counted line, shown as that line shows it. A stale
  business current account does not make Business available stale: it is not part of it.
- **The business build-up** shows the after-tax line, the source's gross balance and held-back lines as it sent them (nothing is
  calculated with them) and the mismatch warning, then reservations, then the total. The active business current accounts are listed
  apart under "Not counted": they are the gross money the after-tax amount replaces.
- **Reservations.** Until they exist the build-up says "No open reservations." (a true statement, not an unknown). The calculation takes
  them as a list, so issue #20 changes `HeadlineService.openReservations` and nothing in `BuildUp`.
- **Pages only read.** No GET handler calls the bank or the source. A source that is not configured shows no business number even when
  an older source left a value in the database.
- **Build-up is a page**, not an inline `<details>`: the contract already speaks of "the build-up page" and it can be linked from
  elsewhere.

## Review findings of PR 47, fixed here

- **An answer older than what is known.** The current value (`AfterTaxService.current()`, was `newest()`) is the stored one with the
  latest `as_of`, compared as an instant (never as text: `...:00Z` sorts after `...:00.500Z`), and on a tie the one received last. A valid
  answer with an older `as_of` is stored in the history but is not current; its fetch row is `ok` with the note `older_than_known`
  (`AfterTaxReason.OLDER_THAN_KNOWN`, shown in the fetch list). It is not a failure: the source answered, and the current value keeps
  ageing from its own `as_of` into `SOURCE_OLD`. The duplicate check now asks whether any stored value is the same answer
  (`AfterTaxStore.holds`), not only the newest, or an older answer received twice would be stored twice (hard rule 10). This changes what
  #18 decided ("the newest received value is current"); `theNewestReceivedValueIsTheCurrentOneEvenWhenItsMomentIsOlder` and the "old"
  step of `AfterTaxPagesTest` changed with it. A third existing assertion changed because the spec puts both numbers on the overview:
  `AfterTaxNotConfiguredPagesTest` no longer expects "Business available" to be absent without a source (it is there as a line saying
  why there is no number, with no amount). The V7 comment saying "the current value is the most recently received row" stays, as a
  merged migration is never edited. A value is stored again only when it is not the current answer and not an older answer already in
  the history, so a source that goes back to an earlier answer for the same moment shows that latest answer, and nothing else is stored twice. A sentence in the contract's clarification section says it; the owner decides on it at merge.
- **A busy database.** When neither the fetch record nor the `store_failed` fallback can be written, `AfterTaxService` keeps the failure
  in a `volatile` field (`lastFailure()` returns it first) until any fetch row is written. Memory only: a restart drops it (issue #49).
- **Unknown stale reasons.** `AccountViews.stale` maps the detail of a failed sync and of a failed fetch through an allow-list (the
  codes of `BankApiException.Reason` as `BankSyncService` writes them, `store_failed`, `unexpected`; `AfterTaxReason`). Any other code is
  `stale.reason.unknown` ("the figure may be out of date"), never a raw key. A test asserts that every allowed code has a message.
- **Contract.** One sentence for the older `as_of`, one for the amount range (above 2^53 - 1 is refused; `AfterTaxMapping` already did).

## Left out

Reservations (#20), fixed costs (#21, #22), charts of the history (#42), pruning it (#44), a changed source URL (#45), keeping the
unrecorded failure across a restart (#49).
