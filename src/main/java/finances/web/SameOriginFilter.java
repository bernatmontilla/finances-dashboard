package finances.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The app has no login (it sits on a private network), so any page the owner's browser opens
 * could post a form to it. A browser says where a request comes from in {@code Sec-Fetch-Site}:
 * a change that does not come from this site itself is refused. Reading is never blocked.
 *
 * <p>A browser that sends no {@code Sec-Fetch-Site} is checked by {@code Origin} when it has one: the
 * origin's host and port must be the {@code Host} the request arrived with. The scheme is not compared:
 * behind a TLS-terminating proxy the application sees {@code http} while the browser sees {@code https},
 * and forwarded headers are not trusted here. A request with neither header (command line, very old
 * browsers) is let through. See {@code docs/decisions/46-error-page-and-origin-check.md}.
 */
@Component
class SameOriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> OWN_SITE = Set.of("same-origin", "none");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE_METHODS.contains(request.getMethod()) && !comesFromThisSite(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean comesFromThisSite(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null) {
            return OWN_SITE.contains(site);
        }
        String origin = request.getHeader("Origin");
        return origin == null || originMatchesHost(origin, request.getHeader("Host"));
    }

    private static boolean originMatchesHost(String origin, String host) {
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (host == null || uri.getHost() == null || scheme == null) {
            return false;
        }
        int defaultPort = "https".equalsIgnoreCase(scheme) ? 443 : 80;
        int originPort = uri.getPort() == -1 ? defaultPort : uri.getPort();
        return authority(uri.getHost(), originPort).equals(hostHeader(host, defaultPort));
    }

    /** The {@code Host} header as host and port, a missing port taking the default of the origin's scheme. */
    private static String hostHeader(String host, int defaultPort) {
        try {
            URI uri = URI.create("//" + host);
            if (uri.getHost() == null) {
                return "";
            }
            return authority(uri.getHost(), uri.getPort() == -1 ? defaultPort : uri.getPort());
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String authority(String host, int port) {
        return host.toLowerCase(Locale.ROOT) + ":" + port;
    }
}
