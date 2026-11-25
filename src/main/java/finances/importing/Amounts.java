package finances.importing;

import finances.money.AmountFormatException;
import finances.money.Money;

/**
 * An amount as a bank file writes it, read with the file's own decimal separator and then by the
 * rules of {@link Money#parse}. The other separator is only accepted as grouping of thousands in the
 * right place; anything else is refused, never repaired.
 */
final class Amounts {

    private Amounts() {
    }

    static Money parse(String cell, char decimal) {
        String text = cell.replace(" ", "").replace(" ", "").strip();
        if (text.isEmpty()) {
            throw new AmountFormatException(AmountFormatException.Reason.EMPTY);
        }
        String sign = "";
        if (text.charAt(0) == '-' || text.charAt(0) == '+') {
            sign = text.substring(0, 1);
            text = text.substring(1);
        }
        char grouping = decimal == ',' ? '.' : ',';
        int point = text.indexOf(decimal);
        if (point >= 0 && text.indexOf(decimal, point + 1) >= 0) {
            throw notANumber();
        }
        String whole = point < 0 ? text : text.substring(0, point);
        String fraction = point < 0 ? null : text.substring(point + 1);
        if (whole.indexOf(grouping) >= 0) {
            if (!whole.matches("[0-9]{1,3}(\\" + grouping + "[0-9]{3})+")) {
                throw notANumber();
            }
            whole = whole.replace(String.valueOf(grouping), "");
        }
        if (fraction != null && fraction.indexOf(grouping) >= 0) {
            throw notANumber();
        }
        // What is left is Money's own plain form: digits, and a point with digits after it.
        return Money.parse(sign + whole + (fraction == null ? "" : "." + fraction));
    }

    private static AmountFormatException notANumber() {
        return new AmountFormatException(AmountFormatException.Reason.NOT_A_NUMBER);
    }
}
