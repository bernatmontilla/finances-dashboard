package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.AccountService;
import finances.accounts.DeliveredTransaction;
import finances.accounts.Kind;
import finances.accounts.Scope;
import finances.accounts.Source;
import finances.accounts.TransactionService;
import finances.accounts.TransactionStatus;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The transactions pages through the real application on a temporary data folder. Fictional data only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionsPagesTest {

    private static final Path DATA = createTempDataFolder();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void dataFolder(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
    }

    private static Path createTempDataFolder() {
        try {
            return Files.createTempDirectory("finances-test-transactions");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void removeDataFolder() throws IOException {
        try (Stream<Path> paths = Files.walk(DATA)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    AccountService accounts;

    @Autowired
    TransactionService transactions;

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static DeliveredTransaction booked(LocalDate date, long cents, String counterparty, String description, String reference) {
        return new DeliveredTransaction(date, null, cents, "EUR", counterparty, "NL10SHOP0000000101", description,
                TransactionStatus.BOOKED, reference, "raw");
    }

    @Test
    void aNewAccountShowsThatNothingWasDeliveredAndHasNoTransactions() throws Exception {
        long id = accounts.addManual("Quiet Account", Scope.PRIVATE, Kind.CURRENT, "").id();

        String page = get("/accounts/" + id).body();
        assertThat(page).contains("No transactions yet.").contains("No transactions delivered yet");
        assertThat(get("/accounts").body()).contains("No transactions delivered yet");
        assertThat(get("/transactions").statusCode()).isEqualTo(200);
    }

    @Test
    void anAccountPageListsItsTransactionsNewestFirstWithPendingMarkAndDeliveryDate() throws Exception {
        long id = accounts.addManual("Everyday Spending", Scope.PRIVATE, Kind.CURRENT, "NL51XMPL0000000001").id();
        LocalDate today = LocalDate.now();
        transactions.deliver(id, Source.CSV, List.of(
                booked(today.minusDays(5), -1234, "Corner Grocery", "Weekly groceries", "R1"),
                booked(today.minusDays(1), 123456, "Example Client BV", "Invoice 7", "R2"),
                new DeliveredTransaction(today, null, -4500, "EUR", "Example Insurance", null, "Premium",
                        TransactionStatus.PENDING, null, "raw")));
        long first = transactions.forAccount(id, 0).items().getFirst().id();
        transactions.setNote(first, "Check this one");

        String page = get("/accounts/" + id).body();

        assertThat(page).contains("Corner Grocery").contains("Weekly groceries").contains("-€12.34")
                .contains("€1,234.56").contains("-€45.00").contains("Check this one");
        assertThat(page.indexOf("Example Insurance")).isLessThan(page.indexOf("Example Client BV"));
        assertThat(page.indexOf("Example Client BV")).isLessThan(page.indexOf("Corner Grocery"));
        assertThat(page).containsOnlyOnce(">pending<");
        assertThat(page).contains("Transactions delivered " + today).contains("(today)");
        assertThat(get("/accounts").body()).contains("Transactions delivered " + today);
    }

    @Test
    void theTransactionsOfAnAccountArePagedAndTheLinksWork() throws Exception {
        long id = accounts.addManual("Busy Account", Scope.BUSINESS, Kind.CURRENT, "NL94XMPL0000000003").id();
        List<DeliveredTransaction> many = new ArrayList<>();
        int total = TransactionService.PAGE_SIZE + 3;
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < total; i++) {
            many.add(booked(start.plusDays(i), -(i + 1), "Corner Grocery", "Item number " + i, "BUSY-" + i));
        }
        transactions.deliver(id, Source.CSV, many);

        String first = get("/accounts/" + id).body();
        assertThat(first).contains("Item number " + (total - 1)).doesNotContain("Item number 0<").contains("Older transactions");
        assertThat(first).doesNotContain("Newer transactions");
        assertThat(first).contains("/accounts/" + id + "?page=1");

        String second = get("/accounts/" + id + "?page=1").body();
        assertThat(second).contains("Item number 0<").contains("Item number 2<").doesNotContain("Item number " + (total - 1) + "<");
        assertThat(second).contains("Newer transactions").doesNotContain("Older transactions");

        assertThat(get("/accounts/" + id + "?page=abc").body()).contains("Item number " + (total - 1));
        assertThat(get("/accounts/" + id + "?page=-4").statusCode()).isEqualTo(200);
        assertThat(get("/accounts/" + id + "?page=99").body()).contains("No transactions on this page.");
    }

    @Test
    void theTransactionsPageShowsEveryAccountsTransactionsWithTheAccountName() throws Exception {
        long one = accounts.addManual("Account One Alpha", Scope.PRIVATE, Kind.CURRENT, "").id();
        long two = accounts.addManual("Account Two Beta", Scope.BUSINESS, Kind.CURRENT, "").id();
        LocalDate day = LocalDate.of(2027, 3, 3);
        transactions.deliver(one, Source.CSV, List.of(booked(day, -100, "Example Telecom", "Phone Alpha", "A1")));
        transactions.deliver(two, Source.BANK, List.of(booked(day.plusDays(1), -200, "Example Gym", "Gym Beta", "B1")));

        String page = get("/transactions").body();

        assertThat(page).contains("All transactions").contains("Phone Alpha").contains("Gym Beta");
        assertThat(page).contains("Account One Alpha").contains("Account Two Beta");
        assertThat(page.indexOf("Gym Beta")).isLessThan(page.indexOf("Phone Alpha"));
        assertThat(page).contains("Last delivery per account");
    }

    @Test
    void anArchivedAccountKeepsItsTransactionsVisible() throws Exception {
        long id = accounts.addManual("Old Account Gamma", Scope.PRIVATE, Kind.CURRENT, "").id();
        transactions.deliver(id, Source.CSV, List.of(booked(LocalDate.of(2027, 5, 5), -100, "Example Landlord", "Rent Gamma", "G1")));
        accounts.archive(id);

        assertThat(get("/transactions").body()).contains("Rent Gamma");
        assertThat(get("/accounts/" + id).body()).contains("Rent Gamma");
    }

    @Test
    void theTransactionsPagesFitAPhoneAndSupportDarkMode() throws Exception {
        long id = accounts.addManual("Phone Account Delta", Scope.PRIVATE, Kind.CURRENT, "").id();
        for (String path : new String[] {"/transactions", "/accounts/" + id}) {
            assertThat(get(path).body()).contains("name=\"viewport\"").contains("name=\"color-scheme\"");
        }
        assertThat(get("/app.css").body()).contains("prefers-color-scheme: dark").contains(".transactions");
    }
}
