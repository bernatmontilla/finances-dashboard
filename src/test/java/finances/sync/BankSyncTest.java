package finances.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import finances.accounts.Account;
import finances.accounts.AccountException;
import finances.accounts.AsOfBasis;
import finances.accounts.BankBalanceInfo;
import finances.accounts.Kind;
import finances.accounts.Scope;
import finances.accounts.Source;
import finances.accounts.Transaction;
import finances.accounts.TransactionStatus;
import finances.money.Money;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Linking a bank and syncing it, against a fake Enable Banking server made from the fictional fixtures of the
 * verification issue (issue #10): link, first sync, second sync adding nothing, pending becoming booked, one
 * account failing, consent expired and renewed, unlink. Nothing real: the fictional set of AGENTS.md only.
 */
class BankSyncTest {

    private static final String SESSION_FILE_CONTENT = FakeEnableBanking.SESSION_ID;
    private static final Map<String, String> USER = Map.of("Psu-Ip-Address", "192.0.2.10", "Psu-User-Agent", "Fictional Browser");

    @TempDir
    Path folder;

    BankHarness h;

    @BeforeEach
    void open() throws Exception {
        h = new BankHarness(folder.resolve("data"), false);
    }

    @AfterEach
    void close() throws Exception {
        // Account information only (hard rule 6): whatever a test did, no payment endpoint was ever asked for.
        assertThat(h.bank.requests()).extracting(FakeEnableBanking.Request::path).noneMatch(path -> path.contains("payment"));
        assertThat(h.bank.requests()).extracting(FakeEnableBanking.Request::body).noneMatch(body -> body.contains("payment"));
        assertThat(h.bank.requests()).allMatch(FakeEnableBanking.Request::tokenValid);
        h.close();
    }

    // ----- helpers

    /** Links the bank and confirms the current and the savings account as new accounts; returns the consent id. */
    private long linkBothAsNewAccounts() {
        LinkOutcome outcome = h.linkBank(USER);
        List<BankAccountLink> links = h.store.links(outcome.consentId());
        h.link.create(outcome.consentId(), links.get(0).id(), "Everyday", Scope.PRIVATE, Kind.CURRENT, USER);
        h.link.create(outcome.consentId(), links.get(1).id(), "Savings", Scope.PRIVATE, Kind.SAVINGS, USER);
        return outcome.consentId();
    }

    private long everyday() {
        return h.accountIdByIban("NL51XMPL0000000001");
    }

    private long savings() {
        return h.accountIdByIban("NL24XMPL0000000002");
    }

    private List<Transaction> everydayTransactions() {
        return h.transactions.forAccount(everyday(), 0).items();
    }

    private SyncRunRow lastRun(long accountId) {
        return h.status.lastRun(accountId).orElseThrow();
    }

    private void nextDay() {
        h.clock.set(h.clock.instant().plusSeconds(24 * 3600));
    }

    // ----- link

    @Test
    void linkingStoresTheConsentAndOffersTheAccountsButCreatesNothingWithoutConfirmation() {
        String url = h.link.start("NL", "Example Bank", null);

        assertThat(url).startsWith("https://example.com/ais/start");
        FakeEnableBanking.Request auth = h.bank.requests("POST", "/auth").getFirst();
        assertThat(auth.body()).contains("\"balances\":true").contains("\"transactions\":true").contains("\"psu_type\":\"personal\"")
                .contains("\"name\":\"Example Bank\"").contains("\"country\":\"NL\"").contains("\"redirect_url\":\"" + BankHarness.REDIRECT + "\"");
        assertThat(h.bank.requests("GET", "/aspsps")).isNotEmpty();
        assertThat(h.accounts.list(false)).as("nothing is created before the owner confirms").isEmpty();
        assertThat(h.count("select count(*) from bank_consent")).isZero();

        LinkOutcome outcome = h.link.complete(h.lastState(), "fictional-code", null, USER);

        assertThat(outcome.empty()).isFalse();
        assertThat(outcome.offered()).isEqualTo(2);
        assertThat(outcome.unsupported()).isEqualTo(1);
        assertThat(outcome.reattached()).isZero();
        assertThat(h.accounts.list(false)).as("still nothing created").isEmpty();
        Consent consent = h.status.consent(outcome.consentId()).orElseThrow();
        assertThat(consent.bankName()).isEqualTo("Example Bank");
        assertThat(consent.status()).isEqualTo(ConsentStatus.AUTHORIZED);
        assertThat(consent.validUntil()).isEqualTo(Instant.parse("2027-05-24T10:00:00Z"));
        assertThat(consent.requiredPsuHeaders()).containsExactly("Psu-Ip-Address", "Psu-User-Agent");
        List<BankAccountLink> links = h.store.links(outcome.consentId());
        assertThat(links).hasSize(3).allMatch(link -> !link.linked());
        assertThat(links).extracting(BankAccountLink::supported).containsExactly(true, true, false);
        assertThat(links.get(0).identificationHash()).isEqualTo("ZmljdGlvbmFsLWhhc2gtMQ==.ZmljdGlvbmFsLXNpZy0x");
        assertThat(links.get(0).iban()).isEqualTo("NL51XMPL0000000001");
    }

    @Test
    void theSessionIdIsAFileInTheSecretsFolderAndNeverInTheDatabase() throws Exception {
        LinkOutcome outcome = h.linkBank(USER);

        Path file = folder.resolve("data/secrets/enable-banking-session-" + outcome.consentId());
        assertThat(Files.readString(file)).isEqualTo(SESSION_FILE_CONTENT);
        try {
            assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
        String database = new String(Files.readAllBytes(h.dataFolder.databaseFile()), StandardCharsets.ISO_8859_1);
        assertThat(database).doesNotContain(FakeEnableBanking.SESSION_ID).doesNotContain("BEGIN PRIVATE KEY");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theFirstSyncRunsInsideTheLinkFlowAsFarBackAsTheBankAllowsWithEitherKeyFormat(boolean pkcs1) throws Exception {
        h.close();
        h = new BankHarness(folder.resolve("data-" + pkcs1), pkcs1);

        LinkOutcome outcome = h.linkBank(USER);
        BankAccountLink first = h.store.links(outcome.consentId()).getFirst();
        SyncSummary summary = h.link.create(outcome.consentId(), first.id(), "Everyday", Scope.PRIVATE, Kind.CURRENT, USER);

        assertThat(summary).isEqualTo(new SyncSummary(SyncSummary.State.DONE, 1, 0));
        List<FakeEnableBanking.Request> asked = h.bank.requests("GET", "/accounts/" + FakeEnableBanking.CURRENT_UID + "/transactions");
        assertThat(asked).hasSize(2);
        assertThat(asked.get(0).query()).containsEntry("strategy", "longest").doesNotContainKey("date_from").doesNotContainKey("continuation_key");
        assertThat(asked.get(1).query()).containsEntry("strategy", "longest").containsEntry("continuation_key", "fictional-continuation-key-1");
        Account account = h.accounts.get(everyday());
        assertThat(account.source()).isEqualTo(Source.BANK);
        assertThat(account.name()).isEqualTo("Everyday");
        assertThat(everydayTransactions()).hasSize(5);
        assertThat(everydayTransactions()).filteredOn(Transaction::pending).singleElement().satisfies(t -> {
            assertThat(t.counterpartyName()).isEqualTo("Example Gym");
            assertThat(t.amount()).isEqualTo(Money.eur(-800));
            assertThat(t.bookingDate()).as("a pending item is dated by its transaction date").isEqualTo(LocalDate.of(2026, 11, 26));
            assertThat(t.source()).isEqualTo(Source.BANK);
        });
        assertThat(everydayTransactions()).filteredOn(t -> "E-0003".equals(t.reference())).singleElement()
                .satisfies(t -> assertThat(t.amount()).as("a credit is positive").isEqualTo(Money.eur(123456)));
        assertThat(everydayTransactions()).filteredOn(t -> "E-0001".equals(t.reference())).singleElement()
                .satisfies(t -> assertThat(t.amount()).as("a debit is negative").isEqualTo(Money.eur(-1234)));
        assertThat(h.transactions.lastDelivered(everyday())).isPresent();
        assertThat(lastRun(everyday()).ok()).isTrue();
        assertThat(lastRun(everyday()).added()).isEqualTo(5);
        assertThat(lastRun(everyday()).trigger()).isEqualTo(SyncTrigger.LINK);
    }

    @Test
    void theBookedBalanceIsStoredWithItsTypeAndTheBanksOwnTimestamp() {
        linkBothAsNewAccounts();

        BankBalanceInfo current = h.accounts.latestBankBalance(everyday()).orElseThrow();
        assertThat(current.type()).as("by type, never by name").isEqualTo("CLBD");
        assertThat(current.booked()).isTrue();
        assertThat(current.amount()).isEqualTo(Money.eur(123456));
        assertThat(current.basis()).isEqualTo(AsOfBasis.BANK_TIME);
        assertThat(current.asOf()).isEqualTo(Instant.parse("2026-11-26T08:30:00Z"));
        assertThat(current.date()).isEqualTo(LocalDate.of(2026, 11, 26));
        BankBalanceInfo savings = h.accounts.latestBankBalance(savings()).orElseThrow();
        assertThat(savings.amount()).isEqualTo(Money.eur(500000));
        assertThat(savings.basis()).as("the bank gave a reference date only").isEqualTo(AsOfBasis.BANK_DATE);
        assertThat(savings.asOf()).isNull();
        assertThat(h.accounts.list(false)).extracting(s -> s.latest().orElseThrow().amount()).containsExactlyInAnyOrder(Money.eur(123456), Money.eur(500000));
    }

    @Test
    void anExistingAccountWithoutABankLinkCanBeAttachedAndKeepsItsHistory() {
        Account manual = h.accounts.addManual("Old everyday", Scope.PRIVATE, Kind.CURRENT, "NL51XMPL0000000001");
        h.accounts.addBalance(manual.id(), Money.eur(1000), LocalDate.of(2026, 11, 1));
        LinkOutcome outcome = h.linkBank(USER);
        BankAccountLink first = h.store.links(outcome.consentId()).getFirst();

        h.link.attach(outcome.consentId(), first.id(), manual.id(), USER);

        Account attached = h.accounts.get(manual.id());
        assertThat(attached.source()).isEqualTo(Source.BANK);
        assertThat(attached.name()).isEqualTo("Old everyday");
        assertThat(h.accounts.history(manual.id())).hasSize(2).extracting(e -> e.amount()).contains(Money.eur(1000), Money.eur(123456));
        assertThat(h.store.link(first.id()).orElseThrow().accountId()).isEqualTo(manual.id());
        assertThatThrownBy(() -> h.accounts.attachToBank(manual.id())).isInstanceOf(AccountException.class);
    }

    @Test
    void aSecondConfirmationOfTheSameAccountCreatesNoOrphanAccount() {
        LinkOutcome outcome = h.linkBank(USER);
        BankAccountLink first = h.store.links(outcome.consentId()).getFirst();
        h.link.create(outcome.consentId(), first.id(), "Everyday", Scope.PRIVATE, Kind.CURRENT, USER);
        // Another request that passed the check before the first one confirmed: simulate it at the store.
        assertThat(h.store.confirm(first.id(), 999, h.clock.instant())).isFalse();
        assertThatThrownBy(() -> h.link.create(outcome.consentId(), first.id(), "Again", Scope.PRIVATE, Kind.CURRENT, USER))
                .isInstanceOfSatisfying(LinkException.class, e -> assertThat(e.reason()).isEqualTo(LinkException.Reason.ALREADY_LINKED));

        assertThat(h.accounts.list(false)).hasSize(1);
    }

    @Test
    void aBalanceOfAnotherTypeWithoutAnAgeIsStoredAsExactlyThat() {
        h.bank.balancesFixture(FakeEnableBanking.CURRENT_UID, "balances-available-only");

        linkBothAsNewAccounts();

        BankBalanceInfo info = h.accounts.latestBankBalance(everyday()).orElseThrow();
        assertThat(info.type()).isEqualTo("ITAV");
        assertThat(info.booked()).isFalse();
        assertThat(info.basis()).isEqualTo(AsOfBasis.FETCHED);
        assertThat(info.asOf()).isEqualTo(Instant.parse("2026-11-26T12:00:00Z"));
    }

    @Test
    void aLinkThatChangedWhileTheFetchWasInFlightStoresNothing() throws Exception {
        linkBothAsNewAccounts();
        int transactions = h.count("select count(*) from transactions");
        Instant success = h.store.linkOfAccount(everyday()).orElseThrow().lastSuccessAt();
        h.bank.transactions(FakeEnableBanking.Transactions.SECOND);
        CountDownLatch seen = new CountDownLatch(1);
        h.bank.balancesDelay(600, seen);
        nextDay();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SyncSummary> run = pool.submit(() -> h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of()));
            assertThat(seen.await(10, TimeUnit.SECONDS)).isTrue();
            // A restore or a renewal in the meantime: the account now has another uid.
            h.jdbc.sql("update bank_account set uid = 'cccccccc-0000-4000-8000-000000000001' where account_id = ?").param(everyday()).update();

            assertThat(run.get(30, TimeUnit.SECONDS).failed()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(lastRun(everyday()).reason()).isEqualTo("store_failed");
        assertThat(h.count("select count(*) from transactions where account_id = ?", everyday())).isEqualTo(5);
        assertThat(h.count("select count(*) from transactions")).isEqualTo(transactions);
        assertThat(h.transactions.forAccount(everyday(), 0).items()).filteredOn(Transaction::pending).hasSize(1);
        assertThat(h.store.linkOfAccount(everyday()).orElseThrow().lastSuccessAt()).isEqualTo(success);
    }

    @Test
    void anAccountInAnotherCurrencyIsListedAsNotSupportedAndCannotBeLinked() {
        LinkOutcome outcome = h.linkBank(USER);
        BankAccountLink multi = h.store.links(outcome.consentId()).get(2);

        assertThat(multi.supported()).isFalse();
        assertThat(multi.currency()).isEqualTo("XXX");
        assertThatThrownBy(() -> h.link.create(outcome.consentId(), multi.id(), "Multi", Scope.PRIVATE, Kind.CURRENT, USER))
                .isInstanceOfSatisfying(LinkException.class, e -> assertThat(e.reason()).isEqualTo(LinkException.Reason.UNSUPPORTED_CURRENCY));
        assertThat(h.accounts.list(false)).isEmpty();
    }

    @Test
    void aBankThatReturnsNoAccountStoresNothingAndClosesTheSession() {
        h.bank.emptySession(true);

        LinkOutcome outcome = h.linkBank(USER);

        assertThat(outcome.empty()).isTrue();
        assertThat(h.count("select count(*) from bank_consent")).isZero();
        assertThat(Files.exists(folder.resolve("data/secrets/enable-banking-session-1"))).isFalse();
        assertThat(h.bank.requests("DELETE", "/sessions/")).hasSize(1);
    }

    @Test
    void anUnknownUsedOrRefusedAuthorisationStoresNothing() {
        assertThatThrownBy(() -> h.link.complete("never-issued", "code", null, USER))
                .isInstanceOfSatisfying(LinkException.class, e -> assertThat(e.reason()).isEqualTo(LinkException.Reason.UNKNOWN_STATE));
        assertThatThrownBy(() -> h.link.complete(null, "code", null, USER)).isInstanceOf(LinkException.class);

        h.link.start("NL", "Example Bank", null);
        String refused = h.lastState();
        assertThatThrownBy(() -> h.link.complete(refused, null, "access_denied", USER))
                .isInstanceOfSatisfying(LinkException.class, e -> assertThat(e.reason()).isEqualTo(LinkException.Reason.DENIED));
        assertThatThrownBy(() -> h.link.complete(refused, "code", null, USER))
                .as("a state works once")
                .isInstanceOfSatisfying(LinkException.class, e -> assertThat(e.reason()).isEqualTo(LinkException.Reason.UNKNOWN_STATE));

        h.link.start("NL", "Example Bank", null);
        String cancelled = h.lastState();
        assertThatThrownBy(() -> h.link.complete(cancelled, "", null, USER))
                .isInstanceOfSatisfying(LinkException.class, e -> assertThat(e.reason()).isEqualTo(LinkException.Reason.DENIED));

        assertThat(h.bank.sessionsCreated()).as("no session was made for any of them").isZero();
        assertThat(h.count("select count(*) from bank_consent")).isZero();
        assertThat(h.accounts.list(false)).isEmpty();
    }

    @Test
    void aBankThatIsNotInTheListIsRefused() {
        assertThatThrownBy(() -> h.link.start("NL", "Some Other Bank", null))
                .isInstanceOfSatisfying(LinkException.class, e -> assertThat(e.reason()).isEqualTo(LinkException.Reason.BANK_UNKNOWN));
        assertThat(h.bank.requests("POST", "/auth")).isEmpty();
    }

    // ----- sync

    @Test
    void aSecondSyncAddsNothingAndStartsAtTheLastSuccessMinusTheOverlap() {
        linkBothAsNewAccounts();
        int transactionsBefore = h.count("select count(*) from transactions");
        int balancesBefore = h.count("select count(*) from balance");
        h.bank.forget();
        nextDay();

        SyncSummary summary = h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());

        assertThat(summary).isEqualTo(new SyncSummary(SyncSummary.State.DONE, 2, 0));
        FakeEnableBanking.Request asked = h.bank.requests("GET", "/accounts/" + FakeEnableBanking.CURRENT_UID + "/transactions").getFirst();
        assertThat(asked.query()).containsEntry("date_from", "2026-11-19").doesNotContainKey("strategy");
        assertThat(h.count("select count(*) from transactions")).isEqualTo(transactionsBefore);
        assertThat(h.count("select count(*) from balance")).as("the same bank balance is not stored twice").isEqualTo(balancesBefore);
        SyncRunRow run = lastRun(everyday());
        assertThat(run.ok()).isTrue();
        assertThat(run.trigger()).isEqualTo(SyncTrigger.SCHEDULED);
        assertThat(run.added()).isZero();
        assertThat(run.updated()).isZero();
        assertThat(run.known()).isEqualTo(5);
    }

    @Test
    void aPendingTransactionBecomingBookedIsOneRowThatKeepsItsNote() {
        linkBothAsNewAccounts();
        Transaction pending = everydayTransactions().stream().filter(Transaction::pending).findFirst().orElseThrow();
        h.transactions.setNote(pending.id(), "With Alex Sample");
        h.bank.transactions(FakeEnableBanking.Transactions.SECOND);
        nextDay();

        h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());

        assertThat(everydayTransactions()).hasSize(5);
        Transaction booked = h.transactions.get(pending.id());
        assertThat(booked.status()).isEqualTo(TransactionStatus.BOOKED);
        assertThat(booked.reference()).isEqualTo("E-0004");
        assertThat(booked.bookingDate()).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(booked.note()).isEqualTo("With Alex Sample");
        assertThat(booked.amount()).isEqualTo(Money.eur(-800));
        SyncRunRow run = lastRun(everyday());
        assertThat(run.updated()).isEqualTo(1);
        assertThat(run.added()).isZero();
        assertThat(run.pendingRemoved()).isZero();

        nextDay();
        h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());
        assertThat(everydayTransactions()).hasSize(5);
        assertThat(lastRun(everyday()).known()).isEqualTo(5);
    }

    @Test
    void oneAccountFailingDoesNotStopTheOthersAndKeepsItsDataAndLastSuccess() {
        linkBothAsNewAccounts();
        Instant savingsSuccess = h.store.linkOfAccount(savings()).orElseThrow().lastSuccessAt();
        int savingsBalances = h.count("select count(*) from balance where account_id = ?", savings());
        h.bank.transactions(FakeEnableBanking.Transactions.SECOND);
        h.bank.fail(FakeEnableBanking.SAVINGS_UID, "error-rate-limit");
        h.bank.forget();
        nextDay();

        SyncSummary summary = h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());

        assertThat(summary).isEqualTo(new SyncSummary(SyncSummary.State.DONE, 1, 1));
        SyncRunRow failed = lastRun(savings());
        assertThat(failed.ok()).isFalse();
        assertThat(failed.reason()).isEqualTo("rate_limited");
        assertThat(h.bank.requests("GET", "/accounts/" + FakeEnableBanking.SAVINGS_UID + "/balances")).as("one attempt, no retry loop").hasSize(1);
        assertThat(h.store.linkOfAccount(savings()).orElseThrow().lastSuccessAt()).isEqualTo(savingsSuccess);
        assertThat(h.count("select count(*) from balance where account_id = ?", savings())).isEqualTo(savingsBalances);
        assertThat(h.accounts.latestBankBalance(savings()).orElseThrow().amount()).isEqualTo(Money.eur(500000));
        assertThat(lastRun(everyday()).ok()).isTrue();
        assertThat(everydayTransactions()).filteredOn(Transaction::pending).isEmpty();

        h.bank.stopFailing(FakeEnableBanking.SAVINGS_UID);
        nextDay();
        assertThat(h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of())).isEqualTo(new SyncSummary(SyncSummary.State.DONE, 2, 0));
        assertThat(h.store.linkOfAccount(savings()).orElseThrow().lastSuccessAt()).isAfter(savingsSuccess);
    }

    @Test
    void aFetchThatFailsHalfwayStoresNothingNotEvenTheFirstPage() {
        LinkOutcome outcome = h.linkBank(USER);
        BankAccountLink first = h.store.links(outcome.consentId()).getFirst();
        h.bank.failSecondPageOfTransactions(true);

        SyncSummary summary = h.link.create(outcome.consentId(), first.id(), "Everyday", Scope.PRIVATE, Kind.CURRENT, USER);

        assertThat(summary).isEqualTo(new SyncSummary(SyncSummary.State.DONE, 0, 1));
        assertThat(everydayTransactions()).isEmpty();
        assertThat(h.count("select count(*) from balance")).as("the balance of a failed run is not stored either").isZero();
        assertThat(h.store.link(first.id()).orElseThrow().lastSuccessAt()).isNull();
        assertThat(lastRun(everyday()).reason()).isEqualTo("bank_error");

        h.bank.failSecondPageOfTransactions(false);
        h.clock.set(h.clock.instant().plusSeconds(3600));
        assertThat(h.sync.syncAll(SyncTrigger.USER, USER)).isEqualTo(new SyncSummary(SyncSummary.State.DONE, 1, 0));
        assertThat(everydayTransactions()).hasSize(5);
        assertThat(h.bank.requests("GET", "/accounts/" + FakeEnableBanking.CURRENT_UID + "/transactions").getLast().query())
                .as("a first sync that failed is a first sync again").containsEntry("strategy", "longest");
    }

    @Test
    void aPendingTransactionTheBankDropsIsRemovedAndAFailedSyncRemovesNothing() {
        linkBothAsNewAccounts();
        assertThat(everydayTransactions()).filteredOn(Transaction::pending).hasSize(1);
        h.bank.transactions(FakeEnableBanking.Transactions.WITHOUT_PENDING);

        h.bank.fail(FakeEnableBanking.CURRENT_UID, "error-rate-limit");
        nextDay();
        h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());
        assertThat(lastRun(everyday()).ok()).isFalse();
        assertThat(everydayTransactions()).as("a failed sync removes nothing").filteredOn(Transaction::pending).hasSize(1);

        h.bank.stopFailing(FakeEnableBanking.CURRENT_UID);
        nextDay();
        h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());

        assertThat(lastRun(everyday()).pendingRemoved()).isEqualTo(1);
        assertThat(everydayTransactions()).hasSize(4).noneMatch(Transaction::pending);
    }

    @Test
    void aDroppedPendingTransactionWithANoteIsKeptAndMarked() {
        linkBothAsNewAccounts();
        Transaction pending = everydayTransactions().stream().filter(Transaction::pending).findFirst().orElseThrow();
        h.transactions.setNote(pending.id(), "With Alex Sample");
        h.bank.transactions(FakeEnableBanking.Transactions.WITHOUT_PENDING);
        nextDay();

        h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());

        SyncRunRow run = lastRun(everyday());
        assertThat(run.pendingRemoved()).isZero();
        assertThat(run.pendingKept()).isEqualTo(1);
        assertThat(h.transactions.get(pending.id())).satisfies(t -> {
            assertThat(t.noLongerReported()).isTrue();
            assertThat(t.note()).isEqualTo("With Alex Sample");
            assertThat(t.pending()).isTrue();
        });
    }

    @Test
    void twoRunsNeverOverlap() throws Exception {
        linkBothAsNewAccounts();
        CountDownLatch seen = new CountDownLatch(1);
        h.bank.balancesDelay(700, seen);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SyncSummary> first = pool.submit(() -> h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of()));
            assertThat(seen.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(h.sync.syncAll(SyncTrigger.USER, USER)).isEqualTo(new SyncSummary(SyncSummary.State.BUSY, 0, 0));
            assertThat(first.get(30, TimeUnit.SECONDS).state()).isEqualTo(SyncSummary.State.DONE);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRunStartedByTheUserSendsTheHeadersTheBankListsAllOrNoneAndTheDailyRunNone() {
        linkBothAsNewAccounts();
        String current = "/accounts/" + FakeEnableBanking.CURRENT_UID + "/balances";

        h.bank.forget();
        h.sync.syncAll(SyncTrigger.SCHEDULED, USER);
        FakeEnableBanking.Request daily = h.bank.requests("GET", current).getFirst();
        assertThat(daily.hasHeader("Psu-Ip-Address")).isFalse();
        assertThat(daily.hasHeader("Psu-User-Agent")).isFalse();
        assertThat(lastRun(everyday()).trigger()).isEqualTo(SyncTrigger.SCHEDULED);
        assertThat(lastRun(everyday()).headersSent()).isFalse();

        h.bank.forget();
        h.sync.syncAll(SyncTrigger.USER, USER);
        FakeEnableBanking.Request online = h.bank.requests("GET", current).getFirst();
        assertThat(online.headers()).containsEntry("psu-ip-address", "192.0.2.10").containsEntry("psu-user-agent", "Fictional Browser");
        assertThat(lastRun(everyday()).headersSent()).isTrue();
        assertThat(lastRun(everyday()).trigger()).isEqualTo(SyncTrigger.USER);

        // The bank wants two headers and only one can be given: none are sent, and it is a background fetch.
        h.bank.forget();
        h.sync.syncAll(SyncTrigger.USER, Map.of("Psu-Ip-Address", "192.0.2.10"));
        FakeEnableBanking.Request partial = h.bank.requests("GET", current).getFirst();
        assertThat(partial.hasHeader("Psu-Ip-Address")).isFalse();
        assertThat(lastRun(everyday()).headersSent()).isFalse();
    }

    // ----- expired consent, renewal, unlink

    @Test
    void anExpiredConsentStopsItsSyncKeepsEverythingAndShowsAsExpired() {
        long consent = linkBothAsNewAccounts();
        int transactions = h.count("select count(*) from transactions");
        h.bank.sessionStatus(FakeEnableBanking.SESSION_ID, "EXPIRED");
        h.bank.forget();
        nextDay();

        SyncSummary summary = h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());

        assertThat(summary).isEqualTo(new SyncSummary(SyncSummary.State.DONE, 0, 2));
        assertThat(h.status.consent(consent).orElseThrow().status()).isEqualTo(ConsentStatus.EXPIRED);
        assertThat(lastRun(everyday()).reason()).isEqualTo("consent_expired");
        assertThat(lastRun(savings()).reason()).isEqualTo("consent_expired");
        assertThat(h.bank.requests("GET", "/accounts/")).as("an ended consent is not fetched").isEmpty();
        assertThat(h.count("select count(*) from transactions")).isEqualTo(transactions);
        assertThat(h.accounts.latestBankBalance(everyday())).isPresent();
        assertThat(h.accounts.list(false)).hasSize(2);

        h.bank.forget();
        nextDay();
        assertThat(h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of())).isEqualTo(new SyncSummary(SyncSummary.State.DONE, 0, 0));
        assertThat(h.bank.requests()).as("its sync has stopped").isEmpty();
    }

    @Test
    void aConsentThatEndsDuringAFetchIsFoundByTheErrorAndTheOtherAccountsOfItAreNotAsked() {
        long consent = linkBothAsNewAccounts();
        h.bank.fail(FakeEnableBanking.CURRENT_UID, "error-expired-session");
        h.bank.forget();
        nextDay();

        SyncSummary summary = h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());

        assertThat(summary.failed()).isEqualTo(2);
        assertThat(h.status.consent(consent).orElseThrow().status()).isEqualTo(ConsentStatus.EXPIRED);
        assertThat(h.bank.requests("GET", "/accounts/" + FakeEnableBanking.SAVINGS_UID)).isEmpty();
    }

    // ----- follow-ups from the review of the bank sync (issue #39)

    private static String transactionsJson(String... items) {
        return "{\"transactions\":[" + String.join(",", items) + "]}";
    }

    private static String item(String reference, String status, String amount, String indicator, String date, String description) {
        String references = reference == null ? "" : "\"entry_reference\":\"" + reference + "\",";
        String dates = date == null ? "" : ",\"booking_date\":\"" + date + "\"";
        return "{" + references + "\"transaction_amount\":{\"currency\":\"EUR\",\"amount\":\"" + amount + "\"},"
                + "\"creditor\":{\"name\":\"Example Gym\"},\"debtor\":{\"name\":\"Sam Voorbeeld\"},"
                + "\"credit_debit_indicator\":\"" + indicator + "\",\"status\":\"" + status + "\""
                + dates + ",\"remittance_information\":[\"" + description + "\"]}";
    }

    @Test
    void aSignedAmountIsReadAndOneThatContradictsItsIndicatorIsRefusedAndCountedWhileTheRestIsStored() {
        h.bank.transactionsBody(transactionsJson(
                item("E-1", "BOOK", "12.34", "DBIT", "2026-11-20", "Unsigned debit"),
                item("E-2", "BOOK", "-5.00", "DBIT", "2026-11-21", "Signed debit"),
                item("E-3", "BOOK", "-7.00", "CRDT", "2026-11-22", "Contradiction"),
                item("E-4", "BOOK", "20.00", "CRDT", "2026-11-23", "Unsigned credit")));

        linkBothAsNewAccounts();

        assertThat(everydayTransactions()).extracting(t -> t.amount().cents()).containsExactlyInAnyOrder(-1234L, -500L, 2000L);
        SyncRunRow run = lastRun(everyday());
        assertThat(run.ok()).as("one refused transaction does not fail the account").isTrue();
        assertThat(run.added()).isEqualTo(3);
        assertThat(run.refused()).isEqualTo(1);
        // Fetched again: the same three are known, the contradiction is refused again, nothing is stored twice.
        nextDay();
        h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());
        assertThat(everydayTransactions()).hasSize(3);
        assertThat(lastRun(everyday()).refused()).isEqualTo(1);
        assertThat(lastRun(everyday()).known()).isEqualTo(3);
    }

    @Test
    void aPendingItemDatedByTheFetchDayIsTakenOverWhenItIsBookedUnderANewReferenceWithAnEarlierDate() {
        // The bank gave the pending item no date at all: it is stored with the day of the fetch, 2026-11-26.
        h.bank.transactionsBody(transactionsJson(item(null, "PDNG", "8.00", "DBIT", null, "Day pass")));
        linkBothAsNewAccounts();
        Transaction pending = everydayTransactions().getFirst();
        assertThat(pending.pending()).isTrue();
        assertThat(pending.bookingDate()).isEqualTo(LocalDate.of(2026, 11, 26));
        h.transactions.setNote(pending.id(), "With Alex Sample");

        // Booked later, dated before the day it was first seen.
        h.bank.transactionsBody(transactionsJson(item("E-9", "BOOK", "8.00", "DBIT", "2026-11-24", "Day pass")));
        nextDay();
        h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());

        assertThat(everydayTransactions()).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(pending.id());
            assertThat(t.status()).isEqualTo(TransactionStatus.BOOKED);
            assertThat(t.bookingDate()).isEqualTo(LocalDate.of(2026, 11, 24));
            assertThat(t.note()).isEqualTo("With Alex Sample");
        });
    }

    @Test
    void aPendingItemThatCameWithADateKeepsTheStrictRuleAgainstAnEarlierBookedDate() {
        h.bank.transactionsBody(transactionsJson(item(null, "PDNG", "8.00", "DBIT", "2026-11-26", "Day pass")));
        linkBothAsNewAccounts();
        assertThat(everydayTransactions()).singleElement().satisfies(t -> assertThat(t.pending()).isTrue());

        h.bank.transactionsBody(transactionsJson(
                item(null, "PDNG", "8.00", "DBIT", "2026-11-26", "Day pass"),
                item("E-9", "BOOK", "8.00", "DBIT", "2026-11-24", "Day pass")));
        nextDay();
        h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());

        // The bank itself dated the pending one 2026-11-26 and booked another purchase two days before it: no take-over.
        assertThat(everydayTransactions()).hasSize(2);
    }

    @Test
    void aSessionFileLeftBehindByAnotherConsentIsNeverUsed() throws Exception {
        long consent = linkBothAsNewAccounts();
        // After a restore, a file of another consent that had the same id is lying in the secrets folder.
        h.sessions.write(consent, "99999999-9999-4999-8999-999999999999", SessionFiles.newTag());
        h.bank.forget();
        nextDay();

        h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());

        assertThat(h.status.consent(consent).orElseThrow().ended()).as("shows as needing Renew").isTrue();
        assertThat(h.bank.requests()).as("the leftover id never went to the bank").extracting(FakeEnableBanking.Request::path)
                .noneMatch(path -> path.contains("99999999"));
        assertThat(h.bank.requests("GET", "/accounts/")).isEmpty();
        assertThat(h.bank.requests("GET", "/sessions/")).isEmpty();
    }

    @Test
    void aConsentWithoutItsSessionFileAfterARestoreShowsAsExpired() throws Exception {
        long consent = linkBothAsNewAccounts();
        Files.delete(folder.resolve("data/secrets/enable-banking-session-" + consent));
        h.bank.forget();

        h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());

        assertThat(h.status.consent(consent).orElseThrow().ended()).isTrue();
        assertThat(h.bank.requests("GET", "/accounts/")).isEmpty();
    }

    @Test
    void renewingReattachesTheSameAccountsByIdentificationHashWithNewUidsAndSessionId() throws Exception {
        long old = linkBothAsNewAccounts();
        long everydayId = everyday();
        h.bank.sessionStatus(FakeEnableBanking.SESSION_ID, "EXPIRED");
        nextDay();
        h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());
        assertThat(h.status.consent(old).orElseThrow().ended()).isTrue();
        int transactionsBefore = h.count("select count(*) from transactions");
        h.bank.transactions(FakeEnableBanking.Transactions.SECOND);
        h.bank.renewedSession(true);
        h.bank.forget();
        nextDay();

        h.link.start("NL", "Example Bank", old);
        LinkOutcome outcome = h.link.complete(h.lastState(), "fictional-code", null, USER);

        assertThat(outcome.reattached()).isEqualTo(2);
        assertThat(outcome.offered()).isZero();
        assertThat(outcome.unsupported()).isEqualTo(1);
        assertThat(outcome.firstSync()).isEqualTo(new SyncSummary(SyncSummary.State.DONE, 2, 0));
        assertThat(h.accounts.list(false)).as("no new account").hasSize(2);
        BankAccountLink link = h.store.linkOfAccount(everydayId).orElseThrow();
        assertThat(link.consentId()).isEqualTo(outcome.consentId()).isNotEqualTo(old);
        assertThat(link.uid()).isEqualTo(FakeEnableBanking.RENEWED_CURRENT_UID);
        assertThat(link.identificationHash()).isEqualTo("ZmljdGlvbmFsLWhhc2gtMQ==.ZmljdGlvbmFsLXNpZy0x");
        assertThat(h.status.consent(outcome.consentId()).orElseThrow().status()).isEqualTo(ConsentStatus.AUTHORIZED);
        assertThat(h.status.consent(old)).as("the old consent is gone").isEmpty();
        assertThat(Files.exists(folder.resolve("data/secrets/enable-banking-session-" + old))).isFalse();
        assertThat(Files.readString(folder.resolve("data/secrets/enable-banking-session-" + outcome.consentId()))).isEqualTo(FakeEnableBanking.RENEWED_SESSION_ID);
        assertThat(h.bank.requests("DELETE", "/sessions/" + FakeEnableBanking.SESSION_ID)).as("the old session is closed").hasSize(1);
        FakeEnableBanking.Request fetched = h.bank.requests("GET", "/accounts/" + FakeEnableBanking.RENEWED_CURRENT_UID + "/transactions").getFirst();
        assertThat(fetched.query()).as("the history is there already: from the last success, with the overlap").containsKey("date_from").doesNotContainKey("strategy");
        assertThat(h.count("select count(*) from transactions")).as("the same transactions are not stored twice").isEqualTo(transactionsBefore);
        assertThat(everydayTransactions()).filteredOn(t -> "E-0004".equals(t.reference())).hasSize(1);
    }

    @Test
    void unlinkingRemovesTheConsentAndTheSessionAndKeepsTheAccountsAndTheirHistoryAsManualAccounts() {
        long consent = linkBothAsNewAccounts();
        int transactions = h.count("select count(*) from transactions");
        h.bank.forget();

        h.link.unlink(consent);

        assertThat(h.bank.requests("DELETE", "/sessions/" + FakeEnableBanking.SESSION_ID)).hasSize(1);
        assertThat(h.count("select count(*) from bank_consent")).isZero();
        assertThat(h.count("select count(*) from bank_account")).isZero();
        assertThat(Files.exists(folder.resolve("data/secrets/enable-banking-session-" + consent))).isFalse();
        assertThat(h.accounts.list(false)).hasSize(2).allMatch(s -> s.account().source() == Source.MANUAL);
        assertThat(h.count("select count(*) from transactions")).isEqualTo(transactions);
        assertThat(h.accounts.history(everyday())).as("the bank balance stays in the history").isNotEmpty();
        // A manual account takes a balance by hand again.
        h.accounts.addBalance(everyday(), Money.eur(100), LocalDate.of(2026, 11, 26));
        assertThat(h.sync.syncAll(SyncTrigger.USER, USER)).isEqualTo(new SyncSummary(SyncSummary.State.DONE, 0, 0));
        assertThat(h.bank.requests("GET", "/accounts/")).isEmpty();
    }

    // ----- what must never reach a log

    @Test
    void noTokenIbanCounterpartyOrAmountReachesTheLogDuringALinkAndAFullSync() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.setLevel(Level.DEBUG);
        root.addAppender(appender);
        try {
            long consent = linkBothAsNewAccounts();
            h.bank.fail(FakeEnableBanking.SAVINGS_UID, "error-rate-limit");
            nextDay();
            h.sync.syncAll(SyncTrigger.USER, USER);
            h.bank.stopFailing(FakeEnableBanking.SAVINGS_UID);
            h.bank.transactions(FakeEnableBanking.Transactions.SECOND);
            nextDay();
            h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());
            h.link.unlink(consent);
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
        }

        List<String> logged = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            if (event.getLoggerName().startsWith("com.sun.net.httpserver")) {
                continue; // the fake bank's own server logging, not this application's
            }
            logged.add(event.getFormattedMessage());
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                logged.add(proxy.getMessage());
            }
        }
        String all = String.join("\n", logged);
        assertThat(all).isNotBlank();
        assertThat(all)
                .doesNotContain(h.bank.lastToken())
                .doesNotContain(FakeEnableBanking.SESSION_ID).doesNotContain(FakeEnableBanking.RENEWED_SESSION_ID)
                .doesNotContain(FakeEnableBanking.CURRENT_UID).doesNotContain(FakeEnableBanking.SAVINGS_UID)
                .doesNotContain("NL51XMPL").doesNotContain("NL24XMPL").doesNotContain("SHOP0000")
                .doesNotContain("Corner Grocery").doesNotContain("Example Energy").doesNotContain("Example Gym")
                .doesNotContain("Robin Example").doesNotContain("Sam Voorbeeld")
                .doesNotContain("12.34").doesNotContain("12,34").doesNotContain("1234.56").doesNotContain("1,234.56").doesNotContain("123456")
                .doesNotContain("192.0.2.10").doesNotContain("Fictional Browser")
                .doesNotContain("ZmljdGlvbmFs").doesNotContain("fictional-code");
    }
}
