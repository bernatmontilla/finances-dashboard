package finances.sync;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import finances.outbound.EnableBankingClient;

/** What pages show of the bank link: whether it is set up, the consents, their accounts and the last sync runs. Read only. */
@Service
public class BankStatus {

    private final BankStore store;
    private final EnableBankingClient client;
    private final String redirectUrl;

    BankStatus(BankStore store, EnableBankingClient client, @Value("${finances.sync.redirect-url:}") String redirectUrl) {
        this.store = store;
        this.client = client;
        this.redirectUrl = redirectUrl == null ? "" : redirectUrl.strip();
    }

    /** Whether the application id and key are in the data folder. */
    public boolean applicationConfigured() {
        return client.configured();
    }

    /** Whether the address the bank sends the browser back to is set ({@code finances.sync.redirect-url}). */
    public boolean redirectConfigured() {
        return !redirectUrl.isEmpty();
    }

    public List<Consent> consents() {
        return store.consents();
    }

    public Optional<Consent> consent(long id) {
        return store.consent(id);
    }

    public List<BankAccountLink> links(long consentId) {
        return store.links(consentId);
    }

    public Optional<BankAccountLink> linkOfAccount(long accountId) {
        return store.linkOfAccount(accountId);
    }

    public Optional<Consent> consentOfAccount(long accountId) {
        return store.linkOfAccount(accountId).flatMap(link -> store.consent(link.consentId()));
    }

    /** The newest runs first. */
    public List<SyncRunRow> runs(int limit) {
        return store.runs(limit);
    }

    public Optional<SyncRunRow> lastRun(long accountId) {
        return store.lastRunOf(accountId);
    }

    /** When the newest successful sync run of the account started; empty when none ever ended well. */
    public Optional<Instant> lastSuccessfulSync(long accountId) {
        return store.lastOkStartOf(accountId);
    }
}
