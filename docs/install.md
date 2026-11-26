# Installing and updating the dashboard

For someone with a small Linux machine (a single-board computer is the reference target). The
dashboard is one jar run as a systemd **user** service, bound to `127.0.0.1`. The machine fetches
its own updates from this repository's GitHub Releases; GitHub never connects to it. Why:
[decisions/arch-deploy.md](decisions/arch-deploy.md).

In this guide `<host>` is the name of your machine, `~` is the home folder of the user that runs the
service, and 8080 is an example port. Nothing here is specific to any installation.

## What you need

- **Java 25** (a JRE is enough): `java -version` must say 25.
- **systemd** with the user manager running, and **lingering** switched on for the user so the
  service starts at boot and keeps running when nobody is logged in:
  `loginctl enable-linger "$USER"` (once, as that user or with `sudo`).
- `curl`, `sha256sum`, `cmp` and `flock` (util-linux): all present on a normal Debian or Ubuntu
  system. `update.sh` checks for them first and exits 1, naming the missing one, so a machine
  without one shows a failed update unit instead of silently never updating.
- A normal user. Nothing here, and nothing in the update, runs as root.

## First install

1. **Folders.** The data folder holds the database, the secrets and the backups. Only you may read
   it, and updates never touch it:

   ```bash
   mkdir -p ~/finances-dashboard/app ~/finances-dashboard/data ~/.config/finances-dashboard
   chmod 700 ~/finances-dashboard/data ~/.config/finances-dashboard
   ```

2. **The files.** Copy `deploy/update.sh` and the three unit files from this repository (or from a
   release checkout) to their places:

   ```bash
   install -m 755 deploy/update.sh ~/finances-dashboard/update.sh
   mkdir -p ~/.config/systemd/user
   cp deploy/finances-dashboard.service deploy/finances-dashboard-update.service \
      deploy/finances-dashboard-update.timer ~/.config/systemd/user/
   systemctl --user daemon-reload
   ```

   The service unit caps the heap at 300 MB (`-Xmx300m`) and the whole process at 500 MB
   (`MemoryMax`); change both together if your machine has more or less to spare. Two things to
   check on your machine, because they depend on how its systemd is set up: `MemoryMax` in a user
   unit only works when the memory controller is delegated to the user manager (on some systems
   this needs a `user@.service` drop-in with `Delegate=memory`; `systemctl --user show
   finances-dashboard.service -p MemoryMax` and `systemd-cgtop` tell), and `PrivateTmp=yes` fails
   with status 226/NAMESPACE where unprivileged user namespaces are disabled (delete that line then).

   The settings go into `~/.config/finances-dashboard/env`, one `NAME=value` per line; both the
   service and the update read that file. Two are needed or useful:

   ```
   FINANCES_REPO=<owner>/<name>
   SERVER_PORT=8080
   ```

   `FINANCES_REPO` is the GitHub repository whose releases to install (the part after
   `github.com/` in its address); the update refuses to run without it. `SERVER_PORT` is the port
   of the app (default 8080) and also the one the update asks `/health` on.

3. **Install the first jar and start.** The update script does a first installation too: it
   downloads the latest release, checks its checksum, installs it, starts the service and waits for
   `/health` to report that version.

   ```bash
   systemctl --user enable finances-dashboard.service
   systemctl --user start finances-dashboard-update.service   # waits until it is done
   journalctl --user -u finances-dashboard-update.service -e
   ```

   (The update runs as a service of its own so that it reads the env file. Run by hand, put the
   settings in the environment: `set -a; . ~/.config/finances-dashboard/env; set +a`.)

4. **Look.** `curl http://127.0.0.1:8080/health` answers `{"status":"ok","version":"…"}`. The
   overview page is at the same address.

5. **Updates by themselves.** Switch on the daily check (random delay of up to two hours):

   ```bash
   systemctl --user enable --now finances-dashboard-update.timer
   systemctl --user list-timers finances-dashboard-update.timer
   ```

## Publishing the app on a private network

The app listens on `127.0.0.1` only, so nothing can reach it from the network until you publish it.
Publish it only on a private network that you control. With Tailscale, for example:

```bash
tailscale serve --bg 8080
```

serves it over HTTPS to your own devices at `https://<host>` (the exact address is printed by
`tailscale serve status`). This is an example, not part of the install: the app has no login of its
own, so anyone who can reach that address sees your finances. Do not use `tailscale funnel` or any
other way of publishing on the open internet.

## How an update works

`update.sh` asks `https://api.github.com` for the latest release of this repository. If it is
newer than the installed version it downloads `finances-dashboard.jar` and
`finances-dashboard.jar.sha256` to a temporary place inside `~/finances-dashboard/app`, verifies
the checksum, keeps the current jar as `previous.jar`, swaps, restarts the service and polls
`/health` (up to five minutes, because the first start after a release may run database
migrations) until it reports the new version. Only then is the new version recorded as installed.
If it does not report in time, the previous jar is put back and the service restarted; the run is
reported as failed, and that release is not tried again until a newer one appears. If a run is cut
off between the swap and the check (power cut, reboot), the next run restarts the service once and
settles it the same way; a run cut off before the swap is not treated as a failed release. Every
restart is preceded by `systemctl --user reset-failed`, so systemd's start limit after a crash loop
cannot block the rollback.

Nothing changes, and the run ends quietly, when there is no network, GitHub is unreachable or
rate-limited (the timer tries again next day), when the download is partial, and when the
installed version is already the latest. A checksum that does not match is refused and reported as
failed, with nothing swapped. Two runs at once are safe: the second leaves at once. The data folder
is never touched.

An automatic rollback puts the old jar back; it does not undo database migrations the new version
already ran. Make a backup before a release you are unsure of ([Backup and restore](#backup-and-restore)).

## Updating by hand

```bash
systemctl --user start finances-dashboard-update.service   # what the timer does; read the result with journalctl
# or directly, with the settings of the env file in the environment:
(set -a; . ~/.config/finances-dashboard/env; set +a; ~/finances-dashboard/update.sh)
```

## Rolling back

An update that fails its health check rolls itself back. To go back to the previous version by
hand, for an update that started fine but that you do not want:

```bash
systemctl --user stop finances-dashboard-update.timer finances-dashboard-update.service
cp ~/finances-dashboard/app/previous.jar ~/finances-dashboard/app/.rollback.jar
mv -f ~/finances-dashboard/app/.rollback.jar ~/finances-dashboard/app/finances-dashboard.jar
cp ~/finances-dashboard/app/previous-version ~/finances-dashboard/app/installed-version
systemctl --user reset-failed finances-dashboard.service
systemctl --user restart finances-dashboard.service
```

The jar is copied to a temporary file first and moved over with `mv -f`: copying straight over
`finances-dashboard.jar` would overwrite the file the running service has open.

Leave the timer off until a newer release exists, or the next run installs the same version again
(an automatic rollback remembers the failed release; a manual one does not). A database migration is never undone: if the new version changed the data, restore a backup made
before the update as well.

## What is in the data folder

Everything the installation owns is in one folder (`~/finances-dashboard/data` in this guide). Only
you may read it (`chmod 700`), and updates never touch it.

| In the data folder | What it is | In a backup? |
|---|---|---|
| `finances.db` | the database: accounts, balances, transactions, notes, everything entered by hand | yes |
| `finances.previous.db` | the database as it was before the last restore, so that one restore can be undone | no |
| `finances.replaced.db` | only for a moment during a restore; see below | no |
| `finances.lock` | lets only one instance use the folder | no |
| `tmp/` | uploads and backups in the making; emptied at every start, never web-visible | no |
| `secrets/enable-banking-application-id` | the id of your Enable Banking application (you put it there) | **no, on purpose** |
| `secrets/enable-banking-key.pem` | the application's private key (you put it there) | **no, on purpose** |
| `secrets/enable-banking-session-<n>` | the session id of bank consent `<n>`, written by the app | **no, on purpose** |
| `secrets/after-tax-token` | the token of the after-tax source, optional (you put it there) | **no, on purpose** |

The secret files are plain files in the data folder, readable by the service user only, never in a
log and never in a backup file. Each issue that adds one lists it in this table.

## Linking your banks (Enable Banking)

Optional: the dashboard is fully usable with CSV import and manual balances. To have balances and
transactions fetched by themselves, the dashboard reads your accounts, **read only**, through Enable
Banking (the application asks for balances and transactions and has no code that could start a
payment). Enable Banking is free for your own accounts, but only for **personal use**: its terms
do not cover business accounts, which should keep CSV import or manual entry.

Nothing here is specific to an installation: `<host>`, `<application id>` and 8080 are placeholders.

1. **Create an application.** In the Enable Banking control panel (find it from enablebanking.com) register an
   account, then a **production** application. Registration asks for a description, a data-protection
   e-mail address, a privacy-policy address and a terms address: for an application that only links your own
   accounts these are not checked, fill in anything valid. Generate a key and a certificate, upload the
   **certificate**, and note the **application id** (a UUID). With OpenSSL, for example:

   ```bash
   openssl req -x509 -newkey rsa:4096 -nodes -keyout enable-banking-key.pem -out certificate.pem \
     -days 730 -subj "/CN=finances-dashboard"
   ```

   The key file may be the old form (`BEGIN RSA PRIVATE KEY`) or the new one (`BEGIN PRIVATE KEY`); both work.
   Keep the key file to yourself: it, with a session id, reads your accounts.

2. **Register the redirect address.** After you give consent at your bank, your browser is sent back to the
   dashboard. Register `http://<host>:8080/settings/bank/callback` (the address you open the dashboard with, with
   the port of `SERVER_PORT`) as a redirect address of the application, and tell the dashboard the same in
   `~/.config/finances-dashboard/env`:

   ```
   FINANCES_SYNC_REDIRECT_URL=http://<host>:8080/settings/bank/callback
   ```

   **Not yet tried:** whether Enable Banking accepts a plain `http` address, a private name or `localhost` as a redirect
   address is not stated in its documentation. This is the first thing to try. If it refuses, there is nothing to work
   around it yet: say so on the issue tracker.

3. **Link your own accounts in the control panel.** In the application, use *Activate by linking accounts* and
   link every account the dashboard should fetch. Only accounts linked there come back through the dashboard; any other
   account is silently left out, and the dashboard then says that the bank returned no accounts.

4. **Put the two files in place** (the data folder is the one of step 1 of the first install):

   ```bash
   mkdir -p ~/finances-dashboard/data/secrets
   chmod 700 ~/finances-dashboard/data/secrets
   install -m 600 enable-banking-key.pem ~/finances-dashboard/data/secrets/enable-banking-key.pem
   printf '%s\n' '<application id>' > ~/finances-dashboard/data/secrets/enable-banking-application-id
   chmod 600 ~/finances-dashboard/data/secrets/enable-banking-application-id
   systemctl --user restart finances-dashboard.service
   ```

   *Settings*, *Bank connection* now says an application is configured. It never shows the id or the key.

5. **Link a bank.** *Settings*, *Bank connection*, *Link a bank*: choose the country and the bank from the list Enable
   Banking offers, give consent at the bank, and come back to a page that lists the accounts the consent covers. For each,
   create a new account (you choose name, scope and kind) or attach it to an existing account without a bank link
   (an account with the same IBAN is proposed; its history stays). Nothing is created until you confirm, and the first
   sync starts at once: do it **within the hour**, because banks give the full history only for about an hour after the
   consent (later fetches often reach back 90 days only). Accounts in another currency than the euro are listed as not supported.

6. **What runs by itself.** A daily sync at `FINANCES_SYNC_TIME` (HH:mm, default `04:30`, your time zone) and *Sync now* on
   the overview. Both fetch what is new since the last successful sync, with an overlap of `FINANCES_SYNC_OVERLAP_DAYS`
   days (default 7). The daily sync is a *background* fetch: many banks allow about four a day per account, and answer
   a rate-limited fetch with an error, which the settings page lists as such (wait about six hours). *Sync now* sends your
   browser's own details to the bank, as a person using the page, when the bank's required headers can all be sent. The
   settings page lists the last runs per account: when, which kind, how it ended and what it stored. `FINANCES_SYNC_ENABLED=false`
   switches the daily run off.

7. **Consent lasts at most 180 days** (less at some banks). Before it ends, or after, the account shows *consent expired*
   and keeps all its history; *Renew* runs the authorisation again and re-attaches the same accounts. *Unlink* removes the
   consent and turns the accounts into accounts entered by hand, keeping their history.

The secret files are **not in a backup**. After restoring on another machine the consents show *consent expired*: renew
them, or copy the files of the table above by hand.

## Connecting an after-tax source

The dashboard does no tax calculation. For the business side it reads one number, *after-tax available*, from an
external bookkeeping tool through `docs/contracts/after-tax-source.md`. Without a source nothing about a business number is shown.

1. **The address.** Set `finances.outbound.after-tax.url` (environment variable `FINANCES_OUTBOUND_AFTER_TAX_URL`) to the
   endpoint, for example `https://<host>/after-tax`. It must be an `http` or `https` address of a host, with no user name,
   no query and no `#` part (a key does not belong in the address: use the token). A host on this machine or on your private
   network is the normal case and is allowed because you configured it. Anything else stops the application at start
   with a message that names the property and not its value. Nothing is ever sent to any other host.
2. **The token** (only when the source wants one). Put it in `secrets/after-tax-token` in the data folder, one line,
   readable by the service user only:

   ```bash
   install -m 600 /dev/null ~/finances-dashboard/data/secrets/after-tax-token
   printf '%s\n' '<token>' > ~/finances-dashboard/data/secrets/after-tax-token
   ```

   It is sent as `Authorization: Bearer <token>` and read again on every fetch, so a changed token needs no restart. Without
   the file no `Authorization` header is sent. A token that holds anything but visible characters (a line break in the middle,
   a space) is not sent: the fetch fails with *the token file cannot be sent as it is*. The token is never shown on a page,
   never logged and never in a backup. Over plain `http` to a host that is not this machine the token travels unencrypted:
   the settings page warns about it; use `https` or a host on this machine.
3. **Try it.** *Settings*, *After-tax source*, **Test the source** fetches once, waits for the answer (at most 10 seconds)
   and shows the value with the moment the source calculated it and its age, or the reason it could not be used. A valid answer
   is stored like any other. The source is also fetched with the daily sync and with *Sync now*, on its own thread: a slow
   or hanging source never delays the bank sync.
4. **What the section shows.** Whether a source and a token are configured (never the address or the token), the last value,
   the gross balance and the reserved lines when the source sends them, a warning when gross minus reserved is not the
   available amount, the last ten fetches, and the *Stale* mark with its reason: the last fetch failed (the last good value
   is kept), or the value's own moment is older than `finances.freshness.after-tax-hours` (48 by default). Every distinct
   value received is kept with its moment, so later versions can chart it.

**Trying it with a static file.** A static file served on this machine is a valid source. Make the file with a recent
`as_of` (an old one is shown as stale) and serve it on the loopback address:

```bash
mkdir -p /tmp/after-tax && cd /tmp/after-tax
printf '{"contract_version": 1, "as_of": "%s", "currency": "EUR", "available_cents": 1234500, "gross_balance_cents": 2000000, "reserved": [{"label": "VAT", "amount_cents": 420000}, {"label": "Income tax", "amount_cents": 345500}]}\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > after-tax.json
python3 -m http.server 8099 --bind 127.0.0.1
# then: finances.outbound.after-tax.url=http://127.0.0.1:8099/after-tax.json
```

Press *Test the source*: the page shows €12,345.00, the gross balance of €20,000.00 and the two reserved lines. Stop the
web server and press the button again: the same value is shown as *Stale* with *the source could not be reached*. A
source that does not follow the contract is refused with a reason (the amounts must be JSON integers, the currency `EUR`, the
version `1`; a moment more than five minutes in the future is refused too).

## Backup and restore

A backup is **one file**: a zip with a small description (`manifest.properties`: format version,
application version, database version, when it was made, a checksum) and a consistent snapshot of
the database. It is made while the application runs, also while a sync or an import is running. It
holds all your financial data, so keep it where only you can read it.

**It does not hold the secrets.** After restoring on a new machine the bank links must be renewed.
To keep the secrets too, copy the secret files of the table above to the same place by hand.

### On the settings page

*Settings*, *Backup*: **Download backup** gives the file. **Restore** takes such a file, checks it
completely (a damaged, cut-off or foreign file, or one made by a newer version, is refused and
changes nothing), shows what it holds and what it will replace, and asks you to type `RESTORE`.
Then it replaces the database, brings it up to date with this version's migrations, and keeps the
database from before as `finances.previous.db`: **Undo the last restore** puts it back (and the
restore that was undone is kept in its place, so you can go forward again).

A backup made by an older version always restores in a newer one. One made by a newer version than
the running one is refused with a message; update first.

### From the command line, for a scheduled copy to another disk

The jar does both without the web page. The service unit sets the data folder in its own
environment, so give it with `--data-dir` (or `FINANCES_DATA_DIR`):

```bash
# Safe while the service runs. A folder as target gives finances-backup-<UTC time>.zip in it;
# an existing file is never overwritten.
java -Xmx128m -jar ~/finances-dashboard/app/finances-dashboard.jar backup \
  --data-dir ~/finances-dashboard/data /mnt/backup-disk/

# Restoring needs the service stopped: it takes the data folder's lock and says so when it is in use.
systemctl --user stop finances-dashboard.service
java -Xmx128m -jar ~/finances-dashboard/app/finances-dashboard.jar restore \
  --data-dir ~/finances-dashboard/data /mnt/backup-disk/finances-backup-<time>.zip      # shows what it holds, changes nothing
java -Xmx128m -jar ~/finances-dashboard/app/finances-dashboard.jar restore \
  --data-dir ~/finances-dashboard/data /mnt/backup-disk/finances-backup-<time>.zip --yes
systemctl --user start finances-dashboard.service
# Undo: java ... undo-restore --data-dir ~/finances-dashboard/data --yes
```

Exit status: 0 done, 1 refused or failed (nothing changed), 2 wrong use or `--yes` missing. For a
daily copy, put the `backup` line in a cron job or a systemd user timer of your own, and prune old
files there; the application never deletes your backups.

A restore briefly needs room for the uploaded file about three times over in `tmp/` (the upload, its copy and the checked database), so leave free space of a few times the backup size on the disk of the data folder; the command line restore needs one copy less.

If the disk is full, a backup fails without leaving a partial file and the database in use is
untouched; so is a restore. A restore is refused before it starts when the data folder has less free
space than the backup's database plus the larger of 16 MiB and the database's own size again (the
size is what the file states about itself, so it is checked against the disk first). If a restore is cut off by a power failure at the very moment of the swap, the next start puts the
database from before back (`finances.replaced.db` becomes `finances.db` when there is no
`finances.db`). If a file `finances.replaced.db` is still there next to a working database, an
earlier restore could not finish tidying up: the file may hold data that exists nowhere else, so a
restore or undo is refused until you have looked at it. Keep it somewhere safe or delete it, with the
service stopped.

If a restore or an undo fails and the message says the database that was in use could not be put back,
that database is kept as `finances.replaced.db` and nothing was deleted. Do not delete or overwrite
any file before you have read this. Stop the service first (`systemctl --user stop finances-dashboard.service`);
the `finances.db-wal` and `finances.db-shm` files next to `finances.db` belong to the half-finished
database and may go. What to do depends on what you pressed:

- **A restore failed**: `finances.db` holds the half-migrated copy of the backup (you still have the
  backup file) and `finances.replaced.db` holds your data. Run
  `rm -f finances.db-wal finances.db-shm && mv -f finances.replaced.db finances.db`, start the service.
- **An undo failed**: `finances.db` holds the earlier database you were going back to, which may exist
  nowhere else, and `finances.replaced.db` holds what was in use. Do not move anything over
  `finances.db`. Run `rm -f finances.db-wal finances.db-shm && mv -n finances.db finances.previous.db &&
  mv -n finances.replaced.db finances.db`, start the service: the earlier database is back under its own name
  (the undo can be tried again, or the file kept) and your data is in use again. If `mv -n` says nothing and
  `finances.previous.db` is still not there, stop and ask for help; do not delete anything.

## Where the logs are

```bash
journalctl --user -u finances-dashboard.service -e          # the app
journalctl --user -u finances-dashboard-update.service -e   # the last update runs
systemctl --user status finances-dashboard.service
```

The logs carry no secrets and no amounts. If the user journal is not kept across reboots on your
machine, create `/var/log/journal` as root once (`sudo mkdir -p /var/log/journal`) or read
`journalctl --user` while the machine is up.
