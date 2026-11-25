package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.AccountService;
import finances.sync.FakeEnableBanking;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The bank link through the real application on a temporary data folder and a fake Enable Banking server:
 * the settings page, choosing a bank, coming back, confirming accounts, Sync now, the account page, renewing and
 * unlinking. Fictional data only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BankPagesTest {

    private static final Path DATA = createTempDataFolder();
    private static final FakeEnableBanking BANK = startBank();
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
        registry.add("finances.outbound.enable-banking.base-url", BANK::baseUrl);
        registry.add("finances.sync.redirect-url", () -> "http://localhost:8080/settings/bank/callback");
        registry.add("finances.sync.enabled", () -> "false");
    }

    private static Path createTempDataFolder() {
        try {
            return Files.createTempDirectory("finances-test-bank");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FakeEnableBanking startBank() {
        try {
            FakeEnableBanking bank = new FakeEnableBanking();
            bank.install(DATA, false);
            return bank;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void cleanUp() throws IOException {
        BANK.close();
        try (Stream<Path> paths = Files.walk(DATA)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    AccountService accounts;

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return CLIENT.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String location(HttpResponse<?> response) {
        return response.headers().firstValue("Location").map(URI::create)
                .map(target -> target.getRawPath() == null || target.getHost() != null && !target.getHost().equals("127.0.0.1")
                        ? target.toString() : target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery()))
                .orElseThrow();
    }

    private static String lastState() {
        String body = BANK.requests("POST", "/auth").getLast().body();
        int at = body.indexOf("\"state\":\"") + "\"state\":\"".length();
        return body.substring(at, body.indexOf('"', at));
    }

    @Test
    void theOwnerLinksABankConfirmsAccountsSyncsRenewsAndUnlinks() throws Exception {
        // The settings page says an application is configured and never shows its id or its key.
        String settings = get("/settings").body();
        assertThat(settings).contains("Bank connection").contains("An Enable Banking application is configured").contains("Link a bank")
                .contains("No bank is linked yet").doesNotContain(FakeEnableBanking.APPLICATION_ID).doesNotContain("BEGIN");
        assertThat(get("/").body()).as("no Sync now before a bank is linked").doesNotContain("Sync now");

        // Country, then bank, from the list the API offers; no logo from another host.
        String countries = get("/settings/bank/link").body();
        assertThat(countries).contains("Netherlands").contains("personal use").contains("never for a payment");
        String banks = get("/settings/bank/link?country=NL").body();
        assertThat(banks).contains("Example Bank").contains("Sample Savings Bank").contains("Only accounts linked in the Enable Banking control panel")
                .doesNotContain("example.com/brands").doesNotContain("<img");

        // The bank's own page is where the browser is sent.
        HttpResponse<String> started = post("/settings/bank/link", Map.of("country", "NL", "bank", "Example Bank"));
        assertThat(started.statusCode()).as("a POST is answered with a redirect to the bank's page").isEqualTo(303);
        assertThat(location(started)).startsWith("https://example.com/ais/start");
        String state = lastState();

        // A callback with an unknown state or an error stores nothing and says so on the settings page.
        HttpResponse<String> unknown = get("/settings/bank/callback?code=c&state=never-issued");
        assertThat(location(unknown)).isEqualTo("/settings?bankFailed=unknown_state");
        assertThat(get("/settings?bankFailed=unknown_state").body()).contains("Nothing was stored");
        assertThat(get("/settings?bankFailed=%3Cscript%3E").body()).doesNotContain("<script>");
        assertThat(accounts.list(false)).isEmpty();

        // Coming back: the accounts wait for confirmation.
        HttpResponse<String> back = get("/settings/bank/callback?code=fictional-code&state=" + state);
        assertThat(back.statusCode()).isEqualTo(302);
        String consentPath = location(back).replaceFirst("\\?.*", "");
        assertThat(consentPath).startsWith("/settings/bank/consents/");
        assertThat(accounts.list(false)).as("nothing created without confirmation").isEmpty();
        String consent = get(consentPath).body();
        assertThat(consent).contains("Example Bank").contains("Accounts waiting for your confirmation").contains("Create and sync")
                .contains("Not supported").contains("currency XXX").contains("Consent valid until 2027-05-24")
                .doesNotContain(FakeEnableBanking.SESSION_ID);

        // Confirm the current account as a new account: the first sync runs at once.
        String linkPath = consentPath + "/accounts/";
        HttpResponse<String> created = post(linkPath + "1/create", Map.of("name", "Everyday", "scope", "private", "kind", "current"));
        assertThat(location(created)).isEqualTo(consentPath + "?sync=done");
        assertThat(get(consentPath + "?sync=done").body()).contains("The first sync ran");
        long everyday = accounts.list(false).getFirst().account().id();

        String account = get("/accounts/" + everyday).body();
        assertThat(account).contains("Bank connection").contains("Booked balance €1,234.56").contains("(CLBD)").contains("correct as of 2026-11-26 08:30")
                .contains("synced").contains("first sync after linking").contains("Example Gym").contains("pending").contains("Corner Grocery")
                .doesNotContain(FakeEnableBanking.SESSION_ID);
        assertThat(get("/transactions").body()).contains("Corner Grocery");

        // Sync now from the overview.
        String overview = get("/").body();
        assertThat(overview).contains("Sync now");
        HttpResponse<String> syncNow = post("/sync", Map.of());
        assertThat(location(syncNow)).startsWith("/?sync=done");
        assertThat(get(location(syncNow)).body()).contains("Accounts synced: 1.");
        // The test's own client is on the loopback address, which is no address of a person (issue #39): the bank lists
        // Psu-Ip-Address as required, so the fetch goes without any of the headers.
        String runs = get("/settings").body();
        assertThat(runs).contains("Last sync runs").contains("Sync now, background fetch (the bank wants headers that cannot be sent)").contains("synced").contains("Example Bank")
                .contains("Accounts linked: 1").contains("Waiting for confirmation: 1");
        assertThat(post("/sync", Map.of()).statusCode()).isEqualTo(302);
        HttpResponse<String> crossSite = CLIENT.send(HttpRequest.newBuilder(uri("/sync")).header("Sec-Fetch-Site", "cross-site")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(crossSite.statusCode()).as("another site cannot start a sync").isEqualTo(403);

        // The consent expires: the account stays and says so, with Renew.
        BANK.sessionStatus(FakeEnableBanking.SESSION_ID, "EXPIRED");
        post("/sync", Map.of());
        String expired = get("/accounts/" + everyday).body();
        assertThat(expired).contains("Consent expired").contains("Renew").contains("Corner Grocery").contains("failed: consent expired");
        assertThat(get("/settings").body()).contains("consent expired").contains("Renew");
        assertThat(get("/").body()).as("an expired consent has nothing to sync").contains("Sync now");

        // Renew: the same accounts, with a new session id and new uids, are re-attached.
        BANK.renewedSession(true);
        HttpResponse<String> renew = post(consentPath + "/renew", Map.of());
        assertThat(location(renew)).startsWith("https://example.com/ais/start");
        HttpResponse<String> renewed = get("/settings/bank/callback?code=fictional-code&state=" + lastState());
        assertThat(location(renewed)).contains("reattached=1");
        assertThat(accounts.list(false)).as("no new account").hasSize(1);
        assertThat(get(location(renewed)).body()).contains("Accounts re-attached to this consent and synced: 1");
        assertThat(get("/accounts/" + everyday).body()).doesNotContain("Consent expired");

        // Unlink: the account and its history stay, as a manual account.
        String newConsent = location(renewed).replaceFirst("\\?.*", "");
        HttpResponse<String> unlinked = post(newConsent + "/unlink", Map.of());
        assertThat(location(unlinked)).isEqualTo("/settings?bank=unlinked");
        assertThat(get("/settings?bank=unlinked").body()).contains("The bank is unlinked").contains("No bank is linked yet");
        assertThat(get("/accounts/" + everyday).body()).contains("Corner Grocery").contains("Entered by hand");
        assertThat(get(consentPath).statusCode()).isEqualTo(404);
        assertThat(BANK.requests()).extracting(FakeEnableBanking.Request::path).noneMatch(path -> path.contains("payment"));
    }

    @Test
    void aBankThatReturnsNoAccountSaysWhereToLinkThem() throws Exception {
        BANK.emptySession(true);
        try {
            post("/settings/bank/link", Map.of("country", "NL", "bank", "Example Bank"));
            HttpResponse<String> back = get("/settings/bank/callback?code=fictional-code&state=" + lastState());

            assertThat(location(back)).isEqualTo("/settings?bank=empty");
            assertThat(get("/settings?bank=empty").body()).contains("The bank returned no accounts").contains("linked in the Enable Banking control panel");
        } finally {
            BANK.emptySession(false);
        }
    }
}
