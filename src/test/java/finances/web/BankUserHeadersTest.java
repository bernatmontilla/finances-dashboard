package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The headers of the person using the page that go with a fetch the user started (issue #39): behind a reverse
 * proxy (tailscale serve) the address the server sees is its own, and that is no address of the person.
 */
class BankUserHeadersTest {

    private static Map<String, String> headersFrom(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/settings/bank/sync");
        request.setRemoteAddr(remoteAddress);
        request.addHeader("User-Agent", "Fictional Browser");
        return BankViews.userHeaders(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.1.2.3", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1", "169.254.10.20", "fe80::1",
            "fe80::1%eth0", "0.0.0.0", "::", "0:0:0:0:0:0:0:0"})
    void noAddressIsSentWhenTheClientLooksLikeTheProxyItself(String remoteAddress) {
        Map<String, String> headers = headersFrom(remoteAddress);

        assertThat(headers).doesNotContainKey("Psu-Ip-Address");
        assertThat(headers).containsEntry("Psu-User-Agent", "Fictional Browser");
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.0.2.10", "10.1.2.3", "100.64.0.7", "2001:db8::10"})
    void anyOtherAddressIsSentAsItIs(String remoteAddress) {
        assertThat(headersFrom(remoteAddress)).containsEntry("Psu-Ip-Address", remoteAddress);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not an address", "example.com", "999.1.1.1"})
    void somethingThatIsNoAddressIsNotSentAndNothingIsLookedUp(String remoteAddress) {
        assertThat(headersFrom(remoteAddress)).doesNotContainKey("Psu-Ip-Address");
    }
}
