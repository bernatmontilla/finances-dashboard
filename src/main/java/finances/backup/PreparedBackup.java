package finances.backup;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** A snapshot ready to be streamed out as one backup file. Closing it deletes the snapshot. */
public final class PreparedBackup implements AutoCloseable {

    private final BackupService service;
    private final Manifest manifest;
    private final Path snapshot;

    PreparedBackup(BackupService service, Manifest manifest, Path snapshot) {
        this.service = service;
        this.manifest = manifest;
        this.snapshot = snapshot;
    }

    public Manifest manifest() {
        return manifest;
    }

    /** A name for the downloaded file: the moment it was made (UTC). */
    public String fileName() {
        return service.fileName(manifest);
    }

    /** Streams the backup file; the output stream stays open. */
    public void writeTo(OutputStream out) throws IOException {
        service.write(manifest, snapshot, out);
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(snapshot);
            Files.deleteIfExists(snapshot.resolveSibling(snapshot.getFileName() + "-wal"));
            Files.deleteIfExists(snapshot.resolveSibling(snapshot.getFileName() + "-shm"));
        } catch (IOException ignored) {
            // the scratch folder is cleaned at the next start
        }
    }
}
