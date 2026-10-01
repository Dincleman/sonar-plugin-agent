package com.example.chatbot;

import org.sonar.api.Plugin;

public class ChatbotPlugin implements Plugin {
    @Override
    public void define(Context context) {
        context.addExtensions(ChatbotSettings.definitions());
        context.addExtension(ChatbotWebService.class);
        context.addExtension(ChatbotPageDefinition.class);
    }
}