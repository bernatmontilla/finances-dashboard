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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Starts the real application on a temporary data folder and asks it for its pages. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationTest {

    private static final Path DATA = createTempDataFolder();

    @DynamicPropertySource
    static void dataFolder(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
    }

    private static Path createTempDataFolder() {
        try {
            return Files.createTempDirectory("finances-test-data");
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

    @Value("${server.address}")
    String address;

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void overviewShowsNameVersionAndNoAccountsYet() throws Exception {
        HttpResponse<String> response = get("/");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Finances dashboard").contains("No accounts yet");
        // The version is the released one (#4), so only its shape is fixed here.
        assertThat(response.body()).containsPattern("Version \\d+\\.\\d+\\.\\d+");
        assertThat(response.body()).contains("name=\"viewport\"");
    }

    @Test
    void stylesheetIsServedByTheApplicationAndSupportsDarkMode() throws Exception {
        HttpResponse<String> response = get("/app.css");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("prefers-color-scheme: dark");
    }

    @Test
    void healthAnswers200WithTheVersionWhenTheDatabaseIsReachable() throws Exception {
        HttpResponse<String> response = get("/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"ok\"").containsPattern("\"version\":\"\\d+\\.\\d+\\.\\d+");
    }

    @Test
    void theDatabaseIsCreatedInTheDataFolderByTheFirstMigration() {
        assertThat(DATA.resolve("finances.db")).exists();
    }

    @Test
    void bindsToLoopbackOnly() {
        assertThat(address).isEqualTo("127.0.0.1");
    }
}
