package com.example.chatbot;

import org.sonar.api.web.page.Context;
import org.sonar.api.web.page.Page;
import org.sonar.api.web.page.PageDefinition;

public class ChatbotPageDefinition implements PageDefinition {
    @Override
    public void define(Context context) {
        context.addPage(
                Page.builder("chatbot/chat_page")
                        .setName("AI Chat")
                        .setScope(Page.Scope.GLOBAL)
                        .build()
        );
    }
}