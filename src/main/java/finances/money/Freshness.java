package finances.money;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * When a figure counts as stale (hard rule 9, PRODUCT invariant 4). Pure rules on plain values: no clock,
 * no database, no web layer. The caller works out the facts (the last successful sync, whether the last
 * run failed, whether the consent ended, the date of the balance) and gets back the one reason that
 * matters most, or nothing when the figure is fresh.
 *
 * <p>Every limit is "older than": exactly on the limit is still fresh, one second or one day more is stale.
 */
public final class Freshness {

    private Freshness() {
    }

    /** Why a figure is stale, most pressing first: the order is the order they are checked in. */
    public enum Reason {
        /** The consent at the bank has ended: nothing new comes in until it is renewed. */
        CONSENT_EXPIRED,
        /** The newest sync run of the account failed ({@link Stale#detail()} is its short reason code). */
        SYNC_FAILED,
        /** The last successful sync is older than the limit for a bank account. */
        SYNC_OLD,
        /** A balance typed in or imported is older than the limit for its kind of account. */
        NOT_UPDATED
    }

    /**
     * A stale figure. {@code age} is how long ago the last successful sync ({@link Reason#SYNC_OLD}) or the
     * balance ({@link Reason#NOT_UPDATED}, a whole number of days) was; zero for the other reasons.
     * {@code detail} is the short code of a failed run for {@link Reason#SYNC_FAILED}, else null.
     */
    public record Stale(Reason reason, Duration age, String detail) {
    }

    /**
     * How old a figure may get before it is stale: hours for a bank account (it is fetched every day), days
     * for what is updated by hand, per kind of account.
     */
    public record Limits(int bankSyncHours, int currentDays, int savingsDays, int investmentDays, int debtDays) {

        public static final int DEFAULT_BANK_SYNC_HOURS = 36;
        public static final int DEFAULT_CURRENT_DAYS = 14;
        public static final int DEFAULT_SAVINGS_DAYS = 45;
        public static final int DEFAULT_INVESTMENT_DAYS = 100;
        public static final int DEFAULT_DEBT_DAYS = 100;

        public Limits {
            if (bankSyncHours < 1 || currentDays < 1 || savingsDays < 1 || investmentDays < 1 || debtDays < 1) {
                throw new IllegalArgumentException("Every freshness limit under finances.freshness must be 1 or more.");
            }
        }

        public static Limits defaults() {
            return new Limits(DEFAULT_BANK_SYNC_HOURS, DEFAULT_CURRENT_DAYS, DEFAULT_SAVINGS_DAYS, DEFAULT_INVESTMENT_DAYS,
                    DEFAULT_DEBT_DAYS);
        }

        public Duration bankSync() {
            return Duration.ofHours(bankSyncHours);
        }
    }

    /**
     * A bank account. A consent that ended wins over a failed run, which wins over an old sync.
     *
     * @param lastSuccess when the newest successful sync started, or null when there has been none
     * @param lastFailure the short reason code of the newest run when it failed, else null
     * @param consentEnded whether the consent is expired, revoked, past its date or without its session
     */
    public static Optional<Stale> bank(Instant now, Instant lastSuccess, String lastFailure, boolean consentEnded, Duration limit) {
        if (consentEnded) {
            return Optional.of(new Stale(Reason.CONSENT_EXPIRED, Duration.ZERO, null));
        }
        if (lastFailure != null) {
            return Optional.of(new Stale(Reason.SYNC_FAILED, Duration.ZERO, lastFailure));
        }
        if (lastSuccess != null) {
            Duration age = Duration.between(lastSuccess, now);
            if (age.compareTo(limit) > 0) {
                return Optional.of(new Stale(Reason.SYNC_OLD, age, null));
            }
        }
        return Optional.empty();
    }

    /** An account updated by hand (a manual or CSV one): stale when its balance is more than {@code limitDays} days old. */
    public static Optional<Stale> byHand(LocalDate balanceDate, LocalDate today, int limitDays) {
        long days = ChronoUnit.DAYS.between(balanceDate, today);
        if (days > limitDays) {
            return Optional.of(new Stale(Reason.NOT_UPDATED, Duration.ofDays(days), null));
        }
        return Optional.empty();
    }
}
