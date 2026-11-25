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

    static final String DATABASE_FILE = "finances.db";
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
