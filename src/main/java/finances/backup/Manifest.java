package finances.backup;

import finances.backup.BackupException.Reason;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Properties;

/**
 * The small description at the front of a backup file: {@code manifest.properties}, plain
 * {@code key=value} lines. The format is a public interface (AGENTS.md, hard rule 4): keys are only
 * ever added, a reader ignores keys it does not know, and {@code formatVersion} goes up only when
 * an older reader could no longer make sense of the file.
 *
 * @param formatVersion   the layout of the file (1: a zip with this manifest and the database)
 * @param applicationVersion the version of the application that made it
 * @param schemaVersion   the newest database migration the snapshot has
 * @param createdAt       when it was made (UTC)
 * @param databaseSha256  checksum of the database file inside, so a damaged file is refused
 * @param databaseBytes   size of the database file inside
 */
public record Manifest(int formatVersion, String applicationVersion, String schemaVersion, Instant createdAt,
        String databaseSha256, long databaseBytes) {

    static final String FORMAT = "finances-backup";

    String toText() {
        Properties properties = new Properties();
        properties.setProperty("format", FORMAT);
        properties.setProperty("formatVersion", Integer.toString(formatVersion));
        properties.setProperty("applicationVersion", applicationVersion);
        properties.setProperty("schemaVersion", schemaVersion);
        properties.setProperty("createdAt", createdAt.toString());
        properties.setProperty("databaseSha256", databaseSha256);
        properties.setProperty("databaseBytes", Long.toString(databaseBytes));
        StringWriter out = new StringWriter();
        try {
            properties.store(out, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // Properties.store writes a date comment line; it is noise in a file that has createdAt.
        return out.toString().lines().filter(line -> !line.startsWith("#")).reduce("", (a, b) -> a + b + "\n");
    }

    /** Reads a manifest; a newer format version is reported before anything else is checked. */
    static Manifest parse(String text, int supportedFormatVersion) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            throw new BackupException(Reason.NOT_A_BACKUP, "The file is not a backup of this application.");
        }
        if (!FORMAT.equals(properties.getProperty("format"))) {
            throw new BackupException(Reason.NOT_A_BACKUP, "The file is not a backup of this application.");
        }
        int version;
        try {
            version = Integer.parseInt(properties.getProperty("formatVersion", "").strip());
        } catch (NumberFormatException e) {
            throw new BackupException(Reason.NOT_A_BACKUP, "The file is not a backup of this application.");
        }
        if (version > supportedFormatVersion) {
            throw new BackupException(Reason.NEWER_FORMAT, "This backup was made by a newer version of the application (backup format "
                    + version + ", this version reads up to " + supportedFormatVersion + "). Update the application first.");
        }
        if (version < 1) {
            throw new BackupException(Reason.NOT_A_BACKUP, "The file is not a backup of this application.");
        }
        try {
            String sha = required(properties, "databaseSha256");
            if (!sha.matches("[0-9a-f]{64}")) {
                throw damaged();
            }
            long bytes = Long.parseLong(required(properties, "databaseBytes"));
            if (bytes < 0) {
                throw damaged();
            }
            return new Manifest(version, required(properties, "applicationVersion"), required(properties, "schemaVersion"),
                    Instant.parse(required(properties, "createdAt")), sha, bytes);
        } catch (NumberFormatException | DateTimeParseException e) {
            throw damaged();
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw damaged();
        }
        return value.strip();
    }

    private static BackupException damaged() {
        return new BackupException(Reason.DAMAGED, "The backup's description is incomplete: the file is damaged.");
    }
}
