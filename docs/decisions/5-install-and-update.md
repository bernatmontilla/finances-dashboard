# #5 Install guide, systemd templates and the self-update script

- **Layout** (all under the installer's home, `%h` in units, `~` in docs): `~/finances-dashboard/app/`
  holds `finances-dashboard.jar`, `previous.jar`, `installed-version`, `previous-version` and the lock;
  `~/finances-dashboard/data` is the data folder; `~/finances-dashboard/update.sh` is the script;
  `~/.config/finances-dashboard/env` is an optional `NAME=value` file read by both units (the port).
  `update.sh` only ever works inside the app folder, so the data folder cannot be touched by it.
- **Installed version** is a file, not asked of the running app: it works while the service is down.
  It is written only after the new version reported healthy. Before the swap a `pending-version`
  marker is written; a run that finds one first settles it (healthy: recorded; otherwise rolled
  back), so a run cut off by systemd or a power cut never leaves an unchecked jar as current.
  `failed-version` remembers a rolled-back release so it is not retried daily; a newer one clears it. A missing file (or jar) means "unknown", so the script installs: that is also
  the first installation.
- **Latest release** is read from the GitHub API with `curl`; the tag is taken with `sed` (no `jq`)
  and must be `vMAJOR.MINOR.PATCH` before it is used in a path or URL. "Newer" is `sort -V`; an
  installed version above the latest release is left alone.
- **Exit codes**: 0 for nothing to do and for "could not check or download" (no network, rate limit,
  partial download: the timer tries again); 1 for refused (checksum mismatch, root) and for a
  rollback, so the journal shows the run as failed.
- **Rollback** puts `previous.jar` back and restarts. It cannot undo migrations the new version ran
  before it failed; the health wait is five minutes so a slow first start is not mistaken for a
  failure, and `docs/install.md` says to back up before a doubtful release. A backup before the swap
  belongs with the backup feature (later issue). With no previous jar
  (a failed first installation) there is nothing to put back; the script says so and exits 1.
- **No owner name in the repository**: `FINANCES_REPO` (owner/name) has no default and is set in the
  installation's env file; the script refuses to run without it (hard rule 1). The owner may decide
  that the public repository's own slug is fine as a default; it is a one-line change.
- **Downloads** use `--proto =https --proto-redir =https`; `FINANCES_CURL_PROTO` exists for the
  `file://` test setup. The checksum is compared explicitly (first field of the `.sha256` file against
  the hash of the downloaded jar), so a sum file naming another file or empty is refused.
- **Time budget**: download limit 300 s each, health wait 300 s, a rollback another 300 s; the update
  unit's `TimeoutStartSec` is 30 minutes.
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

## Hardening after the review in CI (issue #27)

The review of PR 26 found ways to end on a failed service or on a silent "never updates". Changes,
all inside `deploy/update.sh`, `scripts/dev/test-update.sh` and `docs/install.md`; the asset names,
the folder layout and the existing exit codes are unchanged.

- **Crash loop**: `restart()` runs `systemctl --user reset-failed` first (failure ignored), so
  systemd's start limit cannot refuse the rollback's restart.
- **Cut-off run**: with `pending-version` left behind the next run first probes the health once. If
  the pending version is not reporting it restarts the service once, then waits as before. A
  pending version that never reports is rolled back only when the jar differs from `previous.jar`
  (`cmp -s`); if they are the same file the swap never happened, so the marker is dropped, no
  `failed-version` is written, and the run goes on with a normal update. The probe before the
  restart (not in the spec's wording) keeps a service that already runs the pending version from
  being restarted for nothing, and the existing "pending healthy: no restart" case unchanged.
- **Missing tool**: `flock`, `curl` and `sha256sum` are checked up front, exit 1 naming the missing
  one; `cmp` is checked too since the cut-off case depends on it. This is a new exit 1.
- **Manual rollback** in `docs/install.md` copies to a temporary file in `app/` and `mv -f`s it over,
  so the jar the JVM has open is replaced, not overwritten.
- **Test-only switches**: `FINANCES_CURL_PROTO`, `FINANCES_UID` and `FINANCES_SYSTEMCTL` are read
  only when `FINANCES_TEST=1`, which `scripts/dev/test-update.sh` sets; otherwise each one that is
  set is named in a log line as ignored. This replaces the sentence above about `FINANCES_UID`.

