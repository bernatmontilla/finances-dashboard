package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.AsOfBasis;
import finances.accounts.BalanceEntry;
import finances.accounts.BankBalanceInfo;
import finances.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
}
