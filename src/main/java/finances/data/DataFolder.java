package finances.data;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The folder that holds everything an installation owns: the database now, secrets and backups
 * later. Opening it creates it when needed and takes an exclusive lock, so that a second
 * instance on the same folder fails at once instead of corrupting the database.
 */
public final class DataFolder implements AutoCloseable {

    public static final String DATABASE_FILE = "finances.db";
    /** The database as it was before the last restore, kept so that one restore can be undone. */
    public static final String PREVIOUS_DATABASE_FILE = "finances.previous.db";
    /** Where uploads, backups in the making and restores in the making live: inside the folder, never web-visible. */
    public static final String SCRATCH_FOLDER = "tmp";
    /** Where a restore parks the database it replaces for a moment; found here after a power cut, it is put back at start. */
    public static final String REPLACED_DATABASE_FILE = "finances.replaced.db";
    static final String LOCK_FILE = "finances.lock";

    private final Path path;
    private final FileChannel lockChannel;
    private final FileLock lock;

    private DataFolder(Path path, FileChannel lockChannel, FileLock lock) {
        this.path = path;
        this.lockChannel = lockChannel;
        this.lock = lock;
    }

    public static DataFolder open(String setting) {
        if (setting == null || setting.isBlank()) {
            throw new DataFolderException(
                    "The data folder is not set. Set finances.data-dir (or the environment variable"
                            + " FINANCES_DATA_DIR) to the folder where this installation keeps its data.");
        }
        Path folder = Path.of(setting.strip()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(folder);
        } catch (IOException | RuntimeException e) {
            throw new DataFolderException("The data folder " + folder + " cannot be created or is not a folder.", e);
        }
        if (!Files.isDirectory(folder) || !Files.isWritable(folder)) {
            throw new DataFolderException("The data folder " + folder + " is not a writable folder.");
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(folder.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw inUse(folder);
            }
            recoverInterruptedRestore(folder);
            clearScratch(folder);
            return new DataFolder(folder, channel, lock);
        } catch (OverlappingFileLockException e) {
            closeQuietly(channel);
            throw inUse(folder);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new DataFolderException("The data folder " + folder + " cannot be locked.", e);
        } catch (RuntimeException e) {
            closeQuietly(channel);
            throw e;
        }
    }

    /**
     * A restore cut off between moving the old database aside and moving the new one in leaves no
     * {@code finances.db}: without this, the application would start on an empty database and the
     * owner's data would sit unnoticed in the parked file.
     */
    private static void recoverInterruptedRestore(Path folder) throws IOException {
        Path database = folder.resolve(DATABASE_FILE);
        Path parked = folder.resolve(REPLACED_DATABASE_FILE);
        if (!Files.exists(database) && Files.isRegularFile(parked)) {
            Files.move(parked, database);
        }
    }

    /** Whatever an earlier run left in the scratch folder (a cut-off upload, a half-made backup) is stale. */
    private static void clearScratch(Path folder) {
        Path scratch = folder.resolve(SCRATCH_FOLDER);
        if (!Files.isDirectory(scratch)) {
            return;
        }
        try (var entries = Files.list(scratch)) {
            for (Path entry : entries.toList()) {
                if (Files.isRegularFile(entry)) {
                    Files.deleteIfExists(entry);
                }
            }
        } catch (IOException e) {
            throw new DataFolderException("The scratch folder of the data folder " + folder + " cannot be cleaned.", e);
        }
    }

    private static DataFolderException inUse(Path folder) {
        return new DataFolderException("The data folder " + folder
                + " is already in use by another running instance. Stop it first, or use another folder.");
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // nothing left to do
            }
        }
    }

    public Path path() {
        return path;
    }

    public Path databaseFile() {
        return path.resolve(DATABASE_FILE);
    }

    public Path previousDatabaseFile() {
        return path.resolve(PREVIOUS_DATABASE_FILE);
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // closing the channel releases the lock as well
        }
        closeQuietly(lockChannel);
    }
}
