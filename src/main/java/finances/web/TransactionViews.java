package finances.web;

import finances.accounts.Transaction;
import finances.accounts.TransactionPage;
import java.util.List;

/**
 * What a page shows of transactions: amounts formatted by {@code Money}, the texts are message
 * keys in the templates. A transaction always shows its booking date (hard rule 9).
 */
final class TransactionViews {

    private TransactionViews() {
    }

    record Row(long id, long accountId, String accountName, String date, String counterparty, String description,
            String amount, boolean pending, boolean noLongerReported, String note) {

        public boolean hasCounterparty() {
            return counterparty != null;
        }

        public boolean hasDescription() {
            return !description.isEmpty();
        }

        public boolean hasNote() {
            return note != null;
        }
    }

    record PageView(List<Row> rows, int page, boolean hasPrevious, boolean hasNext, int previous, int next) {

        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    /** The page number of a query parameter; anything that is not a number is the first page. */
    static int pageNumber(String text) {
        try {
            return Math.max(0, Integer.parseInt(text.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static Row row(Transaction t) {
        return new Row(t.id(), t.accountId(), t.accountName(), t.bookingDate().toString(), t.counterpartyName(),
                t.description(), t.amount().format(), t.pending(), t.noLongerReported(), t.note());
    }

    static PageView page(TransactionPage page) {
        return new PageView(page.items().stream().map(TransactionViews::row).toList(), page.page(), page.hasPrevious(),
                page.hasNext(), page.page() - 1, page.page() + 1);
    }
}
