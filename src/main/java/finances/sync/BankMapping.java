package finances.sync;

import finances.accounts.AsOfBasis;
import finances.accounts.DeliveredTransaction;
import finances.accounts.TransactionStatus;
import finances.outbound.BankApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Enable Banking's JSON as this application's own values (docs/decisions/6-enable-banking.md, section 5).
 * Anything that is not what the documentation describes throws {@code UNEXPECTED}, and the caller stores
 * nothing of that fetch: a figure is never guessed (hard rules 8 to 10). No value from the JSON reaches a
 * message.
 */
final class BankMapping {

    /** The balance types in the order the app prefers them: the booked ones first (CLBD, else ITBD). */
    private static final List<String> BALANCE_PREFERENCE =
            List.of("CLBD", "ITBD", "OPBD", "CLAV", "ITAV", "XPCD", "VALU", "FWAV", "OPAV", "INFO", "PRCD", "OTHR");
    /** An unsigned decimal with a dot and at most two decimals (more only when the extra ones are zeros). */
    private static final Pattern AMOUNT = Pattern.compile("([0-9]{1,15})(?:\\.([0-9]{1,2})0*)?");

    private BankMapping() {
    }

    record Offered(String uid, String hash, String iban, String holder, String details, String currency, String cashAccountType) {
    }

    /** The balance to store: its type, the day it is from and how its age is known. */
    record ChosenBalance(long cents, String type, LocalDate date, Instant asOf, AsOfBasis basis) {
    }

    /** An answer that is not shaped as documented: the fetch counts as failed and nothing of it is stored. */
    private static BankApiException unexpected() {
        return new BankApiException(BankApiException.Reason.UNEXPECTED, null, 0);
    }

    // ----- sessions

    static String sessionId(JsonNode session) {
        return required(session, "session_id");
    }

    static Instant validUntil(JsonNode session) {
        try {
            return OffsetDateTime.parse(required(session.path("access"), "valid_until")).toInstant();
        } catch (DateTimeParseException e) {
            throw unexpected();
        }
    }

    static List<Offered> accounts(JsonNode session) {
        List<Offered> accounts = new ArrayList<>();
        for (JsonNode node : session.path("accounts")) {
            String uid = text(node, "uid");
            if (uid == null) {
                continue; // a blocked or closed account has none: it cannot be fetched
            }
            String iban = text(node.path("account_id"), "iban");
            accounts.add(new Offered(uid, text(node, "identification_hash"), iban == null ? null : iban.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT),
                    text(node, "name"), text(node, "details"), text(node, "currency") == null ? "XXX" : text(node, "currency"),
                    text(node, "cash_account_type")));
        }
        return accounts;
    }

    // ----- balances

    /** The balance the app stores: the first of the preferred types in euro, with the age the bank states. */
    static ChosenBalance balance(JsonNode balances, Instant fetchedAt, ZoneId zone) {
        ChosenBalance best = null;
        int bestRank = Integer.MAX_VALUE;
        for (JsonNode node : balances.path("balances")) {
            String type = text(node, "balance_type");
            JsonNode amount = node.path("balance_amount");
            if (type == null || !"EUR".equals(text(amount, "currency"))) {
                continue; // only the euro exists (hard rule 8)
            }
            int rank = BALANCE_PREFERENCE.indexOf(type);
            rank = rank < 0 ? BALANCE_PREFERENCE.size() : rank;
            if (rank >= bestRank) {
                continue;
            }
            long cents = cents(required(amount, "amount"));
            String changed = text(node, "last_change_date_time");
            String reference = text(node, "reference_date");
            ChosenBalance chosen;
            try {
                if (changed != null) {
                    Instant at = OffsetDateTime.parse(changed).toInstant();
                    chosen = new ChosenBalance(cents, type, LocalDate.ofInstant(at, zone), at, AsOfBasis.BANK_TIME);
                } else if (reference != null) {
                    chosen = new ChosenBalance(cents, type, LocalDate.parse(reference), null, AsOfBasis.BANK_DATE);
                } else {
                    chosen = new ChosenBalance(cents, type, LocalDate.ofInstant(fetchedAt, zone), fetchedAt, AsOfBasis.FETCHED);
                }
            } catch (DateTimeParseException e) {
                throw unexpected();
            }
            best = chosen;
            bestRank = rank;
        }
        return best;
    }

    // ----- transactions

    /**
     * One transaction as the store takes it, or null for a status the product does not count (scheduled,
     * cancelled, rejected, on hold, other). The amount is unsigned and its direction is
     * {@code credit_debit_indicator}; spending is negative.
     */
    static DeliveredTransaction transaction(JsonNode node, LocalDate fetchDay) {
        String status = required(node, "status");
        TransactionStatus mapped = switch (status) {
            case "BOOK" -> TransactionStatus.BOOKED;
            case "PDNG" -> TransactionStatus.PENDING;
            default -> null;
        };
        if (mapped == null) {
            return null;
        }
        JsonNode amount = node.path("transaction_amount");
        long cents = cents(required(amount, "amount"));
        String direction = required(node, "credit_debit_indicator");
        boolean debit;
        if ("DBIT".equals(direction)) {
            debit = true;
        } else if ("CRDT".equals(direction)) {
            debit = false;
        } else {
            throw unexpected();
        }
        JsonNode counterparty = node.path(debit ? "creditor" : "debtor");
        JsonNode counterpartyAccount = node.path(debit ? "creditor_account" : "debtor_account");
        LocalDate booking;
        LocalDate value;
        try {
            LocalDate bookingDate = date(node, "booking_date");
            LocalDate valueDate = date(node, "value_date");
            LocalDate transactionDate = date(node, "transaction_date");
            // A pending transaction has no booking date: it is dated by when it happened, else by its value date,
            // else by the day of the fetch (docs/decisions/10-bank-sync.md).
            booking = mapped == TransactionStatus.BOOKED
                    ? firstNonNull(bookingDate, valueDate, transactionDate, fetchDay)
                    : firstNonNull(bookingDate, transactionDate, valueDate, fetchDay);
            value = valueDate;
        } catch (DateTimeParseException e) {
            throw unexpected();
        }
        String reference = text(node, "entry_reference");
        return new DeliveredTransaction(booking, value, debit ? -cents : cents, required(amount, "currency"),
                text(counterparty, "name"), text(counterpartyAccount, "iban"), description(node), mapped, reference, node.toString());
    }

    private static String description(JsonNode node) {
        List<String> parts = new ArrayList<>();
        for (JsonNode line : node.path("remittance_information")) {
            if (line.isString() && !line.asString().isBlank()) {
                parts.add(line.asString().strip());
            }
        }
        if (!parts.isEmpty()) {
            return String.join(" ", parts);
        }
        String bankText = text(node.path("bank_transaction_code"), "description");
        return bankText == null ? "" : bankText;
    }

    // ----- values

    /** Whole cents from the bank's unsigned decimal string, without any floating point (hard rule 8). */
    static long cents(String amount) {
        var matcher = AMOUNT.matcher(amount.strip());
        if (!matcher.matches()) {
            throw unexpected();
        }
        long euros = Long.parseLong(matcher.group(1));
        String decimals = matcher.group(2) == null ? "00" : (matcher.group(2) + "0").substring(0, 2);
        try {
            return Math.addExact(Math.multiplyExact(euros, 100L), Long.parseLong(decimals));
        } catch (ArithmeticException e) {
            throw unexpected();
        }
    }

    private static LocalDate date(JsonNode node, String field) {
        String text = text(node, field);
        return text == null ? null : LocalDate.parse(text);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        throw unexpected();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString().strip() : null;
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw unexpected();
        }
        return value;
    }
}
