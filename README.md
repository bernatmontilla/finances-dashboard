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

`GET /health` answers `200` with the version when the database is reachable, and `503` otherwise.

One test class while working: `./mvnw -q test -Dtest=ClassName`.

Working on this repository with AI agents: [AGENTS.md](AGENTS.md) and [docs/workflow.md](docs/workflow.md).
