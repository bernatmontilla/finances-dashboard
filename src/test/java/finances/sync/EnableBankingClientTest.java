package finances.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finances.data.DataFolder;
import finances.outbound.ApplicationCredentials;
import finances.outbound.BankApiException;
import finances.outbound.EnableBankingClient;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one place that talks to Enable Banking: its host, its token, its errors (fictional data only). */
class EnableBankingClientTest {

    @TempDir
    Path folder;

    private EnableBankingClient client(DataFolder data, String baseUrl, long timeoutSeconds) {
        return new EnableBankingClient(new ApplicationCredentials(data), Clock.systemUTC(), baseUrl, timeoutSeconds);
    }

    @Test
    void onlyAnHttpsHostWithoutAPathIsAcceptedAsTheApiAddressAndPlainHttpOnlyForThisMachine() {
        try (DataFolder data = DataFolder.open(folder.toString())) {
            client(data, "https://api.enablebanking.com", 5);
            client(data, "http://127.0.0.1:9999", 5);
            client(data, "http://localhost:9999", 5);
            for (String bad : new String[] {"http://api.enablebanking.com", "http://example.com:8080", "https://api.enablebanking.com/v1",
                    "ftp://example.com", "https://user:secret@api.enablebanking.com", "not a url", "", "https://"}) {
                assertThatThrownBy(() -> client(data, bad, 5)).as(bad).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("finances.outbound.enable-banking.base-url").hasMessageNotContaining("secret");
            }
        }
    }

    @Test
    void withoutTheApplicationFilesNothingIsConfiguredAndNothingIsSent() {
        try (DataFolder data = DataFolder.open(folder.toString())) {
            EnableBankingClient client = client(data, "http://127.0.0.1:9", 5);

            assertThat(client.configured()).isFalse();
            assertThatThrownBy(() -> client.banks(null)).isInstanceOfSatisfying(BankApiException.class,
                    e -> assertThat(e.reason()).isEqualTo(BankApiException.Reason.NOT_CONFIGURED));
        }
    }

    @Test
    void aKeyFileThatIsNotAKeyIsRefusedWithoutQuotingIt() throws Exception {
        Path secrets = Files.createDirectories(folder.resolve("secrets"));
        Files.writeString(secrets.resolve("enable-banking-application-id"), FakeEnableBanking.APPLICATION_ID);
        Files.writeString(secrets.resolve("enable-banking-key.pem"), "-----BEGIN PRIVATE KEY-----\nbm90LWEta2V5LXNlY3JldC10ZXh0\n-----END PRIVATE KEY-----\n");
        try (DataFolder data = DataFolder.open(folder.toString())) {
            EnableBankingClient client = client(data, "http://127.0.0.1:9", 5);

            assertThat(client.configured()).isTrue();
            assertThatThrownBy(() -> client.banks(null)).isInstanceOfSatisfying(BankApiException.class, e -> {
                assertThat(e.reason()).isEqualTo(BankApiException.Reason.NOT_CONFIGURED);
                assertThat(e.getMessage()).doesNotContain("bm90LWEta2V5").doesNotContain("not-a-key");
            });
        }
    }

    @Test
    void aHostThatCannotBeReachedIsUnreachableAndASlowOneTimesOut() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        try (FakeEnableBanking bank = new FakeEnableBanking()) {
            bank.install(folder, false);
            try (DataFolder data = DataFolder.open(folder.toString())) {
                assertThatThrownBy(() -> client(data, "http://127.0.0.1:" + closedPort, 5).banks(null)).isInstanceOfSatisfying(BankApiException.class,
                        e -> assertThat(e.reason()).isEqualTo(BankApiException.Reason.UNREACHABLE));

                bank.balancesDelay(3000, new CountDownLatch(1));
                assertThatThrownBy(() -> client(data, bank.baseUrl(), 1).balances(FakeEnableBanking.CURRENT_UID, Map.of()))
                        .isInstanceOfSatisfying(BankApiException.class, e -> assertThat(e.reason()).isEqualTo(BankApiException.Reason.TIMEOUT));
            }
        }
    }

    @Test
    void errorsAreToldApartByTheirTextCodeNotTheirStatus() throws Exception {
        try (FakeEnableBanking bank = new FakeEnableBanking()) {
            bank.install(folder, false);
            try (DataFolder data = DataFolder.open(folder.toString())) {
                EnableBankingClient client = client(data, bank.baseUrl(), 5);

                bank.fail(FakeEnableBanking.CURRENT_UID, "error-rate-limit");
                assertThatThrownBy(() -> client.balances(FakeEnableBanking.CURRENT_UID, Map.of())).isInstanceOfSatisfying(BankApiException.class, e -> {
                    assertThat(e.reason()).isEqualTo(BankApiException.Reason.RATE_LIMITED);
                    assertThat(e.apiError()).isEqualTo("ASPSP_RATE_LIMIT_EXCEEDED");
                    assertThat(e.getMessage()).doesNotContain("Rate limit");
                });
                bank.fail(FakeEnableBanking.CURRENT_UID, "error-expired-session");
                assertThatThrownBy(() -> client.balances(FakeEnableBanking.CURRENT_UID, Map.of())).isInstanceOfSatisfying(BankApiException.class,
                        e -> assertThat(e.reason()).isEqualTo(BankApiException.Reason.CONSENT_ENDED));
                bank.fail(FakeEnableBanking.CURRENT_UID, "error-psu-header-not-provided");
                assertThatThrownBy(() -> client.balances(FakeEnableBanking.CURRENT_UID, Map.of())).isInstanceOfSatisfying(BankApiException.class,
                        e -> assertThat(e.reason()).isEqualTo(BankApiException.Reason.REJECTED));
                assertThat(bank.requests()).allMatch(FakeEnableBanking.Request::tokenValid);
            }
        }
    }

    @Test
    void anAccountIdThatCouldChangeThePathIsRefusedBeforeARequestIsMade() throws Exception {
        try (FakeEnableBanking bank = new FakeEnableBanking()) {
            bank.install(folder, false);
            try (DataFolder data = DataFolder.open(folder.toString())) {
                EnableBankingClient client = client(data, bank.baseUrl(), 5);

                assertThatThrownBy(() -> client.balances("../payments", Map.of())).isInstanceOf(BankApiException.class);
                assertThatThrownBy(() -> client.session("a/b")).isInstanceOf(BankApiException.class);
                assertThat(bank.requests()).isEmpty();
            }
        }
    }
}
