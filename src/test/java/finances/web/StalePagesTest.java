package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.AccountService;
import finances.accounts.AsOfBasis;
import finances.accounts.Kind;
import finances.accounts.Scope;
import finances.money.Money;
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
 * The age of every figure and the marks on stale ones, through the real application on a temporary data folder (issue #11).
 * The accounts and the bank facts (consents, runs) are put straight into the database, relative to now, so each source
 * and each reason is on the page at once. Fictional data only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StalePagesTest {

    private static final Path DATA = createTempDataFolder();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
        registry.add("finances.sync.enabled", () -> "false");
    }

    private static Path createTempDataFolder() {
        try {
            return Files.createTempDirectory("finances-test-stale");
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
    AccountService accounts;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Clock clock;

    private Instant now;

    @BeforeEach
    void emptyDatabase() {
        for (String table : new String[] {"sync_run", "bank_account", "bank_consent", "transactions", "balance", "account"}) {
            jdbc.sql("delete from " + table).update();
        }
        now = Instant.now(clock);
    }

    private String get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    // ----- the facts, put in relative to now

    /** An authorised consent that is valid for the given number of days more; its session file is there. */
    private long consent(String bank, String status, long validForDays) throws IOException {
        jdbc.sql("""
                insert into bank_consent (bank_name, bank_country, psu_type, required_psu_headers, created_at, valid_until, status, status_changed_at)
                values (?, 'NL', 'personal', '', ?, ?, ?, ?)
                """)
                .params(bank, now.minus(30, ChronoUnit.DAYS).toString(), now.plus(validForDays, ChronoUnit.DAYS).toString(), status, now.toString())
                .update();
        long id = jdbc.sql("select max(id) from bank_consent").query(Long.class).single();
        Path secrets = Files.createDirectories(DATA.resolve("secrets"));
        Files.writeString(secrets.resolve("enable-banking-session-" + id), "fictional-session");
        return id;
    }

    private long bankAccount(String name, long consent, int index) {
        var account = accounts.addFromBank(name, Scope.PRIVATE, Kind.CURRENT, null);
        jdbc.sql("""
                insert into bank_account (consent_id, uid, iban, currency, account_id, linked_at)
                values (?, ?, ?, 'EUR', ?, ?)
                """).params(consent, "uid-" + index, "NL51XMPL000000000" + index, account.id(), now.toString()).update();
        return account.id();
    }

    private void run(long account, Instant started, boolean ok, String reason) {
        jdbc.sql("""
                insert into sync_run (account_id, started_at, finished_at, trigger, headers_sent, outcome, reason)
                values (?, ?, ?, 'scheduled', 0, ?, ?)
                """).params(account, started.toString(), started.toString(), ok ? "ok" : "failed", reason).update();
    }

    private void bankBalance(long account, Instant asOf) {
        LocalDate day = LocalDate.ofInstant(asOf, clock.getZone());
        accounts.addBankBalance(account, Money.eur(123456), day, "CLBD", asOf, AsOfBasis.BANK_TIME);
    }

    private long manual(String name, Kind kind, Integer balanceDaysOld) {
        long id = accounts.addManual(name, Scope.PRIVATE, kind, null).id();
        if (balanceDaysOld != null) {
            accounts.addBalance(id, Money.eur(25000), LocalDate.now(clock).minusDays(balanceDaysOld));
        }
        return id;
    }

    /** The list item of the account with this name in the list of accounts (the last one: the line at the top also links to it). */
    private static String row(String page, String name) {
        Matcher item = Pattern.compile("<li>(?:(?!</li>).)*?>" + Pattern.quote(name) + "</a>.*?</li>", Pattern.DOTALL).matcher(page);
        String last = null;
        while (item.find()) {
            last = item.group();
        }
        assertThat(last).as("a row for " + name).isNotNull();
        return last;
    }

    private void aFreshAFailedAnExpiredAndAnOldAccount() throws IOException {
        long bankA = consent("Example Bank", "authorized", 90);
        long bankB = consent("Sample Savings Bank", "expired", 90);

        long fresh = bankAccount("Fresh Bank Account", bankA, 1);
        bankBalance(fresh, now.minus(2, ChronoUnit.HOURS));
        run(fresh, now.minus(2, ChronoUnit.HOURS), true, null);

        long failed = bankAccount("Failed Bank Account", bankA, 2);
        bankBalance(failed, now.minus(26, ChronoUnit.HOURS));
        run(failed, now.minus(26, ChronoUnit.HOURS), true, null);
        run(failed, now.minus(2, ChronoUnit.HOURS), false, "rate_limited");

        long expired = bankAccount("Expired Bank Account", bankB, 3);
        bankBalance(expired, now.minus(3, ChronoUnit.DAYS));
        run(expired, now.minus(3, ChronoUnit.DAYS), true, null);
        run(expired, now.minus(1, ChronoUnit.DAYS), false, "consent_expired");

        manual("Old Manual Account", Kind.CURRENT, 20);
        manual("Recent Manual Account", Kind.CURRENT, 0);
        manual("Empty Manual Account", Kind.SAVINGS, null);
    }

    // ----- the overview

    @Test
    void theOverviewMarksEachStaleFigureWithItsReasonAndLeavesTheOthersAlone() throws Exception {
        aFreshAFailedAnExpiredAndAnOldAccount();

        String page = get("/");

        String fresh = row(page, "Fresh Bank Account");
        assertThat(fresh).contains("€1,234.56").containsPattern("\\((today|yesterday) \\d\\d:\\d\\d\\)").doesNotContain("Stale");
        String failed = row(page, "Failed Bank Account");
        assertThat(failed).contains("Stale").contains("sync failed: the bank limits fetches, wait about six hours");
        String expired = row(page, "Expired Bank Account");
        assertThat(expired).contains("Stale").contains("consent expired");
        String old = row(page, "Old Manual Account");
        assertThat(old).contains("Stale").contains("not updated for 20 days").contains("(20 days ago)");
        assertThat(row(page, "Recent Manual Account")).doesNotContain("Stale").contains("(today)");
        assertThat(row(page, "Empty Manual Account")).contains("No balance yet").doesNotContain("Stale");
    }

    @Test
    void theMarkDoesNotRelyOnColourAlone() throws Exception {
        aFreshAFailedAnExpiredAndAnOldAccount();

        String old = row(get("/"), "Old Manual Account");

        // A word and a sign inside a control that opens on tap or from the keyboard, with no script involved.
        assertThat(old).contains("<details class=\"stale\"").contains("<summary>\u26A0 Stale</summary>")
                .contains("title=\"not updated for 20 days\"");
    }

    @Test
    void oneLineAtTheTopNamesTheAccountsThatNeedAttentionAndLinksToThem() throws Exception {
        aFreshAFailedAnExpiredAndAnOldAccount();

        String page = get("/");

        Matcher line = Pattern.compile("<div [^>]*id=\"attention\".*?</div>", Pattern.DOTALL).matcher(page);
        assertThat(line.find()).isTrue();
        assertThat(line.group()).contains("3 accounts have a figure that may be out of date")
                .contains(">Failed Bank Account</a>").contains(">Expired Bank Account</a>").contains(">Old Manual Account</a>")
                .doesNotContain("Fresh Bank Account").doesNotContain("Recent Manual Account").doesNotContain("Empty Manual Account");
        assertThat(line.start()).as("it is above the accounts").isLessThan(page.indexOf("Fresh Bank Account"));
    }

    @Test
    void nothingIsAnnouncedWhenEverythingIsFresh() throws Exception {
        long consent = consent("Example Bank", "authorized", 90);
        long fresh = bankAccount("Fresh Bank Account", consent, 1);
        bankBalance(fresh, now.minus(1, ChronoUnit.HOURS));
        run(fresh, now.minus(1, ChronoUnit.HOURS), true, null);
        manual("Recent Manual Account", Kind.CURRENT, 3);

        String page = get("/");

        assertThat(page).doesNotContain("id=\"attention\"").doesNotContain("Stale").doesNotContain("ends on");
    }

    @Test
    void anArchivedAccountIsNotCountedInTheLineAtTheTop() throws Exception {
        long old = manual("Old Manual Account", Kind.CURRENT, 20);
        manual("Another Old Account", Kind.CURRENT, 30);
        assertThat(get("/")).contains("2 accounts have a figure that may be out of date");

        accounts.archive(old);

        String page = get("/");
        assertThat(page).contains("1 account has a figure that may be out of date").doesNotContain("Old Manual Account");
    }

    @Test
    void aConsentThatEndsWithinFourteenDaysIsAnnouncedWithRenewBeforeAnythingIsStale() throws Exception {
        long soon = consent("Example Bank", "authorized", 10);
        long account = bankAccount("Fresh Bank Account", soon, 1);
        bankBalance(account, now.minus(1, ChronoUnit.HOURS));
        run(account, now.minus(1, ChronoUnit.HOURS), true, null);
        long later = consent("Sample Savings Bank", "authorized", 40);
        long other = bankAccount("Other Bank Account", later, 2);
        bankBalance(other, now.minus(1, ChronoUnit.HOURS));
        run(other, now.minus(1, ChronoUnit.HOURS), true, null);

        String page = get("/");

        String endsOn = now.plus(10, ChronoUnit.DAYS).atZone(clock.getZone()).toLocalDate().toString();
        assertThat(page).contains("The consent for Example Bank ends on " + endsOn).contains("in 10 days")
                .contains("/settings/bank/consents/" + soon + "/renew").doesNotContain("Sample Savings Bank ends")
                .doesNotContain("/settings/bank/consents/" + later + "/renew").doesNotContain("id=\"attention\"");
    }

    @Test
    void aConsentThatEndsWithinFourteenDaysButHasOnlyArchivedAccountsIsNotAnnounced() throws Exception {
        long soon = consent("Example Bank", "authorized", 5);
        long account = bankAccount("Fresh Bank Account", soon, 1);
        accounts.archive(account);

        assertThat(get("/")).doesNotContain("ends on");
    }

    // ----- the accounts page and the account page

    @Test
    void theAccountsPageAndTheAccountPageMarkTheSameWay() throws Exception {
        aFreshAFailedAnExpiredAndAnOldAccount();

        String list = get("/accounts");
        assertThat(row(list, "Failed Bank Account")).contains("Stale").contains("sync failed");
        assertThat(row(list, "Fresh Bank Account")).doesNotContain("Stale");
        assertThat(row(list, "Old Manual Account")).contains("not updated for 20 days");

        long oldId = jdbc.sql("select id from account where name = 'Old Manual Account'").query(Long.class).single();
        long freshId = jdbc.sql("select id from account where name = 'Fresh Bank Account'").query(Long.class).single();
        long expiredId = jdbc.sql("select id from account where name = 'Expired Bank Account'").query(Long.class).single();
        assertThat(get("/accounts/" + oldId)).contains("Stale").contains("not updated for 20 days");
        assertThat(get("/accounts/" + expiredId)).contains("Stale").contains("consent expired");
        assertThat(get("/accounts/" + freshId)).doesNotContain("Stale");
    }

    @Test
    void anOldBalanceInTheHistoryOfAnAccountIsNotMarkedOnlyTheCurrentOne() throws Exception {
        long id = manual("Recent Manual Account", Kind.CURRENT, 40);
        accounts.addBalance(id, Money.eur(300), LocalDate.now(clock).minusDays(2));

        assertThat(get("/accounts/" + id)).doesNotContain("Stale");
    }

    @Test
    void theAccountPageOfABankAccountWithoutAnyBalanceStillShowsTheMark() throws Exception {
        long bankB = consent("Sample Savings Bank", "expired", 90);
        long id = bankAccount("Brand New Bank Account", bankB, 4);

        assertThat(get("/accounts/" + id)).contains("Stale").contains("consent expired");
    }

    @Test
    void aStaleBankFigureWithoutAnyBalanceStillSaysWhy() throws Exception {
        long bankB = consent("Sample Savings Bank", "expired", 90);
        bankAccount("Brand New Bank Account", bankB, 4);

        String page = get("/");

        assertThat(row(page, "Brand New Bank Account")).contains("No balance yet").contains("consent expired");
    }

    @Test
    void theLimitsAreReadFromConfigurationWithTheDocumentedNames() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        for (String name : new String[] {"finances.freshness.bank-sync-hours", "finances.freshness.current-days",
                "finances.freshness.savings-days", "finances.freshness.investment-days", "finances.freshness.debt-days"}) {
            assertThat(readme).as("documented in README.md").contains(name);
        }
    }
}
