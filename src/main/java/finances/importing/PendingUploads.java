package finances.importing;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The file of an upload while the owner looks at the preview and corrects the mapping. It is kept in
 * memory only, for a short time, at most {@value #MAX_HELD} at once, and dropped when the import is
 * confirmed: an uploaded file is never written to the data folder and never outlives the import.
 * After a restart (or half an hour) the file is gone and has to be uploaded again.
 */
@Component
public class PendingUploads {

    static final int MAX_HELD = 2;
    static final Duration KEEP = Duration.ofMinutes(30);

    private record Held(long accountId, byte[] bytes, Instant at) {
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Held> held = new LinkedHashMap<>();

    public PendingUploads(Clock clock) {
        this.clock = clock;
    }

    /** Keeps the file for the account and returns the token the preview and the confirmation pass back. */
    public synchronized String hold(long accountId, byte[] bytes) {
        expire();
        while (held.size() >= MAX_HELD) {
            held.remove(held.keySet().iterator().next()); // the oldest goes
        }
        byte[] token = new byte[16];
        random.nextBytes(token);
        String key = HexFormat.of().formatHex(token);
        held.put(key, new Held(accountId, bytes, Instant.now(clock)));
        return key;
    }

    /** The file held under the token for this account, if it is still there. */
    public synchronized Optional<byte[]> find(String token, long accountId) {
        expire();
        Held file = held.get(token);
        return file != null && file.accountId() == accountId ? Optional.of(file.bytes()) : Optional.empty();
    }

    public synchronized void drop(String token) {
        held.remove(token);
    }

    private void expire() {
        Instant oldest = Instant.now(clock).minus(KEEP);
        held.values().removeIf(file -> file.at().isBefore(oldest));
    }
}
