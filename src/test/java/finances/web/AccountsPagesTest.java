package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.AccountException;
import finances.money.AmountFormatException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The accounts pages through the real application on a temporary data folder: the owner's
 * journey from "no accounts" to an overview with balances. Fictional data only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountsPagesTest {

    private static final Path DATA = createTempDataFolder();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void dataFolder(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
    }

    private static Path createTempDataFolder() {
        try {
            return Files.createTempDirectory("finances-test-accounts");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void removeDataFolder() throws IOException {
        try (Stream<Path> paths = Files.walk(DATA)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @LocalServerPort
    int port;

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> form, String... headers) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Posts and follows the redirect the way a browser does; returns the account id from {@code /accounts/{id}}. */
    private long createAccount(String name, String scope, String kind, String iban) throws Exception {
        HttpResponse<String> response = post("/accounts", Map.of("name", name, "scope", scope, "kind", kind, "iban", iban));
        assertThat(response.statusCode()).isEqualTo(302);
        Matcher id = Pattern.compile("/accounts/(\\d+)$").matcher(response.headers().firstValue("Location").orElseThrow());
        assertThat(id.find()).isTrue();
        return Long.parseLong(id.group(1));
    }

    private static String days(int ago) {
        return LocalDate.now().minusDays(ago).toString();
    }

    @Test
    void theOwnerAddsAccountsEntersBalancesCorrectsOneAndSeesThemOnTheOverview() throws Exception {
        long everyday = createAccount("Everyday", "private", "current", "NL51XMPL0000000001");
        long loan = createAccount("Example Loan", "private", "debt", "NL67XMPL0000000004");
        long shop = createAccount("Corner Shop Account", "business", "current", "");

        assertThat(post("/accounts/" + everyday + "/balances", Map.of("amount", "100,00", "date", days(20))).statusCode()).isEqualTo(302);
        assertThat(post("/accounts/" + everyday + "/balances", Map.of("amount", "1.234,56", "date", days(3))).statusCode()).isEqualTo(302);
        assertThat(post("/accounts/" + everyday + "/balances", Map.of("amount", "250.00", "date", days(0))).statusCode()).isEqualTo(302);
        assertThat(post("/accounts/" + loan + "/balances", Map.of("amount", "5000,00", "date", days(1))).statusCode()).isEqualTo(302);

        String history = get("/accounts/" + everyday).body();
        assertThat(history).contains("€100.00").contains("€1,234.56").contains("€250.00");
        assertThat(history.indexOf("€250.00")).isLessThan(history.indexOf("€1,234.56"));
        assertThat(history.indexOf("€1,234.56")).isLessThan(history.indexOf("€100.00"));

        // Correct the middle entry (its edit link is on the page).
        Matcher edit = Pattern.compile("/accounts/" + everyday + "/balances/(\\d+)/edit").matcher(history);
        assertThat(edit.find()).isTrue();
        edit.find(); // the second link is the middle entry (newest first)
        String entryId = edit.group(1);
        HttpResponse<String> form = get("/accounts/" + everyday + "/balances/" + entryId + "/edit");
        assertThat(form.statusCode()).isEqualTo(200);
        assertThat(form.body()).contains("1234.56");
        assertThat(post("/accounts/" + everyday + "/balances/" + entryId, Map.of("amount", "1.300,00", "date", days(3))).statusCode()).isEqualTo(302);

        String corrected = get("/accounts/" + everyday).body();
        assertThat(corrected).contains("€1,300.00").doesNotContain("€1,234.56");

        String overview = get("/").body();
        assertThat(overview).doesNotContain("No accounts yet");
        assertThat(overview).contains("Private").contains("Business").contains("Everyday").contains("Corner Shop Account");
        assertThat(overview.indexOf("Private")).isLessThan(overview.indexOf("Business"));
        assertThat(overview).contains("€250.00").contains("as of " + days(0)).contains("(today)");
        assertThat(overview).contains("Owes €5,000.00").contains("(yesterday)");
        assertThat(overview).contains("No balance yet"); // the business account has none
        assertThat(overview).doesNotContain("€1,300.00"); // only the latest balance of an account is shown
        assertThat(get("/accounts/" + shop).statusCode()).isEqualTo(200);
    }

    @Test
    void anAmountShownAsOwedIsNeverShownAsAvailableMoney() throws Exception {
        long loan = createAccount("Another Loan", "private", "debt", "");
        post("/accounts/" + loan + "/balances", Map.of("amount", "777,00", "date", days(0)));

        String body = get("/accounts/" + loan).body();

        assertThat(body).contains("Owes €777.00");
        assertThat(body.replace("Owes €777.00", "")).doesNotContain("€777.00");
    }

    @Test
    void anAmbiguousAmountIsRefusedWithAMessageAndNothingIsStored() throws Exception {
        long id = createAccount("Ambiguity Account", "private", "current", "");

        HttpResponse<String> response = post("/accounts/" + id + "/balances", Map.of("amount", "1,234", "date", days(0)));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("This could be thousands or decimals");
        assertThat(response.body()).contains("value=\"1,234\""); // what was typed is shown again
        assertThat(get("/accounts/" + id).body()).contains("No balances entered yet.");
    }

    @Test
    void aBalanceFromTheFutureIsRefused() throws Exception {
        long id = createAccount("Future Account", "private", "current", "");

        HttpResponse<String> response = post("/accounts/" + id + "/balances",
                Map.of("amount", "10", "date", LocalDate.now().plusDays(2).toString()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("A balance cannot be from the future.");
        assertThat(get("/accounts/" + id).body()).contains("No balances entered yet.");
    }

    @Test
    void aSecondAccountWithTheSameIbanIsRefusedButTheSameNameIsAllowed() throws Exception {
        createAccount("Twin", "private", "current", "NL94XMPL0000000003");

        HttpResponse<String> duplicate = post("/accounts", Map.of("name", "Different", "scope", "private", "kind", "current", "iban", "NL94 XMPL 0000 0000 03"));

        assertThat(duplicate.statusCode()).isEqualTo(400);
        assertThat(duplicate.body()).contains("Another account already has this IBAN.").contains("value=\"Different\"");
        createAccount("Twin", "business", "current", "");
    }

    @Test
    void anAccountCanBeRenamedMovedAndArchivedAndRestoredButNeverDeleted() throws Exception {
        long id = createAccount("Old Name", "private", "current", "");
        post("/accounts/" + id + "/balances", Map.of("amount", "42,00", "date", days(0)));

        assertThat(post("/accounts/" + id, Map.of("name", "New Name", "scope", "business", "kind", "savings", "iban", "")).statusCode()).isEqualTo(302);
        String page = get("/accounts/" + id).body();
        assertThat(page).contains("New Name").contains("Business").contains("Savings").doesNotContain("Old Name");

        assertThat(post("/accounts/" + id + "/archive", Map.of()).statusCode()).isEqualTo(302);
        assertThat(get("/").body()).doesNotContain("New Name");
        String accounts = get("/accounts").body();
        assertThat(accounts).contains("Archived accounts").contains("New Name").contains("€42.00");
        assertThat(get("/accounts/" + id).body()).contains("€42.00").contains("This account is archived");
        assertThat(post("/accounts/" + id + "/balances", Map.of("amount", "1", "date", days(0))).statusCode()).isEqualTo(400);

        assertThat(post("/accounts/" + id + "/restore", Map.of()).statusCode()).isEqualTo(302);
        assertThat(get("/").body()).contains("New Name");

        assertThat(post("/accounts/" + id + "/delete", Map.of()).statusCode()).isIn(404, 405);
        assertThat(CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/accounts/" + id)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isIn(404, 405);
    }

    @Test
    void aBalanceCanBeRemovedFromItsEditPage() throws Exception {
        long id = createAccount("Removal Account", "private", "current", "");
        post("/accounts/" + id + "/balances", Map.of("amount", "10", "date", days(1)));
        post("/accounts/" + id + "/balances", Map.of("amount", "20", "date", days(0)));
        String page = get("/accounts/" + id).body();
        Matcher edit = Pattern.compile("/accounts/" + id + "/balances/(\\d+)/edit").matcher(page);
        assertThat(edit.find()).isTrue();

        assertThat(post("/accounts/" + id + "/balances/" + edit.group(1) + "/remove", Map.of()).statusCode()).isEqualTo(302);

        String after = get("/accounts/" + id).body();
        assertThat(after).contains("€10.00").doesNotContain("€20.00");
    }

    @Test
    void unknownAccountsAndEntriesAreNotFound() throws Exception {
        assertThat(get("/accounts/99999").statusCode()).isEqualTo(404);
        assertThat(get("/accounts/99999/edit").statusCode()).isEqualTo(404);
        long id = createAccount("Known", "private", "current", "");
        assertThat(get("/accounts/" + id + "/balances/99999/edit").statusCode()).isEqualTo(404);
    }

    @Test
    void aChangeRequestFromAnotherSiteIsRefusedButReadingIsNot() throws Exception {
        HttpResponse<String> crossSite = post("/accounts", Map.of("name", "Forged", "scope", "private", "kind", "current", "iban", ""),
                "Sec-Fetch-Site", "cross-site");
        HttpResponse<String> sameSite = post("/accounts", Map.of("name", "Genuine", "scope", "private", "kind", "current", "iban", ""),
                "Sec-Fetch-Site", "same-origin");

        assertThat(crossSite.statusCode()).isEqualTo(403);
        assertThat(sameSite.statusCode()).isEqualTo(302);
        assertThat(get("/accounts").body()).doesNotContain("Forged").contains("Genuine");
    }

    @Test
    void aChangeRequestWithoutAPageOfOursIsAcceptedButSameSiteIsNot() throws Exception {
        Map<String, String> form = Map.of("name", "Header Check", "scope", "private", "kind", "current", "iban", "");

        assertThat(post("/accounts", form, "Sec-Fetch-Site", "none").statusCode()).isEqualTo(302);
        assertThat(post("/accounts", form, "Sec-Fetch-Site", "same-site").statusCode()).isEqualTo(403);
    }

    @Test
    void anUnknownAccountShowsAPageWithAWayBackNotRawJson() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/accounts/99999")).header("Accept", "text/html").build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("Page not found").contains("Back to the overview").doesNotContain("\"timestamp\"");
    }

    @Test
    void everyReasonTheDomainCanRefuseWithHasAMessage() throws IOException {
        Properties messages = new Properties();
        try (var in = Files.newBufferedReader(Path.of("src/main/resources/messages.properties"))) {
            messages.load(in);
        }
        for (AccountException.Reason reason : AccountException.Reason.values()) {
            assertThat(messages).containsKey(AccountsController.errorKey(new AccountException(reason)));
        }
        for (AmountFormatException.Reason reason : AmountFormatException.Reason.values()) {
            assertThat(messages).containsKey(AccountsController.errorKey(new AmountFormatException(reason)));
        }
    }

    @Test
    void thePagesFitAPhoneAndSupportDarkMode() throws Exception {
        for (String path : new String[] {"/", "/accounts", "/accounts/new"}) {
            assertThat(get(path).body()).contains("name=\"viewport\"").contains("name=\"color-scheme\"");
        }
        assertThat(get("/app.css").body()).contains("prefers-color-scheme: dark");
    }
}
