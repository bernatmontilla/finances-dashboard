package finances.outbound;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Every request to the after-tax source goes through here and nowhere else (AGENTS.md hard rule 7), to the one URL the
 * installer configured ({@code finances.outbound.after-tax.url}) exactly as given: {@code GET}, no body, no query,
 * {@code Accept: application/json} and the token as a bearer token when there is one
 * ({@code docs/contracts/after-tax-source.md}). A redirect is never followed. The whole exchange, up to the last byte of
 * the body, has ten seconds; the body has 64 KiB. Nothing here logs, and no exception holds the URL, the token or
 * anything the source sent (hard rule 11).
 */
@Component
public class AfterTaxClient {

    /** The contract fixes the timeout. */
    public static final Duration TIMEOUT = Duration.ofSeconds(10);
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private final AfterTaxToken token;
    private final URI url;
    private final Duration timeout;
    private final HttpClient http;
    private final ExecutorService readers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "after-tax-read");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public AfterTaxClient(AfterTaxToken token, @Value("${finances.outbound.after-tax.url:}") String url) {
        this(token, url, TIMEOUT);
    }

    /** With a shorter timeout, for tests. */
    public AfterTaxClient(AfterTaxToken token, String url, Duration timeout) {
        this.token = token;
        this.url = checked(url);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Empty means "no source"; else an absolute http or https address of a host, without credentials, query or fragment. */
    private static URI checked(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.strip());
            boolean schemeOk = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
            if (!schemeOk || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "finances.outbound.after-tax.url must be an http or https address of a host, without credentials, query or fragment.");
        }
    }

    /** The most the whole exchange may take. */
    public Duration timeout() {
        return timeout;
    }

    /** Whether a source is configured. */
    public boolean configured() {
        return url != null;
    }

    /** Whether the configured address is https (false when none is configured). */
    public boolean https() {
        return url != null && "https".equals(url.getScheme());
    }

    /** Whether the configured host is this machine (a name or an address that is not reachable from outside). */
    public boolean loopbackHost() {
        if (url == null) {
            return false;
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.equals("[::1]") || host.equals("::1") || host.matches("127(\\.\\d{1,3}){3}");
    }

    /** Whether the token file exists. */
    public boolean tokenPresent() {
        return token.present();
    }

    /** The body of a 200 answer; anything else is an {@link AfterTaxException} with a short code. */
    public byte[] fetch() {
        if (url == null) {
            throw new AfterTaxException(AfterTaxException.Reason.UNREACHABLE);
        }
        Optional<String> bearer = token.read();
        HttpRequest.Builder builder = HttpRequest.newBuilder(url).GET().timeout(timeout).header("Accept", "application/json");
        bearer.ifPresent(value -> builder.header("Authorization", "Bearer " + value));
        long deadline = System.nanoTime() + timeout.toNanos();
        CompletableFuture<HttpResponse<InputStream>> pending = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response = await(pending, deadline);
        InputStream body = response.body();
        try {
            if (response.statusCode() != 200) {
                throw new AfterTaxException(AfterTaxException.Reason.STATUS, response.statusCode());
            }
            return readBody(body, deadline);
        } finally {
            try {
                body.close();
            } catch (IOException ignored) {
                // nothing to do: the answer was already used or refused
            }
        }
    }

    private HttpResponse<InputStream> await(CompletableFuture<HttpResponse<InputStream>> pending, long deadline) {
        try {
            return pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new AfterTaxException(AfterTaxException.Reason.TIMEOUT);
        } catch (ExecutionException e) {
            throw new AfterTaxException(e.getCause() instanceof HttpTimeoutException
                    ? AfterTaxException.Reason.TIMEOUT : AfterTaxException.Reason.UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            throw new AfterTaxException(AfterTaxException.Reason.UNREACHABLE);
        }
    }

    /** Reads at most the limit plus one byte on another thread, so a body that trickles can be cut off by closing it. */
    private byte[] readBody(InputStream body, long deadline) {
        CompletableFuture<byte[]> reading = CompletableFuture.supplyAsync(() -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int n;
                while (total <= MAX_BODY_BYTES && (n = body.read(buffer, 0, Math.min(buffer.length, MAX_BODY_BYTES + 1 - total))) >= 0) {
                    out.write(buffer, 0, n);
                    total += n;
                }
                return out.toByteArray();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, readers);
        byte[] bytes;
        try {
            bytes = reading.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            try {
                body.close();
            } catch (IOException ignored) {
                // closing is what ends the blocked read
            }
            reading.cancel(true);
            throw new AfterTaxException(AfterTaxException.Reason.TIMEOUT);
        } catch (ExecutionException e) {
            throw new AfterTaxException(AfterTaxException.Reason.UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reading.cancel(true);
            throw new AfterTaxException(AfterTaxException.Reason.UNREACHABLE);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new AfterTaxException(AfterTaxException.Reason.TOO_LARGE);
        }
        return bytes;
    }
}
