package finances.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Rules that can be checked by reading files (AGENTS.md): the build enforces them, not the review. */
class GuardTest {

    private static final Path MAIN = Path.of("src/main");
    private static final Path TEMPLATES = MAIN.resolve("resources/templates");
    private static final Path STATIC = MAIN.resolve("resources/static");

    private static List<Path> files(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).sorted().toList();
        }
    }

    @Test
    void noTemplateOrStaticFileReferencesAnotherHost() throws IOException {
        Pattern url = Pattern.compile("(https?:)?//[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|https?://", Pattern.CASE_INSENSITIVE);
        List<String> found = new ArrayList<>();
        List<Path> all = new ArrayList<>(files(TEMPLATES));
        all.addAll(files(STATIC));
        for (Path file : all) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (url.matcher(lines.get(i)).find()) {
                    found.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }
        assertThat(found).as("references to another host (PRODUCT invariant 2)").isEmpty();
    }

    @Test
    void noTemplateHoldsAUserVisibleTextLiteral() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : files(TEMPLATES)) {
            String html = Files.readString(file)
                    .replaceAll("(?s)<!--.*?-->", "")
                    .replaceAll("(?s)<(script|style)\\b.*?</\\1>", "")
                    .replaceAll("(?is)<!DOCTYPE[^>]*>", "");
            // Text comes from th:text / th:utext with an empty element body, so what is left once
            // the tags are gone must be white space.
            String text = html.replaceAll("(?s)<[^>]*>", "").replace("&nbsp;", " ").strip();
            if (!text.isEmpty()) {
                found.add(file + ": text outside a message key: " + text.lines().findFirst().orElse(""));
            }
            Matcher attribute = Pattern.compile("\\s(title|alt|placeholder|aria-label|label|value)=\"[^\"]+\"").matcher(html);
            while (attribute.find()) {
                found.add(file + ": literal attribute" + attribute.group().strip());
            }
        }
        assertThat(found).as("every text comes from messages.properties").isEmpty();
    }

    @Test
    void everyMessageKeyUsedInATemplateExists() throws IOException {
        Properties messages = new Properties();
        try (var in = Files.newBufferedReader(MAIN.resolve("resources/messages.properties"))) {
            messages.load(in);
        }
        Pattern key = Pattern.compile("#\\{([A-Za-z0-9_.-]+)");
        List<String> missing = new ArrayList<>();
        for (Path file : files(TEMPLATES)) {
            Matcher matcher = key.matcher(Files.readString(file));
            while (matcher.find()) {
                if (!messages.containsKey(matcher.group(1))) {
                    missing.add(file + ": " + matcher.group(1));
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void outsideFinancesOutboundNoClassOpensANetworkConnection() throws IOException {
        Pattern network = Pattern.compile(
                "java\\.net\\.http|java\\.net\\.(Socket|ServerSocket|URL|URLConnection|HttpURLConnection|DatagramSocket)\\b"
                        + "|javax\\.net\\.|\\b(HttpClient|RestTemplate|RestClient|WebClient|HttpURLConnection|SocketChannel)\\b"
                        + "|java\\.net\\.\\*|\\bInetSocketAddress\\b|\\bProcessBuilder\\b|Runtime\\.getRuntime\\(\\)\\.exec"
                        + "|\\.openConnection\\(|\\.openStream\\(|new\\s+Socket\\(|new\\s+URL\\(");
        Path outbound = MAIN.resolve("java/finances/outbound");
        List<String> found = new ArrayList<>();
        for (Path file : files(MAIN.resolve("java"))) {
            if (file.startsWith(outbound) || !file.toString().endsWith(".java")) {
                continue;
            }
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (network.matcher(lines.get(i)).find()) {
                    found.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }
        assertThat(found).as("network access belongs in finances.outbound (hard rule 7)").isEmpty();
    }

    @Test
    void noFileHoldsAFixedDataFolderOutsideTheBuild() throws IOException {
        Pattern fixed = Pattern.compile("finances\\.data-dir\\s*[=:]\\s*\\S");
        List<String> found = new ArrayList<>();
        for (Path root : List.of(MAIN.resolve("resources"), Path.of("src/test/resources"))) {
            for (Path file : files(root)) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (fixed.matcher(lines.get(i)).find()) {
                        found.add(file + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(found).as("the data folder has no default; tests use a temporary one").isEmpty();
    }

    @Test
    void theReleaseWorkflowKeepsItsPromises() throws IOException {
        // Issue #4: a release is built on GitHub's runners only, never for a pull request, with
        // the least permission, and with the two public asset names (AGENTS.md hard rule 4).
        String workflow = Files.readString(Path.of(".github/workflows/release.yml"))
                .lines().filter(line -> !line.strip().startsWith("#")).reduce("", (a, b) -> a + b + "\n");
        assertThat(workflow).doesNotContain("RUNS_ON").doesNotContain("pull_request:").doesNotContain("pull_request_target").doesNotContain("self-hosted");
        assertThat(workflow).contains("workflow_run").contains("workflow_run.event == 'push'");
        assertThat(workflow).contains("finances-dashboard.jar").contains("finances-dashboard.jar.sha256");
        assertThat(workflow).contains("permissions: {}").contains("contents: write");
        assertThat(workflow.split("contents: write", -1)).as("contents: write on the release job only").hasSize(2);
        assertThat(workflow).doesNotContain("pull-requests: write").doesNotContain("write-all");
    }

    @Test
    void deployFilesAndTheInstallGuideHoldOnlyPlaceholders() throws IOException {
        // Issue #5, hard rule 1: templates only, no real installation's home path, host or address.
        Pattern homePath = Pattern.compile("/home/|/Users/|/root\\b|[A-Za-z]:\\\\Users");
        Pattern address = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");
        Pattern url = Pattern.compile("https?://([^/\\s\"'`):]+)", Pattern.CASE_INSENSITIVE);
        Pattern tailnet = Pattern.compile("[A-Za-z0-9-]\\.(ts\\.net|local|lan|home)\\b", Pattern.CASE_INSENSITIVE);
        Set<String> allowedHosts = Set.of("api.github.com", "github.com", "localhost", "127.0.0.1", "example.com",
                "tailscale.com", "systemd.io", "<host>");
        List<Path> all = new ArrayList<>(files(Path.of("deploy")));
        all.add(Path.of("docs/install.md"));
        all.add(Path.of("scripts/dev/test-update.sh"));
        List<String> found = new ArrayList<>();
        for (Path file : all) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String where = file + ":" + (i + 1) + ": " + line.strip();
                if (homePath.matcher(line).find() || tailnet.matcher(line).find()) {
                    found.add(where);
                }
                Matcher ip = address.matcher(line);
                while (ip.find()) {
                    if (!ip.group().equals("127.0.0.1")) {
                        found.add(where);
                    }
                }
                Matcher host = url.matcher(line);
                while (host.find()) {
                    if (!allowedHosts.contains(host.group(1).toLowerCase())) {
                        found.add(where);
                    }
                }
            }
        }
        assertThat(found).as("an absolute home path, a real host or an address in deploy/ or docs/install.md").isEmpty();
    }
}
