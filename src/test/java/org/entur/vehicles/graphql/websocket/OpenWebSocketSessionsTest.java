package org.entur.vehicles.graphql.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenWebSocketSessionsTest {

    private static WebSocketSession openSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Test
    void stopClosesEveryOpenSessionWithGoingAway() throws Exception {
        OpenWebSocketSessions sessions = new OpenWebSocketSessions(Duration.ZERO);
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
        OpenWebSocketSessions sessions = new OpenWebSocketSessions(Duration.ZERO);
        WebSocketSession failing = openSession("1");
        WebSocketSession other = openSession("2");
        doThrow(new IOException("broken pipe")).when(failing).close(OpenWebSocketSessions.SHUTTING_DOWN);
        sessions.opened(failing);
        sessions.opened(other);

        sessions.stop();

        verify(other).close(OpenWebSocketSessions.SHUTTING_DOWN);
    }

    @Test
    void closedSessionsAreForgotten() throws Exception {
        OpenWebSocketSessions sessions = new OpenWebSocketSessions(Duration.ZERO);
        WebSocketSession session = openSession("1");
        sessions.opened(session);

        sessions.closed(session);
        sessions.stop();

        assertThat(sessions.count()).isZero();
        verify(session, never()).close(OpenWebSocketSessions.SHUTTING_DOWN);
    }

    @Test
    void closesAreSpreadOverTheConfiguredTime() {
        OpenWebSocketSessions sessions = new OpenWebSocketSessions(Duration.ofMillis(400));
        for (int i = 0; i < 4; i++) {
            sessions.opened(openSession(String.valueOf(i)));
        }

        long started = System.nanoTime();
        sessions.stop();
        long tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        // Three pauses of 100 ms between four closes
        assertThat(tookMillis).isBetween(250L, 2_000L);
    }

    @Test
    void stopsFirstOnShutdown() {
        assertThat(new OpenWebSocketSessions(Duration.ZERO).getPhase()).isEqualTo(Integer.MAX_VALUE);
    }
}
