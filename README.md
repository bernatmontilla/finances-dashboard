# finances-dashboard

A self-hosted personal finances dashboard for one person. It brings bank accounts, savings,
investments and debts together in one place and shows what is really free to spend: money set
aside for taxes or for upcoming expenses is not counted as spendable. Each installation holds one
person's data and runs on that person's own machine; this repository contains code only.

## Build and start it locally

You need a JDK 25. The build runs every test and produces `target/finances-dashboard.jar`:

```bash
bash scripts/dev/build.sh
```

Start it on an empty data folder (it is created on first start, together with the SQLite database):

```bash
java -jar target/finances-dashboard.jar --finances.data-dir=/path/to/empty/folder
```

Then open <http://127.0.0.1:8080/>. The application listens on `127.0.0.1` only; publishing it on
a private network is the installer's job.

| Setting | Meaning |
|---|---|
| `finances.data-dir` (or the environment variable `FINANCES_DATA_DIR`) | **Required.** The folder that holds the database. Starting without it, or twice on the same folder, fails with a clear message. |
| `server.port` (or `SERVER_PORT`) | The port, default `8080`. |
| `finances.import.max-file-size` (or `FINANCES_IMPORT_MAX_FILE_SIZE`) | The largest CSV file that can be uploaded, default `10MB`. |
| `finances.freshness.bank-sync-hours` | A bank account is marked stale when its last successful sync is older than this many hours, default `36`. |
| `finances.freshness.current-days` | A manual or CSV *current* account is marked stale when its newest balance is older than this many days, default `14`. |
| `finances.freshness.savings-days` | The same for a *savings* account, default `45`. |
| `finances.freshness.investment-days` | The same for an *investment* account, default `100`. |
| `finances.freshness.debt-days` | The same for a *debt*, default `100`. |
| `finances.freshness.after-tax-hours` | The after-tax value is marked stale when the moment the source calculated it (`as_of`) is older than this many hours, default `48`. |
| `finances.outbound.after-tax.url` (or `FINANCES_OUTBOUND_AFTER_TAX_URL`) | The address of the after-tax source: an `http` or `https` address of a host, without credentials, query or fragment. Empty (the default) means no source. The token is a file in the data folder, see `docs/install.md`. |

Every limit is a whole number of 1 or more; a smaller one stops the application at start with a message.

Open *Accounts* to add an account (a name, a scope `private` or `business`, a kind `current`,
`savings`, `investment` or `debt`, and an optional IBAN) and to type in its balance with the date it
is from. Every balance is kept: the account page shows the history, newest first. Amounts can be
typed as `1234,56`, `1.234,56` or `1234.56`; `1,234` is refused because it is not clear what it means.
An account is archived, never deleted.

An account whose source is *CSV import* takes files exported from a bank: on its page, *Import a CSV
file* shows a preview and how the file is read (separator, encoding, header row, date format, decimal
separator, which column is which), all of which can be corrected. The mapping is kept per account for
the next upload. Importing the same file twice adds nothing; rows that cannot be read are listed and the
others are imported. The file is held in memory for the preview only and is not kept. A *balance after*
column updates the balance from the newest row.

Every balance is shown with its age in words ("today 07:10", "yesterday", "12 days ago"), and a
balance that may be out of date carries a *Stale* mark; tap it (or hover it) for the reason: *sync
failed*, *consent expired*, *no successful sync for 2 days* or *not updated for 20 days*. A bank account
is judged by its sync, a manual or CSV account by the date of its newest balance, with the limits above.
The overview says so in one line at the top, and announces a bank consent that ends within 14 days with a
*Renew* button before it makes anything stale. Archived accounts are not counted.

### Reservations or fixed costs?

Both are lists kept by hand, and both stay out of each other's way. A **reservation** builds up towards **one**
payment (an insurance bill in December, a new laptop): what is reserved so far is held back from the headline number of its
scope. A **fixed cost** **repeats** (rent, a subscription, a quarterly premium): the *Fixed costs* page gives its monthly and yearly
totals per scope and says which of them are still to come this month, and the overview shows that total beside the headline
numbers, never subtracted from them. For the current month a fixed cost is *paid* once you mark it as paid, or once a matching booked payment arrives (give it the
counterparty's IBAN or name; the amount within 10 % and the date within 7 days), and *still to come* otherwise (unticking a matched
month wins over the match; "still to come" then shows how old the transactions behind it are); a yearly one is simply "next due" in the other months. Use a fixed cost for what comes back, and a reservation for the
one big bill you can see coming, not both for the same expense.

A fixed cost with a counterparty also tells you when something is off, and never changes by itself: a **price change** (the latest charge is
more than 10 % from the stored amount: take over the new price or keep the old one) and a **missing** charge (nothing arrived within 7 days
of its date, after a delivery that should have had it: mark it paid, skip this period or end the fixed cost). The overview shows one line
when any are open (price changes and missing charges, and the proposals below).

After each sync and import the app also looks for fixed costs you did not enter: booked spending to one counterparty that comes back every
month, two months, quarter, half year or year at about the same amount (within 10 %; at least three charges, two for half-yearly and yearly,
and still running). They are listed on the *Fixed costs* page as **proposals**, with when the latest charge was booked. *Add as fixed cost*
opens the add form filled in; *Not a fixed cost* hides the proposal until the price changes by more than 10 %, and the rejection can be
undone on the same page. A proposal changes no number until you add it, and a counterparty that already has a fixed cost is never proposed.

`GET /health` answers `200` with `{"status":"ok","version":"…"}` when the database is reachable, and `503` with `{"status":"unavailable","version":"…"}` otherwise.

One test class while working: `./mvnw -q test -Dtest=ClassName`.

Working on this repository with AI agents: [AGENTS.md](AGENTS.md) and [docs/workflow.md](docs/workflow.md).
