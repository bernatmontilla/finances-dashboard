package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.AccountService;
import finances.accounts.DeliveredTransaction;
import finances.accounts.FixedCostService;
import finances.accounts.Kind;
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
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 * The fixed costs pages through the real application on a temporary data folder (issue #21): the owner's journey from "no fixed
 * costs" to the overview line that lists what is still to come, and back. Dates are built from today, so the test holds on any day.
 * Fictional data only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FixedCostsPagesTest {

    private static final Path DATA = createTempDataFolder();
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
        registry.add("finances.sync.enabled", () -> "false");
    }

    private static Path createTempDataFolder() {
        try {
            return Files.createTempDirectory("finances-test-fixed-costs");
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
    Clock clock;

    @Autowired
    TransactionService transactions;

    @Autowired
    FixedCostService fixedCosts;

    @BeforeEach
    void emptyDatabase() {
        for (String table : new String[] {"fixed_cost_paid", "fixed_cost", "reservation", "after_tax_reserved", "after_tax_value",
                "after_tax_fetch", "sync_run", "bank_account", "bank_consent", "transactions", "balance", "account"}) {
            jdbc.sql("delete from " + table).update();
        }
    }

    // ----- helpers

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static Map<String, String> form(String name, String scope, String amount, String rhythm, String firstDue) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("name", name);
        form.put("scope", scope);
        form.put("amount", amount);
        form.put("rhythm", rhythm);
        form.put("firstDue", firstDue);
        return form;
    }

    private long idOf(String name) {
        return jdbc.sql("select id from fixed_cost where name = ?").param(name).query(Long.class).single();
    }

    private static String headlineAmount(String page, String title) {
        int at = page.indexOf(">" + title + "<");
        assertThat(at).as("a headline titled " + title).isGreaterThanOrEqualTo(0);
        Matcher amount = Pattern.compile("<p class=\"headline-amount[^\"]*\">.*?>(-?€[\\d,.]+)<", Pattern.DOTALL).matcher(page.substring(at));
        assertThat(amount.find()).isTrue();
        return amount.group(1);
    }

    /** The overview with its headline section cut out, to look at what comes after the headline numbers. */
    private static String afterHeadlines(String overview) {
        return overview.substring(overview.indexOf("</section>", overview.indexOf("id=\"headlines\"")));
    }

    // ----- the journey

    @Test
    void theOwnerAddsEditsTicksPausesEndsAndRemovesThroughThePages() throws Exception {
        assertThat(get("/fixed-costs").body()).contains("No fixed costs yet").contains("A reservation builds up towards one payment")
                .contains("href=\"/reservations\"");
        assertThat(get("/fixed-costs/new").statusCode()).isEqualTo(200);
        assertThat(get("/").body()).as("nothing to come, no line").doesNotContain("still to come this month");

        String charged = today().toString();
        HttpResponse<String> created = post("/fixed-costs", form("Example Landlord rent", "private", "1.234,56", "monthly", charged));
        assertThat(created.statusCode()).isEqualTo(302);
        assertThat(created.headers().firstValue("Location").orElseThrow()).endsWith("/fixed-costs");
        long id = idOf("Example Landlord rent");

        String page = get("/fixed-costs").body();
        assertThat(page).contains("Example Landlord rent").contains("€1,234.56").contains("every month").contains("Per month: €1,234.56")
                .contains("Per year: €14,814.72").contains("Still to come this month").contains("expected " + charged);

        String overview = get("/").body();
        assertThat(overview).contains("Fixed costs still to come this month, Private: €1,234.56").contains("Example Landlord rent")
                .contains("expected " + charged).contains("href=\"/fixed-costs\"");
        assertThat(overview.indexOf("fixed-costs-private")).as("placed after the headline numbers")
                .isGreaterThan(overview.indexOf("id=\"headlines\""));

        // Ticked by hand: paid, and gone from the overview.
        assertThat(post("/fixed-costs/" + id + "/tick", Map.of()).statusCode()).isEqualTo(302);
        assertThat(get("/fixed-costs").body()).contains("Paid this month").contains("Not paid after all");
        assertThat(get("/").body()).doesNotContain("still to come this month");
        assertThat(post("/fixed-costs/" + id + "/untick", Map.of()).statusCode()).isEqualTo(302);
        assertThat(get("/").body()).contains("still to come this month");

        // Edit: the form is filled in and the change is kept.
        String edit = get("/fixed-costs/" + id + "/edit").body();
        assertThat(edit).contains("value=\"Example Landlord rent\"").contains("value=\"1234.56\"").contains("value=\"" + charged + "\"");
        assertThat(post("/fixed-costs/" + id, form("Example Landlord rent", "private", "1.300,00", "monthly", charged)).statusCode())
                .isEqualTo(302);
        assertThat(get("/fixed-costs").body()).contains("€1,300.00").doesNotContain("€1,234.56");

        // Pause and resume.
        assertThat(post("/fixed-costs/" + id + "/pause", Map.of()).statusCode()).isEqualTo(302);
        String paused = get("/fixed-costs").body();
        assertThat(paused).contains("Paused").contains("No fixed costs are running.");
        assertThat(get("/").body()).doesNotContain("still to come this month");
        assertThat(post("/fixed-costs/" + id + "/resume", Map.of()).statusCode()).isEqualTo(302);
        assertThat(get("/").body()).contains("still to come this month");

        // End, then resume after the end.
        assertThat(post("/fixed-costs/" + id, form("Example Landlord rent", "private", "1.300,00", "monthly", today().minusMonths(1).toString()))
                .statusCode()).isEqualTo(302);
        assertThat(post("/fixed-costs/" + id + "/end", Map.of("endedOn", today().minusDays(1).toString())).statusCode()).isEqualTo(302);
        String ended = get("/fixed-costs").body();
        assertThat(ended).contains("Ended").contains("since " + today().minusDays(1)).doesNotContain("/fixed-costs/" + id + "/edit");
        assertThat(get("/fixed-costs/" + id + "/edit").statusCode()).as("an ended one is not edited").isEqualTo(404);
        assertThat(post("/fixed-costs/" + id + "/resume", Map.of()).statusCode()).isEqualTo(302);
        assertThat(get("/fixed-costs").body()).contains("Still to come this month");

        // Remove.
        assertThat(post("/fixed-costs/" + id + "/remove", Map.of()).statusCode()).isEqualTo(302);
        assertThat(get("/fixed-costs").body()).contains("No fixed costs yet");
    }

    @Test
    void theOverviewShowsOneBlockPerScopeWithItsOwnTotalAndTheExpectedDates() throws Exception {
        post("/fixed-costs", form("Example Landlord rent", "private", "100.00", "monthly", today().toString()));
        post("/fixed-costs", form("Example Telecom", "private", "12.34", "monthly", today().toString()));
        post("/fixed-costs", form("Example Client BV software", "business", "50.00", "monthly", today().toString()));

        String after = afterHeadlines(get("/").body());

        assertThat(after).contains("Fixed costs still to come this month, Private: €112.34")
                .contains("Fixed costs still to come this month, Business: €50.00").contains("Example Telecom")
                .contains("Example Client BV software");
        assertThat(after.indexOf("Private: €112.34")).isLessThan(after.indexOf("Business: €50.00"));
    }

    @Test
    void aLongerRhythmIsNotToComeThisMonthButShowsWhenItIsNextDue() throws Exception {
        LocalDate lastChargeMonth = today().minusMonths(3); // a yearly cost charged three months ago is not charged this month
        post("/fixed-costs", form("Example Gym", "private", "120.00", "yearly", lastChargeMonth.toString()));
        post("/fixed-costs", form("Example Insurance", "private", "100.00", "quarterly", lastChargeMonth.toString()));

        String page = get("/fixed-costs").body();

        assertThat(page).contains("Not charged this month").contains("next due " + lastChargeMonth.plusYears(1))
                .contains("Per year: €520.00").contains("Per month: €43.34");
        assertThat(page).as("the quarterly one is charged this month").contains("Still to come this month");
        assertThat(get("/").body()).contains("Private: €100.00").doesNotContain("Example Gym");
    }

    @Test
    void theHeadlineNumbersAreTheSameWithAndWithoutFixedCosts() throws Exception {
        long everyday = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        accounts.addBalance(everyday, Money.eur(123456), today());
        String before = get("/").body();
        String privateBefore = headlineAmount(before, "Private free to spend");

        post("/fixed-costs", form("Example Landlord rent", "private", "1.000,00", "monthly", today().toString()));
        String after = get("/").body();

        assertThat(after).contains("Fixed costs still to come this month");
        assertThat(headlineAmount(after, "Private free to spend")).isEqualTo(privateBefore).isEqualTo("€1,234.56");
    }

    // ----- matched against transactions (issue #63)

    private void deliverEnergy(long account, LocalDate booked, long cents, String reference) {
        transactions.deliver(account, Source.CSV, List.of(new DeliveredTransaction(booked, null, cents, "EUR", "Example Energy",
                "NL10SHOP0000000101", "Fictional payment", TransactionStatus.BOOKED, reference, "raw " + reference)));
    }

    private void addEnergy() throws Exception {
        Map<String, String> energy = form("Example Energy", "private", "100.00", "monthly", today().toString());
        energy.put("iban", "NL10SHOP0000000101");
        assertThat(post("/fixed-costs", energy).statusCode()).isEqualTo(302);
    }

    @Test
    void aMatchingPaymentMarksTheMonthPaidShowsItsDateAndAmountAndTheOwnerCanUntickIt() throws Exception {
        long account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        addEnergy();
        long id = idOf("Example Energy");
        assertThat(get("/fixed-costs").body()).contains("Still to come this month");

        deliverEnergy(account, today(), -10000, "REF-1");
        fixedCosts.matchPayments();

        String page = get("/fixed-costs").body();
        assertThat(page).contains("Paid this month").contains("paid by a transaction booked " + today() + ": €100.00")
                .contains("Not paid after all");
        assertThat(get("/").body()).as("paid, so no longer to come").doesNotContain("still to come this month");

        assertThat(post("/fixed-costs/" + id + "/untick", Map.of()).statusCode()).isEqualTo(302);
        assertThat(get("/fixed-costs").body()).contains("Still to come this month").doesNotContain("paid by a transaction");
        fixedCosts.matchPayments();
        assertThat(get("/fixed-costs").body()).as("the owner's untick wins over the match").contains("Still to come this month");
    }

    @Test
    void stillToComeShowsTheAgeOfTheLatestDeliveryOnThePageAndOnTheOverview() throws Exception {
        long account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        addEnergy();

        assertThat(get("/fixed-costs").body()).contains("Matched against transactions").contains("No transactions delivered yet");
        assertThat(get("/").body()).contains("No transactions delivered yet");

        deliverEnergy(account, today().minusDays(30), -999, "REF-OLD");

        assertThat(get("/fixed-costs").body()).contains("Transactions delivered " + today()).contains("(today)");
        assertThat(get("/").body()).contains("Transactions delivered " + today()).contains("(today)");
    }

    @Test
    void theAgeIsMarkedStaleWhenAnAccountInvolvedIsStale() throws Exception {
        long account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        accounts.addBalance(account, Money.eur(1000), today().minusDays(90));
        addEnergy();

        String page = get("/fixed-costs").body();
        assertThat(page).contains("Matched against transactions").contains("Stale").contains("not updated for 90 days");
        assertThat(get("/").body()).contains("not updated for 90 days");
    }

    @Test
    void aScopeWithOnlyFixedCostsKeptByHandShowsNoAge() throws Exception {
        accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null);
        post("/fixed-costs", form("Example Gym", "private", "10.00", "monthly", today().toString()));

        assertThat(get("/fixed-costs").body()).contains("Still to come this month").doesNotContain("Matched against transactions");
        assertThat(get("/").body()).doesNotContain("Matched against transactions");
    }

    @Test
    void theHeadlineNumbersAreTheSameWithAMatchedFixedCost() throws Exception {
        long everyday = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        accounts.addBalance(everyday, Money.eur(123456), today());
        addEnergy();
        String before = headlineAmount(get("/").body(), "Private free to spend");

        deliverEnergy(everyday, today(), -10000, "REF-1");
        fixedCosts.matchPayments();

        assertThat(headlineAmount(get("/").body(), "Private free to spend")).isEqualTo(before);
    }

    @Test
    void theReservationsPageHintsAtFixedCostsAndLinksThere() throws Exception {
        assertThat(get("/reservations").body()).contains("A reservation builds up towards one payment; a fixed cost repeats.")
                .contains("href=\"/fixed-costs\"");
        assertThat(get("/").body()).contains("href=\"/fixed-costs\"");
    }

    @Test
    void editingAFixedCostLinkedToAnArchivedAccountKeepsTheLink() throws Exception {
        long account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        Map<String, String> create = form("Example Gym", "private", "10.00", "monthly", today().toString());
        create.put("account", Long.toString(account));
        assertThat(post("/fixed-costs", create).statusCode()).isEqualTo(302);
        long id = idOf("Example Gym");
        accounts.archive(account);

        String edit = get("/fixed-costs/" + id + "/edit").body();
        assertThat(edit).contains("Everyday (Private, archived)").containsPattern("value=\"" + account + "\"\\s+selected");

        Map<String, String> unchanged = form("Example Gym", "private", "12.00", "monthly", today().toString());
        unchanged.put("account", Long.toString(account));
        assertThat(post("/fixed-costs/" + id, unchanged).statusCode()).isEqualTo(302);
        assertThat(jdbc.sql("select account_id from fixed_cost where id = ?").param(id).query(Long.class).single()).isEqualTo(account);
    }

    // ----- flags (issue #22)

    private void deliverGym(long account, LocalDate booked, long cents, String reference) {
        transactions.deliver(account, Source.CSV, List.of(new DeliveredTransaction(booked, null, cents, "EUR", "Example Gym", null,
                "Fictional payment", TransactionStatus.BOOKED, reference, "raw " + reference)));
    }

    private void addGym() throws Exception {
        Map<String, String> gym = form("Example Gym", "private", "10.00", "monthly", today().toString());
        gym.put("counterparty", "Example Gym");
        assertThat(post("/fixed-costs", gym).statusCode()).isEqualTo(302);
    }

    @Test
    void aPriceChangeIsFlaggedOnThePageAndTheOverviewAndTheOwnerCanTakeItOverOrKeepTheOldPrice() throws Exception {
        long account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        addEnergy();
        long id = idOf("Example Energy");
        assertThat(get("/").body()).doesNotContain("need a look").doesNotContain("needs a look");

        deliverEnergy(account, today(), -15000, "REF-1");

        String page = get("/fixed-costs").body();
        assertThat(page).contains("The latest charge, booked " + today() + ", was €150.00, not €100.00").contains("Take over €150.00").contains("Keep €100.00");
        assertThat(get("/").body()).contains("Fixed costs: 1 needs a look").contains("id=\"fixed-cost-flags\"");

        long transaction = jdbc.sql("select id from transactions where reference = 'REF-1'").query(Long.class).single();
        assertThat(post("/fixed-costs/" + id + "/keep-amount", Map.of("transaction", Long.toString(transaction))).statusCode()).isEqualTo(302);
        assertThat(get("/fixed-costs").body()).doesNotContain("The latest charge, booked");
        assertThat(get("/").body()).doesNotContain("need a look").doesNotContain("needs a look");
        assertThat(jdbc.sql("select amount_cents from fixed_cost where id = ?").param(id).query(Long.class).single()).isEqualTo(10000);

        deliverEnergy(account, today(), -15000, "REF-2");
        long second = jdbc.sql("select id from transactions where reference = 'REF-2'").query(Long.class).single();
        assertThat(get("/fixed-costs").body()).contains("Take over €150.00");
        assertThat(post("/fixed-costs/" + id + "/take-over-amount", Map.of("transaction", Long.toString(second))).statusCode()).isEqualTo(302);
        assertThat(jdbc.sql("select amount_cents from fixed_cost where id = ?").param(id).query(Long.class).single()).isEqualTo(15000);
        assertThat(get("/fixed-costs").body()).doesNotContain("The latest charge, booked").contains("Paid this month");
    }

    @Test
    void answersToAFlagThatIsNotOpenAreRefusedWithStatus400AndStoreNothing() throws Exception {
        long account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        addEnergy();
        long id = idOf("Example Energy");
        deliverEnergy(account, today(), -15000, "REF-1");

        for (String path : new String[] {"/take-over-amount", "/keep-amount"}) {
            HttpResponse<String> refused = post("/fixed-costs/" + id + path, Map.of("transaction", "4711"));
            assertThat(refused.statusCode()).isEqualTo(400);
            assertThat(refused.body()).contains("the flag is no longer open");
            assertThat(post("/fixed-costs/" + id + path, Map.of("transaction", "not a number")).statusCode()).isEqualTo(400);
        }
        assertThat(post("/fixed-costs/" + id + "/mark-paid", Map.of("month", today().toString().substring(0, 7))).statusCode()).isEqualTo(400);
        assertThat(post("/fixed-costs/" + id + "/unskip", Map.of("month", today().toString().substring(0, 7))).statusCode()).isEqualTo(400);
        assertThat(post("/fixed-costs/" + id + "/skip", Map.of("month", "garbage")).statusCode()).isEqualTo(400);
        assertThat(jdbc.sql("select amount_cents from fixed_cost where id = ?").param(id).query(Long.class).single()).isEqualTo(10000);
        assertThat(jdbc.sql("select count(*) from fixed_cost_price_kept").query(Integer.class).single()).isZero();
    }

    @Test
    void aMissingChargeIsFlaggedAndTheOwnerCanMarkItPaidSkipItOrEndTheFixedCost() throws Exception {
        long account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        Map<String, String> gym = form("Example Gym", "private", "10.00", "monthly", today().minusDays(10).toString());
        gym.put("counterparty", "Example Gym");
        assertThat(post("/fixed-costs", gym).statusCode()).isEqualTo(302);
        long id = idOf("Example Gym");
        jdbc.sql("update fixed_cost set created_at = ? where id = ?").params(today().minusDays(60) + "T10:00:00Z", id).update();
        deliverEnergy(account, today(), -999, "REF-OTHER");

        String page = get("/fixed-costs").body();
        assertThat(page).contains("No payment arrived for the charge expected").containsPattern("Mark \\d{4}-\\d{2} as paid").containsPattern("Skip \\d{4}-\\d{2}")
                .contains("End this fixed cost");
        assertThat(get("/").body()).contains("Fixed costs: 1 needs a look");
        Matcher month = Pattern.compile("name=\"month\" value=\"(\\d{4}-\\d{2})\"").matcher(page);
        assertThat(month.find()).isTrue();

        assertThat(post("/fixed-costs/" + id + "/skip", Map.of("month", month.group(1))).statusCode()).isEqualTo(302);
        assertThat(get("/fixed-costs").body()).doesNotContain("No payment arrived");
        assertThat(post("/fixed-costs/" + id + "/unskip", Map.of("month", month.group(1))).statusCode()).isEqualTo(302);
        assertThat(get("/fixed-costs").body()).contains("No payment arrived");
        assertThat(post("/fixed-costs/" + id + "/mark-paid", Map.of("month", month.group(1))).statusCode()).isEqualTo(302);
        assertThat(get("/fixed-costs").body()).doesNotContain("No payment arrived");
        assertThat(jdbc.sql("select count(*) from fixed_cost_paid where month = ?").param(month.group(1)).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void thisMonthsChargeCanBeSkippedAndTheSkipTakenBack() throws Exception {
        accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null);
        post("/fixed-costs", form("Example Gym", "private", "10.00", "monthly", today().toString()));
        long id = idOf("Example Gym");
        String month = today().toString().substring(0, 7);

        assertThat(post("/fixed-costs/" + id + "/skip", Map.of("month", month)).statusCode()).isEqualTo(302);
        String page = get("/fixed-costs").body();
        assertThat(page).contains("Skipped this month").contains("Undo skip").doesNotContain("Still to come this month");
        assertThat(get("/").body()).doesNotContain("still to come this month");

        assertThat(post("/fixed-costs/" + id + "/unskip", Map.of("month", month)).statusCode()).isEqualTo(302);
        assertThat(get("/fixed-costs").body()).contains("Still to come this month").doesNotContain("Undo skip");
    }

    @Test
    void twoOpenFlagsShowOneLineOnTheOverviewAndTheHeadlineNumberIsTheSameWithAndWithoutThem() throws Exception {
        long everyday = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        accounts.addBalance(everyday, Money.eur(123456), today());
        addEnergy();
        addGym();
        String before = headlineAmount(get("/").body(), "Private free to spend");

        deliverEnergy(everyday, today(), -15000, "REF-1");
        deliverGym(everyday, today(), -2000, "REF-2");

        String overview = get("/").body();
        assertThat(overview).contains("Fixed costs: 2 need a look");
        assertThat(headlineAmount(overview, "Private free to spend")).isEqualTo(before);
    }

    @Test
    void aFixedCostOfAnArchivedAccountStillShowsTheStaleMarkOnThePageAndTheOverviewButTheTopLineDoesNotListIt() throws Exception {
        long account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        accounts.addBalance(account, Money.eur(1000), today().minusDays(90));
        Map<String, String> energy = form("Example Energy", "private", "100.00", "monthly", today().toString());
        energy.put("iban", "NL10SHOP0000000101");
        energy.put("account", Long.toString(account));
        assertThat(post("/fixed-costs", energy).statusCode()).isEqualTo(302);
        accounts.archive(account);

        assertThat(get("/fixed-costs").body()).contains("Matched against transactions").contains("Stale").contains("not updated for 90 days");
        String overview = get("/").body();
        assertThat(overview).contains("Matched against transactions").contains("not updated for 90 days");
        assertThat(overview).as("the line at the top counts accounts in use only").doesNotContain("id=\"attention\"");
    }

    // ----- refusals

    @Test
    void aRefusedFormIsShownAgainFilledInWithStatus400AndNothingIsStored() throws Exception {
        HttpResponse<String> refused = post("/fixed-costs", form("Example Gym", "private", "0,00", "monthly", today().toString()));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("The amount must be more than zero.").contains("value=\"Example Gym\"");
        assertThat(post("/fixed-costs", form("  ", "private", "1,00", "monthly", today().toString())).body()).contains("Enter a name.");
        assertThat(post("/fixed-costs", form("Example Gym", "private", "1,00", "weekly", today().toString())).body())
                .contains("Choose how often it is charged.");
        assertThat(post("/fixed-costs", form("Example Gym", "family", "1,00", "monthly", today().toString())).statusCode()).isEqualTo(400);
        assertThat(post("/fixed-costs", form("Example Gym", "private", "1,00", "monthly", "tomorrow")).body()).contains("This is not a date.");
        assertThat(post("/fixed-costs", form("Example Gym", "private", "abc", "monthly", today().toString())).statusCode()).isEqualTo(400);
        Map<String, String> otherScope = form("Example Gym", "business", "1,00", "monthly", today().toString());
        long privateAccount = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        otherScope.put("account", Long.toString(privateAccount));
        assertThat(post("/fixed-costs", otherScope).body()).contains("This account is of the other scope.");
        assertThat(jdbc.sql("select count(*) from fixed_cost").query(Integer.class).single()).isZero();
    }

    @Test
    void ticksThatAreRefusedShowTheMessageAndAnUnknownIdIs404() throws Exception {
        post("/fixed-costs", form("Example Gym", "private", "120.00", "yearly", today().minusMonths(3).toString()));
        long id = idOf("Example Gym");

        HttpResponse<String> refused = post("/fixed-costs/" + id + "/tick", Map.of());

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("This fixed cost is not charged this month.");
        assertThat(post("/fixed-costs/999999/tick", Map.of()).statusCode()).isEqualTo(404);
        assertThat(post("/fixed-costs/999999/pause", Map.of()).statusCode()).isEqualTo(404);
        assertThat(post("/fixed-costs/999999", form("A", "private", "1,00", "monthly", today().toString())).statusCode()).isEqualTo(404);
        assertThat(get("/fixed-costs/999999/edit").statusCode()).isEqualTo(404);
    }

    @Test
    void endingWithAnInvalidDateOrBeforeTheFirstChargeIsRefused() throws Exception {
        post("/fixed-costs", form("Example Gym", "private", "10.00", "monthly", today().minusMonths(1).toString()));
        long id = idOf("Example Gym");

        assertThat(post("/fixed-costs/" + id + "/end", Map.of("endedOn", "soon")).statusCode()).isEqualTo(400);
        HttpResponse<String> early = post("/fixed-costs/" + id + "/end", Map.of("endedOn", today().minusMonths(2).toString()));
        assertThat(early.statusCode()).isEqualTo(400);
        assertThat(early.body()).contains("A fixed cost cannot end before its first charge.");
        assertThat(jdbc.sql("select count(*) from fixed_cost where ended_on is not null").query(Integer.class).single()).isZero();
    }
}
