package com.bot.bot.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Normalizes requests with consecutive duplicate slashes (such as //action)
 * by redirecting to the canonical single-slash path (e.g. /action).
 * This ensures external reverse proxies (like ngrok) or misconfigured base URLs
 * with trailing slashes do not cause 404 Not Found errors on one-click action links.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SlashNormalizingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri != null && uri.contains("//")) {
            String normalized = uri.replaceAll("/+", "/");
            String query = request.getQueryString() != null ? "?" + request.getQueryString() : "";
            response.sendRedirect(normalized + query);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
