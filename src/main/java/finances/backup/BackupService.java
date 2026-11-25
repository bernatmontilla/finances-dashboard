package finances.backup;

import finances.backup.BackupException.Reason;
import finances.data.DataFolder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;
import org.sqlite.SQLiteDataSource;

/**
 * Backup to one file and restore from it (issue #12, docs/decisions/12-backup-and-restore.md).
 *
 * <p>A backup is a zip with two entries: {@code manifest.properties} ({@link Manifest}) and
 * {@code finances.db}, a snapshot made with SQLite's {@code VACUUM INTO}, which reads one consistent
 * state while the application keeps writing. Secrets are not in it. A restore checks the file
 * completely first (zip, manifest, checksum, SQLite's own integrity check, schema version) in the
 * scratch folder inside the data folder, and only then, with the database to itself, swaps the
 * file: the old database becomes {@code finances.previous.db}, the restored one is migrated, and
 * when that fails the old one is put back. Nothing here logs: a backup is the owner's complete
 * financial data (AGENTS.md, hard rules 10 and 11).
 */
public final class BackupService {

    /** The newest backup layout this application reads and writes. */
    public static final int FORMAT_VERSION = 1;
    public static final String RESTORE_WORD = "RESTORE";
    public static final String UNDO_WORD = "UNDO";

    static final String MANIFEST_ENTRY = "manifest.properties";
    static final String DATABASE_ENTRY = "finances.db";

    private static final String DATABASE_FILE = "finances.db";
    private static final String PREVIOUS_FILE = "finances.previous.db";
    private static final String SCRATCH = "tmp";
    private static final Duration GATE_WAIT = Duration.ofSeconds(30);
    private static final int MANIFEST_LIMIT = 16 * 1024;
    /**
     * Free space kept beyond the staged copy of a restore. Migrating the copy can need about as much room
     * again (a table rebuilt next to the old one, the journal), so the margin is the larger of this and the
     * database's own size: a restore needs {@code databaseBytes + max(SPACE_MARGIN, databaseBytes)}.
     */
    static final long SPACE_MARGIN = 16L * 1024 * 1024;
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern MIGRATION_NAME = Pattern.compile("V(\\d+(?:[._]\\d+)*)__.*\\.sql");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path folder;
    private final DatabaseGate gate;
    private final String applicationVersion;
    private final Clock clock;
    private final Duration gateWait;
    private final Disk disk;

    /**
     * @param folder  the data folder (the caller holds {@code DataFolder}'s lock, or knows nothing else restores)
     * @param gate    the gate of the application's data source; a command line run has none to wait for
     */
    public BackupService(Path folder, DatabaseGate gate, String applicationVersion, Clock clock) {
        this(folder, gate, applicationVersion, clock, GATE_WAIT);
    }

    BackupService(Path folder, DatabaseGate gate, String applicationVersion, Clock clock, Duration gateWait) {
        this(folder, gate, applicationVersion, clock, gateWait, Disk.REAL);
    }

    BackupService(Path folder, DatabaseGate gate, String applicationVersion, Clock clock, Duration gateWait, Disk disk) {
        this.disk = disk;
        this.gateWait = gateWait;
        this.folder = folder;
        this.gate = gate;
        this.applicationVersion = applicationVersion;
        this.clock = clock;
    }

    // ----- backup

    /** Makes the snapshot. Close the result when done: it deletes the snapshot. Nothing is sent before this succeeds. */
    public PreparedBackup prepare() {
        Path database = database();
        if (!Files.isRegularFile(database)) {
            throw new BackupException(Reason.NO_DATABASE, "There is no database yet, so there is nothing to back up.");
        }
        Path snapshot = scratchFile("backup-" + token() + ".db");
        try {
            gate.enter(); // not while a restore swaps the file
            try (Connection connection = open(database); PreparedStatement vacuum = connection.prepareStatement("vacuum into ?")) {
                vacuum.setString(1, snapshot.toString());
                vacuum.execute();
            } finally {
                gate.leave();
            }
            String schema;
            try (Connection connection = open(snapshot)) {
                requireIntact(connection);
                schema = schemaVersion(connection)
                        .orElseThrow(() -> new BackupException(Reason.FAILED, "The database has no migration history."));
            }
            Manifest manifest = new Manifest(FORMAT_VERSION, applicationVersion, schema, clock.instant(), sha256(snapshot), Files.size(snapshot));
            return new PreparedBackup(this, manifest, snapshot);
        } catch (SQLException | IOException e) {
            deleteQuietly(snapshot);
            throw new BackupException(Reason.FAILED, "The backup could not be made (is the disk full?). Nothing was changed.", e);
        } catch (RuntimeException e) {
            deleteQuietly(snapshot);
            throw e;
        }
    }

    /**
     * Command line: writes a backup to {@code target}, or to a new file named by the time inside the folder
     * {@code target}. Refuses to overwrite a file; the file appears only when complete. Owner-only.
     */
    public Path backupTo(Path target) {
        try (PreparedBackup backup = prepare()) {
            Path file = Files.isDirectory(target) ? target.resolve(backup.fileName()) : target;
            Path part = file.resolveSibling(file.getFileName() + ".part");
            try {
                if (Files.exists(file)) {
                    throw new BackupException(Reason.TARGET_EXISTS, "The file " + file + " already exists; it is not overwritten.");
                }
                try (OutputStream out = Files.newOutputStream(ownerOnly(part), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    backup.writeTo(out);
                }
                force(part);
                Files.move(part, file);
                return file;
            } catch (IOException e) {
                throw new BackupException(Reason.FAILED, "The backup could not be written to " + file + " (is the disk full?).", e);
            } finally {
                deleteQuietly(part);
            }
        }
    }

    void write(Manifest manifest, Path snapshot, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        ZipEntry description = new ZipEntry(MANIFEST_ENTRY);
        description.setLastModifiedTime(FileTime.from(manifest.createdAt()));
        zip.putNextEntry(description);
        zip.write(manifest.toText().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        ZipEntry database = new ZipEntry(DATABASE_ENTRY);
        database.setLastModifiedTime(FileTime.from(manifest.createdAt()));
        zip.putNextEntry(database);
        Files.copy(snapshot, zip);
        zip.closeEntry();
        zip.finish();
        zip.flush();
    }

    String fileName(Manifest manifest) {
        return "finances-backup-" + FILE_STAMP.format(manifest.createdAt()) + ".zip";
    }

    // ----- restore, step 1: check the file completely

    /** Command line and tests: checks a backup file on disk. */
    public RestorePlan stage(Path file) {
        discardStaged();
        String token = token();
        try {
            return inspect(file, token);
        } catch (RuntimeException e) {
            discardStaged(token);
            throw e;
        }
    }

    /** The web upload: copied into the scratch folder (never held in memory), then checked. */
    public RestorePlan stage(InputStream upload) {
        discardStaged();
        String token = token();
        Path zip = scratchFile("upload-" + token + ".zip");
        try {
            try {
                Files.copy(upload, zip, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new BackupException(Reason.FAILED, "The file could not be received (is the disk full?). Nothing was changed.", e);
            }
            return inspect(zip, token);
        } catch (RuntimeException e) {
            discardStaged(token);
            throw e;
        } finally {
            deleteQuietly(zip);
        }
    }

    /** The plan of an upload that was checked earlier and is still staged. */
    public RestorePlan staged(String token) {
        Path database = stagedDatabase(token);
        if (!Files.isRegularFile(database) || !Files.isRegularFile(stagedManifest(token))) {
            throw new BackupException(Reason.UNKNOWN_UPLOAD, "That upload is gone. Choose the backup file again.");
        }
        try {
            Manifest manifest = Manifest.parse(Files.readString(stagedManifest(token)), FORMAT_VERSION);
            try (Connection connection = open(database)) {
                return new RestorePlan(token, manifest, summary(connection), current());
            }
        } catch (IOException | SQLException e) {
            discardStaged(token);
            throw new BackupException(Reason.UNKNOWN_UPLOAD, "That upload is gone. Choose the backup file again.", e);
        }
    }

    public void discardStaged(String token) {
        if (TOKEN.matcher(token).matches()) {
            deleteQuietly(scratchFile("restore-" + token + ".db"));
            deleteQuietly(scratchFile("restore-" + token + ".manifest"));
            deleteQuietly(scratchFile("restore-" + token + ".db-wal"));
            deleteQuietly(scratchFile("restore-" + token + ".db-shm"));
        }
    }

    private void discardStaged() {
        Path scratch = folder.resolve(SCRATCH);
        if (!Files.isDirectory(scratch)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(scratch, "{restore,upload}-*")) {
            for (Path file : files) {
                deleteQuietly(file);
            }
        } catch (IOException ignored) {
            // stale files are cleaned at the next start as well
        }
    }

    private RestorePlan inspect(Path file, String token) {
        requireZipStart(file);
        Path staged = scratchFile("restore-" + token + ".db");
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry manifestEntry = zip.getEntry(MANIFEST_ENTRY);
            if (manifestEntry == null || manifestEntry.getSize() > MANIFEST_LIMIT) {
                throw new BackupException(Reason.NOT_A_BACKUP, "The file is not a backup of this application.");
            }
            String text;
            try (InputStream in = zip.getInputStream(manifestEntry)) {
                text = new String(in.readNBytes(MANIFEST_LIMIT), StandardCharsets.UTF_8);
            }
            Manifest manifest = Manifest.parse(text, FORMAT_VERSION);
            ZipEntry databaseEntry = zip.getEntry(DATABASE_ENTRY);
            if (databaseEntry == null) {
                throw damaged();
            }
            requireRoomFor(manifest.databaseBytes());
            extract(zip, databaseEntry, staged, manifest);
            Summary backup;
            try (Connection connection = open(staged)) {
                requireIntact(connection);
                String schema = schemaVersion(connection).orElseThrow(BackupService::damaged);
                if (!schema.equals(manifest.schemaVersion())) {
                    throw damaged();
                }
                if (MigrationVersion.fromVersion(schema).compareTo(newestKnownMigration()) > 0) {
                    throw new BackupException(Reason.NEWER_SCHEMA, "This backup holds a newer database (version " + schema
                            + ") than this application knows (" + newestKnownMigration().getVersion() + "). Update the application first.");
                }
                backup = summary(connection);
            }
            Files.writeString(scratchFile("restore-" + token + ".manifest"), manifest.toText());
            return new RestorePlan(token, manifest, backup, current());
        } catch (ZipException e) {
            throw damaged();
        } catch (SQLException e) {
            throw damaged();
        } catch (IOException e) {
            throw new BackupException(Reason.FAILED, "The file could not be read or the disk is full. Nothing was changed.", e);
        }
    }

    /** Refuses before a byte is extracted: the size comes from the file itself, so it is checked against the disk. */
    private void requireRoomFor(long databaseBytes) throws IOException {
        long margin = Math.max(SPACE_MARGIN, databaseBytes);
        long needed = databaseBytes > Long.MAX_VALUE - margin ? Long.MAX_VALUE : databaseBytes + margin;
        if (disk.usableSpace(folder.resolve(SCRATCH)) < needed) {
            throw new BackupException(Reason.NOT_ENOUGH_SPACE,
                    "There is not enough free disk space in the data folder to restore this backup. Nothing was changed.");
        }
    }

    private void extract(ZipFile zip, ZipEntry entry, Path to, Manifest manifest) throws IOException {
        MessageDigest digest = digest();
        long written = 0;
        try (InputStream in = new DigestInputStream(zip.getInputStream(entry), digest);
                OutputStream out = Files.newOutputStream(ownerOnly(to), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > manifest.databaseBytes()) {
                    throw damaged();
                }
                out.write(buffer, 0, read);
            }
        }
        force(to);
        if (written != manifest.databaseBytes() || !HexFormat.of().formatHex(digest.digest()).equals(manifest.databaseSha256())) {
            throw damaged();
        }
    }

    private static void requireZipStart(Path file) {
        byte[] head = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(head, 0, 4);
            if (read < 4 || head[0] != 'P' || head[1] != 'K' || head[2] != 3 || head[3] != 4) {
                throw new BackupException(Reason.NOT_A_BACKUP, "The file is not a backup of this application.");
            }
        } catch (IOException e) {
            throw new BackupException(Reason.FAILED, "The file could not be read.", e);
        }
    }

    // ----- restore, step 2: replace the database

    /** Replaces the database with a staged, checked backup. The word must be typed. */
    public void restore(String token, String typed) {
        if (typed == null || !typed.strip().equalsIgnoreCase(RESTORE_WORD)) {
            throw new BackupException(Reason.NOT_CONFIRMED, "Type " + RESTORE_WORD + " to confirm.");
        }
        Path staged = stagedDatabase(token);
        if (!Files.isRegularFile(staged)) {
            throw new BackupException(Reason.UNKNOWN_UPLOAD, "That upload is gone. Choose the backup file again.");
        }
        replaceDatabaseWith(staged);
        discardStaged(token);
    }

    /** Puts the database from before the last restore back; what is in use now takes its place, so this can be undone too. */
    public void undoRestore(String typed) {
        if (typed == null || !typed.strip().equalsIgnoreCase(UNDO_WORD)) {
            throw new BackupException(Reason.NOT_CONFIRMED, "Type " + UNDO_WORD + " to confirm.");
        }
        if (!hasPrevious()) {
            throw new BackupException(Reason.NOTHING_TO_UNDO, "There is no earlier database to go back to.");
        }
        replaceDatabaseWith(previous());
    }

    public boolean hasPrevious() {
        return Files.isRegularFile(previous());
    }

    /** When the database from before the last restore was last written, which is when it stopped being used. */
    public Optional<Instant> previousFrom() {
        try {
            return hasPrevious() ? Optional.of(Files.getLastModifiedTime(previous()).toInstant()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** What the database in use holds. */
    public Summary current() {
        if (!Files.isRegularFile(database())) {
            return Summary.EMPTY;
        }
        gate.enter();
        try (Connection connection = open(database())) {
            return summary(connection);
        } catch (SQLException e) {
            return Summary.EMPTY;
        } finally {
            gate.leave();
        }
    }

    private void replaceDatabaseWith(Path source) {
        Path database = database();
        Path replaced = folder.resolve(DataFolder.REPLACED_DATABASE_FILE);
        boolean[] oldDatabasePutBack = {true};
        try {
            gate.exclusive(gateWait, () -> {
                if (Files.exists(replaced)) {
                    // Never overwritten: after a power cut or a failed rename it may hold the only copy of the data.
                    throw new BackupException(Reason.LEFTOVER, "An earlier restore left a database aside as " + replaced.getFileName()
                            + " in the data folder. Nothing was changed; see docs/install.md before restoring again.");
                }
                boolean hadDatabase = Files.isRegularFile(database);
                if (hadDatabase) {
                    flush(database);
                    disk.move(database, replaced);
                }
                boolean movedIn = false;
                try {
                    force(source);
                    disk.move(source, database);
                    movedIn = true;
                    migrate(database);
                } catch (Exception failure) {
                    oldDatabasePutBack[0] = rollBack(failure, source, database, replaced, movedIn, hadDatabase);
                    throw failure;
                }
                if (hadDatabase) {
                    try {
                        disk.move(replaced, previous(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException keptAside) {
                        // The restore itself succeeded and must not be reported as failed; the old
                        // database stays as finances.replaced.db, and the next restore refuses
                        // (LEFTOVER) instead of overwriting it (docs/install.md).
                    }
                }
                syncFolder();
                return null;
            });
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            if (!oldDatabasePutBack[0]) {
                throw new BackupException(Reason.OLD_DATABASE_ASIDE, "The restore (or undo) failed and the database that was in use could not be put back. "
                        + "It is kept as " + replaced.getFileName() + " in the data folder: stop the service and see docs/install.md.", e);
            }
            if (e instanceof IOException || e instanceof SQLException) {
                throw new BackupException(Reason.FAILED, "The database could not be replaced (is the disk full?). The old database is still in use.", e);
            }
            throw new BackupException(Reason.MIGRATION_FAILED,
                    "The restored database could not be brought up to date. The old database is back in use and nothing was changed.", e);
        }
    }

    /**
     * Undoes a swap that failed, so that the old database is in use again. Each clean-up step has its own
     * {@code try}: a failure of one is added to {@code failure} (the error that is rethrown and shown) and the
     * next step still runs, so a failing clean-up never keeps the old database from being put back. Two things
     * the owner's data is never put at risk for:
     * <ul>
     * <li>An undo's source is {@code finances.previous.db}, the only copy of that data. The half-migrated file is
     * renamed back to it first, and when that fails the old database is not put back over it.
     * <li>The old database is never put next to journal files of another database: when they cannot be deleted
     * it is not put back, because SQLite would replay them into it.
     * </ul>
     * In both cases the old database stays as {@code finances.replaced.db}, nothing is deleted, and the
     * result is {@code false}.
     *
     * @return whether the old database is in use again (always true when there was none to put back)
     */
    private boolean rollBack(Exception failure, Path source, Path database, Path replaced, boolean movedIn, boolean hadDatabase) {
        boolean sourceIsOnlyCopy = source.equals(previous());
        boolean movedAway = false;
        if (movedIn && (sourceIsOnlyCopy || !hadDatabase)) {
            try {
                disk.move(database, source, StandardCopyOption.REPLACE_EXISTING);
                movedAway = true;
            } catch (IOException | RuntimeException e) {
                failure.addSuppressed(e);
            }
        }
        if (sourceIsOnlyCopy && movedIn && !movedAway) {
            return false; // the half-migrated file stays in use, with its own journal files
        }
        boolean clean = true;
        try {
            deleteSidecars(database);
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
            clean = false;
        }
        if (!hadDatabase) {
            return true;
        }
        if (clean) {
            try {
                // A restore's half-migrated file, when still there, is replaced: its source is only a staged copy.
                disk.move(replaced, database, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException | RuntimeException e) {
                failure.addSuppressed(e);
            }
        }
        if (movedAway) {
            try {
                disk.move(source, database); // a database file must exist: the application creates an empty one otherwise
            } catch (IOException | RuntimeException e) {
                failure.addSuppressed(e);
            }
        }
        return false;
    }

    /** Writes a file's data to the disk before it is renamed into place, so a power cut cannot leave an empty database. */
    private static void force(Path file) throws IOException {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void syncFolder() {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(folder, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ignored) {
            // not every platform can sync a folder; the renames are done either way
        }
    }

    /** Folds the write-ahead log into the file and removes it, so the file alone holds everything. */
    private void flush(Path database) throws SQLException, IOException {
        try (Connection connection = open(database); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("pragma wal_checkpoint(truncate)")) {
            if (result.next() && result.getInt(1) != 0) {
                throw new BackupException(Reason.BUSY, "The database is in use right now. Nothing was changed; try again in a minute.");
            }
        }
        deleteSidecars(database);
    }

    private void deleteSidecars(Path database) throws IOException {
        disk.deleteIfExists(database.resolveSibling(database.getFileName() + "-wal"));
        disk.deleteIfExists(database.resolveSibling(database.getFileName() + "-shm"));
    }

    private void migrate(Path database) {
        SQLiteConfig config = config();
        config.enforceForeignKeys(true); // as the application's own connections: a migration behaves the same
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + database);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    // ----- helpers

    private Path database() {
        return folder.resolve(DATABASE_FILE);
    }

    private Path previous() {
        return folder.resolve(PREVIOUS_FILE);
    }

    private Path stagedDatabase(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new BackupException(Reason.UNKNOWN_UPLOAD, "That upload is gone. Choose the backup file again.");
        }
        return scratchFile("restore-" + token + ".db");
    }

    private Path stagedManifest(String token) {
        return scratchFile("restore-" + token + ".manifest");
    }

    /** A file in the scratch folder inside the data folder: owner-only, never a web-visible or shared place. */
    private Path scratchFile(String name) {
        Path scratch = folder.resolve(SCRATCH);
        try {
            if (!Files.isDirectory(scratch)) {
                try {
                    Files.createDirectories(scratch, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } catch (UnsupportedOperationException e) {
                    Files.createDirectories(scratch);
                }
            }
        } catch (IOException e) {
            throw new BackupException(Reason.FAILED, "The scratch folder inside the data folder cannot be created.", e);
        }
        return scratch.resolve(name);
    }

    private static Path ownerOnly(Path file) throws IOException {
        try {
            Files.deleteIfExists(file);
            return Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            return Files.createFile(file);
        } catch (FileAlreadyExistsException e) {
            return file;
        }
    }

    private static SQLiteConfig config() {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5000);
        return config;
    }

    /** Opens an existing file only: a missing database is never created by looking at it. */
    private static Connection open(Path file) throws SQLException {
        SQLiteConfig config = config();
        config.resetOpenMode(SQLiteOpenMode.CREATE);
        return config.createConnection("jdbc:sqlite:" + file);
    }

    private static void requireIntact(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("pragma integrity_check")) {
            if (!result.next() || !"ok".equals(result.getString(1))) {
                throw damaged();
            }
        }
    }

    private static Optional<String> schemaVersion(Connection connection) throws SQLException {
        if (!tableExists(connection, "flyway_schema_history")) {
            return Optional.empty();
        }
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "select version from flyway_schema_history where success = 1 and version is not null order by installed_rank desc limit 1")) {
            return result.next() ? Optional.ofNullable(result.getString(1)) : Optional.empty();
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select 1 from sqlite_master where type = 'table' and name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        if (!tableExists(connection, table)) {
            return 0;
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("select count(*) from " + table)) {
            return result.next() ? result.getLong(1) : 0;
        }
    }

    private static Summary summary(Connection connection) throws SQLException {
        return new Summary(count(connection, "account"), count(connection, "balance"), count(connection, "transactions"));
    }

    /** The newest migration this application ships; a backup beyond it is refused. */
    static MigrationVersion newestKnownMigration() {
        MigrationVersion newest = MigrationVersion.EMPTY;
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql")) {
                Matcher name = MIGRATION_NAME.matcher(String.valueOf(resource.getFilename()));
                if (name.matches()) {
                    MigrationVersion version = MigrationVersion.fromVersion(name.group(1));
                    if (version.compareTo(newest) > 0) {
                        newest = version;
                    }
                }
            }
        } catch (IOException e) {
            throw new BackupException(Reason.FAILED, "The application's migrations cannot be read.", e);
        }
        return newest;
    }

    private static BackupException damaged() {
        return new BackupException(Reason.DAMAGED, "The backup file is cut off or damaged. Nothing was changed.");
    }

    private static String token() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the scratch folder is cleaned at the next start
        }
    }

    /**
     * The disk operations of a restore that can fail for want of room or permission, as one seam so a test
     * can make them fail. Everything else in this class uses {@link Files} directly.
     */
    interface Disk {
        Disk REAL = new Disk() {};

        default void move(Path from, Path to, CopyOption... options) throws IOException {
            Files.move(from, to, options);
        }

        default void deleteIfExists(Path file) throws IOException {
            Files.deleteIfExists(file);
        }

        /** Free space a new file in {@code folder}'s file store can use. */
        default long usableSpace(Path folder) throws IOException {
            return Files.getFileStore(folder).getUsableSpace();
        }
    }
}
