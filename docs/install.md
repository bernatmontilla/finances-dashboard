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
- `curl`, `sha256sum` and `flock` (util-linux): all present on a normal Debian or Ubuntu system.
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
off between the swap and the check (power cut, reboot), the next run settles it the same way.

Nothing changes, and the run ends quietly, when there is no network, GitHub is unreachable or
rate-limited (the timer tries again next day), when the download is partial, and when the
installed version is already the latest. A checksum that does not match is refused and reported as
failed, with nothing swapped. Two runs at once are safe: the second leaves at once. The data folder
is never touched.

An automatic rollback puts the old jar back; it does not undo database migrations the new version
already ran. Make a backup before a release you are unsure of.

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
systemctl --user stop finances-dashboard-update.timer
cp ~/finances-dashboard/app/previous.jar ~/finances-dashboard/app/finances-dashboard.jar
cp ~/finances-dashboard/app/previous-version ~/finances-dashboard/app/installed-version
systemctl --user restart finances-dashboard.service
```

Leave the timer off until a newer release exists, or the next run installs the same version again
(an automatic rollback remembers the failed release; a manual one does not). A database migration is never undone: if the new version changed the data, restore a backup made
before the update as well.

## Where the logs are

```bash
journalctl --user -u finances-dashboard.service -e          # the app
journalctl --user -u finances-dashboard-update.service -e   # the last update runs
systemctl --user status finances-dashboard.service
```

The logs carry no secrets and no amounts. If the user journal is not kept across reboots on your
machine, create `/var/log/journal` as root once (`sudo mkdir -p /var/log/journal`) or read
`journalctl --user` while the machine is up.
