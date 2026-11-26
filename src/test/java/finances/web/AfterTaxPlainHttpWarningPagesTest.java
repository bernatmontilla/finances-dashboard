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
 * A token over plain http to another host (issue #18): allowed, because a host on the private network is the normal case,
 * but the section warns. The address is a documentation address (192.0.2.0/24); nothing is requested by loading the page.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AfterTaxPlainHttpWarningPagesTest {

    private static final Path DATA = createTempDataFolder();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("finances.data-dir", DATA::toString);
        registry.add("finances.outbound.after-tax.url", () -> "http://192.0.2.10:8080/after-tax.json");
        registry.add("finances.sync.enabled", () -> "false");
    }

    private static Path createTempDataFolder() {
        try {
            Path folder = Files.createTempDirectory("finances-test-aftertax-http");
            Files.createDirectories(folder.resolve("secrets"));
            Files.writeString(folder.resolve("secrets").resolve("after-tax-token"), "fictional-token-0123456789");
            return folder;
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

    @Test
    void theSectionWarnsThatTheTokenTravelsUnencryptedAndNeverShowsTheAddress() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/settings")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.body()).contains("A source is configured (plain http).")
                .contains("The token travels unencrypted over plain http to another host.")
                .doesNotContain("192.0.2.10").doesNotContain("fictional-token");
    }
}
