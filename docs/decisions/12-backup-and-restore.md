# #12 Backup to one file and restore from it

- **Where**: `finances.backup` (already under the `risk:high` globs): `BackupService` (all rules),
  `Manifest`, `PreparedBackup`, `RestorePlan`, `Summary`, `BackupException`, `BackupCommand` (command
  line), `DatabaseGate` + `GatedDataSource` (see below), `BackupConfig`. Web: `SettingsController`,
  `settings.html`, `restore-confirm.html`. `finances.data` changed slightly (`DataConfig`, `DataFolder`);
  note that `src/*/java/finances/data/**` is **not** in `labeler.yml` although it decides where the
  database is and how connections are handed out: worth adding in the next planning round (a worker
  may not edit `.github/`).
- **The file** (format version 1, a public interface, hard rule 4): a zip with `manifest.properties`
  first and `finances.db` second. Manifest keys: `format=finances-backup`, `formatVersion`,
  `applicationVersion`, `schemaVersion` (newest applied Flyway version), `createdAt` (UTC),
  `databaseSha256`, `databaseBytes`. Plain properties, not JSON: no parser to depend on, readable
  by hand. Keys are only added; a reader ignores keys it does not know; `formatVersion` goes up
  only when an old reader could no longer read the file, and a reader refuses a larger number
  with a clear message. `java.util.zip` only: no new dependency.
- **A consistent snapshot while running**: `VACUUM INTO` a file in `<data>/tmp/`. In WAL mode it reads one
  committed state and does not block the writer, so a sync or import running at the time is either in
  it completely or not at all (a test writes pairs of rows in one transaction during eight backups
  and checks none is torn). The snapshot is checked with `integrity_check`, hashed, streamed into the
  zip and deleted. The database is never loaded into memory; the file is streamed to the browser.
  A failure while making the snapshot (disk full) happens before the first byte is sent and leaves
  nothing behind.
- **Secrets are not in it**: the backup holds `finances.db` only. The page says so, and that bank links
  must be renewed after a restore on a new machine; `docs/install.md` lists the data folder's files.
- **Restore is all or nothing**: step 1 (no effect on the running app) copies the upload into
  `<data>/tmp/` (never the system's temporary folder: `DataConfig` gives Tomcat's multipart handling
  that location and no size limit), then checks: starts with a zip header (else *not a backup*),
  opens as a zip (else *damaged*: a cut-off zip has no end record), manifest with the right `format`
  (else *not a backup*), `formatVersion` not newer (else *newer format*), database entry extracted
  with the declared size and SHA-256 (else *damaged*), SQLite `integrity_check`, schema version
  equal to the manifest's and not newer than the newest migration shipped in this jar (else
  *newer schema*). Only what passed is kept, as `restore-<token>.db`; the web page then shows the
  manifest and what is replaced and asks for the word `RESTORE`. A new upload replaces an earlier
  staged one.
- **The swap** (step 2): with the database to itself (`DatabaseGate`), checkpoint and remove the WAL,
  move `finances.db` aside, move the checked file in, run Flyway on it. Success: the old file becomes
  `finances.previous.db` (replacing an older one: one level of undo). Any failure (also an old migration
  history that no longer matches, tested): the restored file is moved back out and the old one back
  in, and the error says nothing was changed. All steps are renames inside one folder: disk full
  cannot hit the swap, only the earlier copy.
- **Undo** is the same swap with `finances.previous.db` as the source, so the database that was in use
  takes its place and the undo can be undone. It asks for the word `UNDO`.
- **Reloading the app's state**: the data source is an unpooled `SQLiteDataSource` and the services keep
  no state, so nothing needs a restart. What does need care is a connection open during the swap:
  `GatedDataSource` wraps every connection and holds one permit of a fair semaphore until it is
  closed; the restore takes all permits (30 s, then *busy*, nothing changed), new connections wait
  for it. Not a read-write lock, because a connection may be closed by another thread.
- **Command line**: `java -jar finances-dashboard.jar backup|restore|undo-restore`, handled in `main`
  before Spring starts (no web server, small heap). `backup` takes no lock, so it works while the
  service runs; `restore` and `undo-restore` take the data folder's lock, so they refuse while the
  service runs. Restore needs `--yes`; without it it shows what it holds and exits 2. A backup never
  overwrites a file and appears under its name only when complete.
- **Logs**: none. The backup code has no logger and the guard test `theBackupCodeNeverLogsAndOnlyTheCommandLinePrints`
  fails the build if one appears; the command line prints versions and counts, never names or amounts.
  Error messages are fixed texts.
- **Data folder additions** (backwards-compatible): `finances.previous.db`, `tmp/` (emptied at every
  start). A restore never touches the lock file or secret files.
- **Fixture**: `src/test/fixtures/backup/format-1.zip` (not under `src/test/resources`: `GuardTest` reads that folder as text) is a backup of format 1 made by this version
  from the fictional set; `theStoredFormat1BackupStillRestores` restores it and checks the data and the
  note. Never regenerate it; when the format changes, add a second fixture next to it.
- **Left out on purpose**: scheduled backups inside the app, encryption of the file, CSV export (spec);
  a progress bar for a restore (it takes a moment on a small database); recovery by the app itself of a
  restore cut off by a power failure between two renames (`finances.replaced.db` is documented in
  `docs/install.md`).
