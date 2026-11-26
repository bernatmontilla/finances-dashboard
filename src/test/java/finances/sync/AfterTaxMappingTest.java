package finances.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What counts as a valid answer of the after-tax source ({@code docs/contracts/after-tax-source.md} and
 * {@code docs/decisions/18-after-tax-source.md}), without Spring and without a network. Fictional amounts only.
 */
class AfterTaxMappingTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

    private static AfterTaxMapping.Result parse(String json) {
        return AfterTaxMapping.parse(json.getBytes(StandardCharsets.UTF_8), NOW);
    }

    private static String body(String field, String value) {
        // The example of the contract with one field replaced; "REMOVE" leaves it out.
        StringBuilder json = new StringBuilder("{");
        String[][] fields = {
                {"contract_version", "1"}, {"as_of", "\"2026-11-26T06:00:00Z\""}, {"currency", "\"EUR\""},
                {"available_cents", "1234500"}, {"gross_balance_cents", "2000000"},
                {"reserved", "[{\"label\": \"VAT\", \"amount_cents\": 420000}, {\"label\": \"Income tax\", \"amount_cents\": 345500}]"}};
        for (String[] pair : fields) {
            String content = pair[0].equals(field) ? value : pair[1];
            if (!"REMOVE".equals(content)) {
                json.append(json.length() > 1 ? "," : "").append('"').append(pair[0]).append("\":").append(content);
            }
        }
        return json.append('}').toString();
    }

    private static void assertRefused(String json, AfterTaxReason reason) {
        AfterTaxMapping.Result result = parse(json);
        assertThat(result.valid()).as(json).isFalse();
        assertThat(result.refusal()).as(json).isEqualTo(reason);
    }

    // ----- the valid answer

    @Test
    void theExampleOfTheContractIsValidWithItsGrossAndItsReservedLines() {
        AfterTaxMapping.Result result = parse(FakeAfterTaxSource.valid(Instant.parse("2026-11-26T06:00:00Z")));

        assertThat(result.valid()).isTrue();
        AfterTaxValue value = result.value();
        assertThat(value.asOf()).isEqualTo(Instant.parse("2026-11-26T06:00:00Z"));
        assertThat(value.receivedAt()).isEqualTo(NOW);
        assertThat(value.availableCents()).isEqualTo(1_234_500);
        assertThat(value.grossBalanceCents()).isEqualTo(2_000_000L);
        assertThat(value.reserved()).containsExactly(new AfterTaxValue.Reserved("VAT", 420_000),
                new AfterTaxValue.Reserved("Income tax", 345_500));
        assertThat(value.mismatch()).isFalse();
    }

    @Test
    void onlyTheRequiredFieldsAreEnoughAndUnknownFieldsAreIgnored() {
        AfterTaxMapping.Result result = parse("""
                {"contract_version": 1, "as_of": "2026-11-26T06:00:00+01:00", "currency": "EUR", "available_cents": 5000,
                 "something_new": {"a": [1, 2]}}
                """);

        assertThat(result.valid()).isTrue();
        assertThat(result.value().asOf()).as("an offset is honoured").isEqualTo(Instant.parse("2026-11-26T05:00:00Z"));
        assertThat(result.value().grossBalanceCents()).isNull();
        assertThat(result.value().reserved()).isEmpty();
        assertThat(result.value().mismatch()).as("nothing to compare").isFalse();
    }

    @Test
    void anOptionalFieldThatIsNullCountsAsAbsent() {
        AfterTaxMapping.Result result = parse(body("gross_balance_cents", "null").replace("\"reserved\":[", "\"reserved\":null,\"x\":["));

        assertThat(result.valid()).isTrue();
        assertThat(result.value().grossBalanceCents()).isNull();
        assertThat(result.value().reserved()).isEmpty();
        assertThat(result.value().mismatch()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, -9_007_199_254_740_991L, 9_007_199_254_740_991L, -1_234_500})
    void anAvailableAmountMayBeNegativeAndAnyIntegerWithinTheSafeRange(long cents) {
        AfterTaxMapping.Result result = parse(body("available_cents", Long.toString(cents)));

        assertThat(result.valid()).isTrue();
        assertThat(result.value().availableCents()).isEqualTo(cents);
    }

    // ----- refused answers

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[1, 2]", "\"text\"", "42", "null", "{\"contract_version\": 1,", "{} {}"})
    void somethingThatIsNotAJsonObjectIsNotJson(String json) {
        assertRefused(json, AfterTaxReason.NOT_JSON);
    }

    @Test
    void aDuplicateKeyIsRefusedAsNotJsonBecauseWhichValueWinsWouldBeAGuess() {
        assertRefused("{\"contract_version\": 1, \"as_of\": \"2026-11-26T06:00:00Z\", \"currency\": \"EUR\", \"available_cents\": 1, "
                + "\"available_cents\": 2}", AfterTaxReason.NOT_JSON);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"contract_version", "as_of", "currency", "available_cents"})
    void aRequiredFieldThatIsMissingOrNullIsRefused(String field) {
        assertRefused(body(field, "REMOVE"), AfterTaxReason.MISSING_FIELD);
        assertRefused(body(field, "null"), AfterTaxReason.MISSING_FIELD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234.5", "\"1234\"", "1e3", "1E3", "1.0", "9007199254740992", "-9007199254740992", "99999999999999999999999",
            "true", "[1]", "{}"})
    void anAmountThatIsNotAnIntegerWithinTheSafeRangeIsRefusedNotRounded(String amount) {
        assertRefused(body("available_cents", amount), AfterTaxReason.NOT_AN_INTEGER);
        assertRefused(body("gross_balance_cents", amount), AfterTaxReason.NOT_AN_INTEGER);
        assertRefused(body("reserved", "[{\"label\": \"VAT\", \"amount_cents\": " + amount + "}]"), AfterTaxReason.NOT_AN_INTEGER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"USD\"", "\"eur\"", "\"EUR \"", "1", "[\"EUR\"]"})
    void onlyExactlyEurIsAccepted(String currency) {
        assertRefused(body("currency", currency), AfterTaxReason.CURRENCY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "0", "\"1\"", "1.0", "true", "[1]"})
    void onlyTheIntegerOneIsAContractVersionWeKnow(String version) {
        assertRefused(body("contract_version", version), AfterTaxReason.VERSION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"2026-11-26T06:00:00\"", "\"2026-11-26\"", "\"yesterday\"", "\"\"", "1795000000", "true", "{}"})
    void aMomentWithoutAnOffsetOrNotText_IsRefused(String asOf) {
        assertRefused(body("as_of", asOf), AfterTaxReason.INVALID_FIELD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "\"VAT\"", "[1]", "[{\"amount_cents\": 1}]", "[{\"label\": \"\", \"amount_cents\": 1}]",
            "[{\"label\": \"  \", \"amount_cents\": 1}]", "[{\"label\": 5, \"amount_cents\": 1}]", "[{\"label\": \"VAT\"}]",
            "[{\"label\": \"VAT\", \"amount_cents\": null}]", "[{\"label\": \"VAT\", \"amount_cents\": -1}]"})
    void aReservedLineWithoutALabelOrWithANegativeOrMissingAmountRefusesTheWholeAnswer(String reserved) {
        assertRefused(body("reserved", reserved), AfterTaxReason.INVALID_FIELD);
    }

    // ----- the moment

    @Test
    void anAsOfUpToFiveMinutesAheadIsAcceptedAndMoreIsRefused() {
        assertThat(parse(body("as_of", "\"2026-11-26T12:05:00Z\"")).valid()).as("exactly five minutes").isTrue();
        assertRefused(body("as_of", "\"2026-11-26T12:05:01Z\""), AfterTaxReason.AS_OF_IN_FUTURE);
        assertRefused(body("as_of", "\"2026-11-27T12:00:00Z\""), AfterTaxReason.AS_OF_IN_FUTURE);
        assertThat(parse(body("as_of", "\"2026-11-26T13:04:59+01:00\"")).valid()).as("the offset counts").isTrue();
    }

    // ----- the build-up

    @Test
    void aGrossThatDoesNotAddUpToTheAvailableAmountIsStoredWithAWarningMark() {
        AfterTaxValue value = parse(body("available_cents", "1234501")).value();

        assertThat(value.availableCents()).as("the available amount is what the source said").isEqualTo(1_234_501);
        assertThat(value.mismatch()).isTrue();
    }

    @Test
    void aSumThatOverflowsDoesNotAddUpEither() {
        String reserved = "[{\"label\": \"A\", \"amount_cents\": 9007199254740991}, {\"label\": \"B\", \"amount_cents\": 9007199254740991}]";

        assertThat(parse(body("reserved", reserved)).value().mismatch()).isTrue();
        assertThat(parse(body("reserved", "[]").replace("1234500", "2000000")).value().mismatch()).as("an empty list adds up to zero").isFalse();
    }

    @Test
    void theBodyIsNeverQuotedInAnythingThatIsKept() {
        AfterTaxMapping.Result result = parse("{\"label\": \"secret-123\" ");

        assertThat(result.refusal().code()).isEqualTo("not_json");
        assertThat(result.toString()).doesNotContain("secret-123");
    }
}
