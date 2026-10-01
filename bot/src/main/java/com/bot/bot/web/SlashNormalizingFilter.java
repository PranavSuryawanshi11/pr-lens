package com.bot.bot.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Normalizes requests with consecutive duplicate slashes (such as //action)
 * or jsessionid path parameters (such as /;jsessionid=...) by redirecting
 * to the canonical clean path (e.g. /action or /).
 * Also wraps the response so encodeRedirectURL never re-appends ;jsessionid=.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SlashNormalizingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        HttpServletResponse cleanResponse = new HttpServletResponseWrapper(response) {
            @Override
            public String encodeRedirectURL(String url) {
                return stripJsessionid(url);
            }

            @Override
            public String encodeURL(String url) {
                return stripJsessionid(url);
            }
        };

        String uri = request.getRequestURI();
        if (uri != null && (uri.contains("//") || uri.toLowerCase().contains(";jsessionid="))) {
            String normalized = stripJsessionid(uri).replaceAll("/+", "/");
            if (normalized.isBlank()) {
                normalized = "/";
            }
            if (!normalized.startsWith("/")) {
                normalized = "/" + normalized;
            }

            if (!normalized.equals(uri)) {
                String query = (request.getQueryString() != null && !request.getQueryString().isBlank())
                        ? "?" + request.getQueryString()
                        : "";
                cleanResponse.sendRedirect(normalized + query);
                return;
            }
        }

        filterChain.doFilter(request, cleanResponse);
    }

    public static String stripJsessionid(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll("(?i);jsessionid=[^/?#;]*", "");
    }
}

