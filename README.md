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

`GET /health` answers `200` with `{"status":"ok","version":"…"}` when the database is reachable, and `503` with `{"status":"unavailable","version":"…"}` otherwise.

One test class while working: `./mvnw -q test -Dtest=ClassName`.

Working on this repository with AI agents: [AGENTS.md](AGENTS.md) and [docs/workflow.md](docs/workflow.md).
