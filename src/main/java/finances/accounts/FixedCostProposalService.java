package finances.accounts;

import finances.accounts.FixedCostException.Reason;
import finances.accounts.RepeatingCharges.Charge;
import finances.accounts.RepeatingCharges.Run;
import finances.money.Money;
import finances.money.Rhythm;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

/**
 * Fixed cost proposals (issue #71): repeating booked spending that looks like a fixed cost. {@link #detect()} reads the booked spending
 * of every account (archived ones included) as a stream in date order, groups it by scope and counterparty (the IBAN when there is
 * one, else the name as {@link TransactionService#normalise}), asks {@link RepeatingCharges} whether a group repeats, and keeps the
 * result in {@code fixed_cost_proposal}. A proposal changes no number and no flag; only the owner's confirmation, through the add form
 * and {@link FixedCostService#create}, makes a fixed cost.
 *
 * <p>This is the only code that writes {@code fixed_cost_proposal}, {@code fixed_cost_proposal_charge} and
 * {@code fixed_cost_rejection}. Proposals are derived data and may be replaced; a rejection is the owner's and is deleted only by
 * {@link #undoRejection}, never by a sync, an import, detection or a migration (hard rule 10). A counterparty that already has a fixed
 * cost of the scope (running, paused or ended) is never proposed, and neither is one the owner rejected at about the same amount; both
 * are checked again when the proposals are read, so a fixed cost created a moment ago hides its proposal at once. Nothing here logs:
 * names, IBANs and amounts are the owner's data (hard rule 11).
 */
@Service
public class FixedCostProposalService {

    /** How far back detection reads: enough for a yearly run of two charges that still runs, and it bounds the work. */
    static final int LOOK_BACK_MONTHS = 25;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AccountService accounts;
    private final TransactionTemplate inTransaction;

    public FixedCostProposalService(JdbcClient jdbc, Clock clock, AccountService accounts, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.accounts = accounts;
        this.inTransaction = new TransactionTemplate(transactions);
    }

    // ----- reading

    /** The open proposals, by scope and name: stored ones minus what has a fixed cost or was rejected since. */
    public List<FixedCostProposal> proposals() {
        List<Owned> owned = fixedCostCounterparties();
        List<FixedCostRejection> rejections = rejections();
        List<FixedCostProposal> result = new ArrayList<>();
        for (FixedCostProposal proposal : stored()) {
            if (!blocked(proposal, owned, rejections)) {
                result.add(proposal);
            }
        }
        result.sort(Comparator.comparing(FixedCostProposal::scope).thenComparing(p -> p.name().toLowerCase(Locale.ROOT))
                .thenComparingLong(FixedCostProposal::id));
        return result;
    }

    /** One open proposal; empty when there is none with this id or it is no longer open. */
    public Optional<FixedCostProposal> open(long id) {
        return proposals().stream().filter(p -> p.id() == id).findFirst();
    }

    public List<FixedCostRejection> rejections() {
        return jdbc.sql("select id, scope, name, counterparty_iban, name_key, amount_cents, rejected_at from fixed_cost_rejection "
                + "order by name collate nocase, id").query(FixedCostProposalService::mapRejection).list();
    }

    // ----- the owner's answers

    /** "Not a fixed cost": stores the rejection of an open proposal. Refused when the proposal is not open. */
    public void reject(long id) {
        FixedCostProposal proposal = open(id).orElseThrow(() -> new FixedCostException(Reason.PROPOSAL_NOT_OPEN));
        jdbc.sql("insert into fixed_cost_rejection (scope, counterparty_iban, name_key, name, amount_cents, rejected_at) "
                + "values (?, ?, ?, ?, ?, ?)").params(proposal.scope().code(), proposal.counterpartyIban(), proposal.nameKey(), proposal.name(),
                        proposal.amount().cents(), Instant.now(clock).toString()).update();
    }

    /** Takes a rejection back and looks for proposals again, so the counterparty shows up at once. */
    public void undoRejection(long id) {
        if (jdbc.sql("delete from fixed_cost_rejection where id = ?").param(id).update() == 0) {
            throw new FixedCostException(Reason.REJECTION_NOT_FOUND);
        }
        detect();
    }

    // ----- detection

    /** One counterparty of one scope as the stream is read. {@code name} is the newest charge's name as delivered, if it had one. */
    private static final class Group {
        final Scope scope;
        final String iban;
        final String key;
        String name;
        String nameKey;
        final Set<String> nameKeys = new HashSet<>();
        final List<Charge> charges = new ArrayList<>();

        Group(Scope scope, String iban, String key) {
            this.scope = scope;
            this.iban = iban;
            this.key = key;
        }
    }

    /**
     * Works the proposals out and keeps them: reads outside any write transaction, then replaces the stored proposals in one short one.
     * A proposal that is still found keeps its id. Running it twice stores the same rows. It writes no table of the owner.
     *
     * @return how many proposals are stored afterwards
     */
    public int detect() {
        LocalDate today = accounts.today();
        Map<String, Group> groups = read(today);
        List<Owned> owned = fixedCostCounterparties();
        List<FixedCostRejection> rejections = rejections();
        List<Found> found = new ArrayList<>();
        for (Group group : groups.values()) {
            Optional<Run> run = RepeatingCharges.find(group.charges, today);
            if (run.isEmpty() || blocked(group, run.get(), owned, rejections)) {
                continue;
            }
            found.add(new Found(group, run.get()));
        }
        String at = Instant.now(clock).toString();
        inTransaction.executeWithoutResult(status -> store(found, at));
        return found.size();
    }

    private record Found(Group group, Run run) {
    }

    /** The booked spending of the look-back window, in date order, as groups of plain values. */
    private Map<String, Group> read(LocalDate today) {
        Set<String> ownIbans = new HashSet<>();
        jdbc.sql("select iban from account where iban is not null").query((rs, row) -> ownIbans.add(FixedCostService.compactIban(rs.getString(1))))
                .list();
        Map<String, Group> groups = new LinkedHashMap<>();
        jdbc.sql("""
                select t.id, t.booking_date, t.amount_cents, t.counterparty_name, t.counterparty_iban, a.scope
                from transactions t join account a on a.id = t.account_id
                where t.status = 'booked' and t.amount_cents < 0 and t.booking_date >= ?
                order by t.booking_date, t.id""").param(today.minusMonths(LOOK_BACK_MONTHS).toString())
                .query((RowCallbackHandler) rs -> add(groups, ownIbans, rs));
        return groups;
    }

    private static void add(Map<String, Group> groups, Set<String> ownIbans, ResultSet rs) throws SQLException {
        long cents = rs.getLong("amount_cents");
        String compact = FixedCostService.compactIban(rs.getString("counterparty_iban"));
        String iban = compact == null || compact.isEmpty() ? null : compact;
        String delivered = rs.getString("counterparty_name");
        String nameKey = TransactionService.normalise(delivered);
        // A payment to one of the installation's own accounts is a transfer, and one with neither an IBAN nor a name has no counterparty.
        if (cents == Long.MIN_VALUE || (iban != null && ownIbans.contains(iban)) || (iban == null && nameKey.isEmpty())) {
            return;
        }
        Scope scope = Scope.parse(rs.getString("scope"));
        String key = scope.code() + (iban != null ? "|i|" + iban : "|n|" + nameKey);
        Group group = groups.computeIfAbsent(key, k -> new Group(scope, iban, k));
        if (!nameKey.isEmpty()) {
            group.name = delivered.strip().replaceAll("\\s+", " ");
            group.nameKey = nameKey;
            group.nameKeys.add(nameKey);
        }
        group.charges.add(new Charge(rs.getLong("id"), LocalDate.parse(rs.getString("booking_date")), -cents));
    }

    private void store(List<Found> found, String at) {
        // The first statement is a write that changes nothing, so the single SQLite writer is taken (and waited for) up front instead of
        // upgrading a read lock halfway (as FixedCostService.matchPayments).
        jdbc.sql("update fixed_cost_proposal set detected_at = detected_at where 0").update();
        Map<String, Long> existing = new HashMap<>();
        jdbc.sql("select group_key, id from fixed_cost_proposal").query((rs, row) -> existing.put(rs.getString(1), rs.getLong(2))).list();
        Set<String> kept = new HashSet<>();
        for (Found item : found) {
            Group group = item.group();
            Charge newest = item.run().newest();
            String name = group.name != null ? group.name : group.iban;
            kept.add(group.key);
            Long id = existing.get(group.key);
            if (id == null) {
                jdbc.sql("""
                        insert into fixed_cost_proposal (group_key, scope, name, counterparty_name, counterparty_iban, name_key, amount_cents,
                            rhythm, first_due, latest_booked, detected_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                        .params(group.key, group.scope.code(), name, group.name, group.iban, group.nameKey == null ? "" : group.nameKey,
                                newest.cents(), item.run().rhythm().code(), newest.booked().toString(), newest.booked().toString(), at)
                        .update();
                id = jdbc.sql("select id from fixed_cost_proposal where group_key = ?").param(group.key).query(Long.class).single();
            } else {
                jdbc.sql("""
                        update fixed_cost_proposal set name = ?, counterparty_name = ?, counterparty_iban = ?, name_key = ?, amount_cents = ?,
                            rhythm = ?, first_due = ?, latest_booked = ? where id = ?""")
                        .params(name, group.name, group.iban, group.nameKey == null ? "" : group.nameKey, newest.cents(),
                                item.run().rhythm().code(), newest.booked().toString(), newest.booked().toString(), id).update();
                jdbc.sql("delete from fixed_cost_proposal_charge where proposal_id = ?").param(id).update();
            }
            for (Charge charge : item.run().charges()) {
                jdbc.sql("insert into fixed_cost_proposal_charge (proposal_id, transaction_id) values (?, ?)").params(id, charge.id()).update();
            }
        }
        for (Map.Entry<String, Long> old : existing.entrySet()) {
            if (!kept.contains(old.getKey())) {
                jdbc.sql("delete from fixed_cost_proposal where id = ?").param(old.getValue()).update();
            }
        }
    }

    // ----- what hides a proposal

    /** What a fixed cost says about its counterparty: the IBAN when it has one, else the name, both as the matching reads them. */
    private record Owned(Scope scope, String iban, String nameKey) {
    }

    private List<Owned> fixedCostCounterparties() {
        return jdbc.sql("select scope, counterparty_iban, counterparty_name from fixed_cost").query((rs, row) -> {
            String iban = FixedCostService.compactIban(rs.getString(2));
            return new Owned(Scope.parse(rs.getString(1)), iban == null || iban.isEmpty() ? null : iban,
                    TransactionService.normalise(rs.getString(3)));
        }).list();
    }

    private static boolean hasFixedCost(Scope scope, String iban, Set<String> nameKeys, List<Owned> owned) {
        for (Owned cost : owned) {
            if (cost.scope() != scope) {
                continue;
            }
            if (cost.iban() != null ? cost.iban().equals(iban) : !cost.nameKey().isEmpty() && nameKeys.contains(cost.nameKey())) {
                return true;
            }
        }
        return false;
    }

    private static boolean wasRejected(Scope scope, String iban, String nameKey, long cents, List<FixedCostRejection> rejections) {
        for (FixedCostRejection rejection : rejections) {
            boolean same = rejection.scope() == scope
                    && (iban != null ? iban.equals(rejection.counterpartyIban()) : rejection.counterpartyIban() == null
                            && nameKey.equals(rejection.nameKey()));
            long gap = ReservationService.amountGap(cents, rejection.amount().cents());
            if (same && ReservationService.amountClose(gap, rejection.amount().cents())) {
                return true;
            }
        }
        return false;
    }

    private static boolean blocked(Group group, Run run, List<Owned> owned, List<FixedCostRejection> rejections) {
        return hasFixedCost(group.scope, group.iban, group.nameKeys, owned)
                || wasRejected(group.scope, group.iban, group.nameKey == null ? "" : group.nameKey, run.newest().cents(), rejections);
    }

    private static boolean blocked(FixedCostProposal proposal, List<Owned> owned, List<FixedCostRejection> rejections) {
        return hasFixedCost(proposal.scope(), proposal.counterpartyIban(), Set.of(proposal.nameKey()), owned)
                || wasRejected(proposal.scope(), proposal.counterpartyIban(), proposal.nameKey(), proposal.amount().cents(), rejections);
    }

    // ----- mapping

    private List<FixedCostProposal> stored() {
        return jdbc.sql("""
                select p.id, p.scope, p.name, p.counterparty_name, p.counterparty_iban, p.name_key, p.amount_cents, p.rhythm, p.first_due,
                       p.latest_booked, (select count(*) from fixed_cost_proposal_charge c where c.proposal_id = p.id) as charges
                from fixed_cost_proposal p""").query((rs, row) -> new FixedCostProposal(rs.getLong("id"), Scope.parse(rs.getString("scope")),
                        rs.getString("name"), rs.getString("counterparty_name"), rs.getString("counterparty_iban"), rs.getString("name_key"),
                        Money.eur(rs.getLong("amount_cents")), Rhythm.parse(rs.getString("rhythm")), LocalDate.parse(rs.getString("first_due")),
                        LocalDate.parse(rs.getString("latest_booked")), rs.getInt("charges"))).list();
    }

    private static FixedCostRejection mapRejection(ResultSet rs, int row) throws SQLException {
        return new FixedCostRejection(rs.getLong("id"), Scope.parse(rs.getString("scope")), rs.getString("name"),
                rs.getString("counterparty_iban"), rs.getString("name_key"), Money.eur(rs.getLong("amount_cents")),
                Instant.parse(rs.getString("rejected_at")));
    }
}
