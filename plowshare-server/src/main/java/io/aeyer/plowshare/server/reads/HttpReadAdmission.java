package io.aeyer.plowshare.server.reads;

import io.aeyer.plowshare.server.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Legacy HTTP reads share account/process budgets with sockets. Upgrade channels retain theirs. */
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
      request.setAttribute(
          LEASE,
          admission.admit(
              principal instanceof String value ? value : null, ReadAdmission.Work.INTERACTIVE));
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
