package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.AccountException;
import finances.importing.BalanceResult;
import finances.importing.CsvImportException;
import finances.importing.RefusalReason;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Importing a CSV file through the pages of the real application on a temporary data folder. Fictional data only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportPagesTest {

    private static final Path DATA = createTempDataFolder();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void dataFolder(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
    }

    private static Path createTempDataFolder() {
        try {
            return Files.createTempDirectory("finances-test-import");
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

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(String path, byte[] file) throws Exception {
        String boundary = "----finances" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"export.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = ImportPagesTest.class.getResourceAsStream("/csv/" + name)) {
            return in.readAllBytes();
        }
    }

    private long createAccount(String name, String source) throws Exception {
        HttpResponse<String> response = post("/accounts", Map.of("name", name, "scope", "private", "kind", "current", "iban", "", "source", source));
        assertThat(response.statusCode()).isEqualTo(302);
        Matcher id = Pattern.compile("/accounts/(\\d+)$").matcher(response.headers().firstValue("Location").orElseThrow());
        assertThat(id.find()).isTrue();
        return Long.parseLong(id.group(1));
    }

    /** What the preview form holds, ready to be posted back: the hidden token and every select as it stands. */
    private static Map<String, String> formOf(String html) {
        Map<String, String> form = new LinkedHashMap<>();
        Matcher token = Pattern.compile("name=\"token\" value=\"([0-9a-f]+)\"").matcher(html);
        assertThat(token.find()).as("a token on the preview").isTrue();
        form.put("token", token.group(1));
        Matcher select = Pattern.compile("<select id=\"(\\w+)\" name=\"\\w+\">(.*?)</select>", Pattern.DOTALL).matcher(html);
        while (select.find()) {
            Matcher chosen = Pattern.compile("<option value=\"([^\"]*)\" selected=\"selected\"").matcher(select.group(2));
            assertThat(chosen.find()).as("an option chosen in " + select.group(1)).isTrue();
            form.put(select.group(1), chosen.group(1));
        }
        if (html.contains("id=\"header\" name=\"header\" type=\"checkbox\" value=\"true\" checked=\"checked\"")) {
            form.put("header", "true");
        }
        return form;
    }

    @Test
    void theOwnerUploadsAPreviewsAndImportsAFileAndImportingItAgainAddsNothing() throws Exception {
        long id = createAccount("Imported current", "csv");
        String account = get("/accounts/" + id).body();
        assertThat(account).contains("/accounts/" + id + "/import").contains("CSV import");

        assertThat(get("/accounts/" + id + "/import").statusCode()).isEqualTo(200);

        HttpResponse<String> preview = upload("/accounts/" + id + "/import", finances.importing.CsvFixtures.semicolonDebitCredit());
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.body()).contains("Corner Grocery").contains("Example Café").contains("5 rows read: 5 can be imported, 0 cannot be read.");
        assertThat(preview.body()).doesNotContain("class=\"error\"");

        Map<String, String> form = formOf(preview.body());
        assertThat(form).containsEntry("delimiter", "semicolon").containsEntry("encoding", "windows-1252")
                .containsEntry("dateFormat", "dd-MM-yyyy").containsEntry("decimal", "comma").containsEntry("header", "true")
                .containsEntry("date", "0").containsEntry("debit", "4").containsEntry("credit", "5").containsEntry("amount", "-1");

        HttpResponse<String> report = post("/accounts/" + id + "/import/confirm", form);
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.body()).contains("Added: 5").contains("Already known: 0").contains("Refused: 0");

        String page = get("/accounts/" + id).body();
        assertThat(page).contains("Corner Grocery").contains("-€12.34").contains("€2,500.00").contains("Transactions delivered");

        // The file is not kept: the same token cannot be used again, and a second upload is deduplicated.
        assertThat(post("/accounts/" + id + "/import/confirm", form).statusCode()).isEqualTo(400);
        HttpResponse<String> again = upload("/accounts/" + id + "/import", finances.importing.CsvFixtures.semicolonDebitCredit());
        Map<String, String> offered = formOf(again.body());
        assertThat(offered).as("the mapping is offered again").isEqualTo(withToken(form, offered));
        HttpResponse<String> second = post("/accounts/" + id + "/import/confirm", offered);
        assertThat(second.body()).contains("Added: 0").contains("Already known: 5");
    }

    private static Map<String, String> withToken(Map<String, String> form, Map<String, String> offered) {
        Map<String, String> expected = new LinkedHashMap<>(form);
        expected.put("token", offered.get("token"));
        return expected;
    }

    @Test
    void aFileWithBadRowsShowsThemInThePreviewAndInTheReportAndTheOthersAreImported() throws Exception {
        long id = createAccount("Bad rows", "csv");

        HttpResponse<String> preview = upload("/accounts/" + id + "/import", resource("two-bad-rows.csv"));
        assertThat(preview.body()).contains("4 rows read: 2 can be imported, 2 cannot be read.")
                .contains("Row 3:").contains("not a date in the chosen format").contains("Row 4:").contains("not guessed");

        HttpResponse<String> report = post("/accounts/" + id + "/import/confirm", formOf(preview.body()));
        assertThat(report.body()).contains("Added: 2").contains("Refused: 2").contains("Rows that were not imported")
                .contains("Row 3:").contains("Row 4:");
    }

    @Test
    void theOwnerCorrectsTheMappingInThePreviewBeforeImporting() throws Exception {
        long id = createAccount("Corrected", "csv");
        HttpResponse<String> preview = upload("/accounts/" + id + "/import", resource("tab-balance.csv"));
        Map<String, String> form = formOf(preview.body());
        assertThat(form).containsEntry("balance", "4").containsEntry("decimal", "point");

        // Wrong on purpose: the date read as month first, and the decimal separator as a comma.
        form.put("dateFormat", "MM/dd/yyyy");
        form.put("decimal", "comma");
        HttpResponse<String> corrected = post("/accounts/" + id + "/import/preview", form);
        assertThat(corrected.statusCode()).isEqualTo(200);
        assertThat(corrected.body()).contains("rows read: 0 can be imported, 3 cannot be read.").contains("selected=\"selected\">MM/dd/yyyy");

        form.put("dateFormat", "dd/MM/yyyy");
        form.put("decimal", "point");
        HttpResponse<String> fixed = post("/accounts/" + id + "/import/preview", form);
        assertThat(fixed.body()).contains("3 rows read: 3 can be imported, 0 cannot be read.").contains("€2,480.00").contains("2026-06-03");

        HttpResponse<String> report = post("/accounts/" + id + "/import/confirm", form);
        assertThat(report.body()).contains("Added: 3").contains("added to the balance history");
        assertThat(get("/accounts/" + id).body()).contains("€2,480.00").contains("as of 2026-06-03");
    }

    @Test
    void aSettingThatIsNotOneOfTheAllowedOnesIsRefusedAndAMissingColumnShowsNoResultYet() throws Exception {
        long id = createAccount("Strict", "csv");
        Map<String, String> form = formOf(upload("/accounts/" + id + "/import", resource("comma-signed.csv")).body());

        Map<String, String> tampered = new LinkedHashMap<>(form);
        tampered.put("dateFormat", "yyyy;DROP TABLE");
        HttpResponse<String> refused = post("/accounts/" + id + "/import/confirm", tampered);
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("One of the settings is not allowed");

        Map<String, String> noAmount = new LinkedHashMap<>(form);
        noAmount.put("amount", "-1");
        HttpResponse<String> incomplete = post("/accounts/" + id + "/import/preview", noAmount);
        assertThat(incomplete.statusCode()).isEqualTo(200);
        assertThat(incomplete.body()).contains("Choose the booking date column and the amount");
        HttpResponse<String> notImported = post("/accounts/" + id + "/import/confirm", noAmount);
        assertThat(notImported.statusCode()).isEqualTo(400);
        assertThat(notImported.body()).contains("Choose the booking date column and the amount first.");
    }

    @Test
    void anEmptyUploadAndAFileThatIsNotAFileAreRefusedPolitely() throws Exception {
        long id = createAccount("Empty", "csv");

        HttpResponse<String> none = upload("/accounts/" + id + "/import", new byte[0]);
        assertThat(none.statusCode()).isEqualTo(400);
        assertThat(none.body()).contains("Choose a file to upload.");

        HttpResponse<String> onlyHeader = upload("/accounts/" + id + "/import", "Date;Amount\n".getBytes(StandardCharsets.UTF_8));
        assertThat(onlyHeader.statusCode()).isEqualTo(200);
        assertThat(onlyHeader.body()).contains("0 rows read: 0 can be imported, 0 cannot be read.");
        HttpResponse<String> report = post("/accounts/" + id + "/import/confirm", formOf(onlyHeader.body()));
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.body()).contains("The file has no rows to import. Nothing was changed.");
    }

    @Test
    void onlyACsvAccountOffersTheImport() throws Exception {
        long manual = createAccount("By hand", "manual");
        assertThat(get("/accounts/" + manual).body()).doesNotContain("/import");
        assertThat(get("/accounts/" + manual + "/import").statusCode()).isEqualTo(404);
        assertThat(upload("/accounts/" + manual + "/import", resource("comma-signed.csv")).statusCode()).isEqualTo(404);
        assertThat(get("/accounts/9999/import").statusCode()).isEqualTo(404);

        long csv = createAccount("Archived csv", "csv");
        assertThat(post("/accounts/" + csv + "/archive", Map.of()).statusCode()).isEqualTo(302);
        assertThat(get("/accounts/" + csv + "/import").statusCode()).isEqualTo(409);
        assertThat(get("/accounts/" + csv).body()).doesNotContain("/import");
    }

    @Test
    void aBankSourceCannotBeChosenWhenAddingAnAccountAndACsvAccountTakesABalanceByHand() throws Exception {
        HttpResponse<String> refused = post("/accounts", Map.of("name", "Sneaky", "scope", "private", "kind", "current", "iban", "", "source", "bank"));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("Choose a source.");

        long csv = createAccount("Typed balance", "csv");
        assertThat(post("/accounts/" + csv + "/balances", Map.of("amount", "100,00", "date", "2026-01-01")).statusCode()).isEqualTo(302);
        assertThat(get("/accounts/" + csv).body()).contains("€100.00");
    }

    @Test
    void everyReasonTheImportCanGiveHasAMessage() throws IOException {
        Properties messages = new Properties();
        try (var in = Files.newBufferedReader(Path.of("src/main/resources/messages.properties"))) {
            messages.load(in);
        }
        for (RefusalReason reason : RefusalReason.values()) {
            assertThat(messages).containsKey("import.refusal." + reason.name().toLowerCase(Locale.ROOT));
        }
        for (BalanceResult result : BalanceResult.values()) {
            if (result != BalanceResult.NONE) {
                assertThat(messages).containsKey("import.balance." + result.name().toLowerCase(Locale.ROOT));
            }
        }
        for (CsvImportException.Reason reason : CsvImportException.Reason.values()) {
            assertThat(messages).containsKey("error.import." + reason.name().toLowerCase(Locale.ROOT));
        }
        for (AccountException.Reason reason : AccountException.Reason.values()) {
            assertThat(messages).containsKey("error.account." + reason.name().toLowerCase(Locale.ROOT));
        }
    }
}
