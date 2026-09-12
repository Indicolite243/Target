package com.stockmanager.assistant.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InternalMcpTokenFilterTests {
    @Test
    void rejectsMissingTokenAndAllowsMatchingToken() throws Exception {
        InternalMcpTokenFilter filter = new InternalMcpTokenFilter("secret-token", "/internal/mcp");
        MockHttpServletRequest rejected = new MockHttpServletRequest("POST", "/internal/mcp");
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
        filter.doFilter(rejected, rejectedResponse, new MockFilterChain());
        assertEquals(401, rejectedResponse.getStatus());

        MockHttpServletRequest accepted = new MockHttpServletRequest("POST", "/internal/mcp");
        accepted.addHeader(InternalMcpTokenFilter.HEADER, "secret-token");
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();
        filter.doFilter(accepted, acceptedResponse, new MockFilterChain());
        assertEquals(200, acceptedResponse.getStatus());
    }
}
