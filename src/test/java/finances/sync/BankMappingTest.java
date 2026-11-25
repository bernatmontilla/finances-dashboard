package finances.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finances.accounts.AsOfBasis;
import finances.accounts.DeliveredTransaction;
import finances.accounts.TransactionStatus;
import finances.outbound.BankApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Enable Banking's JSON as the application's own values, without any server (fictional data only). */
class BankMappingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate FETCH_DAY = LocalDate.of(2026, 11, 26);

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static DeliveredTransaction transaction(String text) {
        return BankMapping.transaction(json(text), FETCH_DAY);
    }

    // ----- amounts: whole cents from the unsigned decimal string, no floating point

    @ParameterizedTest
    @ValueSource(strings = {"12.34", "12.3", "12", "0.05", "1234.56", "12.340", "12.00", "999999999999.99"})
    void anUnsignedAmountWithAtMostTwoDecimalsBecomesCents(String text) {
        long expected = switch (text) {
            case "12.34", "12.340" -> 1234;
            case "12.3" -> 1230;
            case "12", "12.00" -> 1200;
            case "0.05" -> 5;
            case "1234.56" -> 123456;
            default -> 99999999999999L;
        };
        assertThat(BankMapping.cents(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12.345", "-1.00", "+1.00", "1,5", "1.234,56", "abc", "", " ", "12.", ".5", "1e3", "9999999999999999999"})
    void anythingElseIsRefusedAndNeverGuessed(String text) {
        assertThatThrownBy(() -> BankMapping.cents(text))
                .isInstanceOfSatisfying(BankApiException.class, e -> assertThat(e.reason()).isEqualTo(BankApiException.Reason.UNEXPECTED));
    }

    // ----- the day of the fetch is not a date the bank stated (issue #39)

    @Test
    void onlyAPendingItemWithoutAnyDateIsMarkedAsDatedByTheFetch() {
        String pendingWithoutDate = """
                {"transaction_amount":{"currency":"EUR","amount":"8.00"},"creditor":{"name":"Example Gym"},
                 "credit_debit_indicator":"DBIT","status":"PDNG"}
                """;
        DeliveredTransaction undated = transaction(pendingWithoutDate);
        assertThat(undated.datedByFetch()).isTrue();
        assertThat(undated.bookingDate()).isEqualTo(FETCH_DAY);

        for (String field : List.of("booking_date", "transaction_date", "value_date")) {
            DeliveredTransaction dated = transaction(pendingWithoutDate.replace("\"status\":\"PDNG\"", "\"status\":\"PDNG\",\"" + field + "\":\"2026-11-20\""));
            assertThat(dated.datedByFetch()).as(field).isFalse();
            assertThat(dated.bookingDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        }
        assertThat(transaction(withAmount("8.00", "DBIT").replace("\"booking_date\":\"2026-11-20\"", "\"x\":1")).datedByFetch())
                .as("a booked item is never marked").isFalse();
    }

    // ----- amount signs on a transaction (issue #39)

    private static String withAmount(String amount, String indicator) {
        return """
                {"entry_reference":"E-5","transaction_amount":{"currency":"EUR","amount":"%s"},
                 "creditor":{"name":"Corner Grocery"},"debtor":{"name":"Sam Voorbeeld"},
                 "credit_debit_indicator":"%s","status":"BOOK","booking_date":"2026-11-20"}
                """.formatted(amount, indicator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-12.34", "12.34"})
    void aDebitIsSpendingWhetherTheAmountIsUnsignedOrSignedAndAgreesWithTheIndicator(String amount) {
        assertThat(transaction(withAmount(amount, "DBIT")).amountCents()).isEqualTo(-1234);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+12.34", "12.34"})
    void aCreditIsIncomeWhetherTheAmountIsUnsignedOrSignedAndAgreesWithTheIndicator(String amount) {
        assertThat(transaction(withAmount(amount, "CRDT")).amountCents()).isEqualTo(1234);
    }

    @Test
    void aSignThatContradictsTheIndicatorIsRefusedAndNeverGuessed() {
        assertThatThrownBy(() -> transaction(withAmount("-12.34", "CRDT"))).isInstanceOf(BankMapping.Contradiction.class);
        assertThatThrownBy(() -> transaction(withAmount("+12.34", "DBIT"))).isInstanceOf(BankMapping.Contradiction.class);
        // The refusal carries nothing of the transaction.
        assertThatThrownBy(() -> transaction(withAmount("-12.34", "CRDT"))).hasMessage(null);
    }

    @Test
    void aSignedAmountThatIsNotANumberIsStillUnexpected() {
        assertThatThrownBy(() -> transaction(withAmount("--12.34", "DBIT")))
                .isInstanceOfSatisfying(BankApiException.class, e -> assertThat(e.reason()).isEqualTo(BankApiException.Reason.UNEXPECTED));
        assertThatThrownBy(() -> transaction(withAmount("-", "DBIT"))).isInstanceOf(BankApiException.class);
    }

    // ----- transactions

    @Test
    void aDebitIsNegativeAndTakesTheCreditorAsCounterparty() {
        DeliveredTransaction t = transaction("""
                {"entry_reference":"E-1","transaction_amount":{"currency":"EUR","amount":"12.34"},
                 "creditor":{"name":"Corner Grocery"},"creditor_account":{"iban":"NL10SHOP0000000101"},
                 "debtor":{"name":"Sam Voorbeeld"},"debtor_account":{"iban":"NL51XMPL0000000001"},
                 "credit_debit_indicator":"DBIT","status":"BOOK","booking_date":"2026-11-20","value_date":"2026-11-19",
                 "remittance_information":["Groceries","week 47"]}
                """);

        assertThat(t.amountCents()).isEqualTo(-1234);
        assertThat(t.counterpartyName()).isEqualTo("Corner Grocery");
        assertThat(t.counterpartyIban()).isEqualTo("NL10SHOP0000000101");
        assertThat(t.description()).isEqualTo("Groceries week 47");
        assertThat(t.status()).isEqualTo(TransactionStatus.BOOKED);
        assertThat(t.bookingDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(t.valueDate()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(t.reference()).isEqualTo("E-1");
        assertThat(t.currency()).isEqualTo("EUR");
        assertThat(t.raw()).contains("Corner Grocery");
    }

    @Test
    void aCreditIsPositiveAndTakesTheDebtorAsCounterparty() {
        DeliveredTransaction t = transaction("""
                {"transaction_amount":{"currency":"EUR","amount":"100.00"},"debtor":{"name":"Robin Example"},
                 "debtor_account":{"iban":"NL53SHOP0000000103"},"creditor_account":{"iban":"NL51XMPL0000000001"},
                 "credit_debit_indicator":"CRDT","status":"BOOK","booking_date":"2026-11-22"}
                """);

        assertThat(t.amountCents()).isEqualTo(10000);
        assertThat(t.counterpartyName()).isEqualTo("Robin Example");
        assertThat(t.counterpartyIban()).isEqualTo("NL53SHOP0000000103");
        assertThat(t.reference()).isNull();
        assertThat(t.description()).isEmpty();
    }

    @Test
    void theBanksOwnWordingIsTheDescriptionWhenThereIsNoRemittanceInformation() {
        DeliveredTransaction t = transaction("""
                {"transaction_amount":{"currency":"EUR","amount":"1.00"},"credit_debit_indicator":"DBIT","status":"BOOK",
                 "booking_date":"2026-11-22","bank_transaction_code":{"description":"Card payment"}}
                """);

        assertThat(t.description()).isEqualTo("Card payment");
    }

    @Test
    void aPendingTransactionIsDatedByItsTransactionDateThenItsValueDateThenTheDayOfTheFetch() {
        String head = "{\"transaction_amount\":{\"currency\":\"EUR\",\"amount\":\"8.00\"},\"credit_debit_indicator\":\"DBIT\",\"status\":\"PDNG\"";

        assertThat(transaction(head + ",\"transaction_date\":\"2026-11-24\",\"value_date\":\"2026-11-25\"}").bookingDate()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(transaction(head + ",\"value_date\":\"2026-11-25\"}").bookingDate()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(transaction(head + "}").bookingDate()).isEqualTo(FETCH_DAY);
        assertThat(transaction(head + "}").status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void onlyBookedAndPendingTransactionsAreCounted() {
        for (String status : List.of("HOLD", "SCHD", "CNCL", "RJCT", "OTHR")) {
            assertThat(transaction("{\"transaction_amount\":{\"currency\":\"EUR\",\"amount\":\"8.00\"},\"credit_debit_indicator\":\"DBIT\",\"status\":\""
                    + status + "\",\"booking_date\":\"2026-11-24\"}")).as(status).isNull();
        }
    }

    @Test
    void aTransactionThatIsNotShapedAsDocumentedFailsTheWholeFetch() {
        for (String broken : List.of(
                "{\"credit_debit_indicator\":\"DBIT\",\"status\":\"BOOK\",\"booking_date\":\"2026-11-24\"}",
                "{\"transaction_amount\":{\"currency\":\"EUR\",\"amount\":\"8.00\"},\"credit_debit_indicator\":\"XXXX\",\"status\":\"BOOK\",\"booking_date\":\"2026-11-24\"}",
                "{\"transaction_amount\":{\"currency\":\"EUR\",\"amount\":\"8.00\"},\"credit_debit_indicator\":\"DBIT\",\"status\":\"BOOK\",\"booking_date\":\"not a date\"}",
                "{\"transaction_amount\":{\"currency\":\"EUR\",\"amount\":\"8.00\"},\"credit_debit_indicator\":\"DBIT\"}")) {
            assertThatThrownBy(() -> transaction(broken)).as(broken).isInstanceOf(BankApiException.class);
        }
    }

    @Test
    void anotherCurrencyIsPassedOnToTheStoreWhichRefusesAndCountsIt() {
        DeliveredTransaction t = transaction("""
                {"transaction_amount":{"currency":"USD","amount":"8.00"},"credit_debit_indicator":"DBIT","status":"BOOK","booking_date":"2026-11-24"}
                """);

        assertThat(t.currency()).isEqualTo("USD");
    }

    // ----- balances

    private static BankMapping.ChosenBalance balance(String balances) {
        return BankMapping.balance(json(balances), Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void theBookedBalanceIsChosenByTypeNeverByName() {
        BankMapping.ChosenBalance chosen = balance("""
                {"balances":[
                 {"name":"Booked balance","balance_amount":{"currency":"EUR","amount":"100.00"},"balance_type":"CLAV"},
                 {"name":"Available","balance_amount":{"currency":"EUR","amount":"1134.56"},"balance_type":"ITAV"},
                 {"name":"Something","balance_amount":{"currency":"EUR","amount":"1234.56"},"balance_type":"CLBD","last_change_date_time":"2026-11-26T08:30:00Z"}]}
                """);

        assertThat(chosen.type()).isEqualTo("CLBD");
        assertThat(chosen.cents()).isEqualTo(123456);
        assertThat(chosen.basis()).isEqualTo(AsOfBasis.BANK_TIME);
        assertThat(chosen.asOf()).isEqualTo(Instant.parse("2026-11-26T08:30:00Z"));
    }

    @Test
    void aBalanceMayBeNegativeAnOverdraftOrALoanButATransactionAmountMayNot() {
        BankMapping.ChosenBalance overdrawn = balance("""
                {"balances":[{"balance_amount":{"currency":"EUR","amount":"-123.45"},"balance_type":"CLBD"}]}
                """);
        assertThat(overdrawn.cents()).isEqualTo(-12345);
        assertThat(BankMapping.signedCents("-0.05")).isEqualTo(-5);
        assertThat(BankMapping.signedCents("5")).isEqualTo(500);
        for (String bad : List.of("--1", "-", "1-", "+1", "-1.234", "")) {
            assertThatThrownBy(() -> BankMapping.signedCents(bad)).as(bad).isInstanceOf(BankApiException.class);
        }
    }

    @Test
    void theInDayBookedBalanceIsTheFallbackAndAnythingElseKeepsItsOwnType() {
        assertThat(balance("""
                {"balances":[{"balance_amount":{"currency":"EUR","amount":"5.00"},"balance_type":"ITAV"},
                             {"balance_amount":{"currency":"EUR","amount":"6.00"},"balance_type":"ITBD"}]}
                """).type()).isEqualTo("ITBD");
        BankMapping.ChosenBalance expected = balance("""
                {"balances":[{"balance_amount":{"currency":"EUR","amount":"7.00"},"balance_type":"XPCD"}]}
                """);
        assertThat(expected.type()).isEqualTo("XPCD");
        assertThat(expected.cents()).isEqualTo(700);
    }

    @Test
    void theAgeOfABalanceIsTheBanksTimestampElseItsReferenceDateElseOurFetchTimeAsFetched() {
        BankMapping.ChosenBalance byDate = balance("""
                {"balances":[{"balance_amount":{"currency":"EUR","amount":"5.00"},"balance_type":"CLBD","reference_date":"2026-11-25"}]}
                """);
        assertThat(byDate.basis()).isEqualTo(AsOfBasis.BANK_DATE);
        assertThat(byDate.asOf()).isNull();
        assertThat(byDate.date()).isEqualTo(LocalDate.of(2026, 11, 25));

        BankMapping.ChosenBalance unknown = balance("""
                {"balances":[{"balance_amount":{"currency":"EUR","amount":"5.00"},"balance_type":"CLBD"}]}
                """);
        assertThat(unknown.basis()).isEqualTo(AsOfBasis.FETCHED);
        assertThat(unknown.asOf()).isEqualTo(Instant.parse("2026-11-26T12:00:00Z"));
        assertThat(unknown.date()).isEqualTo(FETCH_DAY);
    }

    @Test
    void aBalanceInAnotherCurrencyIsNotUsedAndNoBalanceIsNull() {
        assertThat(balance("{\"balances\":[{\"balance_amount\":{\"currency\":\"USD\",\"amount\":\"5.00\"},\"balance_type\":\"CLBD\"}]}")).isNull();
        assertThat(balance("{\"balances\":[]}")).isNull();
        assertThat(balance("{}")).isNull();
    }

    // ----- sessions

    @Test
    void aSessionGivesItsIdItsEndAndItsAccountsWithoutThoseThatCannotBeFetched() {
        JsonNode session = json("""
                {"session_id":"S-1","access":{"valid_until":"2027-05-24T10:00:00.000000+00:00"},
                 "accounts":[{"uid":"U-1","identification_hash":"H-1","currency":"EUR","cash_account_type":"CACC","name":"Sam Voorbeeld",
                              "account_id":{"iban":"nl51 xmpl 0000 0000 01"}},
                             {"identification_hash":"H-2","currency":"EUR"},
                             {"uid":"U-3","currency":"XXX"}]}
                """);

        assertThat(BankMapping.sessionId(session)).isEqualTo("S-1");
        assertThat(BankMapping.validUntil(session)).isEqualTo(Instant.parse("2027-05-24T10:00:00Z"));
        List<BankMapping.Offered> accounts = BankMapping.accounts(session);
        assertThat(accounts).extracting(BankMapping.Offered::uid).containsExactly("U-1", "U-3");
        assertThat(accounts.getFirst().iban()).isEqualTo("NL51XMPL0000000001");
        assertThat(accounts.get(1).currency()).isEqualTo("XXX");
    }
}
