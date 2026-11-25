package finances.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finances.money.AmountFormatException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The CSV reader and the amount reader on their own: no database, no web. Fictional data only. */
class CsvParsingTest {

    private static List<List<String>> records(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        CsvParser.records(text, delimiter, records::add);
        return records;
    }

    @Test
    void quotedFieldsMayHoldTheDelimiterLineBreaksAndDoubledQuotes() {
        assertThat(records("a,\"b,c\",\"line 1\nline 2\",\"say \"\"hi\"\"\"\n", ','))
                .containsExactly(List.of("a", "b,c", "line 1\nline 2", "say \"hi\""));
    }

    @Test
    void everyKindOfLineEndAndEmptyTrailingLines() {
        assertThat(records("a;b\r\nc;d\re;f\ng;h\n\n\n", ';'))
                .containsExactly(List.of("a", "b"), List.of("c", "d"), List.of("e", "f"), List.of("g", "h"), List.of(""), List.of(""));
        assertThat(records("a;b", ';')).containsExactly(List.of("a", "b"));
        assertThat(records("", ';')).isEmpty();
        assertThat(records("a;;c;\n", ';')).containsExactly(List.of("a", "", "c", ""));
    }

    @Test
    void aQuoteInTheMiddleOfAnUnquotedFieldIsJustAQuote() {
        assertThat(records("5\" pipe;x\n", ';')).containsExactly(List.of("5\" pipe", "x"));
    }

    @Test
    void aByteOrderMarkIsDroppedAndTheEncodingIsDetected() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', ',', 'b'};
        assertThat(CsvFile.detectEncoding(bom)).isEqualTo(CsvMapping.UTF_8);
        assertThat(CsvFile.decode(bom, CsvMapping.UTF_8)).isEqualTo("a,b");
        assertThat(CsvFile.decode(bom, CsvMapping.WINDOWS_1252)).isEqualTo("a,b");

        assertThat(CsvFile.detectEncoding("Café".getBytes(StandardCharsets.UTF_8))).isEqualTo(CsvMapping.UTF_8);
        byte[] windows = "Café".getBytes(java.nio.charset.Charset.forName("windows-1252"));
        assertThat(CsvFile.detectEncoding(windows)).isEqualTo(CsvMapping.WINDOWS_1252);
        assertThat(CsvFile.decode(windows, CsvMapping.WINDOWS_1252)).isEqualTo("Café");
    }

    @Test
    void amountsAreReadWithTheFilesDecimalSeparator() {
        assertThat(Amounts.parse("1.234,56", ',').cents()).isEqualTo(123456);
        assertThat(Amounts.parse("-12,5", ',').cents()).isEqualTo(-1250);
        assertThat(Amounts.parse("1,234.56", '.').cents()).isEqualTo(123456);
        assertThat(Amounts.parse("+0.99", '.').cents()).isEqualTo(99);
        assertThat(Amounts.parse("1 234,00", ',').cents()).isEqualTo(123400);
        assertThat(Amounts.parse("12", '.').cents()).isEqualTo(1200);
        assertThat(Amounts.parse("1.234.567,00", ',').cents()).isEqualTo(123456700);
    }

    @Test
    void anAmountThatCouldMeanTwoThingsOrIsNotOneIsRefused() {
        assertRefused("1,234", ',', AmountFormatException.Reason.AMBIGUOUS);
        assertRefused("5.250", '.', AmountFormatException.Reason.AMBIGUOUS);
        assertRefused("1,23.4", '.', AmountFormatException.Reason.NOT_A_NUMBER);
        assertRefused("1.2.3,00", ',', AmountFormatException.Reason.NOT_A_NUMBER);
        assertRefused("12,34,56", ',', AmountFormatException.Reason.NOT_A_NUMBER);
        assertRefused("12,00 EUR", ',', AmountFormatException.Reason.NOT_A_NUMBER);
        assertRefused("€12,00", ',', AmountFormatException.Reason.NOT_A_NUMBER);
        assertRefused("12,", ',', AmountFormatException.Reason.NOT_A_NUMBER);
        assertRefused(",50", ',', AmountFormatException.Reason.NOT_A_NUMBER);
        assertRefused("12,345678", ',', AmountFormatException.Reason.TOO_MANY_DECIMALS);
        assertRefused("", ',', AmountFormatException.Reason.EMPTY);
        assertRefused("  ", ',', AmountFormatException.Reason.EMPTY);
        assertRefused("99999999999999999999,00", ',', AmountFormatException.Reason.TOO_LARGE);
    }

    private static void assertRefused(String cell, char decimal, AmountFormatException.Reason reason) {
        assertThatThrownBy(() -> Amounts.parse(cell, decimal))
                .isInstanceOfSatisfying(AmountFormatException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void everyAllowedDateFormatReadsAStrictDate() {
        for (String format : CsvMapping.DATE_FORMATS) {
            assertThat(CsvMapping.formatter(format)).as(format).isNotNull();
        }
        assertThat(java.time.LocalDate.parse("20260609", CsvMapping.formatter("yyyyMMdd"))).isEqualTo(java.time.LocalDate.of(2026, 6, 9));
        assertThat(java.time.LocalDate.parse("09.06.26", CsvMapping.formatter("dd.MM.yy"))).isEqualTo(java.time.LocalDate.of(2026, 6, 9));
        assertThatThrownBy(() -> java.time.LocalDate.parse("31-02-2026", CsvMapping.formatter("dd-MM-yyyy")))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    @Test
    void theDetectorReadsAFileWithoutAHeaderAndWithASemicolonAndNoGuessForWhatItCannotPlace() {
        CsvMapping mapping = CsvDetector.detect("2026-06-01;-12,34;Corner Grocery\n2026-06-02;-5,00;Example Gym\n"
                .getBytes(StandardCharsets.UTF_8));

        assertThat(mapping.header()).isFalse();
        assertThat(mapping.delimiter()).isEqualTo(';');
        assertThat(mapping.decimal()).isEqualTo(',');
        assertThat(mapping.date()).isZero();
        assertThat(mapping.amount()).isEqualTo(1);
        assertThat(mapping.name()).as("a text column is not guessed without a header").isEqualTo(-1);
    }

    @Test
    void theDetectorOfAnEmptyOrNonsenseFileProposesAnIncompleteMapping() {
        assertThat(CsvDetector.detect(new byte[0]).isComplete()).isFalse();
        assertThat(CsvDetector.detect("hello\nworld\n".getBytes(StandardCharsets.UTF_8)).isComplete()).isFalse();
    }
}
