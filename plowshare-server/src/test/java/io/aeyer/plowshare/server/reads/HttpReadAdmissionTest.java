package io.aeyer.plowshare.server.reads;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.auth.AuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class HttpReadAdmissionTest {
  static class Endpoint {
    public String read() {
      return "ok";
    }
  }

  @Test
  void httpAndSocketWorkUseTheSamePrincipalBudgetAndCompletionReleasesIt() throws Exception {
    var admission = new BoundedReadAdmission(new ReadCapacityProperties(2, 1, 2, 1, 8, 2));
    var http = new HttpReadAdmission(admission);
    var request = new MockHttpServletRequest("GET", "/v1/fixture");
    request.setAttribute(AuthFilter.HANDLE_ATTRIBUTE, "alice");
    var response = new MockHttpServletResponse();
    var handler = new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("read"));
    assertTrue(http.preHandle(request, response, handler));
    assertThrows(
        ReadUnavailableException.class,
        () -> admission.admit("alice", ReadAdmission.Work.INTERACTIVE));
    assertTrue(
        http.preHandle(request, response, handler), "Async redispatch must not reserve twice");
    http.afterCompletion(request, response, handler, null);
    var next = admission.admit("alice", ReadAdmission.Work.INTERACTIVE);
    next.close();
    next.close();
    assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
  }
}
