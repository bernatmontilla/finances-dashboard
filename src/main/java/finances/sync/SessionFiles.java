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
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A session id is a secret (with the application's key it reads that consent's data), so it is kept in
 * a file in the data folder's {@code secrets/} folder, one per consent, readable by the service user only,
 * and never in the database or in a backup (docs/decisions/10-bank-sync.md). A restore on a new machine
 * therefore finds consents without a file: they show "consent expired" with Renew.
 *
 * <p>The files are named by the consent id only, and after a restore the folder may hold a leftover file of another
 * consent that had the same id. So each session file has a small companion file with a random tag, and the same tag
 * is stored on the consent row ({@code bank_consent.session_tag}): a session file is read only when both agree. A
 * consent made before tags existed has no tag in the row and no companion file, and those two agree as well.
 */
@Component
public class SessionFiles {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path folder;

    public SessionFiles(DataFolder dataFolder) {
        this.folder = dataFolder.path().resolve(ApplicationCredentials.SECRETS_FOLDER);
    }

    /** A new random tag for a consent: 128 bits, as hex. */
    static String newTag() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    Path fileOf(long consentId) {
        return folder.resolve("enable-banking-session-" + consentId);
    }

    Path tagFileOf(long consentId) {
        return folder.resolve("enable-banking-session-" + consentId + ".tag");
    }

    /** A session file for a consent without a tag (one made before tags existed): no companion file, and a leftover one is removed. */
    void write(long consentId, String sessionId) {
        try {
            Files.deleteIfExists(tagFileOf(consentId));
        } catch (IOException e) {
            throw new UncheckedIOException("The session file could not be written.", new IOException("write failed"));
        }
        write(consentId, sessionId, null);
    }

    /**
     * Written whole or not at all: a temporary file first, then moved into place. The tag goes first, so a session file
     * is never in place without its tag.
     */
    void write(long consentId, String sessionId, String tag) {
        try {
            Files.createDirectories(folder);
            if (tag != null) {
                put(tagFileOf(consentId), tag);
            }
            put(fileOf(consentId), sessionId);
        } catch (IOException e) {
            // The cause is dropped: nothing about the file's content may travel into a message.
            throw new UncheckedIOException("The session file could not be written.", new IOException("write failed"));
        }
    }

    private void put(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(folder, "session-", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system: the data folder's own permissions are what is left
            }
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The session id of this consent, or empty when there is no file, or the file carries another tag than the consent's. */
    Optional<String> read(Consent consent) {
        try {
            Path file = fileOf(consent.id());
            if (!Files.isRegularFile(file) || !Objects.equals(consent.sessionTag(), tagOnFile(consent.id()))) {
                return Optional.empty();
            }
            String id = Files.readString(file, StandardCharsets.UTF_8).strip();
            return id.isEmpty() ? Optional.empty() : Optional.of(id);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Whether the consent's session file is there, without reading the secret in it. */
    boolean exists(long consentId) {
        return Files.isRegularFile(fileOf(consentId));
    }

    /** The tag next to the session file; null when there is none (a file from before tags). */
    private String tagOnFile(long consentId) throws IOException {
        Path file = tagFileOf(consentId);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String tag = Files.readString(file, StandardCharsets.UTF_8).strip();
        return tag.isEmpty() ? null : tag;
    }

    void delete(long consentId) {
        for (Path file : new Path[] {fileOf(consentId), tagFileOf(consentId)}) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // an orphan file is harmless: nothing points to it any more
            }
        }
    }
}
