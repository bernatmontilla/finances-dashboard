package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The upload limit is configurable (finances.import.max-file-size) and a larger file is refused politely. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportTooLargeTest {

    private static final Path DATA = createTempDataFolder();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
        registry.add("finances.import.max-file-size", () -> "2KB");
    }

    private static Path createTempDataFolder() {
        try {
            return Files.createTempDirectory("finances-test-import-large");
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

    @Test
    void aFileOverTheConfiguredLimitIsRefusedAndNothingIsHeld() throws Exception {
        String form = "name=" + URLEncoder.encode("Large", StandardCharsets.UTF_8) + "&scope=private&kind=current&iban=&source=csv";
        HttpResponse<String> created = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/accounts"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher id = Pattern.compile("/accounts/(\\d+)$").matcher(created.headers().firstValue("Location").orElseThrow());
        assertThat(id.find()).isTrue();

        StringBuilder rows = new StringBuilder("Date;Amount\n");
        for (int i = 0; i < 400; i++) {
            rows.append("01-06-2026;-1,00\n");
        }
        String boundary = "----large";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.csv\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(rows.toString().getBytes(StandardCharsets.UTF_8));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/accounts/" + id.group(1) + "/import"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("The file is too large");
    }
}
