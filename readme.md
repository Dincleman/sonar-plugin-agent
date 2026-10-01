# SonarQube AI Chatbot Plugin

Плагин для SonarQube, встраивающий в UI чат с AI-агентом. Агент работает через **MCP-сервер SonarQube** — то есть использует официальные инструменты SonarQube (поиск issues, метрики, quality gates), а не «собственные догадки» LLM. Каждый пользователь работает со своим персональным токеном, поэтому видит только те проекты, к которым у него есть права.

---

## Содержание

- [Возможности](#возможности)
- [Архитектура](#архитектура)
- [Требования](#требования)
- [Установка](#установка)
    - [1. Сборка](#1-сборка)
    - [2. Создание таблицы в БД](#2-создание-таблицы-в-бд)
    - [3. Права пользователю БД](#3-права-пользователю-бд)
    - [4. Установка плагина](#4-установка-плагина)
- [Первоначальная настройка](#первоначальная-настройка)
    - [Настройки в админ-панели](#настройки-в-админ-панели)
    - [Генерация ключа шифрования](#генерация-ключа-шифрования)
- [Использование](#использование)
    - [Первый вход пользователя](#первый-вход-пользователя)
    - [Смена токена](#смена-токена)
    - [Очистка истории](#очистка-истории)
- [Структура проекта](#структура-проекта)
- [Описание классов](#описание-классов)
- [HTTP API](#http-api)
- [Безопасность](#безопасность)
- [Диагностика](#диагностика)
- [Частые проблемы](#частые-проблемы)

---

## Возможности

- 💬 **Чат с AI-агентом** прямо в SonarQube UI (страница `AI Chat`).
- 🔧 **Реальные данные из SonarQube** через MCP-сервер: список проектов, метрики, quality gates, issues.
- 🔐 **Персональные токены пользователей**: каждый работает под своими правами SonarQube — чужие проекты не видны.
- 🔒 **Шифрование токенов** AES-256-GCM перед сохранением в БД.
- 🧠 **Агентский цикл**: модель сама решает, какие инструменты вызвать, получает результат и формулирует ответ. Поддерживает до 30 последовательных вызовов.
- 📜 **История диалога** сохраняется в браузере (`sessionStorage`), переживает перезагрузку страницы.
- 🌊 **Поддержка DSML**: если LLM не умеет function calling и возвращает XML-разметку — плагин умеет её распознавать и вырезать из ответа пользователю.
- ⏱ **Таймауты и лимиты**: HTTP-запросы не висят бесконечно, слишком длинные сообщения отсекаются.
- 🐞 **Отладочный режим**: включается в админ-панели, глушит INFO/DEBUG-логи плагина.

---

## Архитектура

```
┌─────────────────┐
│  Браузер (UI)   │
│  chat_page.js   │
└────────┬────────┘
         │ POST /api/chatbot/chat
         │ POST /api/chatbot/set-token
         │ GET  /api/chatbot/token-status
         ▼
┌──────────────────────────┐       ┌────────────────────┐
│  ChatbotWebService       │──────▶│  MCP Server        │
│  (SonarQube plugin)      │       │  sonarqube-mcp-... │
│                          │       └────────────────────┘
│  ┌────────────────────┐  │              │
│  │ UserTokenDao       │──┼──▶ БД        │
│  │ TokenCrypto        │  │  chatbot.    ▼
│  │ ChatbotDataSource  │  │  user_tokens ┌──────────────┐
│  └────────────────────┘  │              │  SonarQube   │
│                          │              │  API         │
│                          │              └──────────────┘
│                          │       ┌────────────────────┐
│                          │──────▶│  LLM Service       │
└──────────────────────────┘       │  /v1/chat/...      │
                                   └────────────────────┘
```

**Поток запроса:**

1. Пользователь открывает страницу `AI Chat`.
2. JS-расширение запрашивает `/api/chatbot/status` — проверяет, настроены ли MCP/AI.
3. JS-расширение запрашивает `/api/chatbot/token-status` — есть ли у пользователя токен.
4. Если токена нет — показывается модалка. Пользователь вводит персональный токен SonarQube.
5. Токен шифруется и сохраняется в `chatbot.user_tokens`.
6. При каждом сообщении:
    - `ChatbotWebService` достаёт токен пользователя из БД, расшифровывает;
    - вызывает MCP `tools/list` — получает список инструментов;
    - вызывает LLM с системным промптом, историей диалога и списком инструментов;
    - если LLM просит вызвать инструмент — вызывает MCP `tools/call` с персональным токеном пользователя;
    - результат возвращает в LLM; цикл повторяется до финального текстового ответа (до 30 шагов);
    - финальный ответ отдаётся браузеру.

**Ключевое архитектурное решение:** токен пользователя используется как в `tools/list`, так и в `tools/call` MCP. Это значит, что MCP, в свою очередь, обращается к SonarQube API с этим токеном, и SonarQube применяет права именно этого пользователя. Пользователь видит только то, что ему разрешено.

---

## Требования

| Компонент | Версия / примечание |
|---|---|
| SonarQube | 10.x или новее (плагин собран под API 9.9+, протестирован на 25.4) |
| JDK | 17 |
| Maven | 3.8+ |
| MCP-сервер SonarQube | Любой совместимый с MCP (`https://github.com/SonarSource/sonarqube-mcp-server` или аналог) |
| LLM-сервис | Любой OpenAI-совместимый (`/v1/chat/completions`), поддерживающий function calling |
| СУБД | PostgreSQL / Oracle / MSSQL / H2 — та же, что у SonarQube |

---

## Установка

### 1. Сборка

```bash
git clone <репозиторий>
cd sonar-plugin-agent
mvn clean package
```

Готовый артефакт: `target/sonar-chatbot-plugin-1.0.0.jar`.

### 2. Создание таблицы в БД

Подключитесь к БД SonarQube от имени администратора и создайте **отдельную схему `chatbot`** и таблицу `user_tokens`.

#### PostgreSQL

```sql
CREATE SCHEMA IF NOT EXISTS chatbot;

CREATE TABLE chatbot.user_tokens (
    user_login    VARCHAR(255) PRIMARY KEY,
    mcp_token_enc TEXT         NOT NULL,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_tokens_login ON chatbot.user_tokens(user_login);
```

#### Oracle

```sql
CREATE TABLE chatbot.user_tokens (
    user_login    VARCHAR2(255) PRIMARY KEY,
    mcp_token_enc CLOB          NOT NULL,
    updated_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX idx_user_tokens_login ON chatbot.user_tokens(user_login);
```

> ⚠️ В Oracle и MSSQL нет `ON CONFLICT`. Класс `UserTokenDao.saveToken` использует PostgreSQL-синтаксис; для других СУБД перепишите его на `MERGE` (см. комментарий в конце метода).

#### Microsoft SQL Server

```sql
IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'chatbot')
    EXEC('CREATE SCHEMA chatbot');

CREATE TABLE chatbot.user_tokens (
    user_login    NVARCHAR(255) NOT NULL PRIMARY KEY,
    mcp_token_enc NVARCHAR(MAX) NOT NULL,
    updated_at    DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
);

CREATE INDEX idx_user_tokens_login ON chatbot.user_tokens(user_login);
```

### 3. Права пользователю БД

Пользователь, под которым работает SonarQube (`sonar.jdbc.username`), должен иметь права на схему `chatbot`.

**PostgreSQL:**
```sql
GRANT USAGE ON SCHEMA chatbot TO sonar;
GRANT SELECT, INSERT, UPDATE, DELETE ON chatbot.user_tokens TO sonar;
```

**Oracle:**
```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON chatbot.user_tokens TO SONAR_USER;
```

**MSSQL:**
```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON SCHEMA::chatbot TO sonar_user;
```

### 4. Установка плагина

1. Скопируйте `sonar-chatbot-plugin-1.0.0.jar` в `<SONAR_HOME>/extensions/plugins/`.
2. Убедитесь, что там нет старой версии плагина (удалите её).
3. Перезапустите SonarQube:
   ```bash
   <SONAR_HOME>/bin/linux-x64/sonar.sh restart
   ```

В логе `web.log` должны появиться строки:

```
[Chatbot] Registering WebService controller: api/chatbot
[Chatbot] ChatbotWebService initialized
```

Обратите внимание: `Creating HikariCP pool` и `UserTokenDao initialized` **не должны** появляться при старте — они появятся позже, при первом запросе.

---

## Первоначальная настройка

### Настройки в админ-панели

Войдите как администратор → **Administration → Configuration → Chatbot**. Заполните:

| Поле | Описание | Пример |
|---|---|---|
| **Encryption Key for User Tokens** | Base64-ключ (32 байта) для AES-256-GCM. См. следующий раздел. | `K7gNu3sdo+OL0...` |
| **Database JDBC URL** | JDBC URL БД SonarQube (та же, где создана схема `chatbot`) | `jdbc:postgresql://db:5432/sonar` |
| **Database Username** | Логин БД | `sonar` |
| **Database Password** | Пароль БД | `...` |
| **MCP Server URL** | Базовый URL MCP-сервера (без `/mcp`) | `https://mcp.company.local` |
| **AI Service URL** | Базовый URL LLM-сервиса (без `/chat/completions`) | `https://api.ai.company.local/v1` |
| **AI Service Token** | Токен доступа к LLM | `sk-...` |
| **AI Model** | Название модели для function calling | `sgl/deepseek-ai/DeepSeek-V4-Flash-0731` |
| **AI System Prompt** | Системный промпт агента | *(см. ниже)* |
| **Enable Debug Logging** | Включает INFO/DEBUG-логи плагина. WARN/ERROR пишутся всегда | `false` |

**Рекомендуемый System Prompt:**

```
Ты — ассистент по качеству кода. Используй ТОЛЬКО инструменты SonarQube MCP.
Не используй другие инструменты. Если пользователь задаёт вопрос, связанный
с кодом, обязательно вызови подходящий инструмент MCP.
```

### Генерация ключа шифрования

Ключ генерируется один раз, командой на любом Linux/Mac:

```bash
openssl rand -base64 32
```

Пример вывода:

```
K7gNu3sdo+OL0wNhqoVWhr3g6s1xYv72ol/pe/Unols=
```

Скопируйте строку и вставьте в поле **Encryption Key for User Tokens**.

> ⚠️ **Важно:**
> - Ключ хранится в БД SonarQube (таблица `properties`) как обычная настройка.
> - При смене ключа **все сохранённые токены станут нечитаемыми** — пользователям придётся ввести их заново.
> - Не храните ключ в открытом виде (в git, в тикетах, в чатах).
> - При бэкапе БД SonarQube ключ попадёт в бэкап автоматически — это удобно, но убедитесь, что доступ к бэкапу ограничен.

После сохранения настроек плагин готов к работе.

---

## Использование

### Первый вход пользователя

1. Войдите в SonarQube как обычный пользователь.
2. Откройте в левом меню **AI Chat** (или перейдите по `/extension/chatbot/chat_page`).
3. Появится модальное окно **«Доступ к MCP»** — это означает, что у вас ещё нет сохранённого токена.
4. Создайте персональный токен:
    - **My Account → Security → Generate Tokens**;
    - Тип: **User Token**;
    - Имя: например, `chatbot`;
    - Скопируйте сгенерированное значение (показывается один раз).
5. Вставьте токен в модальное окно и нажмите **Сохранить**.

Токен шифруется ключом из настроек и сохраняется в `chatbot.user_tokens` привязкой к вашему логину. С этого момента чат работает от вашего имени.

### Смена токена

В шапке чата есть кнопка **«Токен»** — открывает то же модальное окно. Введите новый токен, нажмите Сохранить — он перезапишет прежний. Смена действует сразу.

### Очистка истории

Кнопка **«Очистить»** в шапке удаляет историю текущего диалога из `sessionStorage` браузера. **Токен при этом не удаляется.** История и так не переживает закрытие вкладки — хранится только в рамках сессии.

---

## Структура проекта

```
src/main/java/com/example/chatbot/
├── ChatbotPlugin.java         # точка входа плагина
├── ChatbotPageDefinition.java # регистрация UI-страницы
├── ChatbotSettings.java       # декларация настроек (админ-панель)
├── ChatbotWebService.java     # HTTP-эндпоинты и агентский цикл
├── ChatbotDataSource.java     # пул соединений с БД
├── UserTokenDao.java          # CRUD над chatbot.user_tokens
└── TokenCrypto.java           # AES-256-GCM шифрование токенов

src/main/resources/static/
└── chat_page.js               # UI чата (SonarQube extension)
```

---

## Описание классов

### `ChatbotPlugin`
Точка входа. Регистрирует все компоненты плагина в SonarQube:

```java
context.addExtensions(ChatbotSettings.definitions());
context.addExtension(ChatbotWebService.class);
context.addExtension(ChatbotPageDefinition.class);
```

### `ChatbotPageDefinition`
Регистрирует UI-страницу `chatbot/chat_page` глобального scope — доступна всем авторизованным пользователям из левого меню.

### `ChatbotSettings`
Декларирует все настройки плагина для админ-панели. Содержит константы — ключи настроек:

| Константа | Ключ настройки |
|---|---|
| `ENCRYPTION_KEY` | `sonar.chatbot.encryption.key` |
| `DB_URL` | `sonar.chatbot.db.url` |
| `DB_USERNAME` | `sonar.chatbot.db.username` |
| `DB_PASSWORD` | `sonar.chatbot.db.password` |
| `MCP_URL` | `sonar.chatbot.mcp.url` |
| `AI_URL` | `sonar.chatbot.ai.url` |
| `AI_TOKEN` | `sonar.chatbot.ai.token` |
| `AI_MODEL` | `sonar.chatbot.ai.model` |
| `AI_SYSTEM_PROMPT` | `sonar.chatbot.ai.system.prompt` |
| `DEBUG` | `sonar.chatbot.debug` |

### `ChatbotDataSource`
Ленивый синглтон пула соединений HikariCP.

- Создаётся при первом вызове `get(config)`, когда `Configuration` уже содержит значения из админ-панели.
- Кэширует пул по ключу `(url | username)`. Если админ поменяет URL/логин — старый пул закрывается, создаётся новый.
- Таймауты: connect 10 сек, request timeout 10 сек, idle 10 мин, maxLifetime 30 мин.
- `initializationFailTimeout = -1` — приложение не падает, если БД временно недоступна.

### `TokenCrypto`
Симметричное шифрование AES-256-GCM.

- `encrypt(String)` → возвращает Base64 от `IV || ciphertext || GCM-tag`.
- `decrypt(String)` → расшифровывает Base64-строку.
- Ключ читается из `Configuration` **на каждом вызове** — админ может поменять его без перезапуска SonarQube.
- При смене ключа старые токены станут нечитаемыми: `decrypt` бросит исключение, `UserTokenDao.getToken` вернёт `null`, пользователю будет предложено ввести токен заново.

### `UserTokenDao`
CRUD над таблицей `chatbot.user_tokens`.

| Метод | Что делает |
|---|---|
| `saveToken(login, rawToken)` | Шифрует токен и делает `INSERT ... ON CONFLICT DO UPDATE` |
| `getToken(login)` | Читает, расшифровывает. Возвращает `null`, если записи нет или ключ не подходит |
| `deleteToken(login)` | Удаляет запись |

### `ChatbotWebService`
Основной класс: HTTP-контроллер + агентский цикл.

**Эндпоинты:**

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/api/chatbot/status` | Проверяет, настроены ли MCP и AI |
| GET | `/api/chatbot/token-status` | Есть ли у пользователя сохранённый токен |
| POST | `/api/chatbot/set-token` | Сохранить токен (`token` в теле) |
| POST | `/api/chatbot/clear-token` | Удалить токен пользователя |
| POST | `/api/chatbot/chat` | Отправить сообщение (`message`, `history` в теле) |

**Вспомогательные методы:**

- `requireLogin(request)` — получает логин текущего пользователя через `LocalConnector` и `WsClient`.
- `tokenDao()` — ленивая инициализация DAO.
- `debug(msg, ...)` / `trace(msg, ...)` — логи только при включённом debug.
- `extractAllToolCalls(llmJson)` — достаёт tool calls из ответа LLM (стандартный OpenAI `tool_calls[]` или DSML-разметку в `content`).
- `buildAssistantMessageWithToolCalls(...)` — собирает assistant-сообщение в формате OpenAI (важно: `function.arguments` **обязательно строка с JSON**, а не объект).
- `appendHistory(messages, historyJson)` — добавляет последние N сообщений из истории пользователя.
- `stripDsml(text)` — вырезает DSML-теги из текста перед показом пользователю.
- `listMcpTools(mcpUrl, token)` — `tools/list` к MCP.
- `callMcpTool(mcpUrl, token, name, args)` — `tools/call` к MCP.
- `extractMcpContent(response)` — достаёт полезный текст из ответа MCP (JSON или SSE).
- `callLlm(aiUrl, aiToken, aiModel, messages, tools)` — `POST /chat/completions` к LLM.

---

## HTTP API

Все эндпоинты требуют аутентификации в SonarQube (cookie сессии) и CSRF-токен для POST (плагин использует `window.SonarRequest`, который его автоматически добавляет).

### `GET /api/chatbot/status`

Ответ:
```json
{
  "mcpConfigured": true,
  "aiConfigured": true,
  "mcpUrl": "https://mcp.company.local",
  "aiUrl": "https://api.ai.company.local/v1",
  "aiTokenPresent": true,
  "aiModel": "sgl/deepseek-ai/DeepSeek-V4-Flash-0731",
  "debug": false
}
```

### `GET /api/chatbot/token-status`

```json
{ "hasToken": true }
```

### `POST /api/chatbot/set-token`

Тело: `{ "token": "squ_..." }`

Успех: `{ "ok": true }`  
Ошибка: `{ "error": "..." }`

### `POST /api/chatbot/clear-token`

Успех: `{ "ok": true }`

### `POST /api/chatbot/chat`

Тело:
```json
{
  "message": "какой проект лучший?",
  "history": "[{\"role\":\"user\",\"content\":\"...\"},{\"role\":\"assistant\",\"content\":\"...\"}]"
}
```

Ответ:
```json
{ "reply": "..." }
```

Специальные ответы:
- `{ "error": "TOKEN_REQUIRED" }` — токена нет, нужно показать модалку;
- `{ "error": "Authentication required." }` — сессия истекла;
- `{ "error": "..." }` — прочие ошибки.

---

## Безопасность

### Как хранятся токены

- Токен шифруется **AES-256-GCM** перед записью в БД.
- Ключ шифрования хранится в настройках SonarQube (таблица `properties`), доступен только администраторам.
- В БД лежит `Base64(IV || ciphertext || GCM-tag)` — без ключа расшифровать невозможно.
- IV генерируется случайно для каждого шифрования (`SecureRandom`).
- GCM-tag обеспечивает целостность: подмена данных в БД приведёт к ошибке расшифровки, а не к утечке.

### Как используются токены

- Токен используется как `Authorization: Bearer <token>` при вызовах MCP.
- MCP, в свою очередь, вызывает SonarQube API с этим токеном → SonarQube применяет права пользователя.
- Пользователь видит только те проекты, к которым у него есть права в SonarQube.
- Токен **не передаётся обратно в браузер** и **не логируется** (в `debug`-режиме видны только названия инструментов и аргументы, но не токен).

### Известные компромиссы

- **TLS-проверка отключена** для HTTP-клиента (self-signed сертификаты во внутреннем контуре). Если у вас есть CA — перепишите `createInsecureHttpClient()` на стандартный `HttpClient.newHttpClient()`.
- **Токен хранится в БД SonarQube**, а не в отдельной. Это удобно (всё в одном бэкапе), но требует, чтобы доступ к БД был ограничен.
- **Анонимный доступ** к странице `AI Chat` невозможен — плагин всегда требует аутентификации.

---

## Диагностика

### Включение отладочных логов

Админ-панель → **Chatbot → Enable Debug Logging → true**.

После этого в `web.log` появятся строки:

```
[Chatbot] ========== NEW CHAT REQUEST (user='ivanov') ==========
[Chatbot] Message: какой проект лучший?
[Chatbot] STEP 1: Fetching tools from MCP https://...
[Chatbot] STEP 1 done. Tools JSON: [...]
[Chatbot] STEP 1: Calling LLM https://... model=...
[Chatbot] STEP 1 done. LLM response: {...}
[Chatbot] STEP 1: LLM requested 1 tool call(s)
[Chatbot]   -> Executing tool 'search_my_sonarqube_projects' with args: {}
[Chatbot]   <- Tool 'search_my_sonarqube_projects' result: {...}
[Chatbot] STEP 2: Calling LLM ...
[Chatbot] STEP 2: final answer (2280 chars)
[Chatbot] ========== END CHAT REQUEST ==========
```

**WARN и ERROR** логируются всегда, вне зависимости от настройки.

### Просмотр сохранённых токенов

```sql
SELECT user_login, updated_at, LENGTH(mcp_token_enc) AS enc_len
FROM chatbot.user_tokens
ORDER BY updated_at DESC;
```

`enc_len` обычно 60–100 символов. Само значение `mcp_token_enc` — нечитаемая Base64-строка.

---

## Частые проблемы

### Плагин не устанавливается / SonarQube падает при старте

**Симптом:** в логе `Background initialization failed. Stopping SonarQube`.

**Причина:** обычно — ошибка в конструкторе `ChatbotWebService` (например, обращение к БД до того, как настройки загружены).

**Решение:** в текущей версии инициализация БД ленивая, этой проблемы быть не должно. Если всё же падает — смотрите `Caused by:` в логе, там будет точная причина.

### «Database connection is not configured»

**Причина:** не заполнены `Database JDBC URL` / `Database Username` в админ-панели.

**Решение:** заполните в разделе **Chatbot**. Если БД-настройки уже были у SonarQube — скопируйте из `conf/sonar.properties` (`sonar.jdbc.url`, `sonar.jdbc.username`, `sonar.jdbc.password`).

### «Encryption key is not configured»

**Причина:** не задан ключ шифрования.

**Решение:** сгенерируйте `openssl rand -base64 32` и вставьте в **Encryption Key for User Tokens**.

### «Cannot decrypt stored token for 'user' (encryption key changed?)»

**Причина:** админ поменял ключ шифрования. Старые токены стали нечитаемыми.

**Что делать:** это ожидаемое поведение. Пользователь при следующем входе увидит модалку и введёт токен заново.

### «MCP tools/list failed: HTTP 401»

**Причина:** MCP отверг токен пользователя.

**Что делать:** пользователь должен ввести корректный токен SonarQube через кнопку **«Токен»**. Проверьте, что токен создан в **My Account → Security** с типом **User Token**.

### «HTTP 400 Invalid request payload» на шаге 4

**Причина:** неправильный формат `tool_calls` в assistant-сообщении. `function.arguments` должен быть **строкой с JSON**, а не объектом.

**Решение:** в текущей версии это исправлено (`buildAssistantMessageWithToolCalls` всегда сериализует в строку). Если ошибка повторяется — включите debug и проверьте лог `LLM payload (truncated): ...`.

### «Достигнут лимит шагов агента (30)»

**Причина:** модель зациклилась на вызовах инструментов. Частый сценарий — пользователь спрашивает «какой проект лучший?», а модель начинает перебирать все 88 проектов по одному.

**Решение:**
- Уточните вопрос: «какой из 5 крупнейших проектов имеет лучший quality gate?»
- Или увеличьте `MAX_AGENT_STEPS` (не рекомендуется — время ответа вырастет).
- Или улучшите System Prompt, попросив модель делать выводы по выборке, а не обходить всё.

### «Message too long (max 8000 chars)»

**Причина:** сообщение превышает лимит.

**Решение:** разбейте вопрос на части. Лимит задаётся константой `MAX_USER_MESSAGE_CHARS` в `ChatbotWebService`.

### Чат отвечает, но игнорирует SonarQube

**Причина:** MCP вернул пустой список инструментов (например, HTTP 401), но `listMcpTools` не подсветил ошибку, и LLM ответил «по общим знаниям».

**Решение:** проверьте в логах `MCP tools/list failed: HTTP ...`. Убедитесь, что MCP-сервер доступен и токен пользователя валиден.

---

## Лицензия

Внутренний проект. Использование, модификация и распространение — по согласованию с владельцем.