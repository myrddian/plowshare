package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.auth.AuthFilter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Copies {@code AuthFilter}'s resolved handle onto the socket's attribute map, and admits every
 * upgrade regardless — the filter already decided whether this request gets this far.
 */
class HandleInterceptorTest {

  @Test
  void the_filters_handle_lands_on_the_socket_attributes() {
    MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/v1/events");
    servlet.setAttribute(AuthFilter.HANDLE_ATTRIBUTE, "enzo");
    Map<String, Object> attributes = new HashMap<>();
    assertTrue(
        new HandleInterceptor()
            .beforeHandshake(new ServletServerHttpRequest(servlet), null, null, attributes));
    assertEquals("enzo", attributes.get(EventChannelHandler.HANDLE));
  }

  @Test
  void no_handle_on_the_request_is_no_attribute_and_still_admits() {
    Map<String, Object> attributes = new HashMap<>();
    assertTrue(
        new HandleInterceptor()
            .beforeHandshake(
                new ServletServerHttpRequest(new MockHttpServletRequest()),
                null,
                null,
                attributes));
    assertFalse(attributes.containsKey(EventChannelHandler.HANDLE));
  }
}
