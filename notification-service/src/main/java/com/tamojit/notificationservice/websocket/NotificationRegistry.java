package com.tamojit.notificationservice.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// In-memory — holds live WebSocket sessions per username.
@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationRegistry {
    private final ObjectMapper objectMapper;
    private final Map<String, Set<WebSocketSession>> sessionsByUsername = new ConcurrentHashMap<>();

    public void register(String username, WebSocketSession session) {
        sessionsByUsername.computeIfAbsent(username, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("Registered WebSocket session for user: {} (total: {})", username, sessionsByUsername.get(username).size());
    }

    public void remove(String username, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByUsername.get(username);
        if (sessions == null) {
            return;
        }

        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByUsername.remove(username);
        }
    }

    // Fire-and-forget: if the user isn't currently connected, the notification is silently dropped, not queued.
    public void sendTo(String username, Object payload) {
        Set<WebSocketSession> sessions = sessionsByUsername.get(username);
        if (sessions == null || sessions.isEmpty()) {
            log.info("No active connection for user: {} — notification dropped", username);
            return;
        }

        try {
            TextMessage message = new TextMessage(objectMapper.writeValueAsString(payload));

            for (WebSocketSession session : sessions) {
                if (!session.isOpen()) {
                    continue; // fire & forget
                }

                // synchronized per-session to avoid corrupting the frame stream, if two events for the same user land close together.
                synchronized (session) {
                    session.sendMessage(message);
                }
            }
        } catch (IOException e) {
            log.error("Failed to send notification to user: {}", username, e);
        }
    }
}
