package com.dulleobang.common.ratelimit;

import com.dulleobang.common.contract.ApiInventory;
import com.dulleobang.common.exception.*;
import com.dulleobang.common.ratelimit.service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestRateFilter extends OncePerRequestFilter {
  private final ApiInventory inventory;
  private final RateLimitService limits;
  private final ProblemWriter problems;
  private final boolean enabled;

  public RequestRateFilter(
      ApiInventory inventory, RateLimitService limits, ProblemWriter problems, boolean enabled) {
    this.inventory = inventory;
    this.limits = limits;
    this.problems = problems;
    this.enabled = enabled;
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    var route = inventory.find(request.getMethod(), request.getRequestURI());
    if (enabled && route != null && route.path().startsWith("/api/") && !inventory.isPublic(route))
      try {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        limits.checkAll(
            RateBuckets.privateRequest(
                auth.getName(), route.operationId(), request.getRequestURI()));
      } catch (ApiException ex) {
        problems.write(request, response, ex.status(), ex.code());
        return;
      } catch (org.springframework.dao.DataAccessException ex) {
        problems.write(request, response, 503, "DATABASE_UNAVAILABLE");
        return;
      }
    chain.doFilter(request, response);
  }
}
