package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.AccountService;
import finances.accounts.DeliveredTransaction;
import finances.accounts.Source;
import finances.accounts.TransactionService;
import finances.accounts.TransactionStatus;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The Backup section of the settings page through the real application on a temporary data
 * folder: download, add something, upload, confirm, undo. Fictional data only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettingsBackupPagesTest {

    private static final Path DATA = createTempDataFolder();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void dataFolder(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
    }

    private static Path createTempDataFolder() {
        try {
            return Files.createTempDirectory("finances-test-backup");
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

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> form, String... headers) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(Path file) throws Exception {
        String boundary = "----finances" + HexFormat.of().formatHex(new SecureRandom().generateSeed(8));
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + file.getFileName()
                + "\"\r\nContent-Type: application/zip\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(uri("/settings/backup/check"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(head), HttpRequest.BodyPublishers.ofFile(file),
                        HttpRequest.BodyPublishers.ofString(tail)))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Path download(String name) throws Exception {
        Path file = DATA.resolveSibling(DATA.getFileName() + "-" + name);
        HttpResponse<Path> response = CLIENT.send(
                HttpRequest.newBuilder(uri("/settings/backup/download")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofFile(file));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/zip");
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment; filename=\"finances-backup-").endsWith(".zip\"");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        return file;
    }

    private static String location(HttpResponse<?> response) {
        URI target = URI.create(response.headers().firstValue("Location").orElseThrow());
        return target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
    }

    @Test
    void theOwnerDownloadsABackupRestoresItAfterAChangeAndUndoesTheRestore() throws Exception {
        accounts.addManual("Everyday", finances.accounts.Scope.PRIVATE, finances.accounts.Kind.CURRENT, "NL51XMPL0000000001");
        String page = get("/settings").body();
        assertThat(page).contains("Backup").contains("Secrets are not in the backup").contains("bank links must be renewed").contains("Download backup");
        assertThat(page).doesNotContain("Undo the last restore");

        // A bigger database than the 1 MB default of a multipart upload: real files grow with the data.
        SecureRandom random = new SecureRandom();
        List<DeliveredTransaction> batch = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            byte[] noise = new byte[400];
            random.nextBytes(noise);
            batch.add(new DeliveredTransaction(LocalDate.of(2026, 6, 1), null, -100 - i, "EUR", "Corner Grocery", "NL10SHOP0000000101",
                    "Groceries " + i, TransactionStatus.BOOKED, "REF-" + i, HexFormat.of().formatHex(noise)));
        }
        long everyday = accounts.list(false).get(0).account().id();
        transactions.deliver(everyday, Source.CSV, batch);

        Path backup = download("first.zip");
        assertThat(Files.size(backup)).as("larger than the old upload limit").isGreaterThan(1024 * 1024);

        accounts.addManual("Added after the backup", finances.accounts.Scope.PRIVATE, finances.accounts.Kind.SAVINGS, "");
        assertThat(get("/accounts").body()).contains("Added after the backup");

        // Upload: the file is checked first and nothing changes until the word is typed.
        HttpResponse<String> uploaded = upload(backup);
        assertThat(uploaded.statusCode()).isEqualTo(302);
        String confirmPath = location(uploaded);
        assertThat(confirmPath).contains("/settings/backup/restore/");
        assertThat(get("/accounts").body()).contains("Added after the backup");

        String confirm = get(confirmPath).body();
        assertThat(confirm).contains("Restore from a backup").contains("Backup format 1, database version 9")
                .contains("The backup holds: accounts 1, balances 0, transactions 3,000")
                .contains("The application now holds: accounts 2, balances 0, transactions 3,000").contains("Type RESTORE to confirm");
        assertThat(confirm).doesNotContain("Corner Grocery").doesNotContain("NL51XMPL");

        HttpResponse<String> wrong = post(confirmPath, Map.of("confirm", "yes please"));
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(wrong.body()).contains("The word was not typed correctly");
        assertThat(get("/accounts").body()).contains("Added after the backup");

        HttpResponse<String> restored = post(confirmPath, Map.of("confirm", "restore"));
        assertThat(restored.statusCode()).isEqualTo(302);
        assertThat(location(restored)).endsWith("/settings?done=restored");
        assertThat(get("/accounts").body()).contains("Everyday").doesNotContain("Added after the backup");
        assertThat(get("/transactions").statusCode()).isEqualTo(200);
        String after = get("/settings?done=restored").body();
        assertThat(after).contains("The backup was restored").contains("Undo the last restore");
        assertThat(get(confirmPath).statusCode()).as("the upload is used up").isEqualTo(400);

        HttpResponse<String> notUndone = post("/settings/backup/undo", Map.of("confirm", "restore"));
        assertThat(notUndone.statusCode()).isEqualTo(400);
        assertThat(get("/accounts").body()).doesNotContain("Added after the backup");
        assertThat(post("/settings/backup/undo", Map.of("confirm", "UNDO")).statusCode()).isEqualTo(302);
        assertThat(get("/accounts").body()).contains("Added after the backup");

        try (Stream<Path> scratch = Files.list(DATA.resolve("tmp"))) {
            assertThat(scratch.toList()).as("nothing is left in the scratch folder").isEmpty();
        }
    }

    @Test
    void aFileThatIsNotABackupIsRefusedOnThePageAndChangesNothing() throws Exception {
        Path text = Files.writeString(DATA.resolveSibling(DATA.getFileName() + "-notes.txt"), "not a backup");
        HttpResponse<String> response = upload(text);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("not a backup made by this application").contains("Nothing was changed");
        assertThat(get("/settings").statusCode()).isEqualTo(200);
    }

    @Test
    void aBackupIsOnlyMadeOnARequestFromThePageItself() throws Exception {
        HttpResponse<String> crossSite = post("/settings/backup/download", Map.of(), "Sec-Fetch-Site", "cross-site");
        assertThat(crossSite.statusCode()).isEqualTo(403);
        assertThat(get("/settings/backup/download").statusCode()).as("a download is a POST, never a link").isEqualTo(405);
    }
}
