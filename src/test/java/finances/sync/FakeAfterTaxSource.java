package finances.sync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * A fake after-tax source on this machine (issue #18): serves what a test tells it to, valid, invalid, slow or failing, and
 * records what it was asked. Shaped as {@code docs/contracts/after-tax-source.md} says. Fictional amounts only.
 */
public final class FakeAfterTaxSource implements AutoCloseable {

    public static final String PATH = "/after-tax.json";

    /** What a request looked like. */
    public record Request(String method, String path, String query, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private enum Mode {
        ANSWER, HANG, TRICKLE
    }

    private final HttpServer server;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile Mode mode = Mode.ANSWER;
    private volatile int status = 200;
    private volatile String body = valid(Instant.parse("2026-11-26T06:00:00Z"));
    private volatile String location;
    private volatile int padBytes;

    public FakeAfterTaxSource() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-after-tax");
            thread.setDaemon(true);
            return thread;
        }));
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    /** The example of the contract, as of the given moment: gross 20,000.00, VAT 4,200.00, income tax 3,455.00, available 12,345.00. */
    public static String valid(Instant asOf) {
        return """
                {"contract_version": 1, "as_of": "%s", "currency": "EUR", "available_cents": 1234500,
                 "gross_balance_cents": 2000000,
                 "reserved": [{"label": "VAT", "amount_cents": 420000}, {"label": "Income tax", "amount_cents": 345500}]}
                """.formatted(asOf);
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + PATH;
    }

    /** The address of a path of this server (a redirect target, for instance). */
    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public void answer(String json) {
        answer(200, json);
    }

    public void answer(int status, String json) {
        this.mode = Mode.ANSWER;
        this.status = status;
        this.body = json;
        this.location = null;
        this.padBytes = 0;
    }

    /** A 200 answer made larger than the limit by padding after a valid body. */
    public void answerTooLarge() {
        answer(200, valid(Instant.parse("2026-11-26T06:00:00Z")));
        this.padBytes = 70 * 1024;
    }

    public void redirectTo(String target) {
        answer(302, "");
        this.location = target;
    }

    /** Never answers (until the test ends). */
    public void hang() {
        this.mode = Mode.HANG;
    }

    /** Sends the headers at once and then the body one byte at a time, slowly. */
    public void trickle() {
        this.mode = Mode.TRICKLE;
    }

    public List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public List<Request> requests(String path) {
        return requests().stream().filter(request -> request.path().equals(path)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                headers, requestBody));
        try (exchange) {
            if (!PATH.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            switch (mode) {
                case HANG -> {
                    try {
                        released.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                case TRICKLE -> trickle(exchange);
                case ANSWER -> answer(exchange);
            }
        } catch (IOException e) {
            // the client gave up (a timeout): nothing to do
        }
    }

    private void answer(HttpExchange exchange) throws IOException {
        byte[] bytes = (body + " ".repeat(padBytes)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (location != null) {
            exchange.getResponseHeaders().set("Location", location);
        }
        if (status == 302 || bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void trickle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write('{');
        out.flush();
        for (int i = 0; i < 600 && released.getCount() > 0; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            out.write(' ');
            out.flush();
        }
    }

    @Override
    public void close() {
        released.countDown();
        server.stop(0);
    }
}
