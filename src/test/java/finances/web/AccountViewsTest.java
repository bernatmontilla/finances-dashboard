package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.AsOfBasis;
import finances.accounts.BalanceEntry;
import finances.accounts.BankBalanceInfo;
import finances.money.Freshness;
import finances.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

/** A bank balance is never shown as more than it is (hard rule 9). */
class AccountViewsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);
    private static final Instant FETCHED = Instant.parse("2026-11-26T12:00:00Z");
    private static final BalanceEntry ENTRY = new BalanceEntry(1, 1, Money.eur(123456), TODAY, FETCHED);

    private static AccountViews.BalanceView view(BankBalanceInfo bank) {
        return AccountViews.balance(ENTRY, TODAY, bank, ZoneOffset.UTC);
    }

    @Test
    void aBookedBalanceWithTheBanksOwnTimeNeedsNoNote() {
        assertThat(view(new BankBalanceInfo(Money.eur(123456), TODAY, "CLBD", FETCHED, AsOfBasis.BANK_TIME)).hasNote()).isFalse();
        assertThat(view(null).hasNote()).isFalse();
    }

    @Test
    void aTypeThatIsNotBookedSaysWhichTypeItIs() {
        AccountViews.BalanceView shown = view(new BankBalanceInfo(Money.eur(123456), TODAY, "ITAV", FETCHED, AsOfBasis.FETCHED));

        assertThat(shown.noteKey()).isEqualTo("bank.balance.note.other");
        assertThat(shown.noteArg()).isEqualTo("ITAV");
    }

    @Test
    void aBookedBalanceWithoutAnAgeFromTheBankSaysItWasFetched() {
        AccountViews.BalanceView shown = view(new BankBalanceInfo(Money.eur(123456), TODAY, "CLBD", FETCHED, AsOfBasis.FETCHED));

        assertThat(shown.noteKey()).isEqualTo("bank.balance.note.fetched");
        assertThat(shown.noteArg()).isEqualTo("2026-11-26 12:00");
    }

    @Test
    void aBankBalanceThatIsNotTheHeadlineEntryAddsNoNote() {
        assertThat(view(new BankBalanceInfo(Money.eur(1), TODAY, "ITAV", FETCHED, AsOfBasis.FETCHED)).hasNote()).isFalse();
    }

    // ----- the reason of a stale figure is only ever a key this application names (issue #19, review finding of #47)

    private static AccountViews.StaleView failed(Freshness.Reason reason, String detail) {
        return AccountViews.stale(new Freshness.Stale(reason, Duration.ZERO, detail));
    }

    @Test
    void aFailedSyncNamesItsReasonWhenTheCodeIsOneTheApplicationKnows() {
        AccountViews.StaleView shown = failed(Freshness.Reason.SYNC_FAILED, "rate_limited");

        assertThat(shown.key()).isEqualTo("stale.reason.sync_failed");
        assertThat(shown.detailKey()).isEqualTo("bank.reason.rate_limited");
    }

    @Test
    void aFailedSyncWithACodeThatIsNotKnownShowsAGeneralStaleTextNeverARawKey() {
        for (String made : new String[] {"made_up", "rate_limited.x", "", "../x", "<b>"}) {
            AccountViews.StaleView shown = failed(Freshness.Reason.SYNC_FAILED, made);

            assertThat(shown.key()).as(made).isEqualTo("stale.reason.unknown");
            assertThat(shown.hasDetail()).as(made).isFalse();
        }
    }

    @Test
    void aFailedSourceFetchWithACodeThatIsNotKnownShowsAGeneralStaleText() {
        assertThat(failed(Freshness.Reason.SOURCE_FAILED, "timeout").detailKey()).isEqualTo("aftertax.reason.timeout");
        assertThat(failed(Freshness.Reason.SOURCE_FAILED, "made_up").key()).isEqualTo("stale.reason.unknown");
        assertThat(failed(Freshness.Reason.SOURCE_FAILED, "rate_limited").key())
                .as("a bank code is not a source code").isEqualTo("stale.reason.unknown");
    }

    @Test
    void aMissingCodeStillMeansUnexpected() {
        assertThat(failed(Freshness.Reason.SYNC_FAILED, null).detailKey()).isEqualTo("bank.reason.unexpected");
        assertThat(failed(Freshness.Reason.SOURCE_FAILED, null).detailKey()).isEqualTo("aftertax.reason.unexpected");
    }

    @Test
    void everyKnownCodeAndTheGeneralTextHaveAMessage() {
        ResourceBundle messages = ResourceBundle.getBundle("messages");

        for (String code : AccountViews.BANK_REASON_CODES) {
            assertThat(messages.containsKey("bank.reason." + code)).as("bank.reason." + code).isTrue();
        }
        for (String code : AccountViews.SOURCE_REASON_CODES) {
            assertThat(messages.containsKey("aftertax.reason." + code)).as("aftertax.reason." + code).isTrue();
        }
        assertThat(messages.containsKey("stale.reason.unknown")).isTrue();
    }
}
