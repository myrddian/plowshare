package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.files.SessionCloseListener;
import io.aeyer.plowshare.server.session.ProjectRoots;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class FileChannelShutdownTest {
  @Test
  void socket_close_after_context_close_uses_initialized_listeners_without_resolving_beans()
      throws Exception {
    var cleaned = new AtomicInteger();
    var context = new GenericApplicationContext();
    var config =
        new FileChannelConfig(
            new SessionRegistry(),
            mock(ProjectRoots.class),
            context.getDefaultListableBeanFactory().getBeanProvider(SessionCloseListener.class),
            TestProjectMembers.allowed(),
            mock(WebSocketKeepAlive.class));
    context.registerBean("fileChannel", FileChannelHandler.class, config::fileChannelHandler);
    // This listener depends on the handler, as DefinitionResolver does in production. Snapshot
    // resolution must happen after that construction cycle is complete, before context shutdown.
    context.registerBean(
        "closeListener",
        SessionCloseListener.class,
        () -> {
          assertNotNull(context.getBean(FileChannelHandler.class));
          return session -> {
            assertEquals("shutdown-test", session);
            cleaned.incrementAndGet();
          };
        });
    context.registerBean(
        "listenerBinding",
        SmartInitializingSingleton.class,
        config::fileChannelCloseListenerBinding);
    context.refresh();
    var handler = context.getBean(FileChannelHandler.class);
    var socket = mock(WebSocketSession.class);
    when(socket.getUri())
        .thenReturn(URI.create("ws://example.invalid/v1/files?session=shutdown-test"));
    when(socket.getAttributes()).thenReturn(new ConcurrentHashMap<>());
    when(socket.isOpen()).thenReturn(true);
    handler.afterConnectionEstablished(socket);
    context.close();
    handler.afterConnectionClosed(socket, CloseStatus.GOING_AWAY);
    assertEquals(1, cleaned.get());
    handler.afterConnectionClosed(socket, CloseStatus.GOING_AWAY);
    assertEquals(1, cleaned.get(), "a repeated close does not notify twice");
  }
}
