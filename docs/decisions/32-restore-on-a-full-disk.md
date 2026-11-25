# 32: a restore that goes wrong on a full disk

Follows `12-backup-and-restore.md`. Only `finances/backup` and its tests changed; the backup file
format did not.

## Rollback after a failed migration (`BackupService.rollBack`)

- The steps that undo a failed swap no longer depend on each other. Each has its own `try`; a failure
  of one is added to the original error with `addSuppressed` and the next step still runs. The original
  error (the migration's) is the cause of the `BackupException` that is shown.
- The put-back of the old database (`finances.replaced.db` to `finances.db`) always runs, whatever the
  other steps did. It replaces the half-migrated file, so it works even when that file could not be
  moved away.
- Difference from the spec's wording ("put the old database back first"): the half-migrated file is
  first renamed back to its source (own `try`), then the sidecars are deleted, then the old database is
  put back. Order matters only when everything succeeds: an undo's source is `finances.previous.db`,
  the only copy of that data, and the put-back would overwrite it. When the rename fails, the put-back
  still happens and the half-migrated copy is dropped (a restore keeps the owner's backup file; an
  undo loses the half-migrated previous database; the rename is within one folder and fails only in
  odd cases).
- When the put-back itself fails the message is no longer "the old database is back in use": it is the
  new reason `OLD_DATABASE_ASIDE` (the old database is kept as `finances.replaced.db`, nothing is
  deleted; a later restore refuses with *leftover*, and `DataFolder.open` puts it back at the next
  start when no database is there).
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
