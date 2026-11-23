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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * remaining pods do not take every reconnect - each one streaming a full initial snapshot - in
 * the same instant.
 *
 * <p>The whole close is bounded by {@code closeSpread + closeTimeout}: a close is a blocking
 * write of a close frame, which can stall on a slow client, so closes run on a small pool and
 * stop() returns at the deadline whatever is still pending. Shutdown must not overrun into the
 * Pub/Sub teardown that follows it in the termination grace period.
 */
public class OpenWebSocketSessions implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(OpenWebSocketSessions.class);

    static final CloseStatus SHUTTING_DOWN = CloseStatus.GOING_AWAY.withReason("Server shutting down");

    private static final int MAX_CLOSE_THREADS = 8;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final Duration closeSpread;
    private final Duration closeTimeout;
    private final AtomicInteger closedAfterStop = new AtomicInteger();
    private volatile boolean running;
    private volatile boolean stopping;

    public OpenWebSocketSessions(Duration closeSpread, Duration closeTimeout) {
        this.closeSpread = closeSpread;
        this.closeTimeout = closeTimeout;
    }

    /**
     * Registers a session. Once shutdown has begun, the session is closed with 1001 instead: it
     * connected after closeAll() took its snapshot and would otherwise be cut off with 1006 when
     * the web server stops.
     */
    void opened(WebSocketSession session) {
        sessions.add(session);
        // stop() sets the flag before it takes its snapshot, so a session added before the
        // snapshot is in it, and one added after sees the flag. Whoever removes it closes it.
        if (stopping && sessions.remove(session)) {
            closedAfterStop.incrementAndGet();
            close(session);
        }
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
        stopping = true;
        List<WebSocketSession> open = new ArrayList<>();
        for (WebSocketSession session : sessions) {
            // Claim each one, so a concurrent opened() or a second stop() cannot close it again
            if (session.isOpen() && sessions.remove(session)) {
                open.add(session);
            }
        }
        if (open.isEmpty()) {
            return;
        }

        long started = System.nanoTime();
        List<Duration> delays = closeDelays(open.size(), closeSpread);
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(open.size());
        ScheduledExecutorService closer = Executors.newScheduledThreadPool(
                Math.min(MAX_CLOSE_THREADS, open.size()), Thread.ofPlatform().daemon().name("ws-close-", 0).factory());
        try {
            for (int i = 0; i < open.size(); i++) {
                WebSocketSession session = open.get(i);
                closer.schedule(() -> {
                    try {
                        if (!close(session)) {
                            failed.incrementAndGet();
                        }
                    } finally {
                        done.countDown();
                    }
                }, delays.get(i).toNanos(), TimeUnit.NANOSECONDS);
            }
            boolean finished = done.await(closeSpread.plus(closeTimeout).toNanos(), TimeUnit.NANOSECONDS);
            long pending = finished ? 0 : done.getCount();
            report(open.size(), failed.get(), pending, Duration.ofNanos(System.nanoTime() - started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while closing {} WebSocket sessions; {} not yet closed", open.size(), done.getCount());
        } finally {
            closer.shutdownNow();
        }
    }

    /**
     * When to close each of {@code count} sessions: evenly over {@code spread}, the first at once
     * and the last at the end of it.
     */
    static List<Duration> closeDelays(int count, Duration spread) {
        List<Duration> delays = new ArrayList<>(count);
        long step = count > 1 ? spread.toNanos() / (count - 1) : 0;
        for (int i = 0; i < count; i++) {
            delays.add(Duration.ofNanos(step * i));
        }
        return delays;
    }

    private void report(int total, int failed, long pending, Duration took) {
        int extra = closedAfterStop.get();
        if (failed == 0 && pending == 0) {
            LOG.info("Closed {} WebSocket sessions with {} in {} ms ({} more connected during shutdown)",
                    total, SHUTTING_DOWN.getCode(), took.toMillis(), extra);
        } else {
            // These clients see an abnormal close (1006) instead when the web server stops
            LOG.warn("Closed {} of {} WebSocket sessions with {} in {} ms: {} failed, {} not done by the deadline ({} more connected during shutdown)",
                    total - failed - pending, total, SHUTTING_DOWN.getCode(), took.toMillis(), failed, pending, extra);
        }
    }

    /** @return false if the close frame could not be sent */
    private static boolean close(WebSocketSession session) {
        try {
            session.close(SHUTTING_DOWN);
            return true;
        } catch (Exception e) {
            // A concurrent send, or a client that already left
            LOG.debug("Could not close WebSocket session {} cleanly: {}", session.getId(), e.toString());
            return false;
        }
    }
}
