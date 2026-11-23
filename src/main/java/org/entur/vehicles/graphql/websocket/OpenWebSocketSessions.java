package org.entur.vehicles.graphql.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The GraphQL WebSocket sessions open on this pod, closed with 1001 (going away) on shutdown.
 *
 * <p>Without this, a stopping pod dropped its subscriptions without a close frame, and clients
 * saw an abnormal close (1006) or an I/O error. 1001 tells graphql-ws and similar clients to
 * reconnect at once; by the time SIGTERM arrives, after the preStop sleep, this pod is no longer
 * a Service endpoint, so they reconnect to another pod.
 *
 * <p>Stops in the highest phase, so before the web server's graceful shutdown and before the
 * Pub/Sub subscribers are torn down. The closes are spread over {@code closeSpread} so that the
 * remaining pods do not take every reconnect - each one streaming a full initial snapshot - in the
 * same instant.
 */
public class OpenWebSocketSessions implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(OpenWebSocketSessions.class);

    static final CloseStatus SHUTTING_DOWN = CloseStatus.GOING_AWAY.withReason("Server shutting down");

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final Duration closeSpread;
    private volatile boolean running;

    public OpenWebSocketSessions(Duration closeSpread) {
        this.closeSpread = closeSpread;
    }

    void opened(WebSocketSession session) {
        sessions.add(session);
    }

    void closed(WebSocketSession session) {
        sessions.remove(session);
    }

    int count() {
        return sessions.size();
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void stop() {
        running = false;
        closeAll();
    }

    void closeAll() {
        List<WebSocketSession> open = new ArrayList<>(sessions);
        if (open.isEmpty()) {
            return;
        }
        LOG.info("Closing {} WebSocket sessions with {} over {}", open.size(), SHUTTING_DOWN, closeSpread);
        long pauseNanos = closeSpread.toNanos() / open.size();
        int closed = 0;
        for (WebSocketSession session : open) {
            if (closed > 0 && pauseNanos > 0) {
                try {
                    Thread.sleep(Duration.ofNanos(pauseNanos));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pauseNanos = 0; // close the rest at once
                }
            }
            close(session);
            closed++;
        }
    }

    private static void close(WebSocketSession session) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(SHUTTING_DOWN);
        } catch (Exception e) {
            // A concurrent send or a client that already left; the connection ends either way
            LOG.debug("Could not close WebSocket session {} cleanly: {}", session.getId(), e.toString());
        }
    }
}
