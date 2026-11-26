package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import finances.sync.FakeAfterTaxSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The After-tax source section of the settings page through the real application on a temporary data folder and a fake
 * source on this machine (issue #18): every state of the section, the Test button, Sync now without a bank, the backup, and
 * that neither the address, the token nor an amount of the source shows up where it must not. Fictional data only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AfterTaxPagesTest {

    private static final String TOKEN = "fictional-token-0123456789";
    private static final Path DATA = createTempDataFolder();
    private static final FakeAfterTaxSource SOURCE = startSource();
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
        registry.add("finances.outbound.after-tax.url", SOURCE::url);
        registry.add("finances.sync.enabled", () -> "false");
    }

    private static Path createTempDataFolder() {
        try {
            Path folder = Files.createTempDirectory("finances-test-aftertax");
            Files.createDirectories(folder.resolve("secrets"));
            Files.writeString(folder.resolve("secrets").resolve("after-tax-token"), TOKEN + "\n");
            return folder;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FakeAfterTaxSource startSource() {
        try {
            return new FakeAfterTaxSource();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void cleanUp() throws IOException {
        SOURCE.close();
        try (Stream<Path> paths = Files.walk(DATA)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String location(HttpResponse<?> response) {
        URI target = URI.create(response.headers().firstValue("Location").orElseThrow());
        return target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
    }

    /** Presses Test and reads the settings page it lands on. */
    private String test() throws Exception {
        HttpResponse<String> response = post("/settings/after-tax/test");
        assertThat(response.statusCode()).isEqualTo(302);
        return get(location(response)).body();
    }

    private static String answerAsOf(Duration ago) {
        return FakeAfterTaxSource.valid(Instant.now().minus(ago));
    }

    private static void assertNoSecretsIn(String page) {
        assertThat(page).doesNotContain(TOKEN).doesNotContain(SOURCE.url()).doesNotContain(FakeAfterTaxSource.PATH).doesNotContain("127.0.0.1");
    }

    @Test
    void theSectionShowsEveryStateAndTheTokenTheAddressAndTheRequestStayOutOfEveryPage() throws Exception {
        // Configured, nothing fetched yet: the section says so, shows no figure, and loading the page asks the source nothing.
        String empty = get("/settings").body();
        assertThat(empty).contains("After-tax source").contains("A source is configured (plain http).")
                .contains("A token file is in the data folder.").contains("No value received yet.").contains("Not fetched yet.")
                .contains("Test the source").doesNotContain("Stale");
        assertNoSecretsIn(empty);
        assertThat(SOURCE.requests()).as("a page never calls the source").isEmpty();
        assertThat(get("/").body()).as("the overview shows nothing of it yet").doesNotContain("12,345").doesNotContain("After-tax");

        // A valid answer: the value with its moment and age, the gross and the reserved lines, no stale mark.
        SOURCE.answer(answerAsOf(Duration.ofHours(1)));
        String fresh = test();
        assertThat(fresh).contains("The source answered with a valid value.").contains("€12,345.00").contains("Gross balance: €20,000.00")
                .contains("VAT").contains("€4,200.00").contains("Income tax").contains("€3,455.00").contains("fetched")
                .containsPattern("\\((today|yesterday) \\d\\d:\\d\\d\\)").doesNotContain("Stale").doesNotContain("not the available amount");
        assertNoSecretsIn(fresh);
        assertThat(SOURCE.requests()).hasSize(1);
        assertThat(SOURCE.requests().getFirst().header("Authorization")).isEqualTo("Bearer " + TOKEN);

        // Failing: the last good value stays, marked stale with the reason, and the fetch shows its status.
        SOURCE.answer(500, "{\"error\": \"leaky-secret\"}");
        String failed = test();
        assertThat(failed).contains("The source could not be used.").contains("€12,345.00").contains("Stale")
                .contains("the source could not be used: the source answered with a status other than 200")
                .contains("failed: the source answered with a status other than 200").contains("(status 500)").doesNotContain("leaky-secret");
        assertNoSecretsIn(failed);

        // An answer that is not the contract: refused, with a reason that names the problem, never the text of the answer.
        SOURCE.answer("{\"contract_version\": 1, \"as_of\": \"" + Instant.now() + "\", \"currency\": \"EUR\", \"available_cents\": 12.5}");
        assertThat(test()).contains("failed: an amount was not a whole number of cents").contains("€12,345.00").contains("Stale");

        // An answer calculated before the value shown is valid but does not replace it (issue #19: it used to, being received last).
        SOURCE.answer(answerAsOf(Duration.ofDays(5)));
        String older = test();
        assertThat(older).contains("The source answered with a valid value.").contains("a valid answer, but older than the value shown")
                .doesNotContain("(5 days ago)").doesNotContain("the figure from the source is 5 days old");

        // Old: a value whose own moment is older than the limit is stale although the last request worked (the moments stored
        // so far are moved back, as time itself would do).
        jdbc.sql("update after_tax_value set as_of = ?").param(Instant.now().minus(Duration.ofDays(5)).toString()).update();
        String old = get("/settings").body();
        assertThat(old).contains("(5 days ago)").contains("Stale").contains("the figure from the source is 5 days old");

        // Fresh again, with a gross that does not add up: the available amount as sent, with the warning.
        SOURCE.answer(FakeAfterTaxSource.valid(Instant.now().minusSeconds(600)).replace("1234500", "1234501"));
        String mismatch = test();
        assertThat(mismatch).contains("€12,345.01").contains("The gross balance minus what is reserved is not the available amount.")
                .doesNotContain("Stale");

        // The address of a notice is not a message of its own.
        assertThat(get("/settings?afterTax=busy").body()).contains("A fetch is already running.");
        assertThat(get("/settings?afterTax=%3Cscript%3E").body()).doesNotContain("<script>");

        // The backup holds the values and the owner's other data but never the token.
        Path zipFile = DATA.resolveSibling(DATA.getFileName() + "-backup.zip");
        HttpResponse<Path> backup = CLIENT.send(
                HttpRequest.newBuilder(uri("/settings/backup/download")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofFile(zipFile));
        assertThat(backup.statusCode()).isEqualTo(200);
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName).toList()).containsExactly("manifest.properties", "finances.db");
            for (ZipEntry entry : zip.stream().toList()) {
                String content = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.ISO_8859_1);
                assertThat(content).doesNotContain(TOKEN);
                if (entry.getName().equals("finances.db")) {
                    assertThat(content).contains("after_tax_value").contains("Income tax");
                }
            }
        }
        Files.delete(zipFile);

        // A failure that is not the source's: the error page quotes neither the token, the address nor an amount.
        jdbc.sql("drop table after_tax_reserved").update();
        HttpResponse<String> broken = get("/settings");
        assertThat(broken.statusCode()).isEqualTo(500);
        assertNoSecretsIn(broken.body());
        assertThat(broken.body()).doesNotContain("1234500").doesNotContain("12,345").doesNotContain("Income tax").doesNotContain("after_tax");
    }
}
