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
 * a Service endpoint, so they reconnect to another pod. New handshakes are refused with 503 from
 * the moment shutdown begins (see {@link #isAcceptingConnections()}).
 *
 * <p>Stops in the highest phase, so before the web server's graceful shutdown and before the
 * Pub/Sub subscribers are torn down. The closes are spaced {@code closeSpacing} apart, at most
 * {@code maxCloseSpread} in all, so that the remaining pods do not take every reconnect - each one
 * streaming a full initial snapshot - in the same instant, while a pod with few clients is not
 * held up for the full spread.
 *
 * <p>The whole close is bounded by the spread plus {@code closeTimeout}: a close is a blocking
 * write of a close frame, which can stall on a slow client, so closes run on a small pool and
 * stop() returns at the deadline whatever is still pending. Shutdown must not overrun into the
 * Pub/Sub teardown that follows it in the termination grace period.
 */
public class OpenWebSocketSessions implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(OpenWebSocketSessions.class);

    static final CloseStatus SHUTTING_DOWN = CloseStatus.GOING_AWAY.withReason("Server shutting down");

    private static final int MAX_CLOSE_THREADS = 8;
    /** Failed closes logged one by one at WARN; the rest are only counted */
    private static final int MAX_LOGGED_FAILURES = 10;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final Duration closeSpacing;
    private final Duration maxCloseSpread;
    private final Duration closeTimeout;
    private final AtomicInteger closedAfterStop = new AtomicInteger();
    private final AtomicInteger loggedFailures = new AtomicInteger();
    private volatile boolean running;
    private volatile boolean stopping;

    public OpenWebSocketSessions(Duration closeSpacing, Duration maxCloseSpread, Duration closeTimeout) {
        this.closeSpacing = closeSpacing;
        this.maxCloseSpread = maxCloseSpread;
        this.closeTimeout = closeTimeout;
    }

    /** False from the moment shutdown begins: new handshakes are then refused with 503. */
    boolean isAcceptingConnections() {
        return !stopping;
    }

    /**
     * Registers a session. Once shutdown has begun, the session is closed with 1001 instead: it got
     * past the handshake check just before shutdown began, after closeAll() took its snapshot,
     * and would otherwise be cut off with 1006 when the web server stops.
     */
    void opened(WebSocketSession session) {
        sessions.add(session);
        if (!session.isOpen()) {
            // Closed during its own set-up, possibly before this call: closed() has already run
            // and nothing would ever remove it again
            sessions.remove(session);
            return;
        }
        // closeAll() sets the flag before it takes its snapshot, so a session added before the
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
        stopping = false;
        closedAfterStop.set(0);
        loggedFailures.set(0);
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
        Duration spread = spreadFor(open.size(), closeSpacing, maxCloseSpread);
        List<Duration> delays = closeDelays(open.size(), spread);
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
            boolean finished = done.await(spread.plus(closeTimeout).toNanos(), TimeUnit.NANOSECONDS);
            long pending = finished ? 0 : done.getCount();
            report(open.size(), failed.get(), pending, Duration.ofNanos(System.nanoTime() - started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while closing {} WebSocket sessions; {} not yet closed", open.size(), done.getCount());
        } finally {
            closer.shutdownNow();
        }
    }

    /** {@code spacing} between consecutive closes, but never more than {@code max} in all. */
    static Duration spreadFor(int count, Duration spacing, Duration max) {
        if (count <= 1) {
            return Duration.ZERO;
        }
        Duration spread = spacing.multipliedBy(count - 1);
        return spread.compareTo(max) > 0 ? max : spread;
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
    private boolean close(WebSocketSession session) {
        try {
            session.close(SHUTTING_DOWN);
            return true;
        } catch (Exception e) {
            // A concurrent send, or a client that already left. The client sees 1006 instead.
            if (loggedFailures.incrementAndGet() <= MAX_LOGGED_FAILURES) {
                LOG.warn("Could not close WebSocket session {} with {}: {}", session.getId(), SHUTTING_DOWN.getCode(), e.toString());
            }
            return false;
        }
    }
}
