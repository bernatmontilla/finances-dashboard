package finances.web;

import finances.accounts.AccountSummary;
import finances.accounts.BankBalanceInfo;
import finances.money.BuildUp;
import finances.money.Money;
import finances.sync.HeadlineService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the overview and the build-up pages show of the two headline numbers (issue #19). The numbers and their parts come
 * from {@link BuildUp} through {@link HeadlineService}; this class only turns them into message keys and formatted text (the
 * templates hold none). Nothing here adds the two numbers, and nothing logs.
 */
final class HeadlineViews {

    static final String PRIVATE = "private";
    static final String BUSINESS = "business";

    private HeadlineViews() {
    }

    /** A part that makes a headline stale: an account (named) or the after-tax value ({@code nameKey}), and why. */
    record StalePart(String name, String nameKey, AccountViews.StaleView stale) {
        public boolean hasName() {
            return name != null;
        }
    }

    /** A link in the line that says why a number is not shown: a message ({@code textKey}) or a name, and where it goes. */
    record Link(String textKey, String text, String href) {
        public boolean hasKey() {
            return textKey != null;
        }
    }

    /** Why a number is not shown: one message and where to go from there. */
    record Hidden(String textKey, List<Link> links) {
        public boolean hasLinks() {
            return !links.isEmpty();
        }
    }

    /**
     * One headline number: its amount (null when not shown, then {@code hidden} says why), the age of its oldest counted part, and
     * every stale part. {@code severalParts} says that the age is the oldest one's, not the only one's.
     */
    record Headline(String slug, String titleKey, String amount, boolean negative, AccountViews.BalanceView oldest,
            boolean severalParts, List<StalePart> stale, Hidden hidden) {

        public boolean shown() {
            return amount != null;
        }

        /** Not {@code isStale()}: a template reading {@code headline.stale} would get that boolean instead of the list. */
        public boolean hasStale() {
            return !stale.isEmpty();
        }
    }

    /** A counted or listed part: an account (linked) or the after-tax value ({@code nameKey}), with its figure and age. */
    record Line(String name, String nameKey, Long accountId, AccountViews.BalanceView balance, AccountViews.StaleView stale) {
        public boolean hasName() {
            return name != null;
        }

        public boolean linked() {
            return accountId != null;
        }
    }

    record ReservationLine(String label, String amount) {
    }

    /** A build-up page: the headline, every counted line, the reservations, and the total; and what is listed but not counted. */
    record Page(Headline headline, List<Line> lines, AfterTaxViews.ValueView afterTax, List<ReservationLine> reservations,
            String reserved, String total, List<Line> notCounted) {

        public boolean hasLines() {
            return !lines.isEmpty();
        }

        public boolean hasAfterTax() {
            return afterTax != null;
        }

        public boolean hasReservations() {
            return !reservations.isEmpty();
        }

        public boolean hasNotCounted() {
            return !notCounted.isEmpty();
        }
    }

    /** The two headlines, side by side, for the top of the overview. */
    static List<Headline> headlines(HeadlineService.Headlines both, LocalDate today, ZoneId zone, Map<Long, BankBalanceInfo> banks) {
        return List.of(privateHeadline(both.privateNumber(), today, zone, banks), businessHeadline(both.business(), today, zone));
    }

    static Headline privateHeadline(HeadlineService.PrivateNumber number, LocalDate today, ZoneId zone, Map<Long, BankBalanceInfo> banks) {
        BuildUp.Result result = number.result();
        if (!result.shown()) {
            return hidden(PRIVATE, "headline.private", hiddenFor(result, number.counted()));
        }
        List<StalePart> stale = result.stale().stream().map(line -> new StalePart(line.label(), null, AccountViews.stale(line.stale()))).toList();
        AccountSummary oldestAccount = number.counted().get(identityIndex(result.lines(), result.oldest()));
        AccountViews.BalanceView oldest = balance(oldestAccount, today, zone, banks);
        return new Headline(PRIVATE, "headline.private", result.total().format(), result.total().isNegative(), oldest,
                result.lines().size() > 1, stale, null);
    }

    static Headline businessHeadline(HeadlineService.BusinessNumber number, LocalDate today, ZoneId zone) {
        BuildUp.Result result = number.result();
        if (!result.shown()) {
            return hidden(BUSINESS, "headline.business", hiddenFor(result, List.of()));
        }
        List<StalePart> stale = result.stale().stream().map(line -> new StalePart(null, "buildup.afterTax", AccountViews.stale(line.stale()))).toList();
        return new Headline(BUSINESS, "headline.business", result.total().format(), result.total().isNegative(),
                AfterTaxViews.balance(number.afterTax(), today, zone), false, stale, null);
    }

    private static Headline hidden(String slug, String titleKey, Hidden hidden) {
        return new Headline(slug, titleKey, null, false, null, false, List.of(), hidden);
    }

    /** The one line that says why a number is not shown, with a way forward. Never an amount. */
    private static Hidden hiddenFor(BuildUp.Result result, List<AccountSummary> counted) {
        return switch (result.hidden()) {
            case NO_PRIVATE_CURRENT_ACCOUNT -> new Hidden("headline.hidden.noPrivate",
                    List.of(new Link("headline.hidden.addAccount", null, "/accounts/new")));
            case NO_BALANCE_YET -> new Hidden("headline.hidden.noBalance", counted.stream()
                    .filter(summary -> summary.latest().isEmpty())
                    .map(summary -> new Link(null, summary.account().name(), "/accounts/" + summary.account().id())).toList());
            case NO_SOURCE -> new Hidden("headline.hidden.noSource", List.of(new Link("headline.hidden.toSettings", null, "/settings")));
            case NO_VALUE_YET -> new Hidden("headline.hidden.noValue", List.of(new Link("headline.hidden.toSettings", null, "/settings")));
            case TOO_LARGE -> new Hidden("headline.hidden.tooLarge", List.of());
        };
    }

    private static int identityIndex(List<BuildUp.Line> lines, BuildUp.Line line) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i) == line) {
                return i;
            }
        }
        return 0;
    }

    private static AccountViews.BalanceView balance(AccountSummary summary, LocalDate today, ZoneId zone, Map<Long, BankBalanceInfo> banks) {
        return summary.latest().map(entry -> AccountViews.balance(entry, today, banks.get(summary.account().id()), zone)).orElse(null);
    }

    private static Line accountLine(AccountSummary summary, HeadlineService.Headlines both, LocalDate today, ZoneId zone,
            Map<Long, BankBalanceInfo> banks) {
        long id = summary.account().id();
        return new Line(summary.account().name(), null, id, balance(summary, today, zone, banks), AccountViews.stale(both.stale().get(id)));
    }

    /** The build-up page of one number; null when {@code slug} names neither. */
    static Page page(String slug, HeadlineService.Headlines both, LocalDate today, ZoneId zone, Map<Long, BankBalanceInfo> banks) {
        if (PRIVATE.equals(slug)) {
            Headline headline = privateHeadline(both.privateNumber(), today, zone, banks);
            BuildUp.Result result = both.privateNumber().result();
            List<Line> lines = result.shown()
                    ? both.privateNumber().counted().stream().map(summary -> accountLine(summary, both, today, zone, banks)).toList()
                    : List.of();
            return new Page(headline, lines, null, reservations(result), amount(result.reserved()), amount(result.total()), List.of());
        }
        if (BUSINESS.equals(slug)) {
            HeadlineService.BusinessNumber business = both.business();
            Headline headline = businessHeadline(business, today, zone);
            BuildUp.Result result = business.result();
            List<Line> lines = result.shown()
                    ? List.of(new Line(null, "buildup.afterTax", null, AfterTaxViews.balance(business.afterTax(), today, zone),
                            AccountViews.stale(result.lines().getFirst().stale())))
                    : List.of();
            AfterTaxViews.ValueView detail = result.shown() ? AfterTaxViews.value(business.afterTax(), today, zone) : null;
            List<Line> notCounted = new ArrayList<>();
            for (AccountSummary summary : business.notCounted()) {
                notCounted.add(accountLine(summary, both, today, zone, banks));
            }
            return new Page(headline, lines, detail, reservations(result), amount(result.reserved()), amount(result.total()), notCounted);
        }
        return null;
    }

    private static List<ReservationLine> reservations(BuildUp.Result result) {
        return result.reservations().stream().map(r -> new ReservationLine(r.label(), r.amount().format())).toList();
    }

    private static String amount(Money money) {
        return money == null ? null : money.format();
    }
}
