package finances.sync;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.Account;
import finances.accounts.AccountSummary;
import finances.accounts.Kind;
import finances.accounts.Scope;
import finances.money.Freshness;
import finances.money.Money;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What makes an account's figure stale, from the facts the bank sync stores (consent, recorded runs) and from the date of
 * a typed or imported balance. The clock is the harness's movable one, so every boundary is exact. Fictional data only.
 */
class FreshnessServiceTest {

    private static final Map<String, String> USER = Map.of("Psu-Ip-Address", "192.0.2.10", "Psu-User-Agent", "Fictional Browser");

    @TempDir
    Path folder;

    BankHarness h;
    FreshnessService freshness;

    @BeforeEach
    void open() throws Exception {
        h = new BankHarness(folder.resolve("data"), false);
        freshness = new FreshnessService(h.accounts, h.status, h.sessions, Freshness.Limits.defaults(), h.clock);
    }

    @AfterEach
    void close() throws Exception {
        h.close();
    }

    // ----- helpers

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

    private Optional<Freshness.Stale> standing(long accountId) {
        return Optional.ofNullable(freshness.stale(h.accounts.list(false)).get(accountId));
    }

    private void moveClockBy(Duration duration) {
        h.clock.set(h.clock.instant().plus(duration));
    }

    // ----- bank accounts

    @Test
    void aBankAccountThatJustSyncedIsFresh() {
        linkBothAsNewAccounts();

        assertThat(h.status.lastSuccessfulSync(everyday())).isPresent();
        assertThat(freshness.stale(h.accounts.list(false))).isEmpty();
    }

    @Test
    void theBankSyncLimitIsThirtySixHoursOlderThanThatIsStale() {
        linkBothAsNewAccounts();

        moveClockBy(Duration.ofHours(36));
        assertThat(standing(everyday())).as("exactly on the limit").isEmpty();

        moveClockBy(Duration.ofSeconds(1));
        Freshness.Stale stale = standing(everyday()).orElseThrow();
        assertThat(stale.reason()).isEqualTo(Freshness.Reason.SYNC_OLD);
        assertThat(stale.age()).isEqualTo(Duration.ofHours(36).plusSeconds(1));
    }

    @Test
    void aFailedLastSyncMarksTheAccountWithItsReasonAndASuccessClearsIt() {
        linkBothAsNewAccounts();
        h.bank.fail(FakeEnableBanking.CURRENT_UID, "error-rate-limit");
        moveClockBy(Duration.ofHours(24));
        h.sync.syncAll(SyncTrigger.SCHEDULED, Map.of());

        Freshness.Stale stale = standing(everyday()).orElseThrow();
        assertThat(stale.reason()).isEqualTo(Freshness.Reason.SYNC_FAILED);
        assertThat(stale.detail()).isEqualTo("rate_limited");
        assertThat(h.status.lastSuccessfulSync(everyday())).as("the earlier success is still the last one").isPresent();

        h.bank.stopFailing(FakeEnableBanking.CURRENT_UID);
        moveClockBy(Duration.ofHours(1));
        h.sync.syncAll(SyncTrigger.USER, USER);

        assertThat(standing(everyday())).isEmpty();
    }

    @Test
    void anEndedConsentMarksItsAccountsAsConsentExpired() {
        long consent = linkBothAsNewAccounts();
        h.store.setStatus(consent, ConsentStatus.EXPIRED, h.clock.instant());

        assertThat(standing(everyday()).orElseThrow().reason()).isEqualTo(Freshness.Reason.CONSENT_EXPIRED);
        assertThat(freshness.stale(h.accounts.list(false))).hasSize(2);
    }

    @Test
    void aRevokedConsentLooksTheSame() {
        long consent = linkBothAsNewAccounts();
        h.store.setStatus(consent, ConsentStatus.REVOKED, h.clock.instant());

        assertThat(standing(everyday()).orElseThrow().reason()).isEqualTo(Freshness.Reason.CONSENT_EXPIRED);
    }

    @Test
    void aConsentPastItsDateIsExpiredEvenBeforeAnySyncFoundOut() {
        long consent = linkBothAsNewAccounts();
        Instant validUntil = h.status.consent(consent).orElseThrow().validUntil();

        h.clock.set(validUntil.minusSeconds(1));
        assertThat(standing(everyday()).map(Freshness.Stale::reason)).as("a day before it ends only the old sync counts")
                .contains(Freshness.Reason.SYNC_OLD);

        h.clock.set(validUntil);
        assertThat(standing(everyday()).orElseThrow().reason()).isEqualTo(Freshness.Reason.CONSENT_EXPIRED);
    }

    @Test
    void aConsentWithoutItsSessionFileAfterARestoreIsExpired() throws Exception {
        long consent = linkBothAsNewAccounts();
        Files.delete(folder.resolve("data/secrets/enable-banking-session-" + consent));

        assertThat(standing(everyday()).orElseThrow().reason()).isEqualTo(Freshness.Reason.CONSENT_EXPIRED);
    }

    @Test
    void anArchivedAccountIsNotInTheListOfActiveOnes() {
        linkBothAsNewAccounts();
        long consent = h.store.consents().getFirst().id();
        h.store.setStatus(consent, ConsentStatus.EXPIRED, h.clock.instant());
        h.accounts.archive(everyday());

        List<AccountSummary> active = h.accounts.list(false);

        assertThat(active).extracting(s -> s.account().name()).containsExactly("Savings");
        assertThat(freshness.stale(active)).containsOnlyKeys(h.accountIdByIban("NL24XMPL0000000002"));
    }

    // ----- a consent that ends soon

    @Test
    void aConsentEndingWithinFourteenDaysIsAnnouncedBeforeItMakesAnythingStale() {
        long consent = linkBothAsNewAccounts();
        Instant validUntil = h.status.consent(consent).orElseThrow().validUntil();

        h.clock.set(validUntil.minus(Duration.ofDays(14)).minusSeconds(1));
        assertThat(freshness.expiringConsents()).as("one second more than fourteen days").isEmpty();

        h.clock.set(validUntil.minus(Duration.ofDays(14)));
        List<FreshnessService.ExpiringConsent> expiring = freshness.expiringConsents();
        assertThat(expiring).hasSize(1);
        assertThat(expiring.getFirst().consent().id()).isEqualTo(consent);
        assertThat(expiring.getFirst().daysLeft()).isEqualTo(14);

        h.clock.set(validUntil.minusSeconds(1));
        assertThat(freshness.expiringConsents()).as("still announced on its last day").hasSize(1);

        h.clock.set(validUntil);
        assertThat(freshness.expiringConsents()).as("once it has ended it is stale, not expiring").isEmpty();
    }

    @Test
    void aConsentWhoseAccountsAreAllArchivedIsNotAnnounced() {
        long consent = linkBothAsNewAccounts();
        h.accounts.archive(everyday());
        h.accounts.archive(h.accountIdByIban("NL24XMPL0000000002"));
        h.clock.set(h.status.consent(consent).orElseThrow().validUntil().minus(Duration.ofDays(3)));

        assertThat(freshness.expiringConsents()).isEmpty();
    }

    // ----- accounts updated by hand, per kind

    private Account manual(Kind kind) {
        return h.accounts.addManual("Account " + kind.code(), Scope.PRIVATE, kind, null);
    }

    private Optional<Freshness.Stale> afterDays(Account account, int days) {
        LocalDate today = LocalDate.now(h.clock);
        h.accounts.addBalance(account.id(), Money.eur(12345), today.minusDays(days));
        return standing(account.id());
    }

    @Test
    void aManualCurrentAccountIsStaleAfterFourteenDays() {
        assertThat(afterDays(manual(Kind.CURRENT), 14)).isEmpty();
        Account other = h.accounts.addManual("Other", Scope.PRIVATE, Kind.CURRENT, null);
        Freshness.Stale stale = afterDays(other, 15).orElseThrow();

        assertThat(stale.reason()).isEqualTo(Freshness.Reason.NOT_UPDATED);
        assertThat(stale.age()).isEqualTo(Duration.ofDays(15));
    }

    @Test
    void aSavingsAccountIsStaleAfterFortyFiveDays() {
        assertThat(afterDays(manual(Kind.SAVINGS), 45)).isEmpty();
        assertThat(afterDays(h.accounts.addManual("Other", Scope.PRIVATE, Kind.SAVINGS, null), 46)).isPresent();
    }

    @Test
    void anInvestmentAndADebtAreStaleAfterOneHundredDays() {
        assertThat(afterDays(manual(Kind.INVESTMENT), 100)).isEmpty();
        assertThat(afterDays(h.accounts.addManual("Other", Scope.PRIVATE, Kind.INVESTMENT, null), 101)).isPresent();
        assertThat(afterDays(manual(Kind.DEBT), 100)).isEmpty();
        assertThat(afterDays(h.accounts.addManual("Other debt", Scope.PRIVATE, Kind.DEBT, null), 101)).isPresent();
    }

    @Test
    void aCsvAccountFollowsTheSameLimitsAsAManualOne() {
        Account csv = h.accounts.addCsv("From a file", Scope.PRIVATE, Kind.CURRENT, null);

        assertThat(afterDays(csv, 15).orElseThrow().reason()).isEqualTo(Freshness.Reason.NOT_UPDATED);
    }

    @Test
    void theNewestBalanceDecidesNotAnOlderOne() {
        Account account = manual(Kind.CURRENT);
        LocalDate today = LocalDate.now(h.clock);
        h.accounts.addBalance(account.id(), Money.eur(100), today.minusDays(60));
        assertThat(standing(account.id())).isPresent();

        h.accounts.addBalance(account.id(), Money.eur(200), today.minusDays(2));

        assertThat(standing(account.id())).isEmpty();
    }

    @Test
    void anAccountWithNoBalanceYetIsNotStaleItHasNoFigure() {
        Account account = manual(Kind.CURRENT);

        assertThat(standing(account.id())).isEmpty();
    }

    @Test
    void theLimitsComeFromConfiguration() {
        FreshnessService strict = new FreshnessService(h.accounts, h.status, h.sessions, new Freshness.Limits(2, 3, 5, 7, 9), h.clock);
        Account account = manual(Kind.CURRENT);
        h.accounts.addBalance(account.id(), Money.eur(100), LocalDate.now(h.clock).minusDays(4));

        assertThat(strict.stale(h.accounts.list(false))).containsKey(account.id());
        assertThat(freshness.stale(h.accounts.list(false))).isEmpty();
    }
}
