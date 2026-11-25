# 32: a restore that goes wrong on a full disk

Follows `12-backup-and-restore.md`. Only `finances/backup` and its tests changed; the backup file
format did not.

## Rollback after a failed migration (`BackupService.rollBack`)

- The steps that undo a failed swap no longer depend on each other. Each has its own `try`; a failure
  of one is added to the original error with `addSuppressed` and the next step still runs. The original
  error (the migration's) is the cause of the `BackupException` that is shown.
- A restore (source: a staged copy): the old database (`finances.replaced.db`) is put back first and
  replaces the half-migrated file, so a failing clean-up step cannot keep it from being put back.
- An undo (source: `finances.previous.db`, the only copy of that data): the half-migrated file is first
  renamed back to its source; when that fails the old database is **not** put back over it
  (hard rule 10). Both files stay; the old one as `finances.replaced.db`.
- Journal files: the half-migrated file's `-wal`/`-shm` are deleted before the old database is put back.
  When that fails the old database is **not** put back (SQLite would replay the frames of another
  database into it); it stays as `finances.replaced.db`. This is stricter than the spec ("always put it
  back"): a corrupted old database is worse than one that stays aside, and the cases are the same ones
  where a rename would fail too. Owner: say on the issue if you want it otherwise.
- Whenever the old database is not put back the new reason `OLD_DATABASE_ASIDE` is shown instead of
  "the old database is back in use": it is kept as `finances.replaced.db`, nothing is deleted, the
  service must be stopped (`docs/install.md`). The half-migrated file is moved back to `finances.db`
  if it had been moved away, because the application creates an empty `finances.db` when none exists.
- Test seam: `BackupService.Disk` (package-private) holds the moves, deletes and the free-space
  question of a restore, so tests make them fail the way a full disk does.

## A file that states its own size

- Before anything is extracted, `inspect` refuses with the new reason `NOT_ENOUGH_SPACE` when the usable
  space of the data folder's file store is below `databaseBytes + max(16 MiB, databaseBytes)`. The
  margin is written down in `BackupService.SPACE_MARGIN`: the staged copy needs `databaseBytes`, and
  migrating it (a table rebuilt next to the old one, the journal) can need about as much again. The
  sum saturates, so a crafted `databaseBytes` near `Long.MAX_VALUE` cannot overflow into "enough".
- More bytes than the manifest states: `extract` already stopped at the first byte over `databaseBytes`
  and refused with *damaged* (nothing is written past the stated size, the staged file is deleted).
  A test now pins this. It stays *damaged* and not *not enough space*: the disk is not the problem,
  the file is lying.
- The check applies to the web upload and to the command line alike (both go through `inspect`). The
  uploaded zip itself is already on disk when the check runs; a general upload size limit is out of
  scope (spec).
- No amount, name or path content is in the messages; the new texts are in `messages.properties`.

## Known limits (from the risk check, not fixed here)

- A restore into a data folder with no database, whose migration fails and whose half-migrated file cannot
  be renamed back, still reports "the old database is back in use" although there was none; no data is at
  risk (the half-migrated file is the only file, and the backup file is the owner's).
- `DataFolder.recoverInterruptedRestore` puts `finances.replaced.db` back at the next start without looking
  for journal files of another database. It needs three failures in a row (rename back, journal delete,
  move back) and lives outside `finances/backup`; a follow-up issue if wanted.
- `docs/install.md` has the recovery steps for a restore and for an undo, which differ: after a failed undo
  `finances.db` holds the earlier database and must not be overwritten.
