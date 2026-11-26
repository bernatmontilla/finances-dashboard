package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The Origin rule of the same-origin filter without the web layer: which host spellings count as "ours". */
class SameOriginFilterTest {

    private final SameOriginFilter filter = new SameOriginFilter();

    private int status(String method, String host, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/accounts");
        if (host != null) {
            request.addHeader("Host", host);
        }
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain.getRequest() == null ? response.getStatus() : HttpServletResponse.SC_OK;
    }

    @Test
    void theHostTheBrowserSeesIsComparedWithTheHostHeader() throws Exception {
        assertThat(status("POST", "example.com:8080", "http://example.com:8080")).isEqualTo(200);
        assertThat(status("POST", "example.com", "https://example.com")).isEqualTo(200);
        assertThat(status("POST", "example.com:443", "https://example.com")).isEqualTo(200);
        assertThat(status("POST", "example.com", "http://example.com:80")).isEqualTo(200);
        assertThat(status("POST", "EXAMPLE.com", "http://example.COM")).isEqualTo(200);
        assertThat(status("POST", "[::1]:8080", "http://[::1]:8080")).isEqualTo(200);
    }

    @Test
    void anotherHostOrPortIsRefused() throws Exception {
        assertThat(status("POST", "example.com:8080", "http://example.com:9090")).isEqualTo(403);
        assertThat(status("POST", "example.com", "http://example.com:8080")).isEqualTo(403);
        assertThat(status("POST", "example.com", "http://example.org")).isEqualTo(403);
        assertThat(status("POST", "example.com", "http://example.com.evil.test")).isEqualTo(403);
        assertThat(status("DELETE", "example.com", "http://example.org")).isEqualTo(403);
    }

    @Test
    void anOriginThatIsNoOriginIsRefused() throws Exception {
        assertThat(status("POST", "example.com", "null")).isEqualTo(403);
        assertThat(status("POST", "example.com", "not a url")).isEqualTo(403);
        assertThat(status("POST", "example.com", "")).isEqualTo(403);
    }

    @Test
    void noOriginMeansNoCheckAndReadingIsNeverChecked() throws Exception {
        assertThat(status("POST", "example.com", null)).isEqualTo(200);
        assertThat(status("GET", "example.com", "http://example.org")).isEqualTo(200);
    }
}
