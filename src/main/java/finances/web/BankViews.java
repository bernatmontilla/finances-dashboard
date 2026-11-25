package finances.web;

import finances.accounts.AsOfBasis;
import finances.accounts.BankBalanceInfo;
import finances.sync.BankAccountLink;
import finances.sync.BankStatus;
import finances.sync.Consent;
import finances.sync.SyncRunRow;
import finances.sync.SyncSummary;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What the pages show of the bank link. Texts are message keys; the templates hold none. Nothing here logs. */
final class BankViews {

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int RUNS_SHOWN = 20;

    private BankViews() {
    }

    record ConsentRow(long id, String bankName, String country, boolean ended, String validUntil, int linked, int waiting) {
    }

    record RunRow(String when, long accountId, String accountName, String triggerKey, String reasonKey, boolean ok, int added,
            int updated, int known, int refused, int pendingRemoved, int pendingKept) {
    }

    record SettingsView(boolean configured, boolean redirectConfigured, List<ConsentRow> consents, List<RunRow> runs) {
        public boolean hasConsents() {
            return !consents.isEmpty();
        }

        public boolean hasRuns() {
            return !runs.isEmpty();
        }
    }

    /** The bank facts of one account's page. */
    record AccountBankView(long consentId, String bankName, boolean ended, String validUntil, String balanceKey,
            String balanceType, String balanceAmount, String basisKey, String basisWhen, RunRow lastRun) {
        public boolean hasBalance() {
            return balanceKey != null;
        }

        public boolean hasLastRun() {
            return lastRun != null;
        }
    }

    static SettingsView settings(BankStatus status, ZoneId zone) {
        List<ConsentRow> consents = status.consents().stream().map(consent -> {
            List<BankAccountLink> links = status.links(consent.id());
            int linked = (int) links.stream().filter(BankAccountLink::linked).count();
            int waiting = (int) links.stream().filter(l -> !l.linked() && l.supported()).count();
            return new ConsentRow(consent.id(), consent.bankName(), consent.bankCountry(), consent.ended(),
                    consent.validUntil().atZone(zone).toLocalDate().toString(), linked, waiting);
        }).toList();
        List<RunRow> runs = status.runs(RUNS_SHOWN).stream().map(run -> run(run, zone)).toList();
        return new SettingsView(status.applicationConfigured(), status.redirectConfigured(), consents, runs);
    }

    static RunRow run(SyncRunRow run, ZoneId zone) {
        String trigger = switch (run.trigger()) {
            case SCHEDULED -> "bank.trigger.scheduled";
            case USER -> run.headersSent() ? "bank.trigger.user.online" : "bank.trigger.user.background";
            case LINK -> run.headersSent() ? "bank.trigger.link.online" : "bank.trigger.link.background";
        };
        return new RunRow(moment(run.startedAt(), zone), run.accountId(), run.accountName(), trigger,
                run.ok() ? null : "bank.reason." + (run.reason() == null ? "unexpected" : run.reason()), run.ok(), run.added(),
                run.updated(), run.known(), run.refused(), run.pendingRemoved(), run.pendingKept());
    }

    static AccountBankView account(Consent consent, BankBalanceInfo balance, SyncRunRow lastRun, ZoneId zone) {
        String balanceKey = null;
        String type = null;
        String amount = null;
        String basisKey = null;
        String basisWhen = null;
        if (balance != null) {
            balanceKey = balance.booked() ? "bank.balance.booked" : "bank.balance.other";
            type = balance.type();
            amount = balance.amount().format();
            if (balance.basis() == AsOfBasis.BANK_TIME) {
                basisKey = "bank.balance.asOfTime";
                basisWhen = moment(balance.asOf(), zone);
            } else if (balance.basis() == AsOfBasis.BANK_DATE) {
                basisKey = "bank.balance.asOfDate";
                basisWhen = balance.date().toString();
            } else {
                basisKey = "bank.balance.fetched";
                basisWhen = moment(balance.asOf(), zone);
            }
        }
        return new AccountBankView(consent.id(), consent.bankName(), consent.ended(),
                consent.validUntil().atZone(zone).toLocalDate().toString(), balanceKey, type, amount, basisKey, basisWhen,
                lastRun == null ? null : run(lastRun, zone));
    }

    /** The message for how a sync call ended, as a key. */
    static String summaryKey(SyncSummary summary) {
        return switch (summary.state()) {
            case BUSY -> "bank.sync.busy";
            case NOT_CONFIGURED -> "bank.sync.notConfigured";
            case DONE -> summary.failed() > 0 ? "bank.sync.failed" : summary.ok() == 0 ? "bank.sync.nothing" : "bank.sync.done";
        };
    }

    static String moment(Instant instant, ZoneId zone) {
        return LocalDateTime.ofInstant(instant, zone).format(MOMENT);
    }

    /**
     * The headers of the person using the page, for a fetch the user started (they make the bank treat it as an
     * online fetch, not a background one). They are the user's own details: kept for the one request, never stored
     * or logged.
     */
    static Map<String, String> userHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        put(headers, "Psu-Ip-Address", request.getRemoteAddr());
        put(headers, "Psu-User-Agent", request.getHeader("User-Agent"));
        put(headers, "Psu-Accept", request.getHeader("Accept"));
        put(headers, "Psu-Accept-Charset", request.getHeader("Accept-Charset"));
        put(headers, "Psu-Accept-Encoding", request.getHeader("Accept-Encoding"));
        put(headers, "Psu-Accept-Language", request.getHeader("Accept-Language"));
        put(headers, "Psu-Http-Method", request.getMethod().toUpperCase(Locale.ROOT));
        return headers;
    }

    private static void put(Map<String, String> headers, String name, String value) {
        // Header values must be a single line of printable characters, or the request is not made.
        if (value != null && !value.isBlank() && value.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) {
            headers.put(name, value.strip());
        }
    }

    /** The name of a country as the owner reads it ("NL" is Netherlands); the code itself when unknown. */
    static String countryName(String code) {
        String name = Locale.of("", code).getDisplayCountry(Locale.ENGLISH);
        return name.isBlank() ? code : name;
    }
}
