package com.dulleobang.common.ratelimit;

import com.dulleobang.common.contract.ApiInventory;
import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.common.ratelimit.service.RateLimitService;
import java.lang.reflect.Type;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthRateAdvice extends RequestBodyAdviceAdapter {
  private final ApiInventory inventory;
  private final RateLimitService limits;
  private final boolean enabled;

  public AuthRateAdvice(
      ApiInventory inventory,
      RateLimitService limits,
      @Value("${dulleobang.rate.enabled:true}") boolean enabled) {
    this.inventory = inventory;
    this.limits = limits;
    this.enabled = enabled;
  }

  public boolean supports(
      MethodParameter method, Type type, Class<? extends HttpMessageConverter<?>> converter) {
    return type == byte[].class;
  }

  public Object afterBodyRead(
      Object body,
      HttpInputMessage message,
      MethodParameter method,
      Type type,
      Class<? extends HttpMessageConverter<?>> converter) {
    charge(body);
    return body;
  }

  public Object handleEmptyBody(
      Object body,
      HttpInputMessage message,
      MethodParameter method,
      Type type,
      Class<? extends HttpMessageConverter<?>> converter) {
    charge(body);
    return body;
  }

  private void charge(Object body) {
    if (!enabled) return;
    var request =
        ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    var route = inventory.find(request.getMethod(), request.getRequestURI());
    if (route == null || !inventory.isPublic(route)) return;
    var buckets = new LinkedHashMap<String, Integer>();
    buckets.put("auth-ip:" + request.getRemoteAddr(), 10);
    if (body instanceof byte[] bytes)
      try {
        var json = StrictJson.parse(bytes);
        if (json.path("email").isTextual()) {
          String email = json.path("email").asText().strip().toLowerCase(Locale.ROOT);
          buckets.put(
              "auth-account:"
                  + Hashes.sha256(email.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              5);
        }
      } catch (com.dulleobang.common.exception.ApiException ignored) {
      }
    limits.checkAll(buckets);
  }
}
