package com.dulleobang.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestContextFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String requestId = UUID.randomUUID().toString();
    request.setAttribute("requestId", requestId);
    response.setHeader("X-Request-Id", requestId);
    response.setHeader("Cache-Control", "no-store");
    chain.doFilter(request, response);
  }
}
