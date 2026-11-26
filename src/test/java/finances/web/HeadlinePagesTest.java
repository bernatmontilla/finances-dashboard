package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.AccountService;
import finances.accounts.AsOfBasis;
import finances.accounts.Kind;
import finances.accounts.Scope;
import finances.money.Money;
import finances.sync.FakeAfterTaxSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * The two headline numbers and their build-up pages through the real application on a temporary data folder and a fake
 * after-tax source on this machine (issue #19): every state the issue names, that a build-up adds up to its headline to the
 * cent, that nothing ever shows the two numbers added together or an unknown as zero, and that a page never calls the source.
 * The state with no source configured is {@code AfterTaxNotConfiguredPagesTest}. Fictional data only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HeadlinePagesTest {

    private static final Path DATA = createTempDataFolder();
    private static final FakeAfterTaxSource SOURCE = startSource();
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    /** Private 1,234.56 + 100.00 = 1,334.56 and business 12,345.00: their sum, 13,679.56, must be on no page. */
    private static final String SUM_OF_BOTH = "13,679.56";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
        registry.add("finances.outbound.after-tax.url", SOURCE::url);
        registry.add("finances.sync.enabled", () -> "false");
    }

    private static Path createTempDataFolder() {
        try {
            return Files.createTempDirectory("finances-test-headline");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FakeAfterTaxSource startSource() {
        try {
            return new FakeAfterTaxSource();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void cleanUp() throws IOException {
        SOURCE.close();
        try (Stream<Path> paths = Files.walk(DATA)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    AccountService accounts;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Clock clock;

    private Instant now;

    @BeforeEach
    void emptyDatabase() {
        for (String table : new String[] {"after_tax_reserved", "after_tax_value", "after_tax_fetch", "sync_run", "bank_account",
                "bank_consent", "transactions", "balance", "account"}) {
            jdbc.sql("delete from " + table).update();
        }
        now = Instant.now(clock);
        SOURCE.answer(FakeAfterTaxSource.valid(now.minusSeconds(600)));
    }

    // ----- helpers

    private HttpResponse<String> response(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String get(String path) throws Exception {
        return response(path).body();
    }

    private void fetchAfterTax() throws Exception {
        HttpResponse<String> test = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/settings/after-tax/test"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(test.statusCode()).isEqualTo(302);
    }

    private long account(String name, Scope scope, Kind kind, Long cents, int daysOld) {
        long id = accounts.addManual(name, scope, kind, null).id();
        if (cents != null) {
            accounts.addBalance(id, Money.eur(cents), LocalDate.now(clock).minusDays(daysOld));
        }
        return id;
    }

    private long account(String name, Scope scope, Kind kind, Long cents) {
        return account(name, scope, kind, cents, 0);
    }

    /** The fictional set: two private current accounts and a business current account, plus savings, investment and debt. */
    private void theUsualAccounts() {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, 123_456L);
        account("Second", Scope.PRIVATE, Kind.CURRENT, 10_000L);
        account("Rainy day", Scope.PRIVATE, Kind.SAVINGS, 777_700L);
        account("Shares", Scope.PRIVATE, Kind.INVESTMENT, 888_800L);
        account("Loan", Scope.PRIVATE, Kind.DEBT, 999_900L);
        account("Business current", Scope.BUSINESS, Kind.CURRENT, 987_654L);
    }

    /** The part of the page that holds the headline with this title: up to the next headline or the end of the section. */
    private static String headline(String page, String title) {
        int at = page.indexOf(">" + title + "<");
        assertThat(at).as("a headline titled " + title).isGreaterThanOrEqualTo(0);
        int start = page.lastIndexOf("<div class=\"headline\">", at);
        int next = page.indexOf("<div class=\"headline\">", at);
        int end = page.indexOf("</section>", at);
        return page.substring(start, next >= 0 && next < end ? next : end);
    }

    private static String amountOf(String headline) {
        Matcher amount = Pattern.compile("<p class=\"headline-amount[^\"]*\">.*?>(-?€[\\d,.]+)<", Pattern.DOTALL).matcher(headline);
        assertThat(amount.find()).as("an amount in " + headline).isTrue();
        return amount.group(1);
    }

    private static String totalOf(String buildUp) {
        Matcher total = Pattern.compile("buildup-total\">.*?<strong[^>]*>(-?€[\\d,.]+)<", Pattern.DOTALL).matcher(buildUp);
        assertThat(total.find()).as("a total").isTrue();
        return total.group(1);
    }

    private static final String PRIVATE = "Private free to spend";
    private static final String BUSINESS = "Business available";

    // ----- the five states of the issue

    @Test
    void everythingFreshShowsBothNumbersAgedAndUnmarked() throws Exception {
        theUsualAccounts();
        fetchAfterTax();

        String page = get("/");

        String priv = headline(page, PRIVATE);
        assertThat(amountOf(priv)).isEqualTo("€1,334.56");
        assertThat(priv).contains("as of ").contains("(today)").doesNotContain("Stale").contains("href=\"/build-up/private\"");
        String business = headline(page, BUSINESS);
        assertThat(amountOf(business)).isEqualTo("€12,345.00");
        assertThat(business).containsPattern("\\((today|yesterday) \\d\\d:\\d\\d\\)").doesNotContain("Stale")
                .contains("href=\"/build-up/business\"");
        assertThat(page.indexOf(PRIVATE)).as("the numbers come first").isLessThan(page.indexOf("Everyday"));
        assertThat(page).doesNotContain(SUM_OF_BOTH);
    }

    @Test
    void oneStaleBankAccountMakesThePrivateNumberStaleAndNamesIt() throws Exception {
        long consent = jdbc.sql("""
                insert into bank_consent (bank_name, bank_country, psu_type, required_psu_headers, created_at, valid_until, status, status_changed_at)
                values ('Example Bank', 'NL', 'personal', '', ?, ?, 'authorized', ?) returning id
                """)
                .params(now.minus(30, ChronoUnit.DAYS).toString(), now.plus(90, ChronoUnit.DAYS).toString(), now.toString())
                .query(Long.class).single();
        Files.writeString(Files.createDirectories(DATA.resolve("secrets")).resolve("enable-banking-session-" + consent), "fictional-session");
        long bank = accounts.addFromBank("Bank Everyday", Scope.PRIVATE, Kind.CURRENT, null).id();
        jdbc.sql("insert into bank_account (consent_id, uid, iban, currency, account_id, linked_at) values (?, 'uid-1', 'NL51XMPL0000000001', 'EUR', ?, ?)")
                .params(consent, bank, now.toString()).update();
        Instant failedAt = now.minus(2, ChronoUnit.HOURS);
        accounts.addBankBalance(bank, Money.eur(123_456), LocalDate.ofInstant(failedAt, clock.getZone()), "CLBD", failedAt, AsOfBasis.BANK_TIME);
        jdbc.sql("insert into sync_run (account_id, started_at, finished_at, trigger, headers_sent, outcome, reason) values (?, ?, ?, 'scheduled', 0, 'failed', 'rate_limited')")
                .params(bank, failedAt.toString(), failedAt.toString()).update();
        account("Second", Scope.PRIVATE, Kind.CURRENT, 10_000L);
        fetchAfterTax();

        String page = get("/");

        String priv = headline(page, PRIVATE);
        assertThat(amountOf(priv)).as("a stale account is still counted").isEqualTo("€1,334.56");
        assertThat(priv).contains("Stale").contains("Bank Everyday").contains("sync failed: the bank limits fetches, wait about six hours")
                .contains("Oldest figure:");
        assertThat(headline(page, BUSINESS)).doesNotContain("Stale");
        assertThat(get("/build-up/private")).contains("Bank Everyday").contains("Stale");
    }

    @Test
    void aFailingAfterTaxSourceMakesBusinessStaleWithTheReasonAndKeepsTheLastValue() throws Exception {
        theUsualAccounts();
        fetchAfterTax();
        SOURCE.answer(500, "");
        fetchAfterTax();

        String page = get("/");

        String business = headline(page, BUSINESS);
        assertThat(amountOf(business)).isEqualTo("€12,345.00");
        assertThat(business).contains("Stale").contains("After-tax available")
                .contains("the source could not be used: the source answered with a status other than 200");
        assertThat(headline(page, PRIVATE)).doesNotContain("Stale");
    }

    @Test
    void withNoAccountsAtAllThePrivateNumberIsAReasonAndALinkNeverZero() throws Exception {
        fetchAfterTax();

        String page = get("/");

        String priv = headline(page, PRIVATE);
        assertThat(priv).contains("Not shown: there is no private current account yet.").contains("href=\"/accounts/new\"")
                .doesNotContain("headline-amount");
        assertThat(page).doesNotContain("€0.00");
        assertThat(amountOf(headline(page, BUSINESS))).as("the business side is still there").isEqualTo("€12,345.00");
        assertThat(get("/build-up/private")).contains("Not shown: there is no private current account yet.").doesNotContain("€0.00");
    }

    @Test
    void beforeTheFirstFetchTheBusinessNumberIsAReasonNotZero() throws Exception {
        theUsualAccounts();

        String business = headline(get("/"), BUSINESS);

        assertThat(business).contains("Not shown: the after-tax source has not given a valid value yet.").contains("href=\"/settings\"")
                .doesNotContain("headline-amount").doesNotContain("€");
    }

    // ----- unknown is never zero; every edge has a behaviour

    @Test
    void anAccountWithoutABalanceHidesThePrivateNumberAndLinksToIt() throws Exception {
        long id = account("New account", Scope.PRIVATE, Kind.CURRENT, null);
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, 123_456L);

        String page = get("/");

        String priv = headline(page, PRIVATE);
        assertThat(priv).contains("these accounts have no balance yet").contains("href=\"/accounts/" + id + "\"").contains("New account")
                .doesNotContain("headline-amount").doesNotContain("€1,234.56");
        assertThat(page).doesNotContain("€0.00");
    }

    @Test
    void anOverdraftIsCountedAndANegativeTotalIsShownAsSuchAndSaysSo() throws Exception {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, -1_234L);
        account("Second", Scope.PRIVATE, Kind.CURRENT, 10_000L);
        assertThat(amountOf(headline(get("/"), PRIVATE))).isEqualTo("€87.66");

        account("Third", Scope.PRIVATE, Kind.CURRENT, -20_000L);

        String priv = headline(get("/"), PRIVATE);
        assertThat(amountOf(priv)).isEqualTo("-€112.34");
        assertThat(priv).contains("Below zero");
    }

    @Test
    void aNegativeAfterTaxAmountIsShownAsNegative() throws Exception {
        SOURCE.answer(FakeAfterTaxSource.valid(now.minusSeconds(600)).replace("1234500", "-10000").replace("2000000", "400000"));
        fetchAfterTax();

        String business = headline(get("/"), BUSINESS);

        assertThat(amountOf(business)).isEqualTo("-€100.00");
        assertThat(business).contains("Below zero");
    }

    @Test
    void anArchivedAccountAndTheOtherKindsAreInNeitherNumber() throws Exception {
        theUsualAccounts();
        long old = account("Old account", Scope.PRIVATE, Kind.CURRENT, 55_500L);
        accounts.archive(old);
        fetchAfterTax();

        assertThat(amountOf(headline(get("/"), PRIVATE))).isEqualTo("€1,334.56");
        String buildUp = get("/build-up/private");
        assertThat(buildUp).contains("Everyday").contains("Second").doesNotContain("Old account").doesNotContain("Rainy day")
                .doesNotContain("Shares").doesNotContain("Loan").doesNotContain("€7,777.00");
    }

    @Test
    void aSumThatDoesNotFitIsAReasonNotAWrappedAroundNumber() throws Exception {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, Long.MAX_VALUE / 2 + 1);
        account("Second", Scope.PRIVATE, Kind.CURRENT, Long.MAX_VALUE / 2 + 1);

        HttpResponse<String> overview = response("/");

        assertThat(overview.statusCode()).isEqualTo(200);
        assertThat(headline(overview.body(), PRIVATE)).contains("an amount is too large to add up exactly").doesNotContain("headline-amount");
    }

    // ----- the build-up pages

    @Test
    void thePrivateBuildUpListsEveryCountedLineAndAddsUpToTheHeadlineToTheCent() throws Exception {
        theUsualAccounts();

        String page = get("/");
        String buildUp = get("/build-up/private");

        assertThat(buildUp).contains("Build-up of Private free to spend").contains("Everyday").contains("€1,234.56").contains("Second")
                .contains("€100.00").contains("No open reservations.").contains("(today)");
        assertThat(totalOf(buildUp)).isEqualTo(amountOf(headline(page, PRIVATE))).isEqualTo("€1,334.56");
        assertThat(buildUp).doesNotContain("Business current");
    }

    @Test
    void theBusinessBuildUpShowsTheAfterTaxLineWithGrossAndReservedAndSetsTheBusinessAccountsApart() throws Exception {
        theUsualAccounts();
        fetchAfterTax();

        String page = get("/");
        String buildUp = get("/build-up/business");

        assertThat(buildUp).contains("After-tax available").contains("€12,345.00").contains("Gross balance: €20,000.00")
                .contains("VAT").contains("€4,200.00").contains("Income tax").contains("€3,455.00").contains("No open reservations.")
                .containsPattern("\\((today|yesterday) \\d\\d:\\d\\d\\)").doesNotContain("not the available amount");
        assertThat(totalOf(buildUp)).isEqualTo(amountOf(headline(page, BUSINESS))).isEqualTo("€12,345.00");
        int notCounted = buildUp.indexOf("Not counted");
        assertThat(notCounted).as("a heading for what is listed but not counted").isGreaterThan(buildUp.indexOf("buildup-total"));
        assertThat(buildUp.substring(notCounted)).contains("Business current").contains("€9,876.54").contains("not part of Business available");
        assertThat(buildUp.substring(0, notCounted)).as("the business account is not part of the number").doesNotContain("€9,876.54");
    }

    @Test
    void aGrossThatDoesNotAddUpShowsTheWarningInTheBuildUp() throws Exception {
        SOURCE.answer(FakeAfterTaxSource.valid(now.minusSeconds(600)).replace("1234500", "1234501"));
        fetchAfterTax();

        String buildUp = get("/build-up/business");

        assertThat(buildUp).contains("€12,345.01").contains("The gross balance minus what is reserved is not the available amount.");
    }

    @Test
    void aBusinessBuildUpWithoutAValueStillSetsTheBusinessAccountsApartAndShowsNoAmountForTheNumber() throws Exception {
        account("Business current", Scope.BUSINESS, Kind.CURRENT, 987_654L);

        String buildUp = get("/build-up/business");

        assertThat(buildUp).contains("Not shown: the after-tax source has not given a valid value yet.").doesNotContain("buildup-total");
        assertThat(buildUp.substring(buildUp.indexOf("Not counted"))).contains("€9,876.54");
    }

    @Test
    void aStaleBusinessAccountLeavesBusinessAvailableUnmarked() throws Exception {
        account("Business current", Scope.BUSINESS, Kind.CURRENT, 987_654L, 60);
        fetchAfterTax();

        String buildUp = get("/build-up/business");

        assertThat(headline(get("/"), BUSINESS)).doesNotContain("Stale");
        assertThat(buildUp.substring(buildUp.indexOf("Not counted"))).contains("Stale").contains("not updated for 60 days");
        assertThat(headline(buildUp, BUSINESS)).doesNotContain("Stale");
    }

    @Test
    void anUnknownNumberIsNotFound() throws Exception {
        assertThat(response("/build-up/other").statusCode()).isEqualTo(404);
        assertThat(response("/build-up/").statusCode()).isNotEqualTo(200);
    }

    // ----- what must never happen

    @Test
    void noPageEverShowsTheTwoNumbersAddedTogether() throws Exception {
        theUsualAccounts();
        fetchAfterTax();

        for (String path : List.of("/", "/build-up/private", "/build-up/business", "/accounts", "/settings")) {
            assertThat(get(path)).as(path).doesNotContain(SUM_OF_BOTH).doesNotContain("13679.56").doesNotContain("1367956");
        }
    }

    @Test
    void loadingThePagesNeverCallsTheSource() throws Exception {
        theUsualAccounts();
        int before = SOURCE.requests().size();

        get("/");
        get("/build-up/private");
        get("/build-up/business");
        get("/settings");

        assertThat(SOURCE.requests()).hasSize(before);
    }

    @Test
    void neitherTheAddressNorTheAfterTaxSourceTextLeaksIntoTheHeadlines() throws Exception {
        theUsualAccounts();
        fetchAfterTax();

        for (String path : List.of("/", "/build-up/business")) {
            assertThat(get(path)).as(path).doesNotContain(SOURCE.url()).doesNotContain(FakeAfterTaxSource.PATH).doesNotContain("127.0.0.1");
        }
    }
}
