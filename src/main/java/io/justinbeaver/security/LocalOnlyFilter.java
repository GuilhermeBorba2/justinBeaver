package io.justinbeaver.security;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * This API can open database connections and run SQL, and it listens on loopback without
 * authentication. Two rules keep a web page in the user's browser from driving it:
 * <ul>
 *   <li>{@code Host} (and {@code Origin}, when sent) must be a loopback name. This defeats DNS
 *       rebinding, where an attacker domain resolves to 127.0.0.1.</li>
 *   <li>State-changing requests must carry {@value #CLIENT_HEADER}. A custom header forces a CORS
 *       preflight for cross-origin callers, and this server never answers preflights.</li>
 * </ul>
 */
@Component
public class LocalOnlyFilter extends OncePerRequestFilter {

    public static final String CLIENT_HEADER = "X-JB-Client";

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isLoopbackHost(request.getServerName())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Only loopback hosts are allowed");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !isLoopbackOrigin(origin)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cross-origin requests are not allowed");
            return;
        }
        if (!SAFE_METHODS.contains(request.getMethod()) && request.getHeader(CLIENT_HEADER) == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Missing " + CLIENT_HEADER + " header");
            return;
        }
        chain.doFilter(request, response);
    }

    /** {@code serverName} is the container's parse of the Host header (port already stripped). */
    static boolean isLoopbackHost(String serverName) {
        return serverName != null && LOOPBACK.contains(serverName.toLowerCase());
    }

    static boolean isLoopbackOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && LOOPBACK.contains(host.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
