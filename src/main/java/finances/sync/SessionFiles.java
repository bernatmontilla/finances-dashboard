package finances.sync;

import finances.data.DataFolder;
import finances.outbound.ApplicationCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A session id is a secret (with the application's key it reads that consent's data), so it is kept in
 * a file in the data folder's {@code secrets/} folder, one per consent, readable by the service user only,
 * and never in the database or in a backup (docs/decisions/10-bank-sync.md). A restore on a new machine
 * therefore finds consents without a file: they show "consent expired" with Renew.
 */
@Component
public class SessionFiles {

    private final Path folder;

    public SessionFiles(DataFolder dataFolder) {
        this.folder = dataFolder.path().resolve(ApplicationCredentials.SECRETS_FOLDER);
    }

    Path fileOf(long consentId) {
        return folder.resolve("enable-banking-session-" + consentId);
    }

    /** Written whole or not at all: a temporary file first, then moved into place. */
    void write(long consentId, String sessionId) {
        try {
            Files.createDirectories(folder);
            Path temporary = Files.createTempFile(folder, "session-", ".tmp");
            try {
                try {
                    Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // not a POSIX file system: the data folder's own permissions are what is left
                }
                Files.writeString(temporary, sessionId, StandardCharsets.UTF_8);
                Files.move(temporary, fileOf(consentId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            // The cause is dropped: nothing about the file's content may travel into a message.
            throw new UncheckedIOException("The session file could not be written.", new IOException("write failed"));
        }
    }

    Optional<String> read(long consentId) {
        try {
            Path file = fileOf(consentId);
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String id = Files.readString(file, StandardCharsets.UTF_8).strip();
            return id.isEmpty() ? Optional.empty() : Optional.of(id);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    void delete(long consentId) {
        try {
            Files.deleteIfExists(fileOf(consentId));
        } catch (IOException e) {
            // an orphan file is harmless: nothing points to it any more
        }
    }
}
