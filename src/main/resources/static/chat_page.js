window.registerExtension('chatbot/chat_page', function (options) {
    const container = options.el;

    const HISTORY_KEY = 'chatbot_history_v1';
    const MAX_HISTORY = 20;

    // === Стили ===
    const styles = `
        .chatbot-root {
            display: flex;
            flex-direction: column;
            height: calc(100vh - 120px);
            max-width: 900px;
            margin: 0 auto;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            color: var(--sonar-color-text, #2c2c2c);
        }
        .chatbot-header {
            padding: 16px 24px;
            border-bottom: 1px solid var(--sonar-color-border, #e5e5e5);
            display: flex;
            align-items: center;
            justify-content: space-between;
        }
        .chatbot-header h2 { margin: 0; font-size: 18px; font-weight: 600; }
        .chatbot-header-right { display: flex; align-items: center; gap: 12px; }
        .chatbot-status {
            font-size: 12px; color: #6b7280;
            display: flex; align-items: center; gap: 6px;
        }
        .chatbot-status .dot {
            width: 8px; height: 8px; border-radius: 50%; background: #22c55e;
        }
        .chatbot-status .dot.offline { background: #ef4444; }
        .chatbot-clear {
            font-size: 12px; color: #6b7280;
            background: transparent; border: 1px solid #d1d5db;
            border-radius: 6px; padding: 4px 10px; cursor: pointer;
        }
        .chatbot-clear:hover { background: #f3f4f6; color: #374151; }
        .chatbot-messages {
            flex: 1; overflow-y: auto; padding: 24px;
            display: flex; flex-direction: column; gap: 16px;
            background: var(--sonar-color-background, #fafafa);
        }
        .chatbot-message {
            display: flex; gap: 12px; max-width: 80%;
            animation: fadeIn 0.2s ease;
        }
        .chatbot-message.user { align-self: flex-end; flex-direction: row-reverse; }
        .chatbot-message.ai { align-self: flex-start; }
        .chatbot-avatar {
            width: 32px; height: 32px; border-radius: 50%;
            display: flex; align-items: center; justify-content: center;
            font-size: 14px; font-weight: 600; flex-shrink: 0;
        }
        .chatbot-message.user .chatbot-avatar { background: #2563eb; color: white; }
        .chatbot-message.ai .chatbot-avatar { background: #e5e7eb; color: #374151; }
        .chatbot-bubble {
            padding: 10px 14px; border-radius: 12px;
            font-size: 14px; line-height: 1.5;
            white-space: pre-wrap; word-wrap: break-word;
        }
        .chatbot-message.user .chatbot-bubble {
            background: #2563eb; color: white; border-bottom-right-radius: 4px;
        }
        .chatbot-message.ai .chatbot-bubble {
            background: white; border: 1px solid #e5e7eb; border-bottom-left-radius: 4px;
        }
        .chatbot-bubble.error {
            background: #fef2f2; border-color: #fecaca; color: #b91c1c;
        }
        .chatbot-typing { display: flex; gap: 4px; padding: 12px 16px; }
        .chatbot-typing span {
            width: 8px; height: 8px; border-radius: 50%; background: #9ca3af;
            animation: typing 1.4s infinite;
        }
        .chatbot-typing span:nth-child(2) { animation-delay: 0.2s; }
        .chatbot-typing span:nth-child(3) { animation-delay: 0.4s; }
        .chatbot-input-area {
            padding: 16px 24px;
            border-top: 1px solid var(--sonar-color-border, #e5e5e5);
            display: flex; gap: 12px; background: white;
        }
        .chatbot-input {
            flex: 1; padding: 12px 16px;
            border: 1px solid #d1d5db; border-radius: 8px;
            font-size: 14px; font-family: inherit;
            resize: none; outline: none; transition: border-color 0.15s;
        }
        .chatbot-input:focus {
            border-color: #2563eb;
            box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
        }
        .chatbot-send {
            padding: 0 24px; background: #2563eb; color: white;
            border: none; border-radius: 8px;
            font-size: 14px; font-weight: 500;
            cursor: pointer; transition: background 0.15s;
        }
        .chatbot-send:hover:not(:disabled) { background: #1d4ed8; }
        .chatbot-send:disabled { background: #9ca3af; cursor: not-allowed; }
        .chatbot-empty {
            text-align: center; padding: 60px 24px; color: #6b7280;
        }
        .chatbot-empty h3 { margin: 0 0 8px; color: #374151; }
        .chatbot-error-block { padding: 60px 24px; text-align: center; }
        .chatbot-error-block h3 { color: #b91c1c; }
        .chatbot-modal-overlay {
            position: fixed; inset: 0;
            background: rgba(0,0,0,0.4);
            display: flex; align-items: center; justify-content: center;
            z-index: 10000;
        }
        .chatbot-modal {
            background: white; border-radius: 12px;
            padding: 24px; max-width: 460px; width: 90%;
            box-shadow: 0 10px 40px rgba(0,0,0,0.2);
        }
        .chatbot-modal h3 { margin: 0 0 12px; font-size: 16px; }
        .chatbot-modal p  { color: #6b7280; font-size: 13px; margin: 0 0 16px; line-height: 1.5; }
        .chatbot-modal input {
            width: 100%; padding: 10px 12px;
            border: 1px solid #d1d5db; border-radius: 8px;
            font-size: 14px; box-sizing: border-box;
            font-family: inherit;
        }
        .chatbot-modal input:focus {
            outline: none; border-color: #2563eb;
            box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
        }
        .chatbot-modal-actions {
            display: flex; gap: 8px; justify-content: flex-end;
            margin-top: 16px;
        }
        .chatbot-modal-actions button {
            padding: 8px 16px; border-radius: 6px;
            border: none; cursor: pointer; font-size: 14px;
            font-family: inherit;
        }
        .chatbot-modal-cancel { background: #e5e7eb; color: #374151; }
        .chatbot-modal-save   { background: #2563eb; color: white; }
        .chatbot-modal-save:disabled { background: #9ca3af; cursor: not-allowed; }
        .chatbot-modal-error  { color: #b91c1c; font-size: 12px; margin-top: 8px; }
        @keyframes fadeIn {
            from { opacity: 0; transform: translateY(4px); }
            to { opacity: 1; transform: translateY(0); }
        }
        @keyframes typing {
            0%, 60%, 100% { transform: translateY(0); opacity: 0.5; }
            30% { transform: translateY(-4px); opacity: 1; }
        }
    `;

    // === Утилиты ===
    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    function loadHistory() {
        try {
            const raw = sessionStorage.getItem(HISTORY_KEY);
            if (!raw) return [];
            const arr = JSON.parse(raw);
            return Array.isArray(arr) ? arr : [];
        } catch (e) { return []; }
    }

    function saveHistory(history) {
        try {
            const trimmed = history.slice(-MAX_HISTORY);
            sessionStorage.setItem(HISTORY_KEY, JSON.stringify(trimmed));
        } catch (e) { /* ignore */ }
    }

    function clearHistory() {
        try { sessionStorage.removeItem(HISTORY_KEY); } catch (e) { /* ignore */ }
    }

    // FIX: безопасный запрос JSON. Раньше response.json() падал
    // с "Unexpected token <" при 500 (HTML). Теперь показываем понятную
    // ошибку и возвращаем null.
    async function fetchJson(url, options) {
        try {
            const resp = await fetch(url, Object.assign({ credentials: 'same-origin' }, options));
            const ct = resp.headers.get('content-type') || '';
            if (!ct.includes('application/json')) {
                const text = await resp.text();
                console.error('[Chatbot] Non-JSON response from', url,
                    'status=' + resp.status, 'body=' + text.slice(0, 200));
                return { error: 'Сервер вернул некорректный ответ (HTTP ' + resp.status + ')' };
            }
            return await resp.json();
        } catch (e) {
            console.error('[Chatbot] fetchJson failed for', url, e);
            return { error: 'Сетевая ошибка: ' + e.message };
        }
    }

    // FIX: заменяем все обращения к fetch/json на fetchJson.
    async function checkStatus() {
        const data = await fetchJson('/api/chatbot/status');
        if (data && data.debug) {
            console.log('[Chatbot] debug is enabled server-side');
        }
        return data || { mcpConfigured: false, aiConfigured: false };
    }

    async function checkTokenStatus() {
        const data = await fetchJson('/api/chatbot/token-status');
        return data || { hasToken: false };
    }

    async function sendMessage(message, history) {
        return await window.SonarRequest.postJSON('/api/chatbot/chat', {
            message: message,
            history: JSON.stringify(history)
        });
    }

    async function saveToken(token) {
        return await window.SonarRequest.postJSON('/api/chatbot/set-token', { token });
    }

    async function clearToken() {
        return await window.SonarRequest.postJSON('/api/chatbot/clear-token', {});
    }

    // FIX: обновляем showTokenModal — добавляем закрытие по Escape,
    // сохраняем таймер фокуса и показываем более понятные ошибки.
    function showTokenModal(onSaved) {
        const overlay = document.createElement('div');
        overlay.className = 'chatbot-modal-overlay';
        overlay.innerHTML = `
            <div class="chatbot-modal">
                <h3>Доступ к MCP</h3>
                <p>
                    Введите ваш персональный токен SonarQube. Он будет зашифрован и сохранён
                    на сервере. Используется только для ваших запросов — вы увидите только
                    те проекты, к которым у вас есть доступ.
                    Создается в разделе My Account -> Security с типом User Token.
                    Потом ты сможешь его поменять по кнопке Token.
                </p>
                <input type="password" id="chatbot-token-input"
                       placeholder="Ваш персональный токен" autocomplete="off" maxlength="200">
                <div class="chatbot-modal-error" id="chatbot-token-error"></div>
                <div class="chatbot-modal-actions">
                    <button class="chatbot-modal-cancel" id="chatbot-token-cancel">Отмена</button>
                    <button class="chatbot-modal-save" id="chatbot-token-save">Сохранить</button>
                </div>
            </div>
        `;
        document.body.appendChild(overlay);

        const inputEl = overlay.querySelector('#chatbot-token-input');
        const saveBtn = overlay.querySelector('#chatbot-token-save');
        const cancelBtn = overlay.querySelector('#chatbot-token-cancel');
        const errorEl = overlay.querySelector('#chatbot-token-error');

        // FIX: сохраняем таймер фокуса, чтобы очистить его при закрытии.
        const focusTimer = setTimeout(() => {
            if (document.body.contains(overlay)) inputEl.focus();
        }, 50);

        // FIX: централизованное закрытие — чистит таймер и слушателей.
        function close() {
            clearTimeout(focusTimer);
            document.removeEventListener('keydown', onKey);
            overlay.remove();
        }

        function onKey(e) {
            if (e.key === 'Escape') { e.preventDefault(); close(); }
        }
        document.addEventListener('keydown', onKey);

        cancelBtn.onclick = close;

        async function doSave() {
            const token = inputEl.value.trim();
            if (!token) {
                errorEl.textContent = 'Введите токен';
                return;
            }
            saveBtn.disabled = true;
            errorEl.textContent = '';
            try {
                const resp = await saveToken(token);
                if (resp && resp.ok) {
                    close();
                    onSaved();
                } else {
                    // FIX: показываем сообщение от сервера, если оно есть.
                    errorEl.textContent = (resp && resp.error)
                        ? resp.error
                        : 'Не удалось сохранить токен';
                }
            } catch (e) {
                errorEl.textContent = 'Ошибка: ' + e.message;
            } finally {
                saveBtn.disabled = false;
            }
        }

        saveBtn.onclick = doSave;
        inputEl.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { e.preventDefault(); doSave(); }
        });
    }

    // === Рендер ===
    async function init() {
        const styleEl = document.createElement('style');
        styleEl.textContent = styles;
        container.appendChild(styleEl);

        const status = await checkStatus();

        if (!status.mcpConfigured || !status.aiConfigured) {
            container.innerHTML += `
                <div class="chatbot-root">
                    <div class="chatbot-error-block">
                        <h3>⚠️ Чат не настроен</h3>
                        <p>Обратитесь к администратору SonarQube для настройки MCP и AI сервисов.</p>
                    </div>
                </div>`;
            return;
        }

        // Проверяем, есть ли у пользователя токен
        let tokenStatus;
        try {
            tokenStatus = await checkTokenStatus();
        } catch (e) {
            tokenStatus = { hasToken: false };
        }

        if (!tokenStatus.hasToken) {
            showTokenModal(
                () => { container.innerHTML = ''; init(); },
                () => {
                    // onCancel
                    container.innerHTML += `
                        <div class="chatbot-root">
                            <div class="chatbot-error-block">
                                <h3>Токен не задан</h3>
                                <p>Без персонального токена работа чата невозможна.</p>
                                <button class="chatbot-clear" id="chatbot-retry-token"
                                        style="margin-top:12px;">Ввести токен</button>
                            </div>
                        </div>`;
                    container.querySelector('#chatbot-retry-token').onclick = () => {
                        container.innerHTML = '';
                        init();
                    };
                }
            );
            return;
        }

        const root = document.createElement('div');
        root.className = 'chatbot-root';
        root.innerHTML = `
            <div class="chatbot-header">
                <h2>AI Chat</h2>
                <div class="chatbot-header-right">
                    <button class="chatbot-clear" id="chatbot-change-token" title="Сменить токен">Токен</button>
                    <button class="chatbot-clear" id="chatbot-clear" title="Очистить историю">Очистить</button>
                    <div class="chatbot-status">
                        <span class="dot"></span>
                        <span>${escapeHtml(status.mcpUrl || 'MCP')}</span>
                    </div>
                </div>
            </div>
            <div class="chatbot-messages" id="chatbot-messages"></div>
            <div class="chatbot-input-area">
                <textarea class="chatbot-input" id="chatbot-input"
                          rows="1" placeholder="Введите вопрос..."></textarea>
                <button class="chatbot-send" id="chatbot-send">Отправить</button>
            </div>
        `;
        container.appendChild(root);

        const messagesEl = root.querySelector('#chatbot-messages');
        const inputEl = root.querySelector('#chatbot-input');
        const sendBtn = root.querySelector('#chatbot-send');
        const clearBtn = root.querySelector('#chatbot-clear');
        const changeTokenBtn = root.querySelector('#chatbot-change-token');

        let busy = false;
        let conversationHistory = loadHistory();

        inputEl.addEventListener('input', () => {
            inputEl.style.height = 'auto';
            inputEl.style.height = Math.min(inputEl.scrollHeight, 120) + 'px';
        });

        function addMessage(role, text, isError = false) {
            const msg = document.createElement('div');
            msg.className = `chatbot-message ${role}`;
            const avatar = role === 'user' ? 'Я' : 'AI';
            msg.innerHTML = `
                <div class="chatbot-avatar">${avatar}</div>
                <div class="chatbot-bubble ${isError ? 'error' : ''}">${escapeHtml(text)}</div>
            `;
            messagesEl.appendChild(msg);
            messagesEl.scrollTop = messagesEl.scrollHeight;
        }

        function addTyping() {
            const el = document.createElement('div');
            el.className = 'chatbot-message ai';
            el.id = 'chatbot-typing';
            el.innerHTML = `
                <div class="chatbot-avatar">AI</div>
                <div class="chatbot-bubble">
                    <div class="chatbot-typing">
                        <span></span><span></span><span></span>
                    </div>
                </div>
            `;
            messagesEl.appendChild(el);
            messagesEl.scrollTop = messagesEl.scrollHeight;
        }

        function removeTyping() {
            const el = messagesEl.querySelector('#chatbot-typing');
            if (el) el.remove();
        }

        function renderEmpty() {
            messagesEl.innerHTML = `
                <div class="chatbot-empty">
                    <h3>Начните диалог</h3>
                    <p>Задайте вопрос по качеству кода — агент использует только инструменты SonarQube MCP.</p>
                </div>`;
        }

        function renderHistory() {
            messagesEl.innerHTML = '';
            if (conversationHistory.length === 0) {
                renderEmpty();
                return;
            }
            for (const m of conversationHistory) {
                if (m.role === 'user' || m.role === 'assistant') {
                    addMessage(m.role, m.content || '');
                }
            }
        }

        renderHistory();

        clearBtn.addEventListener('click', () => {
            if (busy) return;
            conversationHistory = [];
            clearHistory();
            renderEmpty();
            inputEl.focus();
        });

        changeTokenBtn.addEventListener('click', () => {
            if (busy) return;
            showTokenModal(() => { /* токен обновлён — ничего больше не делаем */ });
        });

        // FIX: в handleSend добавляем проверку result на null (fetchJson может
        // вернуть null при сетевом сбое) и обрабатываем случай истёкшей сессии.
        async function handleSend() {
            const msg = inputEl.value.trim();
            if (!msg || busy) return;

            // FIX: ограничение длины сообщения на клиенте — быстрее, чем ждать 400.
            if (msg.length > 8000) {
                addMessage('ai', 'Сообщение слишком длинное (максимум 8000 символов).', true);
                return;
            }

            busy = true;
            sendBtn.disabled = true;
            inputEl.value = '';
            inputEl.style.height = 'auto';

            const empty = messagesEl.querySelector('.chatbot-empty');
            if (empty) empty.remove();

            addMessage('user', msg);
            addTyping();

            const historyToSend = conversationHistory.slice(-MAX_HISTORY);

            try {
                const result = await sendMessage(msg, historyToSend);
                removeTyping();

                // FIX: если window.SonarRequest вернул null/undefined —
                // это сетевой сбой, показываем общее сообщение.
                if (!result) {
                    addMessage('ai', 'Сервер не ответил. Попробуйте ещё раз.', true);
                    return;
                }

                if (result.error === 'TOKEN_REQUIRED') {
                    addMessage('ai', 'Нужно задать персональный токен SonarQube.', true);
                    showTokenModal(() => { /* повторить отправку не будем */ });
                } else if (result.error === 'Authentication required.') {
                    // FIX: сессия истекла.
                    addMessage('ai', 'Сессия истекла. Обновите страницу.', true);
                } else if (result.error) {
                    addMessage('ai', result.error, true);
                } else {
                    const reply = result.reply || 'Пустой ответ';
                    addMessage('ai', reply);

                    conversationHistory.push({ role: 'user', content: msg });
                    conversationHistory.push({ role: 'assistant', content: reply });
                    if (conversationHistory.length > MAX_HISTORY) {
                        conversationHistory = conversationHistory.slice(-MAX_HISTORY);
                    }
                    saveHistory(conversationHistory);
                }
            } catch (e) {
                removeTyping();
                addMessage('ai', 'Ошибка: ' + e.message, true);
            } finally {
                busy = false;
                sendBtn.disabled = false;
                inputEl.focus();
            }
        }

        sendBtn.addEventListener('click', handleSend);
        inputEl.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSend();
            }
        });

        inputEl.focus();
    }

    init();
    return function () {};
});