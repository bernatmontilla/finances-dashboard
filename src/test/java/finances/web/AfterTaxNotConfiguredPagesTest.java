package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * No after-tax source configured (issue #18): the application starts, the settings page says so with no Test button, and
 * Sync now and the Test address change nothing and fail nothing. Nothing about a business number is on any page.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AfterTaxNotConfiguredPagesTest {

    private static final Path DATA = createTempDataFolder();
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
        registry.add("finances.sync.enabled", () -> "false");
    }

    private static Path createTempDataFolder() {
        try {
            return Files.createTempDirectory("finances-test-aftertax-none");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void cleanUp() throws IOException {
        try (Stream<Path> paths = Files.walk(DATA)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @LocalServerPort
    int port;

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void withoutASourceTheSectionSaysSoAndNothingFailsOrShowsABusinessNumber() throws Exception {
        String settings = get("/settings").body();
        assertThat(settings).contains("After-tax source").contains("No after-tax source is configured.")
                .doesNotContain("Test the source").doesNotContain("Last value").doesNotContain("Latest fetches");

        HttpResponse<String> syncNow = post("/sync");
        assertThat(syncNow.statusCode()).isEqualTo(302);
        assertThat(get("/").body()).doesNotContain("After-tax").doesNotContain("Business available");

        HttpResponse<String> test = post("/settings/after-tax/test");
        assertThat(test.statusCode()).isEqualTo(302);
        assertThat(test.headers().firstValue("Location").orElseThrow()).endsWith("/settings");
        assertThat(get("/settings").statusCode()).isEqualTo(200);
    }
}
