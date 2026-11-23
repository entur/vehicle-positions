package org.entur.vehicles.graphql.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenWebSocketSessionsTest {

    private static final Duration NO_SPACING = Duration.ZERO;
    private static final Duration MAX_SPREAD = Duration.ofSeconds(5);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static OpenWebSocketSessions registry() {
        return registry(TIMEOUT);
    }

    private static OpenWebSocketSessions registry(Duration timeout) {
        return new OpenWebSocketSessions(NO_SPACING, MAX_SPREAD, timeout);
    }

    private static WebSocketSession openSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Test
    void stopClosesEveryOpenSessionWithGoingAway() throws Exception {
        OpenWebSocketSessions sessions = registry();
        WebSocketSession first = openSession("1");
        WebSocketSession second = openSession("2");
        sessions.opened(first);
        sessions.opened(second);

        sessions.stop();

        verify(first).close(OpenWebSocketSessions.SHUTTING_DOWN);
        verify(second).close(OpenWebSocketSessions.SHUTTING_DOWN);
        assertThat(OpenWebSocketSessions.SHUTTING_DOWN.getCode()).isEqualTo(CloseStatus.GOING_AWAY.getCode());
    }

    @Test
    void aFailedCloseDoesNotStopTheOthers() throws Exception {
        OpenWebSocketSessions sessions = registry();
        WebSocketSession failing = openSession("1");
        WebSocketSession other = openSession("2");
        doThrow(new IOException("broken pipe")).when(failing).close(OpenWebSocketSessions.SHUTTING_DOWN);
        sessions.opened(failing);
        sessions.opened(other);

        sessions.stop();

        verify(other).close(OpenWebSocketSessions.SHUTTING_DOWN);
    }

    @Test
    void aStuckCloseDoesNotHoldUpShutdownPastTheDeadline() throws Exception {
        OpenWebSocketSessions sessions = registry(Duration.ofMillis(200));
        CountDownLatch never = new CountDownLatch(1);
        WebSocketSession stuck = openSession("stuck");
        doAnswer(i -> { never.await(); return null; }).when(stuck).close(any());
        WebSocketSession other = openSession("other");
        sessions.opened(stuck);
        sessions.opened(other);

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> sessions.stop());
        } finally {
            never.countDown();
        }
        verify(other).close(OpenWebSocketSessions.SHUTTING_DOWN);
    }

    @Test
    void aSessionThatConnectsDuringShutdownIsClosedToo() throws Exception {
        OpenWebSocketSessions sessions = registry();
        sessions.stop();

        WebSocketSession late = openSession("late");
        sessions.opened(late);

        verify(late, timeout(1_000)).close(OpenWebSocketSessions.SHUTTING_DOWN);
        assertThat(sessions.count()).isZero();
    }

    @Test
    void closedSessionsAreForgotten() throws Exception {
        OpenWebSocketSessions sessions = registry();
        WebSocketSession session = openSession("1");
        sessions.opened(session);

        sessions.closed(session);
        sessions.stop();

        assertThat(sessions.count()).isZero();
        verify(session, never()).close(any());
    }

    @Test
    void sessionsAlreadyClosedAreNotClosedAgain() throws Exception {
        OpenWebSocketSessions sessions = registry();
        WebSocketSession gone = openSession("gone");
        sessions.opened(gone);
        when(gone.isOpen()).thenReturn(false);

        sessions.stop();

        verify(gone, never()).close(any());
    }

    @Test
    void sessionsAreClosedOnlyOnce() throws Exception {
        OpenWebSocketSessions sessions = registry();
        WebSocketSession session = openSession("1");
        sessions.opened(session);

        sessions.stop();
        sessions.stop();

        verify(session).close(OpenWebSocketSessions.SHUTTING_DOWN);
    }

    @Test
    void closesAreSpreadEvenlyOverTheWindow() {
        assertThat(OpenWebSocketSessions.closeDelays(4, Duration.ofMillis(300)))
                .containsExactly(Duration.ZERO, Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(300));
        assertThat(OpenWebSocketSessions.closeDelays(1, Duration.ofSeconds(5))).containsExactly(Duration.ZERO);
        assertThat(OpenWebSocketSessions.closeDelays(0, Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    void theSpreadGrowsWithTheNumberOfSessionsUpToTheMaximum() {
        Duration spacing = Duration.ofMillis(20);
        assertThat(OpenWebSocketSessions.spreadFor(1, spacing, MAX_SPREAD)).isZero();
        assertThat(OpenWebSocketSessions.spreadFor(2, spacing, MAX_SPREAD)).isEqualTo(Duration.ofMillis(20));
        assertThat(OpenWebSocketSessions.spreadFor(51, spacing, MAX_SPREAD)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OpenWebSocketSessions.spreadFor(5_000, spacing, MAX_SPREAD)).isEqualTo(MAX_SPREAD);
    }

    @Test
    void aSessionClosedDuringItsOwnSetUpIsNotKept() {
        OpenWebSocketSessions sessions = registry();
        WebSocketSession gone = openSession("gone");
        when(gone.isOpen()).thenReturn(false);
        sessions.closed(gone); // the close callback ran first

        sessions.opened(gone);

        assertThat(sessions.count()).isZero();
    }

    @Test
    void newConnectionsAreRefusedOnceShutdownBegins() {
        OpenWebSocketSessions sessions = registry();
        assertThat(sessions.isAcceptingConnections()).isTrue();

        sessions.stop();

        assertThat(sessions.isAcceptingConnections()).isFalse();
    }

    @Test
    void startingAgainAcceptsAndKeepsSessions() throws Exception {
        OpenWebSocketSessions sessions = registry();
        sessions.stop();

        sessions.start();
        WebSocketSession session = openSession("1");
        sessions.opened(session);

        assertThat(sessions.isAcceptingConnections()).isTrue();
        assertThat(sessions.count()).isEqualTo(1);
        verify(session, never()).close(any());
    }

    /** Must close sessions before the web server's graceful shutdown pauses the connector */
    @Test
    void stopsBeforeTheWebServersGracefulShutdown() {
        assertThat(registry().getPhase())
                .isGreaterThan(org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE);
    }
}
