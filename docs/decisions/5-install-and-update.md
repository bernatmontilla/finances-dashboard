# #5 Install guide, systemd templates and the self-update script

- **Layout** (all under the installer's home, `%h` in units, `~` in docs): `~/finances-dashboard/app/`
  holds `finances-dashboard.jar`, `previous.jar`, `installed-version`, `previous-version` and the lock;
  `~/finances-dashboard/data` is the data folder; `~/finances-dashboard/update.sh` is the script;
  `~/.config/finances-dashboard/env` is an optional `NAME=value` file read by both units (the port).
  `update.sh` only ever works inside the app folder, so the data folder cannot be touched by it.
- **Installed version** is a file written at the swap, not asked of the running app: it works while
  the service is down. A missing file (or jar) means "unknown", so the script installs: that is also
  the first installation.
- **Latest release** is read from the GitHub API with `curl`; the tag is taken with `sed` (no `jq`)
  and must be `vMAJOR.MINOR.PATCH` before it is used in a path or URL. "Newer" is `sort -V`; an
  installed version above the latest release is left alone.
- **Exit codes**: 0 for nothing to do and for "could not check or download" (no network, rate limit,
  partial download: the timer tries again); 1 for refused (checksum mismatch, root) and for a
  rollback, so the journal shows the run as failed.
- **Rollback** puts `previous.jar` back, restores the version file and restarts. With no previous jar
  (a failed first installation) there is nothing to put back; the script says so and exits 1.
- **Lock**: `flock` (util-linux) on `.update.lock` in the app folder; a second run exits 0. This is
  the one tool beyond `curl` and `sha256sum`; it is part of util-linux on every systemd machine.
- **Memory**: `-Xmx300m`, `MemoryHigh=420M`, `MemoryMax=500M`. Assumed, to be tuned on the real
  machine (checked after deploy).
- **Tests**: `scripts/dev/test-update.sh` (run by `scripts/dev/build.sh`) drives `update.sh` with a
  fake release folder, a fake `systemctl` and a fake health file through `file://` URLs and
  `FINANCES_*` environment settings. `FINANCES_UID` exists only so the test can run as root in a
  container and still show that uid 0 is refused. A guard test (`GuardTest`) rejects home paths,
  addresses and hosts other than the allowed ones in `deploy/`, `docs/install.md` and the test script.
- **Not verified here**: `systemd-analyze verify --user` cannot start a user manager in the cloud
  sandbox; `systemd-analyze verify` (system mode) passed, with only the expected complaint that the
  installed `update.sh` does not exist in the sandbox.
- **Known limit**: as in `arch-deploy.md`, the checksum protects against a broken download, not a
  compromised repository.
