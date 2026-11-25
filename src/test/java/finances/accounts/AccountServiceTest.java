package finances.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finances.accounts.AccountException.Reason;
import finances.money.Money;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** The accounts rules on a real, migrated SQLite file, without the web layer. */
class AccountServiceTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    private static final String PRIVATE_IBAN = "NL51XMPL0000000001";
    private static final String SAVINGS_IBAN = "NL24XMPL0000000002";

    private AccountService accounts;
    private JdbcClient jdbc;

    @BeforeEach
    void open(@TempDir Path folder) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("finances.db"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        accounts = new AccountService(jdbc, NOW);
    }

    private static Money eur(String typed) {
        return Money.parse(typed);
    }

    @Test
    void addsAManualAccountAndShowsItWithoutABalanceYet() {
        Account account = accounts.addManual("  Everyday  ", Scope.PRIVATE, Kind.CURRENT, "NL51 xmpl 0000 0000 01");

        assertThat(account.name()).isEqualTo("Everyday");
        assertThat(account.iban()).isEqualTo(PRIVATE_IBAN);
        assertThat(account.source()).isEqualTo(Source.MANUAL);
        assertThat(account.archived()).isFalse();
        assertThat(accounts.list(false)).singleElement().satisfies(summary -> {
            assertThat(summary.account()).isEqualTo(account);
            assertThat(summary.latest()).isEmpty();
        });
    }

    @Test
    void entersThreeBalancesCorrectsOneAndSeesTheHistoryAndTheLatest() {
        Account account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, PRIVATE_IBAN);
        BalanceEntry first = accounts.addBalance(account.id(), eur("100,00"), TODAY.minusDays(20));
        BalanceEntry second = accounts.addBalance(account.id(), eur("1.234,56"), TODAY.minusDays(10));
        BalanceEntry third = accounts.addBalance(account.id(), eur("-12,34"), TODAY);

        assertThat(accounts.history(account.id())).extracting(BalanceEntry::id)
                .containsExactly(third.id(), second.id(), first.id());
        assertThat(accounts.list(false).getFirst().latest()).hasValueSatisfying(latest -> {
            assertThat(latest.amount()).isEqualTo(Money.eur(-1234));
            assertThat(latest.date()).isEqualTo(TODAY);
        });

        BalanceEntry corrected = accounts.correctBalance(account.id(), second.id(), eur("1.300,00"), TODAY.minusDays(9));

        assertThat(corrected.id()).isEqualTo(second.id());
        assertThat(corrected.amount()).isEqualTo(Money.eur(130000));
        assertThat(corrected.date()).isEqualTo(TODAY.minusDays(9));
        assertThat(accounts.history(account.id())).extracting(BalanceEntry::amount)
                .containsExactly(Money.eur(-1234), Money.eur(130000), Money.eur(10000));
    }

    @Test
    void theLatestBalanceIsTheOneWithTheNewestDateNotTheLastTyped() {
        Account account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null);
        accounts.addBalance(account.id(), eur("500"), TODAY);
        accounts.addBalance(account.id(), eur("400"), TODAY.minusDays(30));

        assertThat(accounts.list(false).getFirst().latest().orElseThrow().amount()).isEqualTo(Money.eur(50000));
    }

    @Test
    void twoBalancesOnTheSameDayBothAreKeptAndTheLastTypedIsTheLatest() {
        Account account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null);
        accounts.addBalance(account.id(), eur("10"), TODAY);
        accounts.addBalance(account.id(), eur("20"), TODAY);

        assertThat(accounts.history(account.id())).hasSize(2);
        assertThat(accounts.list(false).getFirst().latest().orElseThrow().amount()).isEqualTo(Money.eur(2000));
    }

    @Test
    void removesABalanceEntry() {
        Account account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null);
        BalanceEntry first = accounts.addBalance(account.id(), eur("10"), TODAY.minusDays(1));
        BalanceEntry second = accounts.addBalance(account.id(), eur("20"), TODAY);

        accounts.removeBalance(account.id(), second.id());

        assertThat(accounts.history(account.id())).extracting(BalanceEntry::id).containsExactly(first.id());
        assertThat(accounts.list(false).getFirst().latest().orElseThrow().id()).isEqualTo(first.id());
    }

    @Test
    void aBalanceInTheFutureIsRefusedOnEntryAndOnCorrection() {
        Account account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null);
        BalanceEntry entry = accounts.addBalance(account.id(), eur("10"), TODAY);

        assertRefused(Reason.DATE_IN_FUTURE, () -> accounts.addBalance(account.id(), eur("10"), TODAY.plusDays(1)));
        assertRefused(Reason.DATE_IN_FUTURE, () -> accounts.correctBalance(account.id(), entry.id(), eur("10"), TODAY.plusDays(1)));
        assertRefused(Reason.DATE_REQUIRED, () -> accounts.addBalance(account.id(), eur("10"), null));
        assertThat(accounts.history(account.id())).hasSize(1);
        assertThat(accounts.addBalance(account.id(), eur("10"), TODAY)).isNotNull();
    }

    @Test
    void refusesTheSameIbanTwiceButNotTheSameNameTwice() {
        accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, PRIVATE_IBAN);

        assertRefused(Reason.IBAN_DUPLICATE, () -> accounts.addManual("Other", Scope.PRIVATE, Kind.CURRENT, "nl51xmpl0000000001"));
        assertThat(accounts.addManual("Everyday", Scope.BUSINESS, Kind.CURRENT, null)).isNotNull();
        assertThat(accounts.addManual("Everyday", Scope.BUSINESS, Kind.CURRENT, null)).isNotNull();
        assertThat(accounts.list(false)).hasSize(3);
    }

    @Test
    void anArchivedAccountStillHoldsItsIban() {
        Account account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, PRIVATE_IBAN);
        accounts.archive(account.id());

        assertRefused(Reason.IBAN_DUPLICATE, () -> accounts.addManual("Again", Scope.PRIVATE, Kind.CURRENT, PRIVATE_IBAN));
    }

    @Test
    void renamingAnAccountKeepingItsOwnIbanIsFine() {
        Account account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, PRIVATE_IBAN);
        Account other = accounts.addManual("Savings", Scope.PRIVATE, Kind.SAVINGS, SAVINGS_IBAN);

        Account renamed = accounts.update(account.id(), "Daily", Scope.BUSINESS, Kind.SAVINGS, PRIVATE_IBAN);

        assertThat(renamed.name()).isEqualTo("Daily");
        assertThat(renamed.scope()).isEqualTo(Scope.BUSINESS);
        assertThat(renamed.kind()).isEqualTo(Kind.SAVINGS);
        assertRefused(Reason.IBAN_DUPLICATE, () -> accounts.update(other.id(), "Savings", Scope.PRIVATE, Kind.SAVINGS, PRIVATE_IBAN));
    }

    @Test
    void archivingHidesTheAccountFromTheActiveListAndKeepsItsHistory() {
        Account account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null);
        accounts.addBalance(account.id(), eur("10"), TODAY);

        accounts.archive(account.id());

        assertThat(accounts.list(false)).isEmpty();
        assertThat(accounts.list(true)).singleElement().satisfies(summary -> {
            assertThat(summary.account().archived()).isTrue();
            assertThat(summary.latest()).isPresent();
        });
        assertThat(accounts.history(account.id())).hasSize(1);
        assertRefused(Reason.ARCHIVED, () -> accounts.addBalance(account.id(), eur("20"), TODAY));

        accounts.unarchive(account.id());

        assertThat(accounts.list(false)).hasSize(1);
        assertThat(accounts.addBalance(account.id(), eur("20"), TODAY)).isNotNull();
    }

    @Test
    void thereIsNoWayToDeleteAnAccount() {
        assertThat(AccountService.class.getMethods()).extracting(method -> method.getName())
                .noneMatch(name -> name.startsWith("delete") || name.equals("remove"));
    }

    @Test
    void aDebtIsTheAmountOwedAndCannotBeNegative() {
        Account debt = accounts.addManual("Loan", Scope.PRIVATE, Kind.DEBT, null);

        assertThat(accounts.addBalance(debt.id(), eur("5.000,00"), TODAY).amount()).isEqualTo(Money.eur(500000));
        assertRefused(Reason.DEBT_NEGATIVE, () -> accounts.addBalance(debt.id(), eur("-5"), TODAY));
    }

    @Test
    void anAccountWithBalancesCannotMoveAcrossTheDebtLineInEitherDirection() {
        Account current = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, null);
        Account debt = accounts.addManual("Loan", Scope.PRIVATE, Kind.DEBT, null);
        accounts.addBalance(current.id(), eur("500"), TODAY);
        accounts.addBalance(debt.id(), eur("500"), TODAY);

        assertRefused(Reason.KIND_CHANGE_HAS_BALANCES, () -> accounts.update(current.id(), "Everyday", Scope.PRIVATE, Kind.DEBT, null));
        assertRefused(Reason.KIND_CHANGE_HAS_BALANCES, () -> accounts.update(debt.id(), "Loan", Scope.PRIVATE, Kind.CURRENT, null));
        assertThat(accounts.get(current.id()).kind()).isEqualTo(Kind.CURRENT);
        assertThat(accounts.get(debt.id()).kind()).isEqualTo(Kind.DEBT);
        // Other kind changes keep working, and an account without balances may become a debt.
        assertThat(accounts.update(current.id(), "Everyday", Scope.PRIVATE, Kind.SAVINGS, null).kind()).isEqualTo(Kind.SAVINGS);
        Account empty = accounts.addManual("Empty", Scope.PRIVATE, Kind.CURRENT, null);
        assertThat(accounts.update(empty.id(), "Empty", Scope.PRIVATE, Kind.DEBT, null).kind()).isEqualTo(Kind.DEBT);
    }

    @Test
    void balancesOfAnotherAccountCannotBeTouchedThroughThisOne() {
        Account one = accounts.addManual("One", Scope.PRIVATE, Kind.CURRENT, null);
        Account two = accounts.addManual("Two", Scope.PRIVATE, Kind.CURRENT, null);
        BalanceEntry entry = accounts.addBalance(two.id(), eur("10"), TODAY);

        assertRefused(Reason.NOT_FOUND, () -> accounts.correctBalance(one.id(), entry.id(), eur("99"), TODAY));
        assertRefused(Reason.NOT_FOUND, () -> accounts.removeBalance(one.id(), entry.id()));
        assertThat(accounts.history(two.id())).hasSize(1);
    }

    @Test
    void balancesCanOnlyBeTypedInForAManualAccount() {
        jdbc.sql("insert into account (name, scope, kind, source, created_at) values ('Bank', 'private', 'current', 'bank', '2026-06-01T00:00:00Z')").update();
        long id = jdbc.sql("select id from account").query(Long.class).single();

        assertRefused(Reason.NOT_MANUAL, () -> accounts.addBalance(id, eur("10"), TODAY));
    }

    @Test
    void refusesABadNameBadIbanAndUnknownAccount() {
        assertRefused(Reason.NAME_REQUIRED, () -> accounts.addManual("   ", Scope.PRIVATE, Kind.CURRENT, null));
        assertRefused(Reason.NAME_REQUIRED, () -> accounts.addManual(null, Scope.PRIVATE, Kind.CURRENT, null));
        assertRefused(Reason.NAME_TOO_LONG, () -> accounts.addManual("x".repeat(101), Scope.PRIVATE, Kind.CURRENT, null));
        assertRefused(Reason.IBAN_INVALID, () -> accounts.addManual("A", Scope.PRIVATE, Kind.CURRENT, "not an iban"));
        assertRefused(Reason.NOT_FOUND, () -> accounts.get(999));
        assertRefused(Reason.NOT_FOUND, () -> accounts.addBalance(999, eur("1"), TODAY));
        assertRefused(Reason.INVALID_SCOPE, () -> Scope.parse("shared"));
        assertRefused(Reason.INVALID_KIND, () -> Kind.parse("cash"));
        assertThat(accounts.list(false)).isEmpty();
    }

    @Test
    void listsByScopeThenKindThenName() {
        accounts.addManual("Zed", Scope.PRIVATE, Kind.CURRENT, null);
        accounts.addManual("alpha", Scope.PRIVATE, Kind.CURRENT, null);
        accounts.addManual("Loan", Scope.PRIVATE, Kind.DEBT, null);
        accounts.addManual("Savings", Scope.PRIVATE, Kind.SAVINGS, null);
        accounts.addManual("Shop", Scope.BUSINESS, Kind.CURRENT, null);

        List<String> names = accounts.list(false).stream().map(s -> s.account().name()).toList();

        assertThat(names).containsExactly("alpha", "Zed", "Savings", "Loan", "Shop");
    }

    @Test
    void theSchemaItselfRefusesAnotherCurrencyAndASecondIban() {
        Account account = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, PRIVATE_IBAN);

        assertThatThrownBy(() -> jdbc.sql("insert into balance (account_id, amount_cents, currency, balance_date, entered_at) values (?, 1, 'USD', '2026-06-01', '2026-06-01T00:00:00Z')")
                .param(account.id()).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.sql("insert into account (name, scope, kind, source, iban, created_at) values ('X', 'private', 'current', 'manual', ?, '2026-06-01T00:00:00Z')")
                .param(PRIVATE_IBAN).update()).isInstanceOf(Exception.class);
    }

    private static void assertRefused(Reason reason, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AccountException.class)
                .satisfies(e -> assertThat(((AccountException) e).reason()).isEqualTo(reason));
    }
}
