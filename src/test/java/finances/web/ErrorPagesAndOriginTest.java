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
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * #46: the error page says what went wrong (404, 403, 500) without any detail of the failure, and a
 * change request without {@code Sec-Fetch-Site} is checked by its {@code Origin}. Real application,
 * temporary data folder.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ErrorPagesAndOriginTest.Boom.class)
class ErrorPagesAndOriginTest {

    private static final Path DATA = createTempDataFolder();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String SECRET_DETAIL = "detail-that-must-not-show-1234.56";

    /**
     * A page that always fails the way an unexpected bug would, with a message that must never be shown.
     * Nested in a test class, so component scanning skips it and only this test imports it.
     */
    @Controller
    static class Boom {
        @GetMapping("/test-boom")
        String boom() {
            throw new IllegalStateException(SECRET_DETAIL);
        }
    }

    @DynamicPropertySource
    static void dataFolder(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
    }

    private static Path createTempDataFolder() {
        try {
            return Files.createTempDirectory("finances-test-errors");
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

    private HttpResponse<String> page(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Accept", "text/html").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/accounts"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "text/html")
                .POST(HttpRequest.BodyPublishers.ofString("name=Origin+Check&scope=private&kind=current&iban="));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anUnknownPageSaysNotFoundWithAWayBack() throws Exception {
        HttpResponse<String> response = page("/accounts/99999");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("Page not found").contains("Back to the overview").contains("href=\"/\"");
    }

    @Test
    void aRefusedChangeSaysNotAllowedWithAWayBack() throws Exception {
        HttpResponse<String> response = post("Sec-Fetch-Site", "cross-site");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("Not allowed").contains("Back to the overview").doesNotContain("Page not found");
    }

    @Test
    void anUnexpectedFailureSaysSomethingWentWrongAndShowsNoDetail() throws Exception {
        HttpResponse<String> response = page("/test-boom");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("Something went wrong").contains("Back to the overview")
                .doesNotContain("Page not found").doesNotContain(SECRET_DETAIL).doesNotContain("IllegalStateException")
                .doesNotContain("/test-boom").doesNotContain("\"timestamp\"");
    }

    @Test
    void aChangeWithAForeignOriginAndNoFetchSiteIsRefused() throws Exception {
        assertThat(post("Origin", "https://evil.example.com").statusCode()).isEqualTo(403);
        assertThat(post("Origin", "http://127.0.0.1:1").statusCode()).isEqualTo(403);
        assertThat(post("Origin", "null").statusCode()).isEqualTo(403);
    }

    @Test
    void aChangeWithOurOwnOriginAndNoFetchSitePasses() throws Exception {
        assertThat(post("Origin", "http://127.0.0.1:" + port).statusCode()).isEqualTo(302);
    }

    @Test
    void aChangeWithNeitherHeaderPassesAsBefore() throws Exception {
        assertThat(post().statusCode()).isEqualTo(302);
    }

    @Test
    void theFetchSiteHeaderStillDecidesWhenItIsThere() throws Exception {
        assertThat(post("Sec-Fetch-Site", "cross-site", "Origin", "http://127.0.0.1:" + port).statusCode()).isEqualTo(403);
        assertThat(post("Sec-Fetch-Site", "same-origin", "Origin", "https://evil.example.com").statusCode()).isEqualTo(302);
    }
}
