package io.aeyer.plowshare.server.reads;

import io.aeyer.plowshare.server.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Legacy HTTP reads share bounded account/process admission with sockets. Upgrade channels retain
 * theirs. Waiting owns no database connection and ends before the handler starts; its deadline
 * scope does not remain on a servlet thread across async dispatch. Per-SQL deadlines and physical
 * ownership after servlet timeout remain with their owning lifecycle; {@code afterCompletion} alone
 * does not prove an async worker has stopped. Admission does not time out whole handlers.
 */
@Component
public final class HttpReadAdmission implements WebMvcConfigurer, AsyncHandlerInterceptor {
  private static final String LEASE = HttpReadAdmission.class.getName();
  private final ReadAdmission admission;

  public HttpReadAdmission(ReadAdmission admission) {
    this.admission = admission;
  }

  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(this)
        .order(-100)
        .addPathPatterns("/v1/**")
        .excludePathPatterns("/v1/events", "/v1/files", "/v1/auth", "/v1/auth/**");
  }

  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod) || !request.getMethod().equals("GET")) return true;
    if (request.getAttribute(LEASE) == null) {
      Object principal = request.getAttribute(AuthFilter.HANDLE_ATTRIBUTE);
      ReadAdmission.Lease lease;
      try {
        lease = admission.awaitInteractive(principal instanceof String value ? value : null);
      } catch (ReadUnavailableException capacity) {
        throw new IncomingReadCapacityException(capacity);
      }
      request.setAttribute(LEASE, lease);
    }
    return true;
  }

  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
    Object lease = request.getAttribute(LEASE);
    request.removeAttribute(LEASE);
    if (lease instanceof ReadAdmission.Lease acquired) acquired.close();
  }
}
