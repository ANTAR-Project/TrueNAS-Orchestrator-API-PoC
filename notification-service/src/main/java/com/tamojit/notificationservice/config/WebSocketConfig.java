package com.tamojit.notificationservice.config;

import com.tamojit.notificationservice.websocket.NotificationWebSocketHandler;
import com.tamojit.notificationservice.websocket.TokenHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {
    private final NotificationWebSocketHandler notificationWebSocketHandler;
    private final TokenHandshakeInterceptor tokenHandshakeInterceptor;

    @Value("${notification.ws.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notificationWebSocketHandler, "/ws/notifications")
            .addInterceptors(tokenHandshakeInterceptor)
            .setAllowedOrigins(allowedOrigins.split(","));
    }
}
