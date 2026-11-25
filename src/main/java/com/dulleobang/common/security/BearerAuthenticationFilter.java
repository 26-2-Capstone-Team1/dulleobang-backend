package com.dulleobang.common.security;

import com.dulleobang.common.contract.ApiInventory;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.exception.ProblemWriter;
import com.dulleobang.common.security.repository.SessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerAuthenticationFilter extends OncePerRequestFilter {
  private final ApiInventory inventory;
  private final TokenVerifier tokens;
  private final SessionRepository sessions;
  private final Clock clock;
  private final ProblemWriter problems;

  public BearerAuthenticationFilter(
      ApiInventory inventory,
      TokenVerifier tokens,
      SessionRepository sessions,
      Clock clock,
      ProblemWriter problems) {
    this.inventory = inventory;
    this.tokens = tokens;
    this.sessions = sessions;
    this.clock = clock;
    this.problems = problems;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    var route = inventory.find(request.getMethod(), request.getRequestURI());
    if (route == null
        || !request.getRequestURI().startsWith("/api/")
        || inventory.isPublic(route)) {
      chain.doFilter(request, response);
      return;
    }
    try {
      String authorization = request.getHeader("Authorization");
      if (authorization == null || !authorization.startsWith("Bearer "))
        throw new ApiException(401, "UNAUTHORIZED");
      boolean worker = request.getRequestURI().startsWith("/api/v1/internal/");
      var claims = tokens.verify(authorization.substring(7), worker ? "worker" : "user");
      if (!worker
          && !sessions.active(claims.getStringClaim("sid"), claims.getSubject(), clock.instant()))
        throw new ApiException(401, "UNAUTHORIZED");
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(claims.getSubject(), null, List.of()));
    } catch (ApiException ex) {
      problems.write(request, response, ex.status(), ex.code());
      return;
    } catch (java.text.ParseException ex) {
      problems.write(request, response, 401, "UNAUTHORIZED");
      return;
    } catch (org.springframework.dao.DataAccessException ex) {
      problems.write(request, response, 503, "DATABASE_UNAVAILABLE");
      return;
    }
    chain.doFilter(request, response);
  }
}
