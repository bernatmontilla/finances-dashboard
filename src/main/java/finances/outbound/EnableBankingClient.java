package finances.outbound;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every request to Enable Banking goes through here and nowhere else (AGENTS.md hard rule 7). It talks
 * to the one configured host, never follows a redirect, and only does what account information needs:
 * the bank list, an authorisation, a session, balances and transactions of accounts. There is no payment
 * call in it and none is wanted (hard rule 6). Every request carries a token signed with the application's
 * key (RS256, made with the JDK: no library). Nothing here is logged and no exception message holds a
 * token, session id, IBAN or amount (hard rule 11); the PSU headers are the user's own details and are only
 * passed on.
 */
@Component
public class EnableBankingClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(1);

    private final ApplicationCredentials credentials;
    private final Clock clock;
    private final URI base;
    private final Duration timeout;
    private final HttpClient http;

    public EnableBankingClient(ApplicationCredentials credentials, Clock clock,
            @Value("${finances.outbound.enable-banking.base-url:https://api.enablebanking.com}") String baseUrl,
            @Value("${finances.outbound.timeout-seconds:30}") long timeoutSeconds) {
        this.credentials = credentials;
        this.clock = clock;
        this.base = checkedBase(baseUrl);
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(15, Math.max(1, timeoutSeconds))))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** https only, except for a loopback address (a test double on this machine); no path, no credentials. */
    private static URI checkedBase(String value) {
        try {
            URI uri = URI.create(value.strip());
            String host = uri.getHost();
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host);
            boolean schemeOk = "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback);
            if (host == null || !schemeOk || uri.getUserInfo() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
            return URI.create(uri.getScheme() + "://" + uri.getAuthority());
        } catch (RuntimeException e) {
            throw new IllegalStateException("finances.outbound.enable-banking.base-url must be an https address of a host, without a path.");
        }
    }

    /** Whether the application's id and key are in the data folder. */
    public boolean configured() {
        return credentials.configured();
    }

    // ----- the bank list

    /** The banks that offer account information to a private person, optionally of one country. */
    public JsonNode banks(String country) {
        Map<String, String> query = new LinkedHashMap<>();
        if (country != null && !country.isBlank()) {
            query.put("country", country.strip().toUpperCase(Locale.ROOT));
        }
        query.put("service", "AIS");
        query.put("psu_type", "personal");
        return send("GET", "/aspsps", query, null, Map.of());
    }

    // ----- authorisation and sessions

    /** Starts an authorisation for balances and transactions only; the answer holds the address to send the browser to. */
    public JsonNode startAuthorisation(String bankName, String country, String redirectUrl, String state, Instant validUntil) {
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("valid_until", RFC3339.format(OffsetDateTime.ofInstant(validUntil, ZoneOffset.UTC)));
        access.put("balances", true);
        access.put("transactions", true);
        Map<String, Object> bank = new LinkedHashMap<>();
        bank.put("name", bankName);
        bank.put("country", country);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access", access);
        body.put("aspsp", bank);
        body.put("state", state);
        body.put("redirect_url", redirectUrl);
        body.put("psu_type", "personal");
        body.put("language", "en");
        return send("POST", "/auth", Map.of(), JSON.writeValueAsString(body), Map.of());
    }

    /** Turns the code the bank sent the browser back with into a session. Its account details are shown only once. */
    public JsonNode createSession(String code) {
        return send("POST", "/sessions", Map.of(), JSON.writeValueAsString(Map.of("code", code)), Map.of());
    }

    public JsonNode session(String sessionId) {
        return send("GET", "/sessions/" + segment(sessionId), Map.of(), null, Map.of());
    }

    /** Closes the session, and the consent at the bank where that is possible. */
    public void closeSession(String sessionId) {
        send("DELETE", "/sessions/" + segment(sessionId), Map.of(), null, Map.of());
    }

    // ----- account information

    public JsonNode balances(String accountUid, Map<String, String> psuHeaders) {
        return send("GET", "/accounts/" + segment(accountUid) + "/balances", Map.of(), null, psuHeaders);
    }

    /**
     * One page of transactions. {@code dateFrom} (an ISO date) and {@code longest} choose the period: with
     * {@code longest} the bank gives as far back as it allows, which is only possible for about an hour after
     * the authorisation. While the answer carries a continuation key, call again with it and the same other
     * parameters.
     */
    public JsonNode transactions(String accountUid, String dateFrom, boolean longest, String continuationKey, Map<String, String> psuHeaders) {
        Map<String, String> query = new LinkedHashMap<>();
        if (longest) {
            query.put("strategy", "longest");
        } else if (dateFrom != null) {
            query.put("date_from", dateFrom);
        }
        if (continuationKey != null) {
            query.put("continuation_key", continuationKey);
        }
        return send("GET", "/accounts/" + segment(accountUid) + "/transactions", query, null, psuHeaders);
    }

    // ----- the one place a request is made

    private static String segment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new BankApiException(BankApiException.Reason.UNEXPECTED, null, 0);
        }
        return value;
    }

    private JsonNode send(String method, String path, Map<String, String> query, String body, Map<String, String> psuHeaders) {
        StringBuilder target = new StringBuilder(base.toString()).append(path);
        char separator = '?';
        for (Map.Entry<String, String> parameter : query.entrySet()) {
            target.append(separator)
                    .append(URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        URI uri = URI.create(target.toString());
        if (!base.getAuthority().equalsIgnoreCase(uri.getAuthority())) {
            throw new BankApiException(BankApiException.Reason.UNEXPECTED, null, 0);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + token())
                .header("Accept", "application/json");
        for (Map.Entry<String, String> header : psuHeaders.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new BankApiException(BankApiException.Reason.TIMEOUT, e);
        } catch (IOException e) {
            throw new BankApiException(BankApiException.Reason.UNREACHABLE, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BankApiException(BankApiException.Reason.UNREACHABLE, e);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return parse(response.body());
        }
        throw failure(status, response.body());
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(body);
        } catch (RuntimeException e) {
            throw new BankApiException(BankApiException.Reason.UNEXPECTED, null, 0);
        }
    }

    /** Decides by the API's text code, not by the HTTP status: a 401 has several causes (docs/decisions/6-enable-banking.md). */
    private static BankApiException failure(int status, String body) {
        String error = null;
        try {
            JsonNode answer = parse(body);
            if (answer.hasNonNull("error")) {
                error = answer.get("error").asString();
            }
        } catch (BankApiException ignored) {
            // an error without a readable body is judged by its status
        }
        BankApiException.Reason reason;
        if (error == null) {
            reason = status == 429 ? BankApiException.Reason.RATE_LIMITED
                    : status >= 500 ? BankApiException.Reason.BANK_ERROR : BankApiException.Reason.REJECTED;
        } else {
            reason = switch (error) {
                case "ASPSP_RATE_LIMIT_EXCEEDED" -> BankApiException.Reason.RATE_LIMITED;
                case "EXPIRED_SESSION", "REVOKED_SESSION", "CLOSED_SESSION", "SESSION_DOES_NOT_EXIST" -> BankApiException.Reason.CONSENT_ENDED;
                case "ASPSP_ERROR", "ASPSP_TIMEOUT" -> BankApiException.Reason.BANK_ERROR;
                case "ASPSP_PSU_ACTION_REQUIRED" -> BankApiException.Reason.PSU_ACTION_REQUIRED;
                case "ASPSP_ACCOUNT_NOT_ACCESSIBLE", "ACCOUNT_DOES_NOT_EXIST" -> BankApiException.Reason.NOT_ACCESSIBLE;
                default -> status == 429 ? BankApiException.Reason.RATE_LIMITED
                        : status >= 500 ? BankApiException.Reason.BANK_ERROR : BankApiException.Reason.REJECTED;
            };
        }
        return new BankApiException(reason, error, status);
    }

    // ----- the signed token

    private String token() {
        String applicationId = credentials.applicationId();
        Instant now = Instant.now(clock);
        String header = "{\"typ\":\"JWT\",\"alg\":\"RS256\",\"kid\":\"" + applicationId + "\"}";
        String claims = "{\"iss\":\"enablebanking.com\",\"aud\":\"api.enablebanking.com\",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + now.plus(TOKEN_LIFETIME).getEpochSecond() + "}";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String signingInput = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(credentials.privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + encoder.encodeToString(signature.sign());
        } catch (BankApiException e) {
            throw e;
        } catch (Exception e) {
            throw new BankApiException(BankApiException.Reason.NOT_CONFIGURED, null, 0);
        }
    }
}
