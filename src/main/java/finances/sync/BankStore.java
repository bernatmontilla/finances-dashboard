package finances.sync;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/** The SQL of the bank link: consents, the accounts they cover and the sync runs. No rules in here. */
@Component
class BankStore {

    private static final String CONSENT = """
            select id, bank_name, bank_country, required_psu_headers, created_at, valid_until, status, session_tag from bank_consent
            """;
    private static final String LINK = """
            select id, consent_id, uid, identification_hash, iban, holder_name, details, currency, cash_account_type, account_id, last_success_at
            from bank_account
            """;

    private final JdbcClient jdbc;

    BankStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ----- consents

    long insertConsent(String bankName, String country, List<String> requiredHeaders, Instant now, Instant validUntil, String sessionTag) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        insert into bank_consent (bank_name, bank_country, psu_type, required_psu_headers, created_at, valid_until, status, status_changed_at, session_tag)
                        values (?, ?, 'personal', ?, ?, ?, 'authorized', ?, ?)
                        """)
                .params(bankName, country, String.join(",", requiredHeaders), now.toString(), validUntil.toString(), now.toString(), sessionTag)
                .update(key);
        return key.getKey().longValue();
    }

    Optional<Consent> consent(long id) {
        return jdbc.sql(CONSENT + " where id = ?").param(id).query(BankStore::consent).optional();
    }

    List<Consent> consents() {
        return jdbc.sql(CONSENT + " order by bank_name, id").query(BankStore::consent).list();
    }

    void setStatus(long consentId, ConsentStatus status, Instant now) {
        jdbc.sql("update bank_consent set status = ?, status_changed_at = ? where id = ? and status <> ?")
                .params(status.code(), now.toString(), consentId, status.code())
                .update();
    }

    void deleteConsent(long consentId) {
        jdbc.sql("delete from bank_account where consent_id = ?").param(consentId).update();
        jdbc.sql("delete from bank_consent where id = ?").param(consentId).update();
    }

    // ----- the accounts of a consent

    void insertOffered(long consentId, String uid, String hash, String iban, String holder, String details, String currency,
            String cashAccountType) {
        jdbc.sql("""
                        insert into bank_account (consent_id, uid, identification_hash, iban, holder_name, details, currency, cash_account_type)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(consentId, uid, hash, iban, holder, details, currency, cashAccountType)
                .update();
    }

    List<BankAccountLink> links(long consentId) {
        return jdbc.sql(LINK + " where consent_id = ? order by id").param(consentId).query(BankStore::link).list();
    }

    Optional<BankAccountLink> link(long id) {
        return jdbc.sql(LINK + " where id = ?").param(id).query(BankStore::link).optional();
    }

    Optional<BankAccountLink> linkOfAccount(long accountId) {
        return jdbc.sql(LINK + " where account_id = ?").param(accountId).query(BankStore::link).optional();
    }

    /** A linked account of any consent with this stable key (the hash first, else the IBAN). */
    Optional<BankAccountLink> linkedWithKey(String hash, String iban) {
        if (hash != null) {
            Optional<BankAccountLink> byHash = jdbc.sql(LINK + " where account_id is not null and identification_hash = ?")
                    .param(hash).query(BankStore::link).optional();
            if (byHash.isPresent()) {
                return byHash;
            }
        }
        if (iban == null) {
            return Optional.empty();
        }
        return jdbc.sql(LINK + " where account_id is not null and iban = ? and (identification_hash is null or ? is null)")
                .params(iban, hash)
                .query(BankStore::link)
                .optional();
    }

    /** Whether the offered account was still waiting: false when a second request got there first. */
    boolean confirm(long linkId, long accountId, Instant now) {
        return jdbc.sql("update bank_account set account_id = ?, linked_at = ? where id = ? and account_id is null")
                .params(accountId, now.toString(), linkId)
                .update() == 1;
    }

    void deleteLink(long id) {
        jdbc.sql("delete from bank_account where id = ?").param(id).update();
    }

    /** Moves a linked account to the consent that renewed it: new consent, new uid, the details as the bank gives them now. */
    void rebind(long linkId, long consentId, String uid, String iban, String holder, String details, String cashAccountType) {
        jdbc.sql("""
                        update bank_account set consent_id = ?, uid = ?, iban = coalesce(?, iban), holder_name = ?, details = ?,
                            cash_account_type = ?
                        where id = ?
                        """)
                .params(consentId, uid, iban, holder, details, cashAccountType, linkId)
                .update();
    }

    int linkedCount(long consentId) {
        return jdbc.sql("select count(*) from bank_account where consent_id = ? and account_id is not null")
                .param(consentId)
                .query(Integer.class)
                .single();
    }

    /**
     * Records the success, but only when the link is still as it was when the fetch began (same consent, uid, account
     * and last success): a restore, an unlink or a renewal in the meantime means what was fetched belongs elsewhere.
     */
    boolean markSuccess(BankAccountLink before, Instant startedAt) {
        return jdbc.sql("""
                        update bank_account set last_success_at = ?
                        where id = ? and consent_id = ? and uid = ? and account_id is ? and last_success_at is ?
                        """)
                .params(startedAt.toString(), before.id(), before.consentId(), before.uid(), before.accountId(),
                        before.lastSuccessAt() == null ? null : before.lastSuccessAt().toString())
                .update() == 1;
    }

    // ----- runs

    void insertRun(long accountId, Instant started, Instant finished, SyncTrigger trigger, boolean headersSent, boolean ok,
            String reason, int added, int updated, int known, int refused, int pendingRemoved, int pendingKept) {
        jdbc.sql("""
                        insert into sync_run (account_id, started_at, finished_at, trigger, headers_sent, outcome, reason,
                            added, updated, known, refused, pending_removed, pending_kept)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(accountId, started.toString(), finished.toString(), trigger.code(), headersSent ? 1 : 0,
                        ok ? "ok" : "failed", reason, added, updated, known, refused, pendingRemoved, pendingKept)
                .update();
    }

    List<SyncRunRow> runs(int limit) {
        return jdbc.sql("""
                        select r.id, r.account_id, a.name as account_name, r.started_at, r.trigger, r.headers_sent, r.outcome, r.reason,
                               r.added, r.updated, r.known, r.refused, r.pending_removed, r.pending_kept
                        from sync_run r join account a on a.id = r.account_id
                        order by r.started_at desc, r.id desc limit ?
                        """)
                .param(limit)
                .query(BankStore::run)
                .list();
    }

    Optional<SyncRunRow> lastRunOf(long accountId) {
        return jdbc.sql("""
                        select r.id, r.account_id, a.name as account_name, r.started_at, r.trigger, r.headers_sent, r.outcome, r.reason,
                               r.added, r.updated, r.known, r.refused, r.pending_removed, r.pending_kept
                        from sync_run r join account a on a.id = r.account_id
                        where r.account_id = ? order by r.started_at desc, r.id desc limit 1
                        """)
                .param(accountId)
                .query(BankStore::run)
                .optional();
    }

    /** When the newest successful run of the account started, or empty when none ever ended well. */
    Optional<Instant> lastOkStartOf(long accountId) {
        return jdbc.sql("select started_at from sync_run where account_id = ? and outcome = 'ok' order by started_at desc, id desc limit 1")
                .param(accountId)
                .query(String.class)
                .optional()
                .map(Instant::parse);
    }

    // ----- rows

    private static Consent consent(ResultSet rs, int row) throws SQLException {
        String headers = rs.getString("required_psu_headers");
        return new Consent(
                rs.getLong("id"),
                rs.getString("bank_name"),
                rs.getString("bank_country"),
                headers.isBlank() ? List.of() : Arrays.asList(headers.split(",")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("valid_until")),
                ConsentStatus.parse(rs.getString("status")), rs.getString("session_tag"));
    }

    private static BankAccountLink link(ResultSet rs, int row) throws SQLException {
        long account = rs.getLong("account_id");
        boolean linked = !rs.wasNull();
        String lastSuccess = rs.getString("last_success_at");
        return new BankAccountLink(
                rs.getLong("id"),
                rs.getLong("consent_id"),
                rs.getString("uid"),
                rs.getString("identification_hash"),
                rs.getString("iban"),
                rs.getString("holder_name"),
                rs.getString("details"),
                rs.getString("currency"),
                rs.getString("cash_account_type"),
                linked ? account : null,
                lastSuccess == null ? null : Instant.parse(lastSuccess));
    }

    private static SyncRunRow run(ResultSet rs, int row) throws SQLException {
        return new SyncRunRow(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("account_name"),
                Instant.parse(rs.getString("started_at")),
                SyncTrigger.parse(rs.getString("trigger")),
                rs.getInt("headers_sent") == 1,
                "ok".equals(rs.getString("outcome")),
                rs.getString("reason"),
                rs.getInt("added"),
                rs.getInt("updated"),
                rs.getInt("known"),
                rs.getInt("refused"),
                rs.getInt("pending_removed"),
                rs.getInt("pending_kept"));
    }
}
