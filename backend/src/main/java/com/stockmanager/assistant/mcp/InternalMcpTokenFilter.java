package com.stockmanager.assistant.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 只允许持有内部令牌的 MCP Client 访问工具服务。 */
public final class InternalMcpTokenFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Internal-Token";
    private final byte[] expected;
    private final String endpoint;

    public InternalMcpTokenFilter(String token, String endpoint) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("MCP internal token must not be blank");
        this.expected = token.getBytes(StandardCharsets.UTF_8);
        this.endpoint = normalizeEndpoint(endpoint);
    }

    public static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("MCP endpoint must not be blank");
        String normalized = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        while (normalized.length() > 1 && normalized.endsWith("/"))
            normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals(endpoint) || path.startsWith(endpoint + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "MCP authentication required");
            return;
        }
        chain.doFilter(request, response);
    }
}
