package com.example.chatbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sonar.api.config.Configuration;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.server.ws.WebService.NewAction;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.Users.CurrentWsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatbotWebService implements WebService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatbotWebService.class);

    private static final int MAX_AGENT_STEPS = 30;
    private static final int MAX_HISTORY_MESSAGES = 20;

    // FIX: ограничения на вход, чтобы пользователь не отправил мегабайт
    // текста и не уронил LLM/MCP запросом огромного размера.
    private static final int MAX_USER_MESSAGE_CHARS = 8_000;
    private static final int MAX_HISTORY_JSON_CHARS = 200_000;

    // FIX: таймауты HTTP. Без них Tomcat-потоки зависают навсегда.
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private static final Pattern DSML_INVOKE = Pattern.compile(
            "<(?:｜DSML｜)?invoke\\s+name=\"([^\"]+)\"[^>]*>(.*?)</(?:｜DSML｜)?invoke>",
            Pattern.DOTALL);
    private static final Pattern DSML_PARAM = Pattern.compile(
            "<(?:｜DSML｜)?parameter\\s+name=\"([^\"]+)\"[^>]*>(.*?)</(?:｜DSML｜)?parameter>",
            Pattern.DOTALL);
    private static final Pattern DSML_INVOKE_BLOCK = Pattern.compile(
            "<(?:｜DSML｜)?invoke\\b[^>]*>.*?</(?:｜DSML｜)?invoke>",
            Pattern.DOTALL);
    private static final Pattern DSML_STRAY_TAG = Pattern.compile(
            "</?(?:｜DSML｜)?(?:invoke|parameter)\\b[^>]*>",
            Pattern.DOTALL);

    private final Configuration config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private volatile UserTokenDao tokenDao;

    public ChatbotWebService(Configuration config) {
        this.config = config;
        this.mapper = new ObjectMapper();
        this.httpClient = createInsecureHttpClient();
        LOG.info("[Chatbot] ChatbotWebService initialized");
    }

    private UserTokenDao tokenDao() {
        if (tokenDao == null) {
            synchronized (this) {
                if (tokenDao == null) {
                    tokenDao = new UserTokenDao(
                            ChatbotDataSource.get(config),
                            new TokenCrypto(config));
                    LOG.info("[Chatbot] UserTokenDao initialized");
                }
            }
        }
        return tokenDao;
    }

    // ==================================================================================
    //  Debug logging helpers
    // ==================================================================================

    private boolean isDebug() {
        try {
            return config.getBoolean(ChatbotSettings.DEBUG).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private void debug(String msg, Object... args) {
        if (isDebug()) LOG.info("[Chatbot] " + msg, args);
    }

    private void trace(String msg, Object... args) {
        if (isDebug()) LOG.debug("[Chatbot] " + msg, args);
    }

    /**
     * FIX: добавляем таймауты. NOTE: этот клиент отключает проверку TLS —
     * это осознанный компромисс для внутреннего контура с self-signed
     * сертификатами. Если в вашей инфраструктуре есть CA — замените на
     * обычный HttpClient.newHttpClient().
     */
    private static HttpClient createInsecureHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        @Override public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        @Override public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm(null);
            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .sslParameters(sslParameters)
                    .connectTimeout(HTTP_CONNECT_TIMEOUT)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create insecure HttpClient", e);
        }
    }

    /**
     * FIX: обёртка над httpClient.send, которая корректно обрабатывает
     * InterruptedException (восстанавливает флаг) и добавляет timeout,
     * если он не был задан при построении запроса.
     */
    private HttpResponse<String> sendWithTimeout(HttpRequest req) throws Exception {
        // Если timeout не задан — добавляем дефолтный
        HttpRequest effective = req.timeout() != null
                ? req
                : HttpRequest.newBuilder(req.uri())
                .method(req.method(), req.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
                .timeout(HTTP_REQUEST_TIMEOUT)
                .headers(req.headers().map().entrySet().stream()
                        .flatMap(e -> e.getValue().stream().map(v -> new String[]{e.getKey(), v}))
                        .flatMap(arr -> java.util.stream.Stream.of(arr))
                        .toArray(String[]::new))
                .build();

        try {
            return httpClient.send(effective, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ie) {
            // FIX: восстанавливаем флаг прерывания — иначе Tomcat не сможет
            // корректно остановить поток.
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP request interrupted", ie);
        }
    }

    @Override
    public void define(Context context) {
        LOG.info("[Chatbot] Registering WebService controller: api/chatbot");
        NewController controller = context.createController("api/chatbot")
                .setDescription("AI Chatbot Agent")
                .setSince("1.0");

        NewAction chatAction = controller.createAction("chat")
                .setDescription("Send message to AI agent")
                .setPost(true)
                .setHandler(this::handleChat);

        chatAction.createParam("message")
                .setDescription("User message")
                .setRequired(true)
                .setExampleValue("What can you do?");

        chatAction.createParam("history")
                .setDescription("Previous conversation as JSON array of {role, content}")
                .setRequired(false);

        controller.createAction("status")
                .setDescription("Check chatbot configuration status")
                .setHandler(this::handleStatus);

        NewAction setToken = controller.createAction("set-token")
                .setDescription("Save MCP token for current user")
                .setPost(true)
                .setHandler(this::handleSetToken);

        setToken.createParam("token")
                .setDescription("Personal SonarQube token for MCP access")
                .setRequired(true);

        controller.createAction("clear-token")
                .setDescription("Remove stored MCP token for current user")
                .setPost(true)
                .setHandler(this::handleClearToken);

        controller.createAction("token-status")
                .setDescription("Check if current user has a stored token")
                .setHandler(this::handleTokenStatus);

        controller.done();
    }

    // ==================================================================================
    //  Status / Token management
    // ==================================================================================

    private void handleStatus(Request request, Response response) throws Exception {
        String mcpUrl = config.get(ChatbotSettings.MCP_URL).orElse("");
        String aiUrl = config.get(ChatbotSettings.AI_URL).orElse("");
        String aiToken = config.get(ChatbotSettings.AI_TOKEN).orElse("");
        String aiModel = config.get(ChatbotSettings.AI_MODEL).orElse("");

        boolean mcpConfigured = !mcpUrl.isEmpty();
        boolean aiConfigured = !aiUrl.isEmpty() && !aiToken.isEmpty() && !aiModel.isEmpty();

        response.newJsonWriter()
                .beginObject()
                .prop("mcpConfigured", mcpConfigured)
                .prop("aiConfigured", aiConfigured)
                .prop("mcpUrl", mcpUrl)
                .prop("aiUrl", aiUrl)
                .prop("aiTokenPresent", !aiToken.isEmpty())
                .prop("aiModel", aiModel)
                .prop("debug", isDebug())
                .endObject()
                .close();
    }

    private void handleTokenStatus(Request request, Response response) throws Exception {
        String login = requireLogin(request);
        if (login == null) {
            writeError(response, "Authentication required.");
            return;
        }
        boolean hasToken = tokenDao().getToken(login) != null;

        response.newJsonWriter()
                .beginObject()
                .prop("hasToken", hasToken)
                .endObject()
                .close();
    }

    private void handleSetToken(Request request, Response response) throws Exception {
        String login = requireLogin(request);
        if (login == null) {
            writeError(response, "Authentication required.");
            return;
        }
        String token = request.mandatoryParam("token").trim();
        if (token.isEmpty()) {
            writeError(response, "Token must not be empty.");
            return;
        }

        try {
            tokenDao().saveToken(login, token);
        } catch (Exception e) {
            LOG.error("[Chatbot] Failed to save token for '{}'", login, e);
            writeError(response, "Failed to save token: " + e.getMessage());
            return;
        }

        response.newJsonWriter()
                .beginObject()
                .prop("ok", true)
                .endObject()
                .close();
    }

    private void handleClearToken(Request request, Response response) throws Exception {
        String login = requireLogin(request);
        if (login == null) {
            writeError(response, "Authentication required.");
            return;
        }
        tokenDao().deleteToken(login);

        response.newJsonWriter()
                .beginObject()
                .prop("ok", true)
                .endObject()
                .close();
    }

    private String requireLogin(Request request) {
        try {
            WsClient wsClient = WsClientFactories.getLocal()
                    .newClient(request.localConnector());
            CurrentWsResponse currentUser = wsClient.users().current();
            if (currentUser != null && currentUser.hasLogin()) {
                return currentUser.getLogin();
            }
        } catch (Exception e) {
            LOG.warn("[Chatbot] Failed to get current user login", e);
        }
        return null;
    }

    // ==================================================================================
    //  Основной цикл агента
    // ==================================================================================

    private void handleChat(Request request, Response response) throws Exception {
        String login = requireLogin(request);
        if (login == null) {
            writeError(response, "Authentication required.");
            return;
        }

        String userMessage = request.mandatoryParam("message");
        String historyJson = request.param("history");

        // FIX: ограничиваем размер входных данных, чтобы не отправить
        // в LLM килобайты мусора.
        if (userMessage.length() > MAX_USER_MESSAGE_CHARS) {
            writeError(response, "Message too long (max " + MAX_USER_MESSAGE_CHARS + " chars).");
            return;
        }
        if (historyJson != null && historyJson.length() > MAX_HISTORY_JSON_CHARS) {
            LOG.warn("[Chatbot] History too long ({} chars), truncating", historyJson.length());
            historyJson = null; // просто игнорируем слишком большую историю
        }

        debug("========== NEW CHAT REQUEST (user='{}') ==========", login);
        debug("Message: {}", userMessage);

        String aiUrl = config.get(ChatbotSettings.AI_URL).orElse("");
        String aiToken = config.get(ChatbotSettings.AI_TOKEN).orElse("");
        String aiModel = config.get(ChatbotSettings.AI_MODEL).orElse("");
        String systemPrompt = config.get(ChatbotSettings.AI_SYSTEM_PROMPT).orElse("");
        String mcpUrl = config.get(ChatbotSettings.MCP_URL).orElse("");

        if (aiUrl.isEmpty() || aiToken.isEmpty() || aiModel.isEmpty()) {
            writeError(response, "AI not configured. Contact administrator.");
            return;
        }
        if (mcpUrl.isEmpty()) {
            writeError(response, "MCP not configured. Contact administrator.");
            return;
        }

        String userToken = tokenDao().getToken(login);
        if (userToken == null || userToken.isEmpty()) {
            debug("User '{}' has no personal token — asking to set it", login);
            response.newJsonWriter()
                    .beginObject()
                    .prop("error", "TOKEN_REQUIRED")
                    .endObject()
                    .close();
            return;
        }

        try {
            debug("STEP 1: Fetching tools from MCP {}", mcpUrl);
            String tools = listMcpTools(mcpUrl, userToken);
            debug("STEP 1 done. Tools JSON: {}", truncate(tools, 500));

            ArrayNode messages = mapper.createArrayNode();
            messages.addObject().put("role", "system").put("content", systemPrompt);

            int historyAdded = appendHistory(messages, historyJson);
            debug("History messages appended: {}", historyAdded);

            messages.addObject().put("role", "user").put("content", userMessage);

            String finalReply = null;

            for (int step = 1; step <= MAX_AGENT_STEPS; step++) {
                debug("STEP {}: Calling LLM {} model={}", step, aiUrl, aiModel);
                String llmResponse = callLlm(aiUrl, aiToken, aiModel, messages, tools);
                debug("STEP {} done. LLM response: {}", step, truncate(llmResponse, 1000));

                JsonNode llmJson = mapper.readTree(llmResponse);
                JsonNode messageNode = llmJson.path("choices").path(0).path("message");
                String rawContent = messageNode.path("content").asText("");
                String cleanContent = stripDsml(rawContent);

                List<JsonNode> toolCalls = extractAllToolCalls(llmJson);

                if (toolCalls.isEmpty()) {
                    finalReply = cleanContent;
                    if (finalReply.isEmpty()) {
                        finalReply = "Модель не вернула ответ. Проверьте логи SonarQube.";
                    }
                    debug("STEP {}: final answer ({} chars)", step, finalReply.length());
                    break;
                }

                debug("STEP {}: LLM requested {} tool call(s)", step, toolCalls.size());
                messages.add(buildAssistantMessageWithToolCalls(toolCalls, cleanContent));

                for (JsonNode tc : toolCalls) {
                    String toolName = tc.path("name").asText();
                    JsonNode args = tc.path("arguments");
                    debug("  -> Executing tool '{}' with args: {}", toolName, args);

                    String toolResult;
                    try {
                        toolResult = callMcpTool(mcpUrl, userToken, toolName, args);
                    } catch (Exception e) {
                        // FIX: не валим весь чат из-за одного упавшего
                        // инструмента. Возвращаем LLM сообщение об ошибке —
                        // пусть он сам решит, что делать.
                        LOG.warn("[Chatbot] Tool '{}' failed: {}", toolName, e.getMessage());
                        toolResult = "{\"error\":\"" +
                                e.getMessage().replace("\"", "'") + "\"}";
                    }
                    String toolContent = extractMcpContent(toolResult);
                    debug("  <- Tool '{}' result: {}", toolName, truncate(toolContent, 500));

                    String toolCallId = tc.path("id").asText("call_" + toolName);
                    ObjectNode toolMsg = mapper.createObjectNode();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", toolCallId);
                    toolMsg.put("content", toolContent);
                    messages.add(toolMsg);
                }
            }

            if (finalReply == null) {
                finalReply = "Достигнут лимит шагов агента (" + MAX_AGENT_STEPS
                        + "). Попробуйте переформулировать вопрос.";
                LOG.warn("[Chatbot] Agent hit MAX_AGENT_STEPS={}", MAX_AGENT_STEPS);
            }

            debug("========== END CHAT REQUEST ==========");
            response.newJsonWriter()
                    .beginObject()
                    .prop("reply", finalReply)
                    .endObject()
                    .close();

        } catch (Exception e) {
            LOG.error("[Chatbot] Unhandled exception in handleChat", e);
            writeError(response, "Internal error: " + e.getMessage());
        }
    }

    private int appendHistory(ArrayNode messages, String historyJson) {
        if (historyJson == null || historyJson.isEmpty()) {
            return 0;
        }
        try {
            JsonNode hist = mapper.readTree(historyJson);
            if (!hist.isArray()) {
                LOG.warn("[Chatbot] history is not an array: {}", truncate(historyJson, 200));
                return 0;
            }

            int total = hist.size();
            int start = Math.max(0, total - MAX_HISTORY_MESSAGES);
            int added = 0;

            for (int i = start; i < total; i++) {
                JsonNode h = hist.get(i);
                String role = h.path("role").asText("");
                String content = h.path("content").asText("");
                if (content.isEmpty()) continue;
                if (!"user".equals(role) && !"assistant".equals(role)) continue;

                messages.addObject().put("role", role).put("content", content);
                added++;
            }
            return added;
        } catch (Exception e) {
            LOG.warn("[Chatbot] Failed to parse history: {}", e.getMessage());
            return 0;
        }
    }

    private List<JsonNode> extractAllToolCalls(JsonNode llmJson) {
        List<JsonNode> result = new ArrayList<>();
        JsonNode choices = llmJson.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            return result;
        }
        JsonNode message = choices.get(0).path("message");

        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && toolCalls.size() > 0) {
            for (JsonNode tc : toolCalls) {
                JsonNode fn = tc.path("function");
                ObjectNode fnCopy = fn.deepCopy();

                JsonNode args = fnCopy.path("arguments");
                if (args.isTextual()) {
                    try {
                        fnCopy.set("arguments", mapper.readTree(args.asText()));
                    } catch (Exception e) {
                        LOG.warn("[Chatbot] Failed to parse arguments string: {}", args.asText());
                        fnCopy.set("arguments", mapper.createObjectNode());
                    }
                }

                String id = tc.path("id").asText("");
                if (id.isEmpty()) {
                    id = "call_" + fnCopy.path("name").asText();
                }
                fnCopy.put("id", id);

                result.add(fnCopy);
            }
            return result;
        }

        String content = message.path("content").asText("");
        if (content.contains("invoke") && content.contains("name=")) {
            Matcher invokeMatcher = DSML_INVOKE.matcher(content);
            int counter = 0;
            while (invokeMatcher.find()) {
                String toolName = invokeMatcher.group(1);
                String paramsBlock = invokeMatcher.group(2);

                ObjectNode args = mapper.createObjectNode();
                Matcher paramMatcher = DSML_PARAM.matcher(paramsBlock);
                while (paramMatcher.find()) {
                    String paramName = paramMatcher.group(1);
                    String paramValue = paramMatcher.group(2).trim();
                    try {
                        args.set(paramName, mapper.readTree(paramValue));
                    } catch (Exception e) {
                        args.put(paramName, paramValue);
                    }
                }

                ObjectNode fn = mapper.createObjectNode();
                fn.put("name", toolName);
                fn.set("arguments", args);
                fn.put("id", "dsml_" + toolName + "_" + (counter++));

                debug("DSML tool call detected: name='{}' args={}", toolName, args);
                result.add(fn);
            }
        }
        return result;
    }

    private static String stripDsml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = DSML_INVOKE_BLOCK.matcher(text).replaceAll("");
        result = DSML_STRAY_TAG.matcher(result).replaceAll("");
        return result.trim();
    }

    private ObjectNode buildAssistantMessageWithToolCalls(
            List<JsonNode> toolCalls,
            String assistantContent) {

        ObjectNode assistantMsg = mapper.createObjectNode();
        assistantMsg.put("role", "assistant");

        String content = (assistantContent == null || assistantContent.isEmpty())
                ? "" : assistantContent;
        assistantMsg.put("content", content);

        ArrayNode toolCallsArray = assistantMsg.putArray("tool_calls");
        for (JsonNode tc : toolCalls) {
            String id = tc.path("id").asText("");
            if (id.isEmpty()) {
                id = "call_" + tc.path("name").asText();
            }

            ObjectNode tcNode = toolCallsArray.addObject();
            tcNode.put("id", id);
            tcNode.put("type", "function");

            ObjectNode fn = tcNode.putObject("function");
            fn.put("name", tc.path("name").asText());

            JsonNode args = tc.path("arguments");
            String argsStr;
            if (args.isTextual()) {
                argsStr = args.asText();
            } else if (args.isMissingNode() || args.isNull()) {
                argsStr = "{}";
            } else {
                try {
                    argsStr = mapper.writeValueAsString(args);
                } catch (Exception e) {
                    LOG.warn("[Chatbot] Failed to serialize arguments for tool '{}'",
                            tc.path("name").asText(), e);
                    argsStr = "{}";
                }
            }
            fn.put("arguments", argsStr);
        }
        return assistantMsg;
    }

    private String listMcpTools(String mcpUrl, String mcpToken) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + mcpToken)
                .timeout(HTTP_REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = sendWithTimeout(req);
        if (resp.statusCode() != 200) {
            LOG.error("[Chatbot] MCP tools/list failed: HTTP {}", resp.statusCode());
            return "[]";
        }
        JsonNode json = mapper.readTree(resp.body());
        if (json.has("error")) {
            LOG.error("[Chatbot] MCP tools/list error: {}", json.path("error"));
            return "[]";
        }
        JsonNode toolsNode = json.path("result").path("tools");
        if (!toolsNode.isArray()) {
            return "[]";
        }
        return toolsNode.toString();
    }

    private String callMcpTool(String mcpUrl, String mcpToken,
                               String toolName, JsonNode args) throws Exception {
        JsonNode normalizedArgs;
        if (args.isTextual()) {
            normalizedArgs = mapper.readTree(args.asText());
        } else if (args.isMissingNode() || args.isNull()) {
            normalizedArgs = mapper.createObjectNode();
        } else {
            normalizedArgs = args;
        }

        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", normalizedArgs);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("id", 2);
        payload.put("method", "tools/call");
        payload.set("params", params);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + mcpToken)
                .timeout(HTTP_REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> resp = sendWithTimeout(req);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("MCP tools/call returned HTTP " + resp.statusCode()
                    + ": " + truncate(resp.body(), 500));
        }
        return resp.body();
    }

    /**
     * Достаёт полезный текст из ответа MCP.
     * Поддерживает два формата:
     *   1. JSON: {"jsonrpc":"2.0","result":{"content":[{"type":"text","text":"..."}]}}
     *   2. SSE:  "data: {...}\n\n"
     */
    private String extractMcpContent(String mcpResponse) {
        try {
            // FIX: если ответ начинается с "data:" — это SSE. Пробуем найти
            // JSON-строку после первого "data:" и распарсить её.
            String body = mcpResponse;
            if (body.startsWith("data:")) {
                int nl = body.indexOf('\n');
                if (nl > 0) {
                    body = body.substring("data:".length(), nl).trim();
                }
            }

            JsonNode json = mapper.readTree(body);
            if (json.has("error")) {
                return "MCP error: " + json.path("error").toString();
            }
            JsonNode content = json.path("result").path("content");
            if (content.isArray() && content.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : content) {
                    if ("text".equals(item.path("type").asText())) {
                        sb.append(item.path("text").asText());
                    } else {
                        sb.append(item.toString());
                    }
                    sb.append("\n");
                }
                return sb.toString().trim();
            }
            JsonNode result = json.path("result");
            return result.isMissingNode() ? mcpResponse : result.toString();
        } catch (Exception e) {
            LOG.warn("[Chatbot] Failed to parse MCP response as JSON, returning raw");
            return mcpResponse;
        }
    }

    private String callLlm(String aiUrl, String aiToken, String aiModel,
                           ArrayNode messages, String tools) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", aiModel);

        ObjectNode reasoning = payload.putObject("reasoning");
        reasoning.put("effort", "none");

        payload.set("messages", messages);

        JsonNode toolsNode = mapper.readTree(tools);
        if (toolsNode.isArray() && toolsNode.size() > 0) {
            ArrayNode toolsArray = payload.putArray("tools");
            for (JsonNode tool : toolsNode) {
                ObjectNode toolObj = toolsArray.addObject();
                toolObj.put("type", "function");
                ObjectNode function = toolObj.putObject("function");
                function.put("name", tool.path("name").asText());
                function.put("description", tool.path("description").asText(""));
                JsonNode schema = tool.path("inputSchema");
                function.set("parameters", schema.isMissingNode()
                        ? mapper.createObjectNode().put("type", "object")
                        : schema);
            }
            payload.put("tool_choice", "auto");
        }

        String payloadStr = payload.toString();
        trace("LLM payload (truncated): {}", truncate(payloadStr, 2000));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(aiUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + aiToken)
                .header("x-bf-passthrough-extra-params", "true")
                .timeout(HTTP_REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(payloadStr))
                .build();

        HttpResponse<String> resp = sendWithTimeout(req);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("LLM returned HTTP " + resp.statusCode()
                    + ": " + truncate(resp.body(), 500));
        }
        return resp.body();
    }

    private void writeError(Response response, String message) throws Exception {
        // FIX: сообщения клиентских ошибок не должны засорять ERROR-лог.
        // Настоящие падения логируются в handleChat через LOG.error.
        LOG.info("[Chatbot] Client error response: {}", message);
        response.newJsonWriter().beginObject().prop("error", message).endObject().close();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}