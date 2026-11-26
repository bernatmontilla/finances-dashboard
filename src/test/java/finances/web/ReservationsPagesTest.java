package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.Account;
import finances.accounts.AccountService;
import finances.accounts.DeliveredTransaction;
import finances.accounts.Kind;
import finances.accounts.ReservationService;
import finances.accounts.Scope;
import finances.accounts.Source;
import finances.accounts.TransactionService;
import finances.accounts.TransactionStatus;
import finances.money.Money;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The reservations pages through the real application on a temporary data folder (issue #20): the owner's journey from "no
 * reservations" to a headline that is lowered by them, and back. No after-tax source is configured here, so the business side
 * is the state without a business number. Fictional data only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationsPagesTest {

    private static final Path DATA = createTempDataFolder();
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
        registry.add("finances.sync.enabled", () -> "false");
    }

    private static Path createTempDataFolder() {
        try {
            return Files.createTempDirectory("finances-test-reservations");
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

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AccountService accounts;

    @Autowired
    ReservationService reservations;

    @Autowired
    TransactionService transactions;

    @Autowired
    Clock clock;

    @BeforeEach
    void emptyDatabase() {
        for (String table : new String[] {"reservation", "after_tax_reserved", "after_tax_value", "after_tax_fetch", "sync_run",
                "bank_account", "bank_consent", "transactions", "balance", "account"}) {
            jdbc.sql("delete from " + table).update();
        }
    }

    // ----- helpers

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> form, String... headers) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static Map<String, String> form(String name, String scope, String amount, String due, String yearly, String reserved) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("name", name);
        form.put("scope", scope);
        form.put("amount", amount);
        form.put("due", due);
        if (yearly != null) {
            form.put("yearly", yearly);
        }
        if (reserved != null) {
            form.put("reserved", reserved);
        }
        return form;
    }

    private void everydayAccount(long cents) {
        long id = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        accounts.addBalance(id, Money.eur(cents), today());
    }

    private long idOf(String name) {
        return jdbc.sql("select id from reservation where name = ?").param(name).query(Long.class).single();
    }

    private static String headlineAmount(String page, String title) {
        int at = page.indexOf(">" + title + "<");
        assertThat(at).as("a headline titled " + title).isGreaterThanOrEqualTo(0);
        Matcher amount = Pattern.compile("<p class=\"headline-amount[^\"]*\">.*?>(-?€[\\d,.]+)<", Pattern.DOTALL).matcher(page.substring(at));
        assertThat(amount.find()).isTrue();
        return amount.group(1);
    }

    // ----- the journey

    @Test
    void theOwnerCreatesEditsSetsReservedClosesReopensAndRemovesThroughThePages() throws Exception {
        assertThat(get("/reservations").body()).contains("No reservations yet");
        assertThat(get("/reservations/new").statusCode()).isEqualTo(200);

        String due = today().plusDays(100).toString();
        HttpResponse<String> created = post("/reservations", form("Example Insurance premium", "private", "1.200,00", due, "yes", "200,00"));
        assertThat(created.statusCode()).isEqualTo(302);
        assertThat(created.headers().firstValue("Location").orElseThrow()).endsWith("/reservations");
        long id = idOf("Example Insurance premium");

        String page = get("/reservations").body();
        assertThat(page).contains("Example Insurance premium").contains("€200.00").contains("€1,200.00").contains("Repeats every year")
                .contains("per month").contains("Due " + due);
        assertThat(page).as("open and not overdue").doesNotContain("Overdue");

        // The edit form is filled in; changing the amount keeps what is reserved.
        String edit = get("/reservations/" + id + "/edit").body();
        assertThat(edit).contains("value=\"Example Insurance premium\"").contains("1200.00");
        assertThat(post("/reservations/" + id, form("Example Insurance premium", "private", "2400.00", due, "yes", null)).statusCode()).isEqualTo(302);
        assertThat(get("/reservations").body()).contains("€2,400.00").contains("Reserved €200.00 of €2,400.00");

        // Set what is reserved by hand.
        assertThat(post("/reservations/" + id + "/reserved", Map.of("reserved", "500.00")).statusCode()).isEqualTo(302);
        assertThat(get("/reservations").body()).contains("Reserved €500.00 of €2,400.00");
        HttpResponse<String> tooMuch = post("/reservations/" + id + "/reserved", Map.of("reserved", "2400.01"));
        assertThat(tooMuch.statusCode()).isEqualTo(400);
        assertThat(tooMuch.body()).contains("What is reserved must be from zero up to the amount needed.");

        // Close: a yearly one starts next year's round, and the closed one is listed under Closed.
        assertThat(post("/reservations/" + id + "/close", Map.of()).statusCode()).isEqualTo(302);
        page = get("/reservations").body();
        assertThat(page).contains("Closed").contains("Reopen");
        assertThat(page.split("Example Insurance premium", -1)).as("the closed one and the next round").hasSize(3);
        assertThat(page).contains("Due " + today().plusDays(100).plusYears(1));

        // Reopen: the untouched next round goes again.
        assertThat(post("/reservations/" + id + "/reopen", Map.of()).statusCode()).isEqualTo(302);
        page = get("/reservations").body();
        assertThat(page.split("Example Insurance premium", -1)).hasSize(2);
        assertThat(page).doesNotContain("Reopen");

        // Remove.
        assertThat(post("/reservations/" + id + "/remove", Map.of()).statusCode()).isEqualTo(302);
        assertThat(get("/reservations").body()).contains("No reservations yet");
        assertThat(get("/reservations/" + id + "/edit").statusCode()).isEqualTo(404);
        assertThat(post("/reservations/" + id + "/close", Map.of()).statusCode()).isEqualTo(404);
    }

    @Test
    void aRefusedFormIsShownAgainFilledInWithTheReason() throws Exception {
        HttpResponse<String> response = post("/reservations", form("   ", "private", "10,00", today().plusDays(5).toString(), null, null));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("Enter a name.").contains("value=\"10,00\"");

        assertThat(post("/reservations", form("A", "private", "0", today().plusDays(5).toString(), null, null)).body())
                .contains("The amount must be more than zero.");
        assertThat(post("/reservations", form("A", "private", "abc", today().plusDays(5).toString(), null, null)).statusCode()).isEqualTo(400);
        assertThat(post("/reservations", form("A", "private", "5,00", today().minusDays(1).toString(), null, null)).body())
                .contains("The due date cannot be before today.");
        assertThat(post("/reservations", form("A", "private", "5,00", "", null, null)).body()).contains("Enter a due date.");
        assertThat(post("/reservations", form("A", "private", "5,00", "soon", null, null)).body()).contains("This is not a date.");
        assertThat(post("/reservations", form("A", "elsewhere", "5,00", today().plusDays(5).toString(), null, null)).statusCode()).isEqualTo(400);
        assertThat(get("/reservations").body()).contains("No reservations yet");
    }

    @Test
    void aPostFromAnotherOriginIsRefusedAndChangesNothing() throws Exception {
        HttpResponse<String> response = post("/reservations", form("Example Insurance premium", "private", "10,00",
                today().plusDays(5).toString(), null, null), "Origin", "https://evil.example.com");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(get("/reservations").body()).contains("No reservations yet");

        long id = reservations.create("Example Insurance premium", Scope.PRIVATE, Money.eur(1_000), today().plusDays(5), false, null).id();
        assertThat(post("/reservations/" + id + "/remove", Map.of(), "Origin", "https://evil.example.com").statusCode()).isEqualTo(403);
        assertThat(post("/reservations/" + id + "/close", Map.of(), "Sec-Fetch-Site", "cross-site").statusCode()).isEqualTo(403);
        assertThat(reservations.get(id).isOpen()).isTrue();
    }

    @Test
    void theBuildUpPagesListTheReservationsAndTheirSum() throws Exception {
        everydayAccount(123_456);
        assertThat(get("/build-up/private").body()).contains("No open reservations.");

        reservations.create("Example Insurance premium", Scope.PRIVATE, Money.eur(100_000), today().plusDays(30), false, Money.eur(30_000));
        reservations.create("Example Landlord deposit", Scope.PRIVATE, Money.eur(50_000), today().plusDays(60), false, Money.eur(5_000));

        String build = get("/build-up/private").body();
        assertThat(build).contains("Example Insurance premium").contains("−€300.00").contains("Example Landlord deposit").contains("−€50.00")
                .contains("Reserved in total: €350.00").contains("href=\"/reservations\"");
        assertThat(build).doesNotContain("No open reservations.");
        assertThat(headlineAmount(get("/").body(), "Private free to spend")).isEqualTo("€884.56");
        // Each reservation lowers its own scope only: the business page has no source here, so nothing to list.
        assertThat(get("/build-up/business").body()).doesNotContain("Example Insurance premium");
    }

    @Test
    void theOverviewShowsTheReservedTotalAndTheNextOnesDuePerScopeWithOverdueFlagged() throws Exception {
        everydayAccount(123_456);
        assertThat(get("/").body()).doesNotContain("Reserved:");

        long late = reservations.create("Example Insurance premium", Scope.PRIVATE, Money.eur(100_000), today().plusDays(30), false, Money.eur(10_000)).id();
        reservations.create("Example Gym year", Scope.PRIVATE, Money.eur(20_000), today().plusDays(60), false, Money.eur(2_000));
        reservations.create("Example Telecom bundle", Scope.PRIVATE, Money.eur(30_000), today().plusDays(90), false, null);
        reservations.create("Example Energy deposit", Scope.PRIVATE, Money.eur(40_000), today().plusDays(120), false, null);
        reservations.create("Example Client BV retainer", Scope.BUSINESS, Money.eur(70_000), today().plusDays(10), false, Money.eur(7_000));

        String overview = get("/").body();
        assertThat(overview).contains("Reserved: €120.00").contains("Reserved: €70.00");
        assertThat(overview).contains("Example Insurance premium").contains("Example Gym year").contains("Example Telecom bundle");
        assertThat(overview).as("only the next three per scope").doesNotContain("Example Energy deposit");
        assertThat(overview).as("nothing overdue yet").doesNotContain("Overdue");

        // The insurance is now past its due date: fully reserved, flagged, never dropped.
        jdbc.sql("update reservation set due_date = ?, anchor_date = ? where id = ?")
                .params(today().minusDays(2).toString(), today().minusDays(30).toString(), late).update();
        overview = get("/").body();
        assertThat(overview).contains("Overdue").contains("Reserved €1,000.00 of €1,000.00").contains("Reserved: €1,020.00");
        assertThat(get("/reservations").body()).contains("Overdue").contains("Reserved €1,000.00 of €1,000.00");
        assertThat(overview.indexOf("Example Insurance premium")).as("overdue ones first").isLessThan(overview.indexOf("Example Gym year"));
    }

    @Test
    void withoutAnAfterTaxSourceTheBusinessGroupSaysThereIsNoBusinessNumberToReduce() throws Exception {
        reservations.create("Example Client BV retainer", Scope.BUSINESS, Money.eur(70_000), today().plusDays(10), false, null);

        assertThat(get("/reservations").body()).contains("Example Client BV retainer")
                .contains("There is no business number to reduce");
        assertThat(get("/").body()).contains("There is no business number to reduce");
        assertThat(get("/").body()).contains("Not shown: no after-tax source is configured");
    }

    @Test
    void everyReservationsPageHasTheNavLinkAndNoHorizontalLayoutTrap() throws Exception {
        String page = get("/reservations").body();
        assertThat(page).contains("href=\"/reservations\"").contains("name=\"viewport\"");
        assertThat(get("/").body()).contains("href=\"/reservations\">Reservations<");
    }

    // ----- closing against a payment (issue #51), and the two review points of #20

    private long payment(Account account, LocalDate date, long cents, TransactionStatus status, String reference) {
        transactions.deliver(account.id(), Source.CSV, List.of(new DeliveredTransaction(date, null, cents, "EUR", "Example Insurance",
                "NL10SHOP0000000101", "Premium " + reference, status, status == TransactionStatus.BOOKED ? reference : null, "raw:" + reference)));
        return jdbc.sql("select max(id) from transactions").query(Long.class).single();
    }

    private Account everyday() {
        return accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, "NL51XMPL0000000001");
    }

    @Test
    void thePickerListsTheLikelyPaymentsFirstAndTheOwnerClosesAgainstOne() throws Exception {
        long id = reservations.create("Example Insurance premium", Scope.PRIVATE, Money.eur(120_000), today().plusDays(2), false, null).id();
        Account everyday = everyday();
        long other = payment(everyday, today().minusDays(30), -999, TransactionStatus.BOOKED, "REF-A");
        long likely = payment(everyday, today().minusDays(1), -123_456, TransactionStatus.BOOKED, "REF-B");
        payment(everyday, today().minusDays(1), -777, TransactionStatus.PENDING, "REF-C");
        payment(everyday, today().minusDays(1), 5_000, TransactionStatus.BOOKED, "REF-D");

        HttpResponse<String> picker = get("/reservations/" + id + "/close");
        assertThat(picker.statusCode()).isEqualTo(200);
        String page = picker.body();
        assertThat(page).contains("Close against a payment").contains("Likely match").contains("€1,234.56").contains("€9.99")
                .contains("Everyday").contains("Example Insurance");
        assertThat(page).as("the likely one first").containsSubsequence("value=\"" + likely + "\"", "value=\"" + other + "\"");
        assertThat(page).as("no pending one, no income").doesNotContain("€7.77").doesNotContain("€50.00");

        assertThat(post("/reservations/" + id + "/close", Map.of("transaction", Long.toString(likely))).statusCode()).isEqualTo(302);

        String list = get("/reservations").body();
        assertThat(list).contains("Paid €1,234.56 on " + today().minusDays(1) + " from Everyday").contains("€34.56 more than reserved");
        assertThat(get("/reservations/" + id + "/close").statusCode()).as("closed: nothing to pick for").isEqualTo(404);

        // Reopening clears the link: the payment and the difference are gone.
        assertThat(post("/reservations/" + id + "/reopen", Map.of()).statusCode()).isEqualTo(302);
        assertThat(get("/reservations").body()).doesNotContain("more than reserved").doesNotContain("Paid €");
    }

    @Test
    void theDifferenceIsShownForLessThanReservedAndForExactlyAsReserved() throws Exception {
        long less = reservations.create("Example Gym year", Scope.PRIVATE, Money.eur(20_000), today().plusDays(2), false, null).id();
        long exact = reservations.create("Example Telecom bundle", Scope.PRIVATE, Money.eur(30_000), today().plusDays(2), false, null).id();
        Account everyday = everyday();
        long cheaper = payment(everyday, today(), -15_000, TransactionStatus.BOOKED, "REF-1");
        long same = payment(everyday, today(), -30_000, TransactionStatus.BOOKED, "REF-2");

        assertThat(post("/reservations/" + less + "/close", Map.of("transaction", Long.toString(cheaper))).statusCode()).isEqualTo(302);
        assertThat(post("/reservations/" + exact + "/close", Map.of("transaction", Long.toString(same))).statusCode()).isEqualTo(302);

        assertThat(get("/reservations").body()).contains("€50.00 less than reserved").contains("Exactly as reserved");
    }

    @Test
    void closingWithABlankTransactionClosesWithoutOneAsBefore() throws Exception {
        long id = reservations.create("Example Gym year", Scope.PRIVATE, Money.eur(20_000), today().plusDays(2), false, null).id();

        assertThat(post("/reservations/" + id + "/close", Map.of("transaction", "")).statusCode()).isEqualTo(302);

        assertThat(reservations.get(id).isOpen()).isFalse();
        assertThat(reservations.get(id).transactionId()).isNull();
        assertThat(get("/reservations").body()).doesNotContain("than reserved").doesNotContain("Paid €");
    }

    @Test
    void aRefusedPaymentShowsThePickerAgainWithTheReasonAndChangesNothing() throws Exception {
        long id = reservations.create("Example Gym year", Scope.PRIVATE, Money.eur(20_000), today().plusDays(2), false, null).id();
        Account everyday = everyday();
        long pending = payment(everyday, today(), -20_000, TransactionStatus.PENDING, "REF-P");
        long income = payment(everyday, today(), 20_000, TransactionStatus.BOOKED, "REF-I");
        long business = payment(accounts.addManual("Business", Scope.BUSINESS, Kind.CURRENT, "NL94XMPL0000000003"), today(), -20_000,
                TransactionStatus.BOOKED, "REF-B");

        HttpResponse<String> notBooked = post("/reservations/" + id + "/close", Map.of("transaction", Long.toString(pending)));
        assertThat(notBooked.statusCode()).isEqualTo(400);
        assertThat(notBooked.body()).contains("Close against a payment").contains("Only a booked payment can settle a reservation.");
        assertThat(post("/reservations/" + id + "/close", Map.of("transaction", Long.toString(income))).body())
                .contains("Only money that left an account can settle a reservation.");
        assertThat(post("/reservations/" + id + "/close", Map.of("transaction", Long.toString(business))).body())
                .contains("This payment is from an account of the other scope.");
        assertThat(post("/reservations/" + id + "/close", Map.of("transaction", "999999")).body()).contains("This payment does not exist.");
        assertThat(post("/reservations/" + id + "/close", Map.of("transaction", "abc")).statusCode()).isEqualTo(400);

        assertThat(reservations.get(id).isOpen()).isTrue();
        assertThat(post("/reservations/999999/close", Map.of("transaction", "1")).statusCode()).isEqualTo(404);
        assertThat(get("/reservations/999999/close").statusCode()).isEqualTo(404);
    }

    @Test
    void aClosedReservationCannotBeRemovedByADirectPost() throws Exception {
        long id = reservations.create("Example Gym year", Scope.PRIVATE, Money.eur(20_000), today().plusDays(2), false, null).id();
        reservations.close(id);

        HttpResponse<String> response = post("/reservations/" + id + "/remove", Map.of());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("A closed reservation cannot be changed. Reopen it first.");
        assertThat(reservations.find(id)).isPresent();
    }

    @Test
    void aReservationThatIsDueOffersNoWayToSetWhatIsReservedAndRefusesIt() throws Exception {
        long due = reservations.create("Example Gym year", Scope.PRIVATE, Money.eur(20_000), today(), false, null).id();
        long coming = reservations.create("Example Telecom bundle", Scope.PRIVATE, Money.eur(30_000), today().plusDays(40), false, null).id();

        String dueEdit = get("/reservations/" + due + "/edit").body();
        assertThat(dueEdit).doesNotContain("name=\"reserved\"").contains("This reservation is due: its full amount counts as reserved");
        HttpResponse<String> refused = post("/reservations/" + due + "/reserved", Map.of("reserved", "10,00"));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("This reservation is due: its full amount counts as reserved");
        assertThat(reservations.get(due).anchorAmount()).isEqualTo(Money.zero());

        assertThat(get("/reservations/" + coming + "/edit").body()).contains("name=\"reserved\"")
                .doesNotContain("its full amount counts as reserved");
    }
}
