package finances.importing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Fictional bank exports for the tests. The Windows-1252 one is built here, as bytes, because the
 * guard tests read every file under src/test/resources as UTF-8 text.
 */
public final class CsvFixtures {

    private CsvFixtures() {
    }

    /** Semicolon, decimal comma, separate debit and credit columns, Windows-1252, CRLF; two identical rows. */
    public static byte[] semicolonDebitCredit() {
        return ("Booking date;Counterparty;Counterparty IBAN;Description;Debit;Credit\r\n"
                + "03-06-2026;Corner Grocery;NL10SHOP0000000101;Groceries;12,34;\r\n"
                + "04-06-2026;Example Energy;NL80SHOP0000000102;Energy June;1.234,56;\r\n"
                + "05-06-2026;Example Client BV;NL53SHOP0000000103;Invoice 2026-017;;2.500,00\r\n"
                + "05-06-2026;Example Caf\u00e9;;Coffee;3,50;\r\n"
                + "05-06-2026;Example Caf\u00e9;;Coffee;3,50;\r\n").getBytes(Charset.forName("windows-1252"));
    }

    /** A file of src/test/resources/csv. */
    public static byte[] resource(String name) throws IOException {
        try (InputStream in = CsvFixtures.class.getResourceAsStream("/csv/" + name)) {
            return in.readAllBytes();
        }
    }
}
