package com.bot.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Main application class for the PR Review Bot.
 * Bootstraps Spring Boot and loads .env configuration before context initialization.
 */
@SpringBootApplication
public class BotApplication {

    public static void main(String[] args) {
        loadDotEnv();
        configureDatabaseFallback();
        SpringApplication.run(BotApplication.class, args);
    }

    /**
     * Loads .env file from the working directory into system properties.
     * Spring's property resolution ({@code ${...}}) checks system properties
     * after OS environment variables, so values set here will be available
     * for {@code application.yaml} placeholder resolution.
     *
     * Does NOT override existing OS environment variables.
     */
    private static void loadDotEnv() {
        File envFile = findEnvFile();
        if (envFile == null) {
            System.err.println("Warning: .env file not found (checked ./, ./bot/.env, ../.env)");
            return;
        }

        try {
            Properties props = new Properties();
            try (FileReader reader = new FileReader(envFile, StandardCharsets.UTF_8)) {
                props.load(reader);
            }

            int loaded = 0;
            for (String key : props.stringPropertyNames()) {
                if (System.getenv(key) == null && System.getProperty(key) == null) {
                    System.setProperty(key, props.getProperty(key));
                    loaded++;
                }
            }
            System.out.println("Loaded " + loaded + " entries from " + envFile.getName());
        } catch (IOException e) {
            System.err.println("Warning: Failed to load .env file: " + e.getMessage());
        }
    }

    /** Search common locations for the .env file. */
    private static File findEnvFile() {
        String[] candidates = { ".env", "bot/.env", "../.env" };
        for (String path : candidates) {
            File f = new File(path);
            if (f.isFile()) return f;
        }
        return null;
    }

    /**
     * If PostgreSQL is not reachable on the configured host/port, automatically
     * fall back to the embedded H2 file database to ensure zero-configuration execution.
     */
    private static void configureDatabaseFallback() {
        String dbUrl = System.getProperty("DATABASE_URL");
        if (dbUrl == null) {
            dbUrl = System.getenv("DATABASE_URL");
        }

        // If no custom DATABASE_URL or using default localhost PostgreSQL or H2
        if (dbUrl == null || dbUrl.contains("h2:") || dbUrl.contains("localhost:5432/pr_triage") || dbUrl.contains("127.0.0.1:5432/pr_triage")) {
            boolean isH2 = dbUrl != null && dbUrl.contains("h2:");
            boolean reachable = !isH2 && isPortReachable("localhost", 5432, 500);
            if (!reachable) {
                System.out.println("[DB] PostgreSQL not detected on localhost:5432. Falling back to embedded H2 database (data/pr_triage)...");
                new File("data").mkdirs();
                new File("bot/data").mkdirs();
                System.setProperty("DATABASE_URL", "jdbc:h2:./data/pr_triage;AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
                System.setProperty("DATABASE_USER", "sa");
                System.setProperty("DATABASE_PASSWORD", "");
                System.setProperty("spring.jpa.database-platform", "org.hibernate.dialect.H2Dialect");
                System.setProperty("spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.H2Dialect");
            }
        }
    }

    private static boolean isPortReachable(String host, int port, int timeoutMs) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
