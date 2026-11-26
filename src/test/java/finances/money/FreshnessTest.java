package finances.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The staleness rules, without a web layer or a database: each reason and each side of each limit. */
class FreshnessTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final Duration LIMIT = Duration.ofHours(36);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);

    // ----- bank accounts

    @Test
    void aBankAccountThatSyncedRecentlyAndWellIsFresh() {
        assertThat(Freshness.bank(NOW, NOW.minusSeconds(3600), null, false, LIMIT)).isEmpty();
    }

    @Test
    void aSyncExactlyOnTheLimitIsStillFreshAndOneSecondMoreIsStale() {
        assertThat(Freshness.bank(NOW, NOW.minus(LIMIT), null, false, LIMIT)).isEmpty();

        Freshness.Stale stale = Freshness.bank(NOW, NOW.minus(LIMIT).minusSeconds(1), null, false, LIMIT).orElseThrow();

        assertThat(stale.reason()).isEqualTo(Freshness.Reason.SYNC_OLD);
        assertThat(stale.age()).isEqualTo(LIMIT.plusSeconds(1));
    }

    @Test
    void aFailedLastSyncIsStaleHoweverRecentTheLastSuccessWas() {
        Freshness.Stale stale = Freshness.bank(NOW, NOW.minusSeconds(60), "rate_limited", false, LIMIT).orElseThrow();

        assertThat(stale.reason()).isEqualTo(Freshness.Reason.SYNC_FAILED);
        assertThat(stale.detail()).isEqualTo("rate_limited");
    }

    @Test
    void anEndedConsentIsStaleAndOutranksAFailedRunAndAnOldSync() {
        Freshness.Stale stale = Freshness.bank(NOW, NOW.minus(Duration.ofDays(30)), "consent_expired", true, LIMIT).orElseThrow();

        assertThat(stale.reason()).isEqualTo(Freshness.Reason.CONSENT_EXPIRED);
        assertThat(stale.detail()).isNull();
    }

    @Test
    void aFailedRunOutranksAnOldSync() {
        assertThat(Freshness.bank(NOW, NOW.minus(Duration.ofDays(30)), "unreachable", false, LIMIT).orElseThrow().reason())
                .isEqualTo(Freshness.Reason.SYNC_FAILED);
    }

    @Test
    void anAccountThatNeverSyncedWellIsNotStaleByAgeAlone() {
        assertThat(Freshness.bank(NOW, null, null, false, LIMIT)).isEmpty();
        assertThat(Freshness.bank(NOW, null, null, true, LIMIT).orElseThrow().reason()).isEqualTo(Freshness.Reason.CONSENT_EXPIRED);
        assertThat(Freshness.bank(NOW, null, "bank_error", false, LIMIT).orElseThrow().reason()).isEqualTo(Freshness.Reason.SYNC_FAILED);
    }

    // ----- accounts updated by hand

    @Test
    void aBalanceFromTodayAndOneOnTheLimitAreFresh() {
        assertThat(Freshness.byHand(TODAY, TODAY, 14)).isEmpty();
        assertThat(Freshness.byHand(TODAY.minusDays(14), TODAY, 14)).isEmpty();
    }

    @Test
    void aBalanceOneDayPastTheLimitIsStaleAndSaysHowManyDays() {
        Freshness.Stale stale = Freshness.byHand(TODAY.minusDays(15), TODAY, 14).orElseThrow();

        assertThat(stale.reason()).isEqualTo(Freshness.Reason.NOT_UPDATED);
        assertThat(stale.age()).isEqualTo(Duration.ofDays(15));
        assertThat(stale.detail()).isNull();
    }

    // ----- the limits

    @Test
    void theDefaultsAreThoseOfTheSpec() {
        Freshness.Limits limits = Freshness.Limits.defaults();

        assertThat(limits.bankSync()).isEqualTo(Duration.ofHours(36));
        assertThat(limits.currentDays()).isEqualTo(14);
        assertThat(limits.savingsDays()).isEqualTo(45);
        assertThat(limits.investmentDays()).isEqualTo(100);
        assertThat(limits.debtDays()).isEqualTo(100);
    }

    @Test
    void aLimitBelowOneIsRefusedAtStartInsteadOfMarkingEverythingStale() {
        assertThatThrownBy(() -> new Freshness.Limits(0, 14, 45, 100, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Freshness.Limits(36, 14, -1, 100, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    // ----- the after-tax source (issue #18)

    private static final Duration AFTER_TAX_LIMIT = Duration.ofHours(48);

    @Test
    void anAfterTaxValueExactlyOnTheLimitIsFreshAndOneSecondMoreIsStale() {
        assertThat(Freshness.afterTax(NOW, NOW.minus(AFTER_TAX_LIMIT), null, AFTER_TAX_LIMIT)).isEmpty();

        Freshness.Stale stale = Freshness.afterTax(NOW, NOW.minus(AFTER_TAX_LIMIT).minusSeconds(1), null, AFTER_TAX_LIMIT).orElseThrow();
        assertThat(stale.reason()).isEqualTo(Freshness.Reason.SOURCE_OLD);
        assertThat(stale.age()).isEqualTo(AFTER_TAX_LIMIT.plusSeconds(1));
        assertThat(stale.detail()).isNull();
    }

    @Test
    void aFailedFetchWinsOverAnOldValueAndNamesItsReason() {
        Freshness.Stale stale = Freshness.afterTax(NOW, NOW.minus(Duration.ofDays(30)), "timeout", AFTER_TAX_LIMIT).orElseThrow();

        assertThat(stale.reason()).isEqualTo(Freshness.Reason.SOURCE_FAILED);
        assertThat(stale.detail()).isEqualTo("timeout");
        assertThat(Freshness.afterTax(NOW, NOW.minusSeconds(60), "status", AFTER_TAX_LIMIT)).as("a recent value is stale all the same")
                .isPresent();
    }

    @Test
    void anAfterTaxValueFromTheToleratedFutureIsFreshAndNoValueYetIsNotStale() {
        assertThat(Freshness.afterTax(NOW, NOW.plusSeconds(300), null, AFTER_TAX_LIMIT)).isEmpty();
        assertThat(Freshness.afterTax(NOW, null, null, AFTER_TAX_LIMIT)).isEmpty();
        assertThat(Freshness.afterTax(NOW, null, "timeout", AFTER_TAX_LIMIT)).as("nothing to mark: the page says no value yet").isEmpty();
    }

    @Test
    void theAfterTaxLimitDefaultsToFortyEightHoursAndBelowOneIsRefusedAtStart() {
        assertThat(Freshness.Limits.defaults().afterTax()).isEqualTo(Duration.ofHours(48));
        assertThat(new Freshness.Limits(36, 14, 45, 100, 100).afterTax()).isEqualTo(Duration.ofHours(48));
        assertThatThrownBy(() -> new Freshness.Limits(36, 14, 45, 100, 100, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
