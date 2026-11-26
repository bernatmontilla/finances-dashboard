package finances.sync;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a 200 answer of the after-tax source is worth: pure rules from the body to a value or one refusal code, as
 * {@code docs/contracts/after-tax-source.md} says (version 1) plus the decisions of {@code docs/decisions/18-after-tax-source.md}.
 * Nothing is repaired or guessed: an amount that is not a JSON integer within the safe range is refused, not rounded.
 * No exception or code here holds anything the source sent (hard rule 11).
 */
public final class AfterTaxMapping {

    /** The largest amount a JSON number carries exactly (2^53 - 1): beyond it the source's own tool may already have rounded. */
    static final long SAFE_LIMIT = 9_007_199_254_740_991L;
    /** How far ahead of our own clock an {@code as_of} may be (clock skew between two machines). */
    static final Duration FUTURE_ALLOWANCE = Duration.ofMinutes(5);

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final BigInteger SAFE = BigInteger.valueOf(SAFE_LIMIT);

    private AfterTaxMapping() {
    }

    /** A value, or the reason it was refused. */
    public record Result(AfterTaxValue value, AfterTaxReason refusal) {
        public boolean valid() {
            return value != null;
        }
    }

    private static final class Refused extends RuntimeException {
        private final transient AfterTaxReason reason;

        Refused(AfterTaxReason reason) {
            super(reason.code(), null, false, false);
            this.reason = reason;
        }
    }

    public static Result parse(byte[] body, Instant now) {
        try {
            return new Result(value(body, now), null);
        } catch (Refused refused) {
            return new Result(null, refused.reason);
        }
    }

    private static AfterTaxValue value(byte[] body, Instant now) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (RuntimeException e) {
            // A duplicate key, a trailing token and a number too long to read end here too; the message could quote the body.
            throw new Refused(AfterTaxReason.NOT_JSON);
        }
        if (root == null || !root.isObject()) {
            throw new Refused(AfterTaxReason.NOT_JSON);
        }
        JsonNode version = required(root, "contract_version");
        if (!(version.isIntegralNumber() && version.canConvertToInt() && version.intValue() == 1)) {
            throw new Refused(AfterTaxReason.VERSION);
        }
        JsonNode currency = required(root, "currency");
        if (!currency.isString() || !"EUR".equals(currency.stringValue())) {
            throw new Refused(AfterTaxReason.CURRENCY);
        }
        Instant asOf = asOf(required(root, "as_of"), now);
        long available = amount(required(root, "available_cents"));
        Long gross = optional(root, "gross_balance_cents") == null ? null : amount(optional(root, "gross_balance_cents"));
        JsonNode reservedNode = optional(root, "reserved");
        List<AfterTaxValue.Reserved> reserved = reservedNode == null ? null : reserved(reservedNode);
        boolean mismatch = gross != null && reserved != null && !adds(gross, reserved, available);
        return new AfterTaxValue(0, now, asOf, available, gross, mismatch, reserved == null ? List.of() : reserved);
    }

    private static JsonNode required(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new Refused(AfterTaxReason.MISSING_FIELD);
        }
        return node;
    }

    /** An optional field: absent and {@code null} are the same. */
    private static JsonNode optional(JsonNode root, String name) {
        JsonNode node = root.get(name);
        return node == null || node.isNull() || node.isMissingNode() ? null : node;
    }

    /** ISO 8601 with an offset or {@code Z}; a time without an offset is refused, and so is one more than five minutes ahead. */
    private static Instant asOf(JsonNode node, Instant now) {
        if (!node.isString()) {
            throw new Refused(AfterTaxReason.INVALID_FIELD);
        }
        Instant asOf;
        try {
            asOf = OffsetDateTime.parse(node.stringValue()).toInstant();
        } catch (DateTimeParseException e) {
            throw new Refused(AfterTaxReason.INVALID_FIELD);
        }
        if (asOf.isAfter(now.plus(FUTURE_ALLOWANCE))) {
            throw new Refused(AfterTaxReason.AS_OF_IN_FUTURE);
        }
        return asOf;
    }

    /** A JSON integer literal within the safe range: not a decimal, an exponent, a string or a boolean. */
    private static long amount(JsonNode node) {
        if (!node.isIntegralNumber()) {
            throw new Refused(AfterTaxReason.NOT_AN_INTEGER);
        }
        BigInteger value = node.bigIntegerValue();
        if (value.abs().compareTo(SAFE) > 0) {
            throw new Refused(AfterTaxReason.NOT_AN_INTEGER);
        }
        return value.longValueExact();
    }

    private static List<AfterTaxValue.Reserved> reserved(JsonNode node) {
        if (!node.isArray()) {
            throw new Refused(AfterTaxReason.INVALID_FIELD);
        }
        List<AfterTaxValue.Reserved> lines = new ArrayList<>();
        for (JsonNode entry : node) {
            if (!entry.isObject()) {
                throw new Refused(AfterTaxReason.INVALID_FIELD);
            }
            JsonNode label = entry.get("label");
            JsonNode cents = entry.get("amount_cents");
            if (label == null || !label.isString() || label.stringValue().isBlank() || cents == null || cents.isNull()) {
                throw new Refused(AfterTaxReason.INVALID_FIELD);
            }
            long amount = amount(cents);
            if (amount < 0) {
                throw new Refused(AfterTaxReason.INVALID_FIELD);
            }
            lines.add(new AfterTaxValue.Reserved(label.stringValue(), amount));
        }
        return lines;
    }

    /** Gross minus the reserved lines is the available amount; a sum that overflows does not add up. */
    private static boolean adds(long gross, List<AfterTaxValue.Reserved> reserved, long available) {
        try {
            long sum = 0;
            for (AfterTaxValue.Reserved line : reserved) {
                sum = Math.addExact(sum, line.amountCents());
            }
            return Math.subtractExact(gross, sum) == available;
        } catch (ArithmeticException e) {
            return false;
        }
    }
}
