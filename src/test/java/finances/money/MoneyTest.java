package finances.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            1234,56    | 123456
            1.234,56   | 123456
            1234.56    | 123456
            1,234.56   | 123456
            12,5       | 1250
            12.5       | 1250
            12         | 1200
            0,05       | 5
            0.99       | 99
            1.234.567  | 123456700
            1,234,567  | 123456700
            -12,34     | -1234
            +12,34     | 1234
            -0         | 0
            007        | 700
              100.00   | 10000
            """)
    void parsesTypedAmounts(String text, long cents) {
        assertThat(Money.parse(text)).isEqualTo(Money.eur(cents));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            1,234  | AMBIGUOUS
            1.234  | AMBIGUOUS
            12,345 | AMBIGUOUS
            123.456| AMBIGUOUS
            1234,567 | TOO_MANY_DECIMALS
            0,500    | TOO_MANY_DECIMALS
            1,2345   | TOO_MANY_DECIMALS
            1.234,567 | TOO_MANY_DECIMALS
            abc      | NOT_A_NUMBER
            12 EUR   | NOT_A_NUMBER
            1 234    | NOT_A_NUMBER
            12,      | NOT_A_NUMBER
            .5       | NOT_A_NUMBER
            -        | NOT_A_NUMBER
            1.2.3    | NOT_A_NUMBER
            1,23,456 | NOT_A_NUMBER
            1.5,3    | NOT_A_NUMBER
            1,234.567.89 | NOT_A_NUMBER
            1e5      | NOT_A_NUMBER
            """)
    void refusesAmbiguousOrMalformedInputWithAReason(String text, AmountFormatException.Reason reason) {
        assertThatThrownBy(() -> Money.parse(text))
                .isInstanceOf(AmountFormatException.class)
                .satisfies(e -> assertThat(((AmountFormatException) e).reason()).isEqualTo(reason));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void refusesEmptyInput(String text) {
        assertThatThrownBy(() -> Money.parse(text))
                .isInstanceOf(AmountFormatException.class)
                .satisfies(e -> assertThat(((AmountFormatException) e).reason()).isEqualTo(AmountFormatException.Reason.EMPTY));
        assertThatThrownBy(() -> Money.parse(null)).isInstanceOf(AmountFormatException.class);
    }

    @Test
    void refusesAnAmountTooLargeForLongCents() {
        assertThatThrownBy(() -> Money.parse("99999999999999999999"))
                .isInstanceOf(AmountFormatException.class)
                .satisfies(e -> assertThat(((AmountFormatException) e).reason()).isEqualTo(AmountFormatException.Reason.TOO_LARGE));
        assertThatThrownBy(() -> Money.parse("92233720368547758,08")).isInstanceOf(AmountFormatException.class);
    }

    @Test
    void theErrorNeverRepeatsTheTypedText() {
        assertThatThrownBy(() -> Money.parse("1,234")).hasMessageNotContaining("1,234");
    }

    @Test
    void formatsWithGroupingTwoDecimalsAndTheEuroSign() {
        assertThat(Money.eur(123456).format()).isEqualTo("€1,234.56");
        assertThat(Money.eur(5).format()).isEqualTo("€0.05");
        assertThat(Money.eur(0).format()).isEqualTo("€0.00");
        assertThat(Money.eur(100000000).format()).isEqualTo("€1,000,000.00");
        assertThat(Money.eur(-1234).format()).isEqualTo("-€12.34");
        assertThat(Money.eur(-99).format()).isEqualTo("-€0.99");
    }

    @Test
    void formatAndParseRoundTrip() {
        for (long cents : new long[] {0, 1, 99, 100, 123456, -123456, 100000000, Long.MAX_VALUE}) {
            Money money = Money.eur(cents);
            assertThat(Money.parse(money.format().replace("€", ""))).isEqualTo(money);
        }
    }

    @Test
    void addsSubtractsAndNegatesInIntegerCents() {
        Money a = Money.eur(1000);
        Money b = Money.eur(-250);
        assertThat(a.plus(b)).isEqualTo(Money.eur(750));
        assertThat(a.minus(b)).isEqualTo(Money.eur(1250));
        assertThat(a.negate()).isEqualTo(Money.eur(-1000));
        assertThat(Money.eur(10).plus(Money.eur(20)).cents()).isEqualTo(30);
        assertThat(b.isNegative()).isTrue();
        assertThat(a.isNegative()).isFalse();
        assertThat(Money.zero().isZero()).isTrue();
    }

    @Test
    void sumsAListIncludingAnEmptyOneAndNegativeAmounts() {
        assertThat(Money.sum(List.of(Money.eur(1000), Money.eur(-400), Money.eur(5)))).isEqualTo(Money.eur(605));
        assertThat(Money.sum(List.of())).isEqualTo(Money.zero());
    }

    @Test
    void doesNotLetASumOverflowSilently() {
        assertThatThrownBy(() -> Money.eur(Long.MAX_VALUE).plus(Money.eur(1))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void refusesAnotherCurrency() {
        assertThatThrownBy(() -> new Money(100, "USD")).isInstanceOf(UnsupportedCurrencyException.class);
        assertThatThrownBy(() -> Money.of(100, "usd")).isInstanceOf(UnsupportedCurrencyException.class);
        assertThatThrownBy(() -> Money.of(100, null)).isInstanceOf(UnsupportedCurrencyException.class);
        assertThat(Money.of(100, "EUR")).isEqualTo(Money.eur(100));
        assertThat(Money.eur(100).currency()).isEqualTo("EUR");
    }
}
