package finances.outbound;

import finances.data.DataFolder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The token of the after-tax source: one file in the data folder's {@code secrets/} folder that the installer put
 * there (docs/install.md). It is read on every fetch, never shown, logged or put into a backup. A file that is
 * missing or empty means "no token": no Authorization header is sent. A file with anything but visible ASCII
 * once stripped (a line break inside, a control character) is not sent at all.
 */
@Component
public class AfterTaxToken {

    public static final String TOKEN_FILE = "after-tax-token";

    private final Path file;

    public AfterTaxToken(DataFolder dataFolder) {
        this.file = dataFolder.path().resolve(ApplicationCredentials.SECRETS_FOLDER).resolve(TOKEN_FILE);
    }

    /** Whether there is a token file (it says nothing about its content). */
    public boolean present() {
        return Files.isRegularFile(file);
    }

    /** The token, or empty when there is none; fails with {@code TOKEN_UNREADABLE} when it cannot be sent as it is. */
    Optional<String> read() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String token;
        try {
            token = Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException e) {
            // The cause is dropped on purpose: its message could quote part of the file.
            throw new AfterTaxException(AfterTaxException.Reason.TOKEN_UNREADABLE);
        }
        if (token.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                throw new AfterTaxException(AfterTaxException.Reason.TOKEN_UNREADABLE);
            }
        }
        return Optional.of(token);
    }
}
