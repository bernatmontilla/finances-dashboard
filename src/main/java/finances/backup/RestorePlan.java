package finances.backup;

/**
 * A backup file that was checked completely and is waiting for the owner's confirmation.
 *
 * @param token   names the staged database; only the service resolves it
 * @param backup  what the backup holds
 * @param current what the database in use holds now, which a restore replaces
 */
public record RestorePlan(String token, Manifest manifest, Summary backup, Summary current) {
}
