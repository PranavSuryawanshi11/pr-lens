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
}
