package com.bot.bot.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SlashNormalizingFilterTest {

    private final SlashNormalizingFilter filter = new SlashNormalizingFilter();

    @Test
    void redirectsDoubleSlashToSingleSlash() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "//action");
        request.setQueryString("token=abc123xyz&do=approve");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(302, response.getStatus());
        assertEquals("/action?token=abc123xyz&do=approve", response.getRedirectedUrl());
    }

    @Test
    void passesNormalPathThroughChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/action");
        request.setQueryString("token=abc123xyz&do=approve");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertNull(response.getRedirectedUrl());
        assertEquals(200, response.getStatus());
    }

    @Test
    void redirectsJsessionidPathToCanonicalPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/;jsessionid=38AC1B5C762438B8BDA2CEE90C0278B2");
        request.setQueryString("user=PranavSuryawanshi11");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(302, response.getStatus());
        assertEquals("/?user=PranavSuryawanshi11", response.getRedirectedUrl());
    }

    @Test
    void redirectsDuplicateSlashWithJsessionid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "//;jsessionid=12345");
        request.setQueryString("user=octocat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(302, response.getStatus());
        assertEquals("/?user=octocat", response.getRedirectedUrl());
    }
}

