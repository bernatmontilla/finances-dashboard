package finances.sync;

import java.time.Instant;

/** One recorded fetch of the after-tax source. {@code reason} is a short code ({@link AfterTaxReason#code()}), null when it went well. */
public record AfterTaxFetchRow(Instant startedAt, Instant finishedAt, String trigger, boolean ok, String reason, Integer httpStatus) {
}
