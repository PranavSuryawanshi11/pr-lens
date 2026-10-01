package com.bot.bot.config;

import jakarta.servlet.SessionTrackingMode;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Ensures session tracking strictly uses HTTP cookies and disables URL rewriting (jsessionid).
 * This prevents Tomcat from appending ;jsessionid= to redirect URLs.
 */
@Configuration
public class WebSessionConfig {

    @Bean
    public ServletContextInitializer servletContextInitializer() {
        return servletContext -> {
            servletContext.setSessionTrackingModes(Set.of(SessionTrackingMode.COOKIE));
        };
    }
}
