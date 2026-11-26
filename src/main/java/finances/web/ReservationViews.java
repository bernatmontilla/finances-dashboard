package finances.web;

import finances.accounts.Reservation;
import finances.accounts.ReservationCandidate;
import finances.accounts.ReservationPayment;
import finances.accounts.ReservationStanding;
import finances.accounts.ReservationSummary;
import finances.accounts.Scope;
import finances.accounts.Transaction;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

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

    /** A closed reservation, kept for history. */
    record ClosedRow(long id, String name, String amount, String dueDate, String closedOn, boolean yearly, Payment payment) {
        public boolean hasPayment() {
            return payment != null;
        }
    }

    /** The payment a closed reservation was closed against; {@code comparison} is "more", "less" or "same". */
    record Payment(String paid, String bookedOn, String who, String difference, String comparison) {
    }

    /** One line of the picker: a booked payment, marked when it is a likely match. */
    record Candidate(long id, String bookedOn, String who, String account, String paid, boolean likely) {
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

    static ClosedRow closedRow(Reservation r, Optional<ReservationPayment> payment, ZoneId zone) {
        return new ClosedRow(r.id(), r.name(), r.amount().format(), r.dueDate().toString(),
                r.closedAt().atZone(zone).toLocalDate().toString(), r.yearly(), payment.map(ReservationViews::payment).orElse(null));
    }

    static Payment payment(ReservationPayment p) {
        Transaction t = p.transaction();
        return new Payment(p.paid().format(), t.bookingDate().toString(), who(t), p.gap().format(), p.isMore() ? "more" : p.isLess() ? "less" : "same");
    }

    static Candidate candidate(ReservationCandidate c, ZoneId zone) {
        Transaction t = c.transaction();
        return new Candidate(t.id(), t.bookingDate().toString(), who(t), t.accountName(), c.paid().format(), c.likely());
    }

    private static String who(Transaction t) {
        String name = t.counterpartyName();
        return name != null && !name.isBlank() ? name : t.description() == null ? "" : t.description();
    }

    /** One scope of the reservations page: every open reservation and the closed ones. */
    static Group group(Scope scope, ReservationSummary summary, List<ReservationStanding> open, List<Reservation> closed, Function<Reservation, Optional<ReservationPayment>> payments,
            ZoneId zone, boolean sourceConfigured) {
        return new Group(scope.code(), AccountViews.scopeKey(scope), summary.hasOpen() ? summary.reserved().format() : null,
                open.stream().map(ReservationViews::row).toList(), closed.stream().map(r -> closedRow(r, payments.apply(r), zone)).toList(),
                scope == Scope.BUSINESS && !sourceConfigured);
    }

    /** One scope of the overview: the total reserved and the next ones due; nothing at all when no reservation is open. */
    static Group overview(Scope scope, ReservationSummary summary, boolean sourceConfigured) {
        return new Group(scope.code(), AccountViews.scopeKey(scope), summary.hasOpen() ? summary.reserved().format() : null,
                summary.next().stream().map(ReservationViews::row).toList(), List.of(), scope == Scope.BUSINESS && !sourceConfigured);
    }
}
