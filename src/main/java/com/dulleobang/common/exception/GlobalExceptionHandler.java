package com.dulleobang.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private final ProblemWriter writer;

  public GlobalExceptionHandler(ProblemWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ApiProblem> domain(ApiException ex, HttpServletRequest request) {
    return response(request, ex.status(), ex.code());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiProblem> validation(HttpServletRequest request) {
    return response(request, 422, "VALIDATION_FAILED");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiProblem> json(HttpServletRequest request) {
    return response(request, 400, "MALFORMED_JSON");
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiProblem> missing(HttpServletRequest request) {
    return response(request, 404, "RESOURCE_NOT_FOUND");
  }

  @ExceptionHandler({
    org.springframework.dao.TransientDataAccessException.class,
    org.springframework.dao.DataAccessResourceFailureException.class
  })
  public ResponseEntity<ApiProblem> storage(HttpServletRequest request) {
    return response(request, 503, "DATABASE_UNAVAILABLE");
  }

  @ExceptionHandler(
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiProblem> parameter(HttpServletRequest request) {
    return response(request, 400, "INVALID_QUERY_PARAMETER");
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiProblem> media(HttpServletRequest request) {
    return response(request, 415, "UNSUPPORTED_MEDIA_TYPE");
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ApiProblem> accept(HttpServletRequest request) {
    return response(request, 406, "NOT_ACCEPTABLE");
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiProblem> method(
      org.springframework.web.HttpRequestMethodNotSupportedException ex,
      HttpServletRequest request) {
    var result = response(request, 405, "METHOD_NOT_ALLOWED");
    var headers = new org.springframework.http.HttpHeaders();
    headers.putAll(result.getHeaders());
    if (ex.getSupportedHttpMethods() != null) headers.setAllow(ex.getSupportedHttpMethods());
    return new ResponseEntity<>(result.getBody(), headers, result.getStatusCode());
  }

  @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
  public ResponseEntity<ApiProblem> missingParameter(HttpServletRequest request) {
    return response(request, 400, "INVALID_QUERY_PARAMETER");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiProblem> unexpected(Exception ex, HttpServletRequest request) {
    return response(request, 500, "INTERNAL_ERROR");
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiProblem> multipartSize(HttpServletRequest request) {
    return response(request, 413, "BODY_TOO_LARGE");
  }

  @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
  public ResponseEntity<ApiProblem> multipart(HttpServletRequest request) {
    return response(request, 422, "INVALID_MULTIPART");
  }

  private ResponseEntity<ApiProblem> response(HttpServletRequest request, int status, String code) {
    ApiProblem body = writer.body(request, status, code);
    var builder =
        ResponseEntity.status(status)
            .header("Cache-Control", "no-store")
            .header("X-Request-Id", body.requestId())
            .header("Content-Type", "application/problem+json");
    if (status == 401) builder.header("WWW-Authenticate", "Bearer");
    if (status == 429 || status == 503)
      builder.header(
          "Retry-After",
          status == 429 ? Long.toString(60 - java.time.Instant.now().getEpochSecond() % 60) : "1");
    return builder.body(body);
  }
}
