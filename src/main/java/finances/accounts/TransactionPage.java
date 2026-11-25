package finances.accounts;

import java.util.List;

/** One page of a listing, newest first. {@code page} counts from 0; there is no total, only whether more follow. */
public record TransactionPage(List<Transaction> items, int page, boolean hasNext) {

    public boolean hasPrevious() {
        return page > 0;
    }
}
