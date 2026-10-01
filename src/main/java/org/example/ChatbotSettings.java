package com.example.chatbot;

import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.PropertyType;

import java.util.List;

public class ChatbotSettings {

    public static final String MCP_URL = "sonar.chatbot.mcp.url";
    public static final String AI_URL = "sonar.chatbot.ai.url";
    public static final String AI_TOKEN = "sonar.chatbot.ai.token";
    public static final String AI_MODEL = "sonar.chatbot.ai.model";
    public static final String AI_SYSTEM_PROMPT = "sonar.chatbot.ai.system.prompt";
    public static final String ENCRYPTION_KEY = "sonar.chatbot.encryption.key";
    public static final String DEBUG = "sonar.chatbot.debug";

    // --- Подключение к БД для таблицы chatbot.user_tokens ---
    public static final String DB_URL      = "sonar.chatbot.db.url";
    public static final String DB_USERNAME = "sonar.chatbot.db.username";
    public static final String DB_PASSWORD = "sonar.chatbot.db.password";

    public static List<PropertyDefinition> definitions() {
        return List.of(
                PropertyDefinition.builder(ENCRYPTION_KEY)
                        .name("Encryption Key for User Tokens")
                        .description("Base64-ключ (32 байта) для AES-256-GCM шифрования персональных " +
                                "токенов пользователей. Сгенерировать: openssl rand -base64 32. " +
                                "ВНИМАНИЕ: при смене ключа все сохранённые токены станут нечитаемыми — " +
                                "пользователям придётся ввести их заново.")
                        .type(PropertyType.PASSWORD)
                        .category("Chatbot")
                        .index(1)
                        .build(),

                PropertyDefinition.builder(DB_URL)
                        .name("Database JDBC URL")
                        .description("JDBC URL базы данных SonarQube (или отдельной БД, где создана " +
                                "схема chatbot). Пример для PostgreSQL: " +
                                "jdbc:postgresql://localhost:5432/sonar")
                        .category("Chatbot")
                        .index(2)
                        .build(),

                PropertyDefinition.builder(DB_USERNAME)
                        .name("Database Username")
                        .description("Имя пользователя БД с правами SELECT/INSERT/UPDATE/DELETE " +
                                "на схему chatbot.")
                        .category("Chatbot")
                        .index(3)
                        .build(),

                PropertyDefinition.builder(DB_PASSWORD)
                        .name("Database Password")
                        .description("Пароль пользователя БД.")
                        .type(PropertyType.PASSWORD)
                        .category("Chatbot")
                        .index(4)
                        .build(),

                PropertyDefinition.builder(MCP_URL)
                        .name("MCP Server URL")
                        .description("Базовый URL MCP сервера SonarQube (без /mcp). " +
                                "Например: https://sonarqube-mcp.company.local. " +
                                "Персональный токен для доступа к MCP каждый пользователь " +
                                "задаёт сам в интерфейсе чата.")
                        // FIX: раньше был default http://localhost:8080 — в проде
                        // это почти всегда ошибка. Пустой default заставит админа
                        // задуматься.
                        .category("Chatbot")
                        .index(5)
                        .build(),

                PropertyDefinition.builder(AI_URL)
                        .name("AI Service URL")
                        .description("Базовый URL внутренней AI службы. Например: https://api.ai.company.local/v1")
                        .category("Chatbot")
                        .index(6)
                        .build(),

                PropertyDefinition.builder(AI_TOKEN)
                        .name("AI Service Token")
                        .description("Токен для аутентификации во внутренней AI службе.")
                        .type(PropertyType.PASSWORD)
                        .category("Chatbot")
                        .index(7)
                        .build(),

                PropertyDefinition.builder(AI_MODEL)
                        .name("AI Model")
                        .description("Имя модели для function calling, например: sgl/deepseek-ai/DeepSeek-V4-Flash-0731")
                        .defaultValue("sgl/deepseek-ai/DeepSeek-V4-Flash-0731")
                        .category("Chatbot")
                        .index(8)
                        .build(),

                PropertyDefinition.builder(AI_SYSTEM_PROMPT)
                        .name("AI System Prompt")
                        .description("Системный промпт для агента.")
                        .defaultValue("Ты — ассистент по качеству кода. Используй ТОЛЬКО инструменты SonarQube MCP.")
                        .category("Chatbot")
                        .index(9)
                        .build(),

                PropertyDefinition.builder(DEBUG)
                        .name("Enable Debug Logging")
                        .description("Если выключено — плагин не пишет INFO/DEBUG сообщения в лог " +
                                "SonarQube. WARN и ERROR выводятся всегда. " +
                                "Включайте только на время диагностики.")
                        .type(PropertyType.BOOLEAN)
                        .defaultValue("false")
                        .category("Chatbot")
                        .index(10)
                        .build()
        );
    }
}