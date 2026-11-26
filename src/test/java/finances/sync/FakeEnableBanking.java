package finances.sync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake Enable Banking server built from the fictional fixtures of the verification issue
 * ({@code src/test/resources/enablebanking/}), shaped as the API reference documents them. It checks that every
 * request carries a token signed with the application's key, and records what it was asked. Nothing real in it.
 */
public final class FakeEnableBanking implements AutoCloseable {

    public static final String APPLICATION_ID = "00000000-0000-4000-8000-0000000000aa";
    public static final String CURRENT_UID = "aaaaaaaa-0000-4000-8000-000000000001";
    public static final String SAVINGS_UID = "aaaaaaaa-0000-4000-8000-000000000002";
    public static final String RENEWED_CURRENT_UID = "bbbbbbbb-0000-4000-8000-000000000001";
    public static final String RENEWED_SAVINGS_UID = "bbbbbbbb-0000-4000-8000-000000000002";
    public static final String SESSION_ID = "11111111-1111-4111-8111-111111111111";
    public static final String RENEWED_SESSION_ID = "22222222-2222-4222-8222-222222222222";

    /** What a request looked like. */
    public record Request(String method, String path, Map<String, String> query, Map<String, String> headers, String body, boolean tokenValid) {
        public boolean hasHeader(String name) {
            return headers.containsKey(name.toLowerCase(Locale.ROOT));
        }
    }

    /** What the transactions endpoint answers. */
    public enum Transactions {
        /** Two pages, the pending gym day pass last. */
        FIRST,
        /** The same booked items and the gym day pass now booked under a new reference. */
        SECOND,
        /** Only the first page's two booked items, no continuation: the pending item is no longer reported. */
        WITHOUT_PENDING
    }

    private final HttpServer server;
    private final KeyPair keys;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> sessionStatusCodes = new HashMap<>();
    private final Map<String, String> sessionStatuses = new HashMap<>();
    private final Map<String, String> failures = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, String> balanceTypesOverride = Collections.synchronizedMap(new HashMap<>());
    private volatile Transactions transactions = Transactions.FIRST;
    private volatile String transactionsBody;
    private volatile boolean failSecondPage;
    private volatile boolean renewedSession;
    private volatile boolean emptySession;
    private volatile long balancesDelayMillis;
    private volatile CountDownLatch balancesSeen = new CountDownLatch(0);
    private final AtomicInteger sessionsCreated = new AtomicInteger();

    public FakeEnableBanking() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keys = generator.generateKeyPair();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The application's key in the PKCS#8 form ("BEGIN PRIVATE KEY"), as a browser download or recent OpenSSL gives it. */
    String privateKeyPkcs8() {
        return pem("PRIVATE KEY", keys.getPrivate().getEncoded());
    }

    /** The same key as PKCS#1 ("BEGIN RSA PRIVATE KEY"), as {@code openssl genrsa} gave it for years. */
    String privateKeyPkcs1() {
        byte[] pkcs8 = keys.getPrivate().getEncoded();
        // The PKCS#8 envelope of an RSA key ends with an octet string that holds the PKCS#1 key.
        int start = indexOfOctetString(pkcs8);
        byte[] pkcs1 = new byte[pkcs8.length - start];
        System.arraycopy(pkcs8, start, pkcs1, 0, pkcs1.length);
        return pem("RSA PRIVATE KEY", pkcs1);
    }

    private static int indexOfOctetString(byte[] pkcs8) {
        // 30 82 LL LL | 02 01 00 | 30 0d <13 bytes> | 04 82 LL LL | <pkcs1>
        int offset = 4 + 3 + 2 + 13;
        if (pkcs8[offset] != 0x04) {
            throw new IllegalStateException("unexpected PKCS#8 layout");
        }
        int lengthBytes = pkcs8[offset + 1] & 0x7f;
        return offset + 2 + lengthBytes;
    }

    private static String pem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    /** Puts the application id and the key where the installer puts them. */
    public void install(Path dataFolder, boolean pkcs1) throws IOException {
        Path secrets = Files.createDirectories(dataFolder.resolve("secrets"));
        Files.writeString(secrets.resolve("enable-banking-application-id"), APPLICATION_ID + "\n");
        Files.writeString(secrets.resolve("enable-banking-key.pem"), pkcs1 ? privateKeyPkcs1() : privateKeyPkcs8());
    }

    // ----- what the test controls

    public void transactions(Transactions mode) {
        this.transactions = mode;
        this.transactionsBody = null;
    }

    /** Answers the current account's transactions with exactly this JSON (one page, no continuation) until {@link #transactions} is called. */
    void transactionsBody(String json) {
        this.transactionsBody = json;
    }

    void failSecondPageOfTransactions(boolean fail) {
        this.failSecondPage = fail;
    }

    /** Answers every request about this account uid with a documented error fixture and status. */
    public void fail(String uid, String errorFixture) {
        failures.put(uid, errorFixture);
    }

    public void stopFailing(String uid) {
        failures.remove(uid);
    }

    /** What {@code GET /sessions/{id}} says: AUTHORIZED, EXPIRED or REVOKED. */
    public void sessionStatus(String sessionId, String status) {
        sessionStatuses.put(sessionId, status);
    }

    /** The next {@code POST /sessions} gives the renewed session (new session id, new uids, same hashes). */
    public void renewedSession(boolean renewed) {
        this.renewedSession = renewed;
    }

    public void emptySession(boolean empty) {
        this.emptySession = empty;
    }

    /** Answers the balances of this account with another fixture. */
    public void balancesFixture(String uid, String fixture) {
        balanceTypesOverride.put(uid, fixture);
    }

    void balancesDelay(long millis, CountDownLatch seen) {
        this.balancesDelayMillis = millis;
        this.balancesSeen = seen;
    }

    public List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public List<Request> requests(String method, String pathPrefix) {
        return requests().stream().filter(r -> r.method().equals(method) && r.path().startsWith(pathPrefix)).toList();
    }

    public void forget() {
        requests.clear();
    }

    public String lastToken() {
        List<Request> all = requests();
        return all.isEmpty() ? "" : all.getLast().headers().getOrDefault("authorization", "").replaceFirst("^Bearer ", "");
    }

    public int sessionsCreated() {
        return sessionsCreated.get();
    }

    // ----- the server

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = new HashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                String[] kv = pair.split("=", 2);
                query.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean valid = tokenValid(headers.get("authorization"));
        requests.add(new Request(method, path, query, headers, body, valid));
        if (!valid) {
            respond(exchange, 401, "{\"message\":\"Invalid token\",\"code\":401,\"error\":\"INVALID_TOKEN\"}");
            return;
        }
        try {
            route(exchange, method, path, query);
        } catch (RuntimeException e) {
            respond(exchange, 500, "{\"message\":\"fake server\",\"code\":500,\"error\":\"FAKE\"}");
        }
    }

    private void route(HttpExchange exchange, String method, String path, Map<String, String> query) throws IOException {
        if (method.equals("GET") && path.equals("/aspsps")) {
            String country = query.get("country");
            String all = fixture("aspsps-nl");
            respond(exchange, 200, country != null && !country.equals("NL") ? "{\"aspsps\":[]}" : all);
        } else if (method.equals("POST") && path.equals("/auth")) {
            respond(exchange, 200, fixture("auth-response"));
        } else if (method.equals("POST") && path.equals("/sessions")) {
            sessionsCreated.incrementAndGet();
            if (emptySession) {
                respond(exchange, 200, "{\"session_id\":\"" + SESSION_ID + "\",\"access\":{\"valid_until\":\"2027-05-24T10:00:00.000000+00:00\"},\"accounts\":[]}");
            } else {
                respond(exchange, 200, fixture(renewedSession ? "session-renewed" : "session-authorized"));
            }
        } else if (method.equals("GET") && path.startsWith("/sessions/")) {
            String id = path.substring("/sessions/".length());
            String status = sessionStatuses.getOrDefault(id, "AUTHORIZED");
            String fixture = status.equals("EXPIRED") ? "session-get-expired" : status.equals("REVOKED") ? "session-get-revoked" : "session-get-authorized";
            respond(exchange, 200, fixture(fixture));
        } else if (method.equals("DELETE") && path.startsWith("/sessions/")) {
            respond(exchange, 200, "{}");
        } else if (method.equals("GET") && path.matches("/accounts/[^/]+/balances")) {
            String uid = path.split("/")[2];
            balancesSeen.countDown();
            sleep(balancesDelayMillis);
            if (failure(exchange, uid)) {
                return;
            }
            respond(exchange, 200, fixture(balanceTypesOverride.getOrDefault(uid, uid.endsWith("1") ? "balances-current" : "balances-savings")));
        } else if (method.equals("GET") && path.matches("/accounts/[^/]+/transactions")) {
            String uid = path.split("/")[2];
            if (failure(exchange, uid)) {
                return;
            }
            transactions(exchange, uid, query.get("continuation_key"));
        } else {
            respond(exchange, 404, "{\"message\":\"unknown\",\"code\":404,\"error\":\"NOT_FOUND\"}");
        }
    }

    private void transactions(HttpExchange exchange, String uid, String key) throws IOException {
        if (!uid.endsWith("1")) {
            respond(exchange, 200, "{\"transactions\":[]}");
            return;
        }
        if (transactionsBody != null) {
            respond(exchange, 200, transactionsBody);
            return;
        }
        switch (transactions) {
            case FIRST -> {
                if (key == null) {
                    respond(exchange, 200, fixture("transactions-current-page1"));
                } else if (failSecondPage) {
                    respond(exchange, 500, "{\"message\":\"Bank error\",\"code\":500,\"error\":\"ASPSP_ERROR\"}");
                } else {
                    respond(exchange, 200, fixture("transactions-current-page2"));
                }
            }
            case SECOND -> respond(exchange, 200, fixture("transactions-current-second-sync"));
            case WITHOUT_PENDING -> respond(exchange, 200, fixture("transactions-current-page1").replace(",\n  \"continuation_key\": \"fictional-continuation-key-1\"", ""));
        }
    }

    private boolean failure(HttpExchange exchange, String uid) throws IOException {
        String fixture = failures.get(uid);
        if (fixture == null) {
            return false;
        }
        String body = fixture(fixture);
        respond(exchange, fixture.equals("error-rate-limit") ? 429 : fixture.equals("error-expired-session") ? 401
                : fixture.equals("error-psu-header-not-provided") ? 422 : 500, body);
        return true;
    }

    private static void sleep(long millis) {
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    static String fixture(String name) {
        try (InputStream in = FakeEnableBanking.class.getResourceAsStream("/enablebanking/" + name + ".json")) {
            if (in == null) {
                throw new IllegalStateException("no fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token as the reference describes it: RS256, the application id as kid, signed with the application's key. */
    private boolean tokenValid(String authorization) {
        try {
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                return false;
            }
            String[] parts = authorization.substring("Bearer ".length()).split("\\.");
            if (parts.length != 3) {
                return false;
            }
            String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            if (!header.contains("\"alg\":\"RS256\"") || !header.contains("\"kid\":\"" + APPLICATION_ID + "\"") || !header.contains("\"typ\":\"JWT\"")
                    || !claims.contains("\"iss\":\"enablebanking.com\"") || !claims.contains("\"aud\":\"api.enablebanking.com\"")) {
                return false;
            }
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify((RSAPublicKey) keys.getPublic());
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
