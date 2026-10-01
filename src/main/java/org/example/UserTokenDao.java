package com.example.chatbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class UserTokenDao {

    private static final Logger LOG = LoggerFactory.getLogger(UserTokenDao.class);

    private final DataSource dataSource;
    private final TokenCrypto crypto;

    public UserTokenDao(DataSource dataSource, TokenCrypto crypto) {
        this.dataSource = dataSource;
        this.crypto = crypto;
    }

    /**
     * Сохраняет токен пользователя (upsert).
     * Использует PostgreSQL-синтаксис ON CONFLICT. Для Oracle/MSSQL
     * перепишите на MERGE (см. комментарий в конце метода).
     */
    public void saveToken(String userLogin, String rawToken) throws Exception {
        String encrypted = crypto.encrypt(rawToken);

        String sql = "INSERT INTO chatbot.user_tokens (user_login, mcp_token_enc, updated_at) " +
                "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (user_login) DO UPDATE SET " +
                "mcp_token_enc = EXCLUDED.mcp_token_enc, " +
                "updated_at = CURRENT_TIMESTAMP";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userLogin);
            ps.setString(2, encrypted);
            ps.executeUpdate();
            LOG.info("[Chatbot] Token saved for user '{}'", userLogin);
        }
    }

    /**
     * Возвращает расшифрованный токен или null, если у пользователя его нет.
     *
     * Различает три случая:
     *  1. Записи нет → null (норма)
     *  2. Запись есть, но не расшифровывается → null + WARN (смена ключа)
     *  3. Ошибка БД → null + ERROR (транзиентный сбой, надо перезапустить пул)
     */
    public String getToken(String userLogin) {
        String sql = "SELECT mcp_token_enc FROM chatbot.user_tokens WHERE user_login = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userLogin);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String enc = rs.getString(1);
                try {
                    return crypto.decrypt(enc);
                } catch (Exception e) {
                    // Скорее всего, поменялся ключ шифрования.
                    LOG.warn("[Chatbot] Cannot decrypt stored token for '{}' " +
                            "(encryption key changed?). Will ask user to re-enter.", userLogin);
                    return null;
                }
            }
        } catch (SQLException e) {
            // FIX: отличаем SQL-ошибку от прочих. Явно логируем с SQLState.
            LOG.error("[Chatbot] DB error while loading token for '{}' (SQLState={}): {}",
                    userLogin, e.getSQLState(), e.getMessage(), e);
            return null;
        } catch (Exception e) {
            LOG.error("[Chatbot] Unexpected error while loading token for '{}'", userLogin, e);
            return null;
        }
    }

    public void deleteToken(String userLogin) {
        String sql = "DELETE FROM chatbot.user_tokens WHERE user_login = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userLogin);
            ps.executeUpdate();
            LOG.info("[Chatbot] Token deleted for user '{}'", userLogin);
        } catch (SQLException e) {
            LOG.error("[Chatbot] DB error while deleting token for '{}' (SQLState={}): {}",
                    userLogin, e.getSQLState(), e.getMessage(), e);
        }
    }

    // === ПРИМЕЧАНИЕ по диалектам SQL ===
    // PostgreSQL:  ON CONFLICT (user_login) DO UPDATE SET ...
    // Oracle:      MERGE INTO chatbot.user_tokens t
    //              USING (SELECT ? AS user_login, ? AS mcp_token_enc FROM dual) s
    //              ON (t.user_login = s.user_login)
    //              WHEN MATCHED THEN UPDATE SET t.mcp_token_enc = s.mcp_token_enc,
    //                                           t.updated_at    = CURRENT_TIMESTAMP
    //              WHEN NOT MATCHED THEN INSERT (user_login, mcp_token_enc)
    //                                    VALUES (s.user_login, s.mcp_token_enc);
    // SQL Server:  аналогично Oracle, но без FROM dual.
}