package com.example.chatbot;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sonar.api.config.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Пул соединений с БД для таблицы chatbot.user_tokens.
 *
 * Особенности:
 *  - Ленивая инициализация: пул создаётся при первом обращении, когда
 *    Configuration уже содержит значения из админ-панели.
 *  - Кэшируется по паре (url, user): если админ поменяет настройки,
 *    пул будет пересоздан при следующем обращении (старый закрывается).
 *  - Таймауты ограничены, чтобы запросы не висели бесконечно.
 */
public class ChatbotDataSource {

    private static final Logger LOG = LoggerFactory.getLogger(ChatbotDataSource.class);

    // FIX: держим не только сам DataSource, но и "ключ" конфига,
    // по которому он был создан. Если ключ изменится — пересоздаём пул.
    private static final AtomicReference<DataSource> INSTANCE = new AtomicReference<>();
    private static final AtomicReference<String> INSTANCE_KEY = new AtomicReference<>();

    public static DataSource get(Configuration config) {
        String url      = config.get(ChatbotSettings.DB_URL).orElse("").trim();
        String user     = config.get(ChatbotSettings.DB_USERNAME).orElse("").trim();
        String password = config.get(ChatbotSettings.DB_PASSWORD).orElse("");

        if (url.isEmpty() || user.isEmpty()) {
            throw new IllegalStateException(
                    "Database connection is not configured. " +
                            "Set 'Database JDBC URL' and 'Database Username' " +
                            "in SonarQube admin settings (category 'Chatbot').");
        }

        // FIX: ключ идентифицирует конфиг. Пароль в ключ не включаем,
        // чтобы не хранить его лишний раз в памяти и в логах.
        String key = url + "|" + user;

        DataSource existing = INSTANCE.get();
        if (existing != null && key.equals(INSTANCE_KEY.get())) {
            return existing;
        }

        synchronized (ChatbotDataSource.class) {
            existing = INSTANCE.get();
            if (existing != null && key.equals(INSTANCE_KEY.get())) {
                return existing;
            }

            // FIX: закрываем старый пул, если конфиг изменился.
            if (existing instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                    LOG.info("[Chatbot] Closed previous HikariCP pool (config changed)");
                } catch (Exception e) {
                    LOG.warn("[Chatbot] Failed to close previous pool", e);
                }
            }

            DataSource created = create(url, user, password);
            INSTANCE.set(created);
            INSTANCE_KEY.set(key);
            return created;
        }
    }

    private static DataSource create(String url, String user, String password) {
        String driver = guessDriver(url);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setDriverClassName(driver);

        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("chatbot-pool");

        // FIX: таймауты. Без них getConnection() может висеть 30+ секунд,
        // блокируя Tomcat-поток.
        cfg.setConnectionTimeout(10_000);       // 10 сек — ожидание свободного соединения
        cfg.setValidationTimeout(5_000);        // 5 сек — проверка живости
        cfg.setIdleTimeout(600_000);            // 10 мин — простой
        cfg.setMaxLifetime(1_800_000);          // 30 мин — максимальная жизнь соединения

        // FIX: не валим приложение, если БД временно недоступна на старте.
        // Первый getConnection() покажет реальную ошибку.
        cfg.setInitializationFailTimeout(-1);

        LOG.info("[Chatbot] Creating HikariCP pool for {}", url);
        return new HikariDataSource(cfg);
    }

    private static String guessDriver(String url) {
        if (url.startsWith("jdbc:postgresql:")) return "org.postgresql.Driver";
        if (url.startsWith("jdbc:oracle:"))     return "oracle.jdbc.OracleDriver";
        if (url.startsWith("jdbc:sqlserver:"))  return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        if (url.startsWith("jdbc:h2:"))         return "org.h2.Driver";
        throw new IllegalStateException("Cannot guess JDBC driver for URL: " + url);
    }
}