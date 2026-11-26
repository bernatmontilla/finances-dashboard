package finances.sync;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import finances.accounts.Kind;
import finances.accounts.Scope;
import finances.money.Freshness;
import finances.outbound.AfterTaxClient;
import finances.outbound.AfterTaxToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * The after-tax source (issue #18) against a fake source on this machine that serves valid, invalid, slow and failing
 * answers: what is requested, what is stored, what is kept when something goes wrong, and that a slow source never delays
 * anything else. Fictional amounts only.
 */
class AfterTaxServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final String TOKEN = "fictional-token-0123456789";

    @TempDir
    Path folder;

    BankHarness h;
    FakeAfterTaxSource source;
    AfterTaxStore store;
    AfterTaxService service;

    @BeforeEach
    void open() throws Exception {
        h = new BankHarness(folder.resolve("data"), false);
        source = new FakeAfterTaxSource();
        store = new AfterTaxStore(h.jdbc);
        service = serviceFor(source.url(), Duration.ofSeconds(5));
    }

    @AfterEach
    void close() throws Exception {
        source.close();
        h.close();
    }

    private AfterTaxService serviceFor(String url, Duration timeout) {
        AfterTaxClient client = new AfterTaxClient(new AfterTaxToken(h.dataFolder), url, timeout);
        return new AfterTaxService(client, store, Freshness.Limits.defaults(), h.clock, h.manager);
    }

    private void writeToken(String content) throws IOException {
        Path secrets = h.folder.resolve("secrets");
        Files.createDirectories(secrets);
        Files.writeString(secrets.resolve("after-tax-token"), content);
    }

    private void awaitIdle() throws InterruptedException {
        for (int i = 0; i < 300 && !service.idle(); i++) {
            Thread.sleep(50);
        }
        assertThat(service.idle()).isTrue();
    }

    private String validAsOf(String instant) {
        return FakeAfterTaxSource.valid(Instant.parse(instant));
    }

    // ----- the request

    @Test
    void theRequestIsAGetOnTheConfiguredPathWithNoQueryNoBodyAndNoAuthorizationWithoutATokenFile() {
        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.OK);

        assertThat(source.requests()).hasSize(1);
        FakeAfterTaxSource.Request request = source.requests().getFirst();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo(FakeAfterTaxSource.PATH);
        assertThat(request.query()).isNull();
        assertThat(request.body()).isEmpty();
        assertThat(request.header("Accept")).isEqualTo("application/json");
        assertThat(request.header("Authorization")).isNull();
    }

    @Test
    void aTokenFileIsSentAsABearerTokenStripped() throws Exception {
        writeToken("  " + TOKEN + "\n");

        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.OK);

        assertThat(source.requests().getFirst().header("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(service.tokenPresent()).isTrue();
    }

    @Test
    void anEmptyTokenFileSendsNoAuthorization() throws Exception {
        writeToken("\n");

        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.OK);

        assertThat(source.requests().getFirst().header("Authorization")).isNull();
    }

    @Test
    void aTokenThatCannotBeSentAsItIsMakesNoRequestAndIsRecorded() throws Exception {
        for (String token : List.of("first\nsecond", "tab\there", "bell\u0007", "café", "two words")) {
            writeToken(token);

            assertThat(service.test()).as(token).isEqualTo(AfterTaxService.TestResult.FAILED);

            assertThat(service.lastFailure()).isEqualTo("token_unreadable");
        }
        assertThat(source.requests()).as("nothing was sent").isEmpty();
        assertThat(service.current()).isEmpty();
    }

    @Test
    void withoutAConfiguredSourceNothingIsRequestedStoredOrFailing() {
        AfterTaxService none = serviceFor("", Duration.ofSeconds(5));

        none.start(AfterTaxService.Trigger.SCHEDULED);
        none.start(AfterTaxService.Trigger.USER);

        assertThat(none.configured()).isFalse();
        assertThat(none.test()).isEqualTo(AfterTaxService.TestResult.NOT_CONFIGURED);
        assertThat(none.current()).isEmpty();
        assertThat(none.standing()).isEmpty();
        assertThat(none.recentFetches(10)).isEmpty();
        assertThat(source.requests()).isEmpty();
    }

    @Test
    void anAddressThatIsNotAnHttpOrHttpsHostWithoutCredentialsQueryOrFragmentStopsTheStartWithoutQuotingIt() {
        for (String bad : List.of("ftp://example.com/x", "example.com/x", "http:///x", "https://user:pw@example.com/x",
                "https://example.com/x?key=secret-value", "https://example.com/x?", "https://example.com/x#frag", "http://exa mple.com/")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> serviceFor(bad, Duration.ofSeconds(1)))
                    .as(bad).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("finances.outbound.after-tax.url")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-value").doesNotContain("example.com").doesNotContain("pw"));
        }
        assertThat(serviceFor("https://example.com/after-tax", Duration.ofSeconds(1)).https()).isTrue();
        assertThat(serviceFor("http://127.0.0.1:8080/x", Duration.ofSeconds(1)).tokenOverPlainHttp()).isFalse();
    }

    @Test
    void aTokenOverPlainHttpToAnotherHostIsWarnedAboutAndNeverRefused() throws Exception {
        writeToken(TOKEN);

        assertThat(serviceFor("http://192.0.2.10:8080/x", Duration.ofSeconds(1)).tokenOverPlainHttp()).isTrue();
        assertThat(serviceFor("https://example.com/x", Duration.ofSeconds(1)).tokenOverPlainHttp()).isFalse();
        assertThat(serviceFor("http://localhost:8080/x", Duration.ofSeconds(1)).tokenOverPlainHttp()).isFalse();
        assertThat(service.tokenOverPlainHttp()).as("a source on this machine").isFalse();
    }

    // ----- a valid answer

    @Test
    void aValidAnswerIsStoredWithItsGrossReservedLinesAndMomentAndIsFresh() {
        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.OK);

        AfterTaxValue value = service.current().orElseThrow();
        assertThat(value.availableCents()).isEqualTo(1_234_500);
        assertThat(value.grossBalanceCents()).isEqualTo(2_000_000L);
        assertThat(value.reserved()).extracting(AfterTaxValue.Reserved::label).containsExactly("VAT", "Income tax");
        assertThat(value.asOf()).isEqualTo(Instant.parse("2026-11-26T06:00:00Z"));
        assertThat(value.receivedAt()).isEqualTo(NOW);
        assertThat(service.standing()).isEmpty();
        assertThat(service.recentFetches(10)).singleElement().satisfies(row -> {
            assertThat(row.ok()).isTrue();
            assertThat(row.trigger()).isEqualTo("test");
            assertThat(row.reason()).isNull();
        });
    }

    @Test
    void theDailyRunAndSyncNowStartAFetchOnTheirOwnThread() throws Exception {
        service.start(AfterTaxService.Trigger.SCHEDULED);
        awaitIdle();
        service.start(AfterTaxService.Trigger.USER);
        awaitIdle();

        assertThat(source.requests()).hasSize(2);
        assertThat(service.recentFetches(10)).extracting(AfterTaxFetchRow::trigger).containsExactly("user", "scheduled");
    }

    @Test
    void theSchedulersDailyRunStartsAFetchEvenWithoutABank() throws Exception {
        SyncScheduler scheduler = new SyncScheduler(h.sync, service, h.clock, true, "04:30");

        scheduler.runOnce();
        awaitIdle();

        assertThat(source.requests()).hasSize(1);
        assertThat(service.current()).isPresent();
    }

    // ----- history

    @Test
    void twoIdenticalAnswersLeaveOneValueAndTwoFetchRecords() {
        service.test();
        h.clock.set(NOW.plusSeconds(60));
        service.test();

        assertThat(service.history()).hasSize(1);
        assertThat(service.recentFetches(10)).hasSize(2).allMatch(AfterTaxFetchRow::ok);
        assertThat(service.current().orElseThrow().receivedAt()).as("the first receipt stands").isEqualTo(NOW);
    }

    @Test
    void anAnswerThatDiffersOnlyInItsMomentAddsAValueAndHistoryKeepsBothInOrder() {
        service.test();
        source.answer(validAsOf("2026-11-26T11:00:00Z"));
        h.clock.set(NOW.plusSeconds(60));
        service.test();

        assertThat(service.history()).extracting(AfterTaxValue::asOf)
                .containsExactly(Instant.parse("2026-11-26T06:00:00Z"), Instant.parse("2026-11-26T11:00:00Z"));
        assertThat(service.current().orElseThrow().asOf()).isEqualTo(Instant.parse("2026-11-26T11:00:00Z"));
        assertThat(service.history().getFirst().reserved()).hasSize(2);
    }

    // Issue #19 changes what #18 decided here: an answer whose moment is older than the stored value's is kept in the history
    // but is no longer the value shown (it used to replace it because it was received last).
    @Test
    void anAnswerWithAnOlderMomentIsKeptInTheHistoryButDoesNotReplaceTheCurrentValue() {
        service.test();
        source.answer(validAsOf("2026-11-26T05:00:00Z"));
        h.clock.set(NOW.plusSeconds(60));
        service.test();

        assertThat(service.current().orElseThrow().asOf()).isEqualTo(Instant.parse("2026-11-26T06:00:00Z"));
        assertThat(service.history()).extracting(AfterTaxValue::asOf)
                .containsExactly(Instant.parse("2026-11-26T06:00:00Z"), Instant.parse("2026-11-26T05:00:00Z"));
        AfterTaxFetchRow newest = service.recentFetches(1).getFirst();
        assertThat(newest.ok()).as("it is a valid answer, not a failure").isTrue();
        assertThat(newest.reason()).isEqualTo("older_than_known");
        assertThat(service.lastFailure()).isNull();
        assertThat(service.standing()).as("the current value is still fresh").isEmpty();
    }

    @Test
    void aLaterAnswerWithANewerMomentBecomesTheCurrentValue() {
        service.test();
        source.answer(validAsOf("2026-11-26T07:00:00Z"));
        h.clock.set(NOW.plusSeconds(60));
        service.test();

        assertThat(service.current().orElseThrow().asOf()).isEqualTo(Instant.parse("2026-11-26T07:00:00Z"));
        assertThat(service.recentFetches(1).getFirst().reason()).isNull();
    }

    @Test
    void theCurrentValueIsPickedByTheInstantNotByTheTextOfItsMoment() {
        // "…:00Z" sorts after "…:00.500Z" as text although it is the earlier moment.
        source.answer(validAsOf("2026-11-26T06:00:00.500Z"));
        service.test();
        source.answer(validAsOf("2026-11-26T06:00:00Z"));
        service.test();

        assertThat(service.current().orElseThrow().asOf()).isEqualTo(Instant.parse("2026-11-26T06:00:00.500Z"));
        assertThat(service.history()).hasSize(2);
    }

    @Test
    void whenTwoValuesShareAMomentTheOneReceivedLastIsCurrent() {
        service.test();
        source.answer(FakeAfterTaxSource.valid(Instant.parse("2026-11-26T06:00:00Z")).replace("1234500", "1234501"));
        h.clock.set(NOW.plusSeconds(60));
        service.test();

        assertThat(service.current().orElseThrow().availableCents()).isEqualTo(1_234_501);
    }

    @Test
    void anOlderAnswerReceivedTwiceIsStoredOnce() {
        service.test();
        source.answer(validAsOf("2026-11-26T05:00:00Z"));
        service.test();
        service.test();
        service.test();

        assertThat(service.history()).hasSize(2);
        assertThat(h.count("select count(*) from after_tax_reserved")).as("two lines for each of the two values").isEqualTo(4);
        assertThat(service.recentFetches(10)).hasSize(4).allMatch(AfterTaxFetchRow::ok);
    }

    @Test
    void aGrossThatDoesNotAddUpIsStoredWithTheMismatchMark() {
        source.answer(FakeAfterTaxSource.valid(Instant.parse("2026-11-26T06:00:00Z")).replace("1234500", "1234501"));

        service.test();

        AfterTaxValue value = service.current().orElseThrow();
        assertThat(value.availableCents()).isEqualTo(1_234_501);
        assertThat(value.mismatch()).isTrue();
    }

    @Test
    void onlyTheNewest100FetchRecordsAreKeptAndTheValueHistoryIsNeverPruned() {
        service.test();
        for (int i = 0; i < 105; i++) {
            store.insertFetch(new AfterTaxFetchRow(NOW.plusSeconds(i), NOW.plusSeconds(i), "scheduled", false, "timeout", null));
        }

        assertThat(store.fetchCount()).isEqualTo(100);
        assertThat(service.history()).hasSize(1);
        assertThat(service.recentFetches(1).getFirst().startedAt()).isEqualTo(NOW.plusSeconds(104));
    }

    // ----- what is kept when something goes wrong

    private void assertKeptAndFailed(String reason, Integer status) {
        AfterTaxValue before = service.current().orElseThrow();
        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.FAILED);

        assertThat(service.current()).as(reason).contains(before);
        assertThat(service.history()).as(reason).hasSize(1);
        assertThat(service.lastFailure()).as(reason).isEqualTo(reason);
        assertThat(service.recentFetches(1).getFirst().httpStatus()).as(reason).isEqualTo(status);
        assertThat(service.standing()).as(reason).hasValueSatisfying(stale -> {
            assertThat(stale.reason()).isEqualTo(Freshness.Reason.SOURCE_FAILED);
            assertThat(stale.detail()).isEqualTo(reason);
        });
    }

    @Test
    void everyKindOfBadAnswerKeepsTheLastGoodValueAndMarksItStaleWithTheReason() {
        service.test();

        source.answer(500, "{\"error\": \"database password is hunter2\"}");
        assertKeptAndFailed("status", 500);
        source.answer(404, "");
        assertKeptAndFailed("status", 404);
        source.answer(204, "");
        assertKeptAndFailed("status", 204);
        source.answer("not json");
        assertKeptAndFailed("not_json", null);
        source.answer("[1]");
        assertKeptAndFailed("not_json", null);
        source.answer("{\"contract_version\": 1, \"as_of\": \"2026-11-26T06:00:00Z\", \"currency\": \"EUR\"}");
        assertKeptAndFailed("missing_field", null);
        source.answer(validAsOf("2026-11-26T06:00:00Z").replace("1234500", "1234.5"));
        assertKeptAndFailed("not_an_integer", null);
        source.answer(validAsOf("2026-11-26T06:00:00Z").replace("\"EUR\"", "\"USD\""));
        assertKeptAndFailed("currency", null);
        source.answer(validAsOf("2026-11-26T06:00:00Z").replace("\"contract_version\": 1", "\"contract_version\": 2"));
        assertKeptAndFailed("version", null);
        source.answer(validAsOf("2026-11-26T06:00:00Z").replace("Z\"", "\""));
        assertKeptAndFailed("invalid_field", null);
        source.answer(validAsOf("2026-11-26T12:05:01Z"));
        assertKeptAndFailed("as_of_in_future", null);
        source.answerTooLarge();
        assertKeptAndFailed("too_large", null);
    }

    @Test
    void aRedirectIsAFailureWithItsStatusAndTheTargetIsNeverCalled() {
        service.test();
        source.redirectTo(source.url("/elsewhere"));

        assertKeptAndFailed("status", 302);

        assertThat(source.requests("/elsewhere")).isEmpty();
    }

    @Test
    void aSourceThatIsNotThereIsUnreachable() throws Exception {
        service.test();
        String gone;
        try (FakeAfterTaxSource closed = new FakeAfterTaxSource()) {
            gone = closed.url();
        }
        service = serviceFor(gone, Duration.ofSeconds(5));
        service.recentFetches(1); // the same database: the value stored before is still the last good one
        AfterTaxValue before = service.current().orElseThrow();

        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.FAILED);

        assertThat(service.current()).contains(before);
        assertThat(service.lastFailure()).isEqualTo("unreachable");
    }

    @Test
    void aFailureBeforeTheFirstValueMarksNothingStaleAndStoresNoValue() {
        source.answer(500, "");

        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.FAILED);

        assertThat(service.current()).isEmpty();
        assertThat(service.standing()).isEmpty();
        assertThat(service.recentFetches(10)).hasSize(1);
    }

    @Test
    void aValueThatCannotBeStoredIsReportedAsSuchAndLeavesNothingHalfStored() {
        h.jdbc.sql("drop table after_tax_reserved").update();

        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.FAILED);

        assertThat(service.lastFailure()).isEqualTo("store_failed");
        assertThat(h.count("select count(*) from after_tax_value")).isZero();
    }

    @Test
    void whenNoFetchRecordCanBeWrittenTheFailureIsKeptInMemoryUntilARecordIsWritten() {
        service.test();
        assertThat(service.standing()).isEmpty();
        h.jdbc.sql("create trigger fetch_busy before insert on after_tax_fetch begin select raise(abort, 'database is locked'); end")
                .update();
        h.clock.set(NOW.plusSeconds(60));

        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.FAILED);

        assertThat(service.lastFailure()).isEqualTo("store_failed");
        assertThat(service.standing()).hasValueSatisfying(stale -> {
            assertThat(stale.reason()).isEqualTo(Freshness.Reason.SOURCE_FAILED);
            assertThat(stale.detail()).isEqualTo("store_failed");
        });
        assertThat(service.recentFetches(10)).as("the earlier record still says fetched").hasSize(1);

        h.jdbc.sql("drop trigger fetch_busy").update();
        h.clock.set(NOW.plusSeconds(120));
        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.OK);

        assertThat(service.lastFailure()).isNull();
        assertThat(service.standing()).isEmpty();
    }

    // ----- a slow source

    @Test
    void aSourceThatNeverAnswersEndsAsATimeoutWithinTheCap() {
        service = serviceFor(source.url(), Duration.ofMillis(700));
        source.hang();
        long started = System.nanoTime();

        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.FAILED);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        assertThat(service.lastFailure()).isEqualTo("timeout");
    }

    @Test
    void aSourceThatSendsHeadersAndThenTricklesTheBodyIsCutOffToo() {
        service = serviceFor(source.url(), Duration.ofMillis(700));
        source.trickle();
        long started = System.nanoTime();

        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.FAILED);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        assertThat(service.lastFailure()).isEqualTo("timeout");
    }

    @Test
    void aSecondStartWhileAFetchRunsIsSkippedAndATestSaysBusy() throws Exception {
        service = serviceFor(source.url(), Duration.ofMillis(1500));
        source.hang();

        service.start(AfterTaxService.Trigger.SCHEDULED);
        for (int i = 0; i < 100 && source.requests().isEmpty(); i++) {
            Thread.sleep(20);
        }
        service.start(AfterTaxService.Trigger.USER);

        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.BUSY);
        assertThat(source.requests()).as("only the first start asked").hasSize(1);
        awaitIdle();
        assertThat(service.recentFetches(10)).singleElement().satisfies(row -> assertThat(row.reason()).isEqualTo("timeout"));
    }

    @Test
    void aSourceThatHangsDelaysNeitherTheBankSyncNorTheDailyRun() throws Exception {
        long consent = linkAndCreateAccounts();
        service = serviceFor(source.url(), Duration.ofSeconds(3));
        source.hang();
        SyncScheduler scheduler = new SyncScheduler(h.sync, service, h.clock, true, "04:30");
        h.bank.forget();

        long started = System.nanoTime();
        scheduler.runOnce();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).as("the daily run did not wait for the source").isLessThan(Duration.ofMillis(2500));
        assertThat(h.bank.requests("GET", "/accounts/")).as("the bank sync ran").isNotEmpty();
        assertThat(consent).isPositive();
        assertThat(service.idle()).as("the fetch is still waiting for the source").isFalse();
        awaitIdle();
        assertThat(service.lastFailure()).isEqualTo("timeout");
    }

    private long linkAndCreateAccounts() {
        Map<String, String> user = Map.of("Psu-Ip-Address", "192.0.2.10", "Psu-User-Agent", "Fictional Browser");
        LinkOutcome outcome = h.linkBank(user);
        List<BankAccountLink> links = h.store.links(outcome.consentId());
        h.link.create(outcome.consentId(), links.get(0).id(), "Everyday", Scope.PRIVATE, Kind.CURRENT, user);
        return outcome.consentId();
    }

    // ----- freshness

    @Test
    void aValueIsFreshAtExactlyTheLimitAndStaleOneSecondLater() {
        h.clock.set(Instant.parse("2026-11-26T06:00:00Z"));
        service.test(); // as of 06:00
        h.clock.set(Instant.parse("2026-11-28T06:00:00Z"));

        assertThat(service.standing()).as("exactly 48 hours").isEmpty();

        h.clock.set(Instant.parse("2026-11-28T06:00:01Z"));
        assertThat(service.standing()).hasValueSatisfying(stale -> {
            assertThat(stale.reason()).isEqualTo(Freshness.Reason.SOURCE_OLD);
            assertThat(stale.age()).isEqualTo(Duration.ofHours(48).plusSeconds(1));
        });
    }

    @Test
    void anAsOfFiveMinutesAheadIsAcceptedWithAgeZeroAndMoreIsRefused() {
        source.answer(validAsOf("2026-11-26T12:05:00Z"));
        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.OK);
        assertThat(service.standing()).isEmpty();

        source.answer(validAsOf("2026-11-26T12:05:01Z"));
        assertThat(service.test()).isEqualTo(AfterTaxService.TestResult.FAILED);
        assertThat(service.lastFailure()).isEqualTo("as_of_in_future");
        assertThat(service.history()).hasSize(1);
    }

    @Test
    void aFailureWinsOverAnOldMomentAndAGoodFetchClearsIt() {
        service.test();
        h.clock.set(NOW.plus(Duration.ofDays(5)));
        assertThat(service.standing()).hasValueSatisfying(stale -> assertThat(stale.reason()).isEqualTo(Freshness.Reason.SOURCE_OLD));

        source.answer(500, "");
        service.test();
        assertThat(service.standing()).hasValueSatisfying(stale -> assertThat(stale.reason()).isEqualTo(Freshness.Reason.SOURCE_FAILED));

        source.answer(validAsOf("2026-12-01T11:00:00Z"));
        service.test();
        assertThat(service.standing()).isEmpty();
    }

    // ----- what must never reach a log

    @Test
    void noUrlTokenAmountOrLabelReachesTheLogDuringValidInvalidSlowAndFailingFetches() throws Exception {
        writeToken(TOKEN);
        AfterTaxService quick = serviceFor(source.url(), Duration.ofMillis(600));
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.setLevel(Level.DEBUG);
        root.addAppender(appender);
        try {
            quick.test();
            source.answer(500, "{\"error\": \"leaky-secret\"}");
            quick.test();
            source.answer("{\"label\": \"leaky-label\", \"available_cents\": 1234.5");
            quick.test();
            source.hang();
            quick.test();
            source.trickle();
            quick.test();
            source.answer(FakeAfterTaxSource.valid(Instant.parse("2026-11-26T06:00:00Z")).replace("1234500", "1234501"));
            quick.test();
            h.jdbc.sql("drop table after_tax_reserved").update();
            quick.test();
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
        }

        List<String> logged = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            if (event.getLoggerName().startsWith("com.sun.net.httpserver")) {
                continue; // the fake source's own server logging, not this application's
            }
            logged.add(event.getFormattedMessage());
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                logged.add(proxy.getMessage());
            }
        }
        String all = String.join("\n", logged);
        assertThat(all).contains("After-tax fetch finished");
        assertThat(all).doesNotContain(TOKEN).doesNotContain(source.url()).doesNotContain("127.0.0.1").doesNotContain(FakeAfterTaxSource.PATH)
                .doesNotContain("after-tax.json").doesNotContain("1234500").doesNotContain("1234501").doesNotContain("12,345")
                .doesNotContain("2000000").doesNotContain("VAT").doesNotContain("Income tax").doesNotContain("leaky");
    }
}
