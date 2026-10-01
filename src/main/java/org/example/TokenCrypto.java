package com.example.chatbot;

import org.sonar.api.config.Configuration;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM шифрование для персональных токенов.
 * Ключ берётся из настройки sonar.chatbot.encryption.key (Base64, 32 байта).
 * Сгенерировать: openssl rand -base64 32
 *
 * Ключ читается на каждом вызове — если админ поменяет его, все ранее
 * сохранённые токены станут нечитаемыми (это ожидаемое поведение).
 *
 * Формат зашифрованного значения: Base64(IV || ciphertext || GCM-tag).
 */
public class TokenCrypto {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int IV_LENGTH = 12;       // bytes

    private final Configuration config;
    private final SecureRandom random = new SecureRandom();

    public TokenCrypto(Configuration config) {
        this.config = config;
    }

    /**
     * Читает и валидирует ключ шифрования из Configuration.
     * Бросает IllegalStateException с понятным сообщением, если ключ
     * не задан или неверного формата.
     */
    private SecretKey currentKey() {
        String base64Key = config.get(ChatbotSettings.ENCRYPTION_KEY).orElse("");
        if (base64Key.isBlank()) {
            throw new IllegalStateException(
                    "Encryption key is not configured. Set 'Encryption Key for User Tokens' " +
                            "in SonarQube admin settings (category 'Chatbot'). " +
                            "Generate: openssl rand -base64 32");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Encryption key is not valid Base64", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "Encryption key must be 256 bits (32 bytes after Base64 decode), " +
                            "got " + keyBytes.length + " bytes");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** Возвращает Base64(IV || ciphertext || tag). */
    public String encrypt(String plaintext) throws Exception {
        // FIX: защита от null — иначе получим NullPointerException
        // где-то глубоко в doFinal().
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }

        SecretKey key = currentKey();

        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        ByteBuffer buf = ByteBuffer.allocate(iv.length + ciphertext.length);
        buf.put(iv);
        buf.put(ciphertext);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    public String decrypt(String encryptedBase64) throws Exception {
        if (encryptedBase64 == null || encryptedBase64.isEmpty()) {
            throw new IllegalArgumentException("encrypted payload must not be empty");
        }

        SecretKey key = currentKey();

        byte[] data;
        try {
            data = Base64.getDecoder().decode(encryptedBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Encrypted payload is not valid Base64", e);
        }

        if (data.length < IV_LENGTH + 16) { // 16 = GCM tag minimum
            throw new IllegalArgumentException("Invalid encrypted payload: too short");
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        byte[] iv = new byte[IV_LENGTH];
        buf.get(iv);
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);

        Cipher cipher = Cipher.getInstance(ALGO);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}