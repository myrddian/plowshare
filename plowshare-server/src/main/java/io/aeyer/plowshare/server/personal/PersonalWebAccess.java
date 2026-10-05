package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.server.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Type;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Legacy HTTP and WebSocket controls share the same Personal ownership boundary. */
@ControllerAdvice
public class PersonalWebAccess extends RequestBodyAdviceAdapter implements WebMvcConfigurer {
  private final PersonalAccess access;

  public PersonalWebAccess(PersonalAccess access) {
    this.access = access;
  }

  private static String handle(HttpServletRequest request) {
    return (String) request.getAttribute(AuthFilter.HANDLE_ATTRIBUTE);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new org.springframework.web.servlet.HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request,
                  jakarta.servlet.http.HttpServletResponse response,
                  Object handler) {
                String handle = handle(request);
                access.project(request.getParameter("project"), handle);
                access.conversation(request.getParameter("conversation"), handle);
                Object variables =
                    request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                if (variables instanceof Map<?, ?> path) {
                  access.check(PersonalScopeDecoder.decode(path), handle);
                  if (request.getServletPath().startsWith("/v1/conversations/"))
                    access.conversation(PersonalScopeDecoder.identity(path.get("id")), handle);
                }
                return true;
              }
            })
        .addPathPatterns("/v1/**");
  }

  @Override
  public boolean supports(
      MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
    return true;
  }

  @Override
  public Object afterBodyRead(
      Object body,
      HttpInputMessage message,
      MethodParameter parameter,
      Type type,
      Class<? extends HttpMessageConverter<?>> converter) {
    if (!(RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes)) return body;
    String handle = handle(attributes.getRequest());
    if (body instanceof Map<?, ?> payload)
      access.check(PersonalScopeDecoder.decode(payload), handle);
    else if (body.getClass().isRecord()) {
      for (var component : body.getClass().getRecordComponents()) {
        if (!component.getName().equals("project") && !component.getName().equals("conversation"))
          continue;
        try {
          var accessor = component.getAccessor();
          accessor.trySetAccessible();
          Object value = accessor.invoke(body);
          if (component.getName().equals("project"))
            access.project(PersonalScopeDecoder.identity(value), handle);
          else access.conversation(PersonalScopeDecoder.identity(value), handle);
        } catch (ReflectiveOperationException failure) {
          throw new IllegalStateException("Could not check Personal request scope", failure);
        }
      }
    }
    return body;
  }
}
