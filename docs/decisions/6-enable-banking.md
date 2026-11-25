# 6: What Enable Banking gives (from its public documentation)

> **Read first. Two findings change what the owner can rely on.**
>
> 1. **Own-account use is possible and free, but only for personal use and only for accounts linked
>    in advance.** A production application can be put in *restricted mode* by linking your own
>    accounts to it in the Control Panel; this is free (Terms: "Use of the Control Panel and the API
>    under these Terms is free of charge") and is meant for "the personal use of private
>    individuals". Restricted mode returns **only the accounts linked in the Control Panel**; any
>    other account is silently stripped from the authorisation result. So every account the app
>    should sync is linked twice: once in the Control Panel (whitelist), once through the app's own
>    authorisation flow.
> 2. **The Terms exclude business accounts.** "Use … is limited to Linked Accounts and is available
>    solely for evaluation purposes or for the personal use of private individuals", and the Terms
>    "do not grant any right … to use the API for any business or professional purpose, including
>    … accessing account information belonging to businesses". The same text says a business user
>    who registers an application and links accounts is tolerated as *evaluation* "unless and until
>    Enable Banking provides written notice" that the application will be deactivated. Our product
>    has `business`-scope accounts. The private accounts are covered; the business current account
>    is **not** covered as ordinary personal use and can be switched off by Enable Banking. The
>    business account should therefore keep a working `csv`/`manual` source (it already has one).
>    This is the owner's call; it is also the one thing to decide before relying on the bank source
>    for the business scope.
>
> The plan of #10 holds with the changes in *Does the plan of #10 hold?* below. Nothing was tried
> against a real account: everything here is **documented, not yet tried against a real account**.

Status of every statement below: **[doc]** the documentation says it; **[inf]** my inference from
what it says (to be tried); **[?]** not found in the pages read.

Sources (all read on the date of this issue; `https://enablebanking.com/...`):
`docs/api/reference/` (API reference), `docs/api/linked-accounts/` (own accounts),
`docs/api/control-panel/` (application registration), `docs/faq/` (FAQ), `terms/` (Terms of
Service), `docs/markets/nl/` (Dutch market). Section names are given in each answer.

## 1. Can an individual register an application and link their own accounts, free of charge?

- **Yes [doc].** Register an account in the Control Panel (an e-mail address and a one-time link),
  register an application in the `PRODUCTION` environment, then use *Activate by linking accounts*
  (`docs/api/linked-accounts/`). The application becomes "active in restricted mode … until an
  agreement has been signed". Terms, *Pricing and Payment*: "Use of the Control Panel and the API
  under these Terms is free of charge"; "No fees will be applied retroactively without prior
  notice". The FAQ ("How much does it cost") says the paid, volume-based price applies to the
  contract route; "when getting started you can use sandbox environment or link own accounts".
- **Conditions [doc]** (Terms, *Restriction of Use*): production use is limited to Linked Accounts,
  for evaluation or for personal use by private individuals; accounts must belong to the person who
  linked them; no commercial use; "the number of Linked Accounts per Application may be limited"
  (the limit is **not published [?]**). The Terms also forbid bypassing rate limits and abusive
  automation.
- **Limits per day [doc, partly]**: "Quotas for API requests per second, per hour and per day may
  apply" (Terms, *Quotas*), numbers not published [?]. The real daily limit is the bank's: many
  banks allow **4 background fetches per day** per account (FAQ, "Why am I getting 429"). See 4.
- **Registration needs more than a name [doc]** (`docs/api/control-panel/`): a production
  application needs a description, a data-protection e-mail, a privacy-policy URL and a
  terms-of-service URL. For restricted activation by linking accounts these "are **not checked**"
  (FAQ). `docs/install.md` (in #10) must tell the installer to fill them with anything valid.
- **Restricted-mode surprise [doc]** (FAQ, "empty list of accounts"): linking one account does not
  activate all accounts. Authorising an account that was not linked returns an **empty list**, not
  an error.
- **Deleting the Control Panel account [doc]** removes the identifiers of the linked accounts and
  deactivates the application.

## 2. How the application authenticates, and what is a secret

- **A key pair and a signed token [doc]** (reference, *Authentication*). Generate an RSA key
  (the reference shows 4096 bits) and a self-signed certificate, upload the **certificate** in the
  Control Panel, receive an **application id** (a UUID). Alternatively the browser generates the
  key and downloads a `<application id>.pem` file once.
- **Every request carries a JWT [doc]** in `Authorization`: header `{"typ":"JWT","alg":"RS256",
  "kid":"<application id>"}`, body `{"iss":"enablebanking.com","aud":"api.enablebanking.com",
  "iat":<now>,"exp":<now + at most 86400 s>}`. Only RS256. A new token may be made for every
  request. The JDK signs RS256 itself, so this needs **no new dependency [inf]**.
- **Secrets**: the **private key** (never leaves the data folder). The **application id** is the
  `kid` of every token and appears in the request log; keep it in the data folder too, but it is
  not a signing secret. **Session ids** are secrets in practice: a session id plus the JWT key
  reads that consent's data; the token "can be used … to access other users data" (FAQ), so it
  must never be shown to a browser. Account `uid`s are only valid inside their session [doc].
- **Key file format [?]**: the reference shows `openssl genrsa` (PKCS#1 or PKCS#8 depending on the
  OpenSSL version) and a browser-generated `.pem`. Which one the Java side must accept is not
  stated; the code should accept both, and the owner's first run proves it.
- **The token identifies the application, not a person [doc]** (FAQ): one token reaches every
  session of the application.
- **Hosts [doc]**: `https://api.enablebanking.com` for the API (the older `api.tilisy.com` is
  marked deprecated and must not be used). The application is registered at
  `https://enablebanking.com/cp`. The bank's authorisation pages are opened **in the user's
  browser** (`auth.enablebanking.com`-style URL returned by `POST /auth`), not by the server, so
  `finances.outbound` needs only `api.enablebanking.com`. The bank list returns **logo URLs on
  Enable Banking's host**: the app must not load them in a page (hard rule 7); show the bank's name.

## 3. The authorisation flow and the redirect URL

Flow [doc] (reference, *Account information flow*):

1. `GET /aspsps?country=NL&service=AIS` (the `psu_type` filter exists too) lists the banks.
2. `POST /auth` with `access.valid_until`, `aspsp {name, country}`, `state`, `redirect_url`,
   `psu_type` → `{url, authorization_id, psu_id_hash}`. The browser is sent to `url`.
3. After the bank, the browser returns to `redirect_url` with `code` and `state`, or with `error`
   and `error_description` (`access_denied` for "Denied data sharing consent" and "Cancelled by
   user"; other texts come from banks).
4. `POST /sessions {code}` → `{session_id, accounts[], aspsp, psu_type, access}`. "Some of the
   information returned in that call is shown only once": **store the account details (IBAN,
   name, currency, type, `identification_hash`) at this moment**; `GET /sessions/{id}` returns only
   `uid` and `identification_hash` per account.

Details:

- **`state` [doc]** is any string we choose and is returned unchanged: use a random value per
  attempt, keep it server-side, and refuse a callback whose `state` is unknown (the "unknown state
  value" edge of #10).
- **`psu_type` [doc]**: `personal` or `business`. It decides which login the bank shows; "a mismatch
  … can result in authorization failures or the inability to see expected accounts", and the
  default depends on the bank, so always send it. The *scope* the owner picks in the link flow maps
  to it.
- **`access` [doc]**: `valid_until` (RFC 3339 with offset, at most now + the bank's
  `maximum_consent_validity`), `balances`, `transactions`, optional `accounts`, and
  `transactions_extended_history` (default `true`: the user may get an extra consent step to see
  history older than 90 days). **Ask for balances and transactions only**; there is no payment
  field in `POST /auth`, and payments are a separate endpoint (`POST /payments`) that we never
  call.
- **The redirect URL is registered in advance [doc]**: the application has a list `redirect_urls`;
  anything else is refused with `REDIRECT_URI_NOT_ALLOWED`.
- **May it be `localhost` or a private-network address? [?]** The pages read do not say. The
  reference's own examples use both `https://` and plain `http://` URLs. The redirect is performed
  by **the user's browser**, so an address that only that browser can reach (a private name, a
  `localhost` with a port) should work **[inf]**; but an `http://` or private-name URL might be
  refused at registration or by a bank. This is the first thing for the owner to try (last
  section). If it fails, the fallback is a user-pasted callback (the owner copies the final URL
  from the address bar into a form), which needs no reachable redirect at all.
- **`authorization_id` vs `session_id` [doc]**: different values; the session id is created by
  `POST /sessions`.

## 4. Banks, consent length, history, fetch frequency

- **Bank list [doc]**: `GET /aspsps` (optionally `country`, `psu_type`, `service`). Each entry:
  `name`, `country`, `logo`, `psu_types`, `auth_methods`, `maximum_consent_validity` (seconds),
  `beta`, `bic`, `required_psu_headers`. A bank is identified by **`name` + `country`**; there is no
  id, and a rebranded bank changes `name`, so fetch the list again before a renewal (FAQ, "Is there
  a unique ASPSP identifier?"). Whether a bank covers business as well as personal accounts is in
  `psu_types`. The Dutch market page (`docs/markets/nl/`) lists the main Dutch banks and says they
  authenticate through a redirect with the bank's app; the exact coverage is the live `GET /aspsps`.
- **Consent length [doc]**: chosen by us in `valid_until`, capped by `maximum_consent_validity`;
  "for the majority of ASPSPs … **180 days**" (15 552 000 s in the example). Renewal is a new
  authorisation; there is no refresh call, and token refresh at the bank is handled by Enable
  Banking. It **may end earlier [doc]**: the user's bank can expire it for a yearly KYC survey, a
  second authorisation by the same user can expire the first at some banks, and a certificate
  change at the bank can end it.
- **How far back [doc]**: "most ASPSPs provide at least one year", many two to three years or more
  - but **only for roughly one hour after the authorisation**, after which many banks limit
  access to the **last 90 days** (FAQ, two questions). Requesting dates before the account was
  opened can raise an error. `transactions_extended_history` can add an extra consent step.
  **Design consequence**: the first sync must run **right after the authorisation, in the same
  flow**, with `strategy=longest`, not at the next daily run.
- **How often [doc]**: when the *application* triggers a fetch (a schedule) the bank sees a
  "background" fetch and many banks allow **4 per day per account**; a rate-limited call returns
  429 with `ASPSP_RATE_LIMIT_EXCEEDED`, and the FAQ says to continue **after 6 hours**. When *a user*
  triggers a fetch, the PSU headers (`Psu-Ip-Address`, `Psu-User-Agent`, …) mark it as "online" and
  the daily limit "does not apply". Rules [doc]: send **all** of the headers a bank lists in
  `required_psu_headers` or **none** (otherwise 422 `PSU_HEADER_NOT_PROVIDED`); a scheduled fetch
  sends none. Whether a bank accepts the address of a user on a private network as `Psu-Ip-Address`
  is **[?]**; try it.
- **Retries [doc]**: `ASPSP_ERROR` (a failure at the bank) should be retried "after 1 minute, 1 hour,
  2 hours, 4 hours" (FAQ).
- **Paging [doc]**: `GET /accounts/{uid}/transactions` takes `date_from`, `date_to` (inclusive,
  UTC, plain dates), `continuation_key`, `transaction_status`, `strategy` (`default` or `longest`;
  `date_to` is ignored by `longest`). While `continuation_key` is not null, repeat the call with it
  and **identical other parameters**; a page can be **empty and still carry a key**; page size
  varies; the key is valid only in the current session. `date_from` in the future is
  `DATE_FROM_IN_FUTURE`; `date_to` without `date_from` is `DATE_TO_WITHOUT_DATE_FROM`;
  `WRONG_TRANSACTIONS_PERIOD` exists for a period the bank refuses.
- **No historic balance [doc]**: banks give no balance for a past date; `balance_after_transaction`
  (when the bank sends it) is the only help.

## 5. The shape of what comes back

All payloads below are in `src/test/resources/enablebanking/`, written in fictional data and shaped
after the reference. Field names are the documented ones.

**Account** (`session-authorized.json`, `accounts[]`): `account_id.iban` (or `other`),
`all_account_ids[]` (`identification`, `scheme_name`), `name` (holder), `details`, `usage`
(`PRIV` or `ORGA`), `cash_account_type` (`CACC`, `SVGS`, `LOAN`, `CARD`, `CASH`, `OTHR`),
`product`, `currency`, `credit_limit`, `uid`, `identification_hash`, `identification_hashes[]`.
`uid` is "valid only until the session … is in the AUTHORIZED status" and may be missing for a
blocked or closed account. **Currency `XXX` is a valid value [doc]** (FAQ): multi-currency accounts
or an unknown currency; the app lists such an account as not supported.

**Balances** (`balances-current.json`): `GET /accounts/{uid}/balances` →
`{balances:[{name, balance_amount:{currency, amount}, balance_type, last_change_date_time?,
reference_date?, last_committed_transaction?}]}`. `balance_type` is one of `CLAV, CLBD, FWAV, INFO,
ITAV, ITBD, OPAV, OPBD, OTHR, PRCD, VALU, XPCD`. "Each bank supports only a limited number of types.
The most common are `CLBD`, `ITAV` and `XPCD`" (FAQ). **The booked balance is `CLBD`** (the app
shows it; `ITBD` is the in-day booked balance and is the fallback **[inf]**); `ITAV`/`CLAV` are
"available" (they include credit limit or holds), `XPCD` is the expected balance. A bank may offer
none of the booked types, then the app stores what it gets **with its type** and does not call it
booked. `last_change_date_time` is optional: when absent, the age of the figure is the time of our
fetch **[inf]**. The `name` is only a label: the reference's own example calls a `CLAV` balance
"Booked balance", so **decide by `balance_type`, never by `name`**.

**Transactions** (`transactions-current-page1.json` and others): `{transactions:[…],
continuation_key?}`. A transaction has: `entry_reference?`, `transaction_amount:{currency, amount}`,
`credit_debit_indicator` (`CRDT` or `DBIT`), `status`, `booking_date?`, `value_date?`,
`transaction_date?`, `creditor?{name,…}` and `creditor_account?{iban}` or `debtor?`/`debtor_account?`
(only the counterparty's side is filled for a card or transfer on our account, and the own side may
also be present), `remittance_information?[]` (free text and/or a reference), `reference_number?` +
`reference_number_schema?`, `bank_transaction_code?{description, code, sub_code}`,
`merchant_category_code?`, `balance_after_transaction?`, `exchange_rate?`, `note?`, `transaction_id?`.
Only `transaction_amount`, `credit_debit_indicator` and `status` are mandatory [doc].

- **Amounts [doc]**: `amount` is a **string** with a dot as decimal separator, "allowed precision
  varies". It is **unsigned**; the direction is `credit_debit_indicator` **[inf]** (the reference
  never shows a minus sign). Parse the string to integer cents without `double` (hard rule 8);
  refuse more than two decimals or a currency other than `EUR`.
- **Statuses [doc]**: `BOOK` booked, `PDNG` pending, `HOLD`, `SCHD` scheduled, `CNCL`, `RJCT`, `OTHR`.
  Only `BOOK` and `PDNG` matter for the product; the others are kept in the raw record and not
  counted **[inf]**.
- **What identifies a transaction [doc]**: `entry_reference`, "both unique and immutable for
  accounts with the same identification hashes", usable "across multiple PSU authentication
  sessions", but **not globally unique** (the same value occurs on different accounts) and **not
  guaranteed**: "some [banks] do not provide transaction entry references at all, and some provide
  duplicate values". It is "in most cases" present **only for booked** transactions. For a pending
  one it is present only if the bank keeps the same id after booking; "otherwise, pending
  transactions should be excluded when matching". Enable Banking may generate synthetic ones.
  **`transaction_id` is not an identity** ("may change if the list … is retrieved again"; it is
  only for `GET /accounts/{uid}/transactions/{transaction_id}` details).
- **Counterparty and description**: `creditor.name` / `debtor.name` (free text, optional),
  `*_account.iban` (optional), `remittance_information` (a list of strings; may contain the
  structured reference as well), `bank_transaction_code.description` (the bank's own wording).
  Bundled payments are not split into their parts by banks (FAQ).
- **Dates**: all dates are plain `YYYY-MM-DD`; timestamps (`last_change_date_time`, session times)
  are RFC 3339.

**Session** (`session-get-*.json`): `GET /sessions/{id}` → `status`, `accounts[]` (uids),
`accounts_data[{uid, identification_hash}]`, `aspsp`, `psu_type`, `access.valid_until`, `created`,
`authorized?`, `closed?`. `status` is `AUTHORIZED`, `EXPIRED`, `REVOKED` (by the user), `CLOSED`
(by the application), `CANCELLED`, `INVALID`, `PENDING_AUTHORIZATION` or `RETURNED_FROM_BANK`.
`DELETE /sessions/{id}` closes the session and "the PSU's bank consent … if possible" (this is
*unlink*).

## 6. Expired or revoked consent

- **Error [doc]**: errors have the shape `{message, code, error, detail?}` where `error` is the text
  code (`error-*.json`). An ended consent shows as `EXPIRED_SESSION` (HTTP **401**, "however, a 401
  … may occur for other errors as well. Thus … base their logic on errors rather than response
  codes"), `REVOKED_SESSION` or `CLOSED_SESSION` (status codes not stated [?]). Others the sync
  must tell apart: `ASPSP_RATE_LIMIT_EXCEEDED` (429), `ASPSP_ERROR` and `ASPSP_TIMEOUT` (bank side,
  retry), `ASPSP_ACCOUNT_NOT_ACCESSIBLE`, `ACCOUNT_DOES_NOT_EXIST`, `SESSION_DOES_NOT_EXIST`,
  `ASPSP_PSU_ACTION_REQUIRED` (the user must do something at the bank),
  `PSU_HEADER_NOT_PROVIDED` (422), `WRONG_CONTINUATION_KEY`. `GET /sessions/{id}` before a fetch
  helps but "it is still possible for the session to expire … even if the previous response
  indicated that the session was valid", so handle the error during a fetch as well.
- **Renewal does NOT return the same identifiers [doc]** (FAQ, "How should re-authorisation be
  performed"): the new session has a **different session id** and **different account `uid`s**.
  The stable match is **`identification_hash`** ("across different sessions and even different
  users"); the IBAN (or masked card number, BBAN) also matches. The user may also pick a different
  set of accounts in the renewal, so an account of the old consent may be missing in the new one.
  (`session-renewed.json` has new uids and the same hashes as `session-authorized.json`.)
- Enable Banking recommends warning the user before `valid_until`.

## Does the plan of #10 hold?

**Yes, with these changes** (where #10 contradicts this file, this file wins, as #10 says):

1. **Whitelisting comes first.** "Link a bank" can only return accounts that were linked in the
   Control Panel beforehand; an empty account list on return is the normal signal for "not
   whitelisted" and needs its own message. `docs/install.md` explains it. Two consents per
   account (Control Panel link, then the app's session). **Does this change the design enough to
   stop and ask?** The worker of #10 decides; I think not: it is a step in the installer's
   instructions and a message on the page.
2. **Match by `identification_hash`, store `uid` per session.** *Renew* "re-attaches the same
   account" by `identification_hash` (and IBAN), never by `uid`. A consent stores
   `session_id`, `valid_until`, `status`; an account stores `identification_hash`, IBAN, the current
   `uid`. After a renewal the old session is closed with `DELETE`.
3. **The first sync runs inside the link flow**, right after `POST /sessions`, with
   `strategy=longest` and `transactions_extended_history` left at its default; a later first sync
   may reach only 90 days. Subsequent syncs use `date_from` = last success minus the overlap.
4. **Pending transactions have no reliable key.** Booked transactions are keyed by account plus
   `entry_reference`; when it is missing, or repeated inside one fetch, by the hash of date,
   amount, counterparty and description. **Pending ones are not keyed at all**: each sync replaces
   the account's pending set with the pending transactions it just received, and drops a pending
   one when the same fetch shows its booked counterpart (the fixture `transactions-current-second-
   sync.json` has a pending item that comes back booked, with a new `entry_reference`). This keeps
   the arch decision "stored as pending and replaced when booked, never counted twice" and
   removes the assumption that the pending id survives.
5. **"Sync now" and the daily sync differ.** The scheduled sync sends no PSU headers and stays
   within about 4 fetches per account per day (a daily run is far below it; "Sync now" pressed many
   times is not); a 429 means "wait six hours" and is a normal failure of that account, not
   an error. "Sync now" may send PSU headers only if it can send **all** the headers the bank lists;
   otherwise it is a background fetch and must respect the limit. Unknown whether a private-network
   address is accepted as `Psu-Ip-Address` [?].
6. **Retries**: `ASPSP_ERROR`/timeouts are retried with growing intervals, not hammered.
7. **Balances by `balance_type`** (booked = `CLBD`, fallback `ITBD`), with `last_change_date_time`
   when present, our fetch time otherwise.
8. **Business scope**: see the warning at the top; the plan assumes bank sync for business accounts
   which the Terms do not grant as personal use.
9. **No logos** from the bank list (hard rule 7).
10. Everything else in #10 holds: account information only, a single outbound host
    (`api.enablebanking.com`), a consent expiry date per consent, "consent expired" with *Renew*,
    unlink = `DELETE /sessions/{id}` and turn the accounts into manual accounts, other currency
    listed as not supported (also `XXX`), failure of one account does not stop the others.

## What the owner must try with a real account before relying on it

1. Register a production application in the Control Panel (fill description, privacy and terms
   URLs with anything valid) and link the **private** accounts with *Activate by linking accounts*.
   Check what the Control Panel says about the number of linked accounts.
2. Does the Control Panel accept `http://` and a private or `localhost` address as redirect URL?
   Does a bank send the browser back to it? (Fallback: a pasted callback.)
3. Which of the owner's banks appear in `GET /aspsps?country=NL`, with which `psu_types`, which
   `maximum_consent_validity` and which `required_psu_headers`.
4. Which balance types each of the owner's accounts returns (`CLBD`?) and whether
   `last_change_date_time` is filled.
5. How far back `strategy=longest` reaches directly after the authorisation, and what a fetch an
   hour later gives (90 days?). Does `entry_reference` exist, for booked and for pending items,
   and does a pending item keep its id when booked?
6. Is the amount string ever signed or with more than two decimals?
7. How many fetches per day a bank allows without PSU headers (when does 429 start), and whether a
   user fetch with `Psu-Ip-Address` of a private-network client is accepted.
8. Renew a consent early: are the session id and the `uid`s new, and the `identification_hash` the
   same? Does the old consent end by itself?
9. Which key file the Java side must read (PKCS#1 from `openssl genrsa` or PKCS#8 from the browser
   download).
10. Whether the **business** current account can be linked at all, given the Terms (see the top),
    and whether the owner accepts the "evaluation" status.
11. The exact status code of `REVOKED_SESSION`, `CLOSED_SESSION` and `REDIRECT_URI_NOT_ALLOWED`
    (the status codes in `error-redirect-not-allowed.json` are an assumption; the others in
    `error-*.json` are documented).

## Fixtures

`src/test/resources/enablebanking/` (fictional, shaped as the reference documents; no real name,
IBAN, reference or amount; ids are obviously invented):

| File | Endpoint / meaning |
|---|---|
| `aspsps-nl.json` | `GET /aspsps?country=NL`: two fictional banks, one with `beta`, one without `required_psu_headers` |
| `auth-request.json`, `auth-response.json` | `POST /auth` request and response |
| `session-authorized.json` | `POST /sessions` response: private current, private savings, a multi-currency account (`XXX`, no IBAN) |
| `session-renewed.json` | the same after a renewal: new session id and `uid`s, same `identification_hash`es |
| `session-get-authorized.json`, `-expired.json`, `-revoked.json` | `GET /sessions/{id}` in three statuses |
| `balances-current.json`, `balances-savings.json` | `GET /accounts/{uid}/balances` (`CLBD`, `ITAV`, `XPCD`; one with no timestamp) |
| `transactions-current-page1.json`, `-page2.json` | a first sync in two pages (`continuation_key`); page 2 has a transaction without `entry_reference` and a pending one |
| `transactions-current-second-sync.json` | the second sync: the same booked items again plus the pending one now booked |
| `transactions-empty-with-continuation.json` | an empty page that still has a key |
| `error-expired-session.json`, `error-rate-limit.json`, `error-psu-header-not-provided.json`, `error-redirect-not-allowed.json` | error bodies (`{message, code, error, detail?}`) |

The files leave out fields the product does not use (postal addresses, agents, `payments` in the
bank list); the reference marks them optional. The reference's own samples contain placeholder
values; none were copied.
