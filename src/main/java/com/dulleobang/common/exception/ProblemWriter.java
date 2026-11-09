package com.dulleobang.common.exception;

import com.dulleobang.common.json.StrictJson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ProblemWriter {
  public ApiProblem body(HttpServletRequest request, int status, String code) {
    String id = (String) request.getAttribute("requestId");
    if (id == null) id = UUID.randomUUID().toString();
    return new ApiProblem(
        "urn:dulleobang:problem:" + code.toLowerCase(java.util.Locale.ROOT),
        HttpStatus.valueOf(status).getReasonPhrase(),
        status,
        code,
        request.getRequestURI(),
        code,
        id,
        List.of());
  }

  public void write(
      HttpServletRequest request, HttpServletResponse response, int status, String code)
      throws IOException {
    ApiProblem problem = body(request, status, code);
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Request-Id", problem.requestId());
    if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
    if (status == 429 || status == 503)
      response.setHeader(
          "Retry-After",
          status == 429 ? Long.toString(60 - java.time.Instant.now().getEpochSecond() % 60) : "1");
    response.getWriter().write(StrictJson.stringify(problem));
  }
}
