package finances.web;

import finances.accounts.ClosedReservation;
import finances.accounts.PaymentCandidate;
import finances.accounts.Reservation;
import finances.accounts.ReservationStanding;
import finances.accounts.ReservationSummary;
import finances.accounts.Scope;
import java.time.ZoneId;
import java.util.List;

/**
 * What the reservations page and the overview show of reservations (issue #20): the figures come from
 * {@code ReservationService} and {@code finances.money.Reserving}; this class only formats them and picks message keys. It adds,
 * subtracts and compares no amounts, and nothing here logs.
 */
final class ReservationViews {

    private ReservationViews() {
    }

    /** An open reservation: its figures as of today. {@code perMonth} is null once it is due. */
    record Row(long id, String name, String amount, String dueDate, String reserved, String perMonth, boolean yearly, boolean overdue) {
        public boolean hasPerMonth() {
            return perMonth != null;
        }
    }

    /**
     * A closed reservation, kept for history. When it was closed against a payment (issue #51), {@code payment} says what was paid,
     * when and from which account, and {@code differenceKey} and {@code difference} say how it compares with the reserved amount
     * (the amount is without a sign; the key says more, less or exactly). Both are null without a payment.
     */
    record ClosedRow(long id, String name, String amount, String dueDate, String closedOn, boolean yearly, Payment payment,
            String differenceKey, String difference) {
        public boolean hasPayment() {
            return payment != null;
        }

        public boolean hasDifferenceAmount() {
            return difference != null;
        }
    }

    /** The payment a reservation was closed against. */
    record Payment(String paid, String date, String account, String counterparty) {
    }

    /** A payment the owner may close a reservation against; {@code likely} marks the ones close in amount and date. */
    record CandidateRow(long id, String date, String account, String counterparty, String description, String amount, boolean likely) {
    }

    /**
     * The reservations of one scope. {@code reserved} is the total reserved so far (null when nothing is open) and {@code noNumber}
     * says that there is no business number for them to reduce (no after-tax source).
     */
    record Group(String slug, String labelKey, String reserved, List<Row> open, List<ClosedRow> closed, boolean noNumber) {
        public boolean hasOpen() {
            return !open.isEmpty();
        }

        public boolean hasClosed() {
            return !closed.isEmpty();
        }

        public boolean isEmpty() {
            return open.isEmpty() && closed.isEmpty();
        }
    }

    static Row row(ReservationStanding standing) {
        Reservation r = standing.reservation();
        return new Row(r.id(), r.name(), r.amount().format(), r.dueDate().toString(), standing.reserved().format(),
                standing.perMonth().map(m -> m.format()).orElse(null), r.yearly(), standing.overdue());
    }

    static ClosedRow closedRow(ClosedReservation closed, ZoneId zone) {
        Reservation r = closed.reservation();
        String closedOn = r.closedAt().atZone(zone).toLocalDate().toString();
        if (!closed.hasPayment()) {
            return new ClosedRow(r.id(), r.name(), r.amount().format(), r.dueDate().toString(), closedOn, r.yearly(), null, null, null);
        }
        var payment = closed.payment();
        String key = closed.difference().isZero() ? "reservation.difference.exact"
                : closed.difference().isNegative() ? "reservation.difference.less" : "reservation.difference.more";
        String difference = closed.difference().isZero() ? null : closed.difference().isNegative()
                ? closed.difference().negate().format() : closed.difference().format();
        return new ClosedRow(r.id(), r.name(), r.amount().format(), r.dueDate().toString(), closedOn, r.yearly(),
                new Payment(closed.paid().format(), payment.bookingDate().toString(), payment.accountName(), payment.counterpartyName()),
                key, difference);
    }

    static CandidateRow candidateRow(PaymentCandidate candidate) {
        var t = candidate.transaction();
        return new CandidateRow(t.id(), t.bookingDate().toString(), t.accountName(), t.counterpartyName(), t.description(),
                t.amount().negate().format(), candidate.likely());
    }

    /** One scope of the reservations page: every open reservation and the closed ones. */
    static Group group(Scope scope, ReservationSummary summary, List<ReservationStanding> open, List<ClosedReservation> closed, ZoneId zone,
            boolean sourceConfigured) {
        return new Group(scope.code(), AccountViews.scopeKey(scope), summary.hasOpen() ? summary.reserved().format() : null,
                open.stream().map(ReservationViews::row).toList(), closed.stream().map(r -> closedRow(r, zone)).toList(),
                scope == Scope.BUSINESS && !sourceConfigured);
    }

    /** One scope of the overview: the total reserved and the next ones due; nothing at all when no reservation is open. */
    static Group overview(Scope scope, ReservationSummary summary, boolean sourceConfigured) {
        return new Group(scope.code(), AccountViews.scopeKey(scope), summary.hasOpen() ? summary.reserved().format() : null,
                summary.next().stream().map(ReservationViews::row).toList(), List.of(), scope == Scope.BUSINESS && !sourceConfigured);
    }
}
