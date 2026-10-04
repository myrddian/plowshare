package io.aeyer.plowshare.server.access;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** The legacy HTTP surface shares the socket policy; multipart bytes are gated before ingestion. */
@Component
@ControllerAdvice(basePackages = "io.aeyer.plowshare.server.api")
public class ProjectHttpAuthorization extends RequestBodyAdviceAdapter
    implements WebMvcConfigurer, HandlerInterceptor {
  private final ProjectAuthorization authorization;
  private final ObjectMapper json;

  public ProjectHttpAuthorization(ProjectAuthorization authorization, ObjectMapper json) {
    this.authorization = authorization;
    this.json = json;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(this)
        .addPathPatterns("/v1/**")
        .excludePathPatterns("/v1/auth/**", "/v1/auth", "/v1/events", "/v1/files");
  }

  @Override
  public boolean supports(
      MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
    return true;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response,
      Object handler) {
    if (!(handler instanceof org.springframework.web.method.HandlerMethod method)
        || !method.getBeanType().getPackageName().equals("io.aeyer.plowshare.server.api"))
      return true;
    // JSON body requests are checked after binding, so a missing project is not mistaken for global
    // scope.
    if (method.hasMethodAnnotation(org.springframework.web.bind.annotation.PostMapping.class)
        || method.hasMethodAnnotation(org.springframework.web.bind.annotation.PutMapping.class)) {
      if (request.getContentType() != null
          && request.getContentType().startsWith("application/json")) return true;
    }
    require(request, Map.of());
    return true;
  }

  @Override
  public Object afterBodyRead(
      Object body,
      HttpInputMessage input,
      MethodParameter parameter,
      Type type,
      Class<? extends HttpMessageConverter<?>> converter) {
    var request =
        ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    require(request, json.convertValue(body, new TypeReference<Map<String, Object>>() {}));
    return body;
  }

  private void require(HttpServletRequest request, Map<String, Object> body) {
    Map<String, Object> payload = new LinkedHashMap<>(body);
    request
        .getParameterMap()
        .forEach(
            (name, values) -> {
              if (values.length > 0) payload.putIfAbsent(name, values[0]);
            });
    String pattern =
        String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
    Object attributes = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    Map<?, ?> path = attributes instanceof Map<?, ?> values ? values : Map.of();
    if (path.containsKey("name") && pattern.startsWith("/v1/projects/"))
      payload.put("project", path.get("name"));
    String resource =
        pattern.startsWith("/v1/conversations/")
            ? "conversation"
            : pattern.startsWith("/v1/memories/")
                ? "memory"
                : pattern.startsWith("/v1/proposals/")
                    ? "proposal"
                    : pattern.startsWith("/v1/jobs/")
                        ? "job"
                        : pattern.startsWith("/v1/orchestrations/")
                            ? "id"
                            : pattern.startsWith("/v1/board/topics/") ? "topic" : null;
    if (resource != null && path.containsKey("id")) payload.put(resource, path.get("id"));
    authorization.require(
        operation(request.getMethod(), pattern),
        payload,
        (String) request.getAttribute(AuthFilter.HANDLE_ATTRIBUTE));
  }

  static String operation(String method, String path) {
    boolean get = method.equals("GET");
    if (path.startsWith("/v1/projects")) return get ? "project.list" : "project.define";
    if (path.equals("/v1/conversations")) return get ? "conversation.list" : "conversation.open";
    if (path.startsWith("/v1/conversations/"))
      return "conversation." + path.substring(path.lastIndexOf('/') + 1);
    if (path.equals("/v1/entries/search")) return "conversation.search";
    if (path.startsWith("/v1/orchestrations/")) return "orchestration.record";
    if (path.equals("/v1/agents")) return get ? "agent.list" : "agent.define";
    if (path.startsWith("/v1/agents/")) return "agent.run";
    if (path.equals("/v1/curate")) return "agent.curate";
    if (path.equals("/v1/jobs")) return "job.list";
    if (path.startsWith("/v1/jobs/"))
      return get ? "job.status" : "job." + path.substring(path.lastIndexOf('/') + 1);
    if (path.equals("/v1/memories")) return "memory.write";
    if (path.startsWith("/v1/memories/"))
      return get && path.endsWith("/{id}")
          ? "memory.read"
          : "memory." + path.substring(path.lastIndexOf('/') + 1);
    if (path.equals("/v1/proposals")) return "proposal.list";
    if (path.startsWith("/v1/proposals/"))
      return "proposal." + path.substring(path.lastIndexOf('/') + 1);
    if (path.equals("/v1/images")) return "image.upload";
    if (path.equals("/v1/search")) return "web.search";
    if (path.equals("/v1/fetch")) return "web.fetch";
    if (path.equals("/v1/documents")) return get ? "document.list" : "information.upload";
    if (path.equals("/v1/documents/{id}")) return "document.detail";
    if (path.equals("/v1/documents/chunks/{id}")) return "document.chunk";
    if (path.startsWith("/v1/documents/"))
      return "document." + path.substring(path.lastIndexOf('/') + 1);
    if (path.startsWith("/v1/search/providers"))
      return get ? "provider.list" : "provider.deregister";
    if (path.equals("/v1/board/topics/{id}/topup")) return "board.topup";
    if (path.equals("/v1/buffers/purge")) return "buffer.purge";
    if (path.equals("/v1/retention/sweep")) return "retention.sweep";
    // New HTTP mutations remain restricted until their operation and scope are declared.
    return "admin.http";
  }
}
