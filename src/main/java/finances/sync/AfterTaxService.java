package finances.sync;

import finances.money.Freshness;
import finances.outbound.AfterTaxClient;
import finances.outbound.AfterTaxException;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The after-tax source (issue #18, {@code docs/contracts/after-tax-source.md}): fetches it, keeps what is valid and what
 * happened, and tells the pages what is stored and how fresh it is. The rules of a valid answer are
 * {@link AfterTaxMapping}, the rules of freshness are {@code Freshness.afterTax}.
 *
 * <p>A fetch runs on its own thread with its own flag: it never shares the bank sync's lock and never waits for it, and the
 * daily run and "Sync now" only start it and go on. A start while one runs is skipped. The request is made with no database
 * transaction open; one short transaction then stores the value (when valid and not the one stored last) and the record of
 * the fetch. Nothing here logs more than the short outcome code (hard rule 11).
 */
@Service
public class AfterTaxService {

    private static final Logger LOG = LoggerFactory.getLogger(AfterTaxService.class);

    /** What started a fetch. */
    public enum Trigger {
        SCHEDULED("scheduled"), USER("user"), TEST("test");

        private final String code;

        Trigger(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** How a test ended. */
    public enum TestResult {
        NOT_CONFIGURED, OK, FAILED, BUSY
    }

    private final AfterTaxClient client;
    private final AfterTaxStore store;
    private final Freshness.Limits limits;
    private final Clock clock;
    private final TransactionTemplate inTransaction;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "after-tax-fetch");
        thread.setDaemon(true);
        return thread;
    });

    AfterTaxService(AfterTaxClient client, AfterTaxStore store, Freshness.Limits limits, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.client = client;
        this.store = store;
        this.limits = limits;
        this.clock = clock;
        this.inTransaction = new TransactionTemplate(transactionManager);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    // ----- fetching

    /** Starts a fetch and returns at once. Does nothing without a configured source, and when one is already running. */
    public void start(Trigger trigger) {
        if (!client.configured() || !busy.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    run(trigger);
                } catch (RuntimeException e) {
                    LOG.warn("An after-tax fetch failed unexpectedly ({}).", e.getClass().getSimpleName());
                } finally {
                    busy.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            busy.set(false);
        }
    }

    /** One fetch the owner asked for: waits for it (the exchange has a hard cap) and says how it ended. */
    public TestResult test() {
        if (!client.configured()) {
            return TestResult.NOT_CONFIGURED;
        }
        if (!busy.compareAndSet(false, true)) {
            return TestResult.BUSY;
        }
        Future<Boolean> fetch;
        try {
            fetch = executor.submit(() -> {
                try {
                    return run(Trigger.TEST);
                } finally {
                    busy.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            busy.set(false);
            return TestResult.FAILED;
        }
        try {
            return fetch.get(client.timeout().toMillis() + 5_000, TimeUnit.MILLISECONDS) ? TestResult.OK : TestResult.FAILED;
        } catch (TimeoutException | ExecutionException e) {
            return TestResult.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TestResult.FAILED;
        }
    }

    /** Whether no fetch is running (tests wait for it). */
    boolean idle() {
        return !busy.get();
    }

    private boolean run(Trigger trigger) {
        Instant started = Instant.now(clock);
        AfterTaxReason failure = null;
        Integer status = null;
        AfterTaxValue value = null;
        try {
            AfterTaxMapping.Result result = AfterTaxMapping.parse(client.fetch(), started);
            value = result.value();
            failure = result.refusal();
        } catch (AfterTaxException e) {
            failure = AfterTaxReason.of(e.reason());
            status = e.reason() == AfterTaxException.Reason.STATUS ? e.status() : null;
        } catch (RuntimeException e) {
            failure = AfterTaxReason.UNREACHABLE;
        }
        failure = record(trigger, started, value, failure, status);
        LOG.info("After-tax fetch finished: {}.", failure == null ? "ok" : failure.code());
        return failure == null;
    }

    /** Stores what the fetch brought and its record in one short transaction; returns the reason when storing failed too. */
    private AfterTaxReason record(Trigger trigger, Instant started, AfterTaxValue value, AfterTaxReason failure, Integer status) {
        try {
            inTransaction.executeWithoutResult(tx -> {
                if (value != null) {
                    Optional<AfterTaxValue> newest = store.newest();
                    if (newest.isEmpty() || !newest.get().sameAnswerAs(value)) {
                        store.insertValue(value);
                    }
                }
                store.insertFetch(row(trigger, started, failure, status));
            });
            return failure;
        } catch (RuntimeException e) {
            try {
                inTransaction.executeWithoutResult(tx -> store.insertFetch(row(trigger, started, AfterTaxReason.STORE_FAILED, null)));
            } catch (RuntimeException again) {
                LOG.warn("An after-tax fetch could not be recorded ({}).", again.getClass().getSimpleName());
            }
            return AfterTaxReason.STORE_FAILED;
        }
    }

    private AfterTaxFetchRow row(Trigger trigger, Instant started, AfterTaxReason failure, Integer status) {
        return new AfterTaxFetchRow(started, Instant.now(clock), trigger.code(), failure == null,
                failure == null ? null : failure.code(), failure == AfterTaxReason.STATUS ? status : null);
    }

    // ----- what the pages show

    /** Whether a source is configured ({@code finances.outbound.after-tax.url}). */
    public boolean configured() {
        return client.configured();
    }

    public boolean https() {
        return client.https();
    }

    public boolean tokenPresent() {
        return client.tokenPresent();
    }

    /** A token that travels over plain http to a host that is not this machine: shown as a warning, never refused. */
    public boolean tokenOverPlainHttp() {
        return client.configured() && !client.https() && client.tokenPresent() && !client.loopbackHost();
    }

    /** The newest value received, or empty when there is none yet. */
    public Optional<AfterTaxValue> newest() {
        return store.newest();
    }

    /** Every distinct value received, oldest first (later milestones chart it). */
    public List<AfterTaxValue> history() {
        return store.history();
    }

    /** The newest fetches, newest first. */
    public List<AfterTaxFetchRow> recentFetches(int limit) {
        return store.fetches(limit);
    }

    /** The reason code of the newest fetch when it failed, else null. */
    public String lastFailure() {
        return store.fetches(1).stream().filter(row -> !row.ok()).map(AfterTaxFetchRow::reason).findFirst().orElse(null);
    }

    /** Why the newest value is stale, or empty when it is fresh or there is none. */
    public Optional<Freshness.Stale> standing() {
        return standing(newest().orElse(null));
    }

    /** Why the given (newest) value is stale, or empty when it is fresh or null. */
    public Optional<Freshness.Stale> standing(AfterTaxValue newest) {
        return Freshness.afterTax(Instant.now(clock), newest == null ? null : newest.asOf(), lastFailure(), limits.afterTax());
    }
}
