package com.dulleobang.report.controller;

import com.dulleobang.common.contract.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.report.service.ReportService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReportController {
  private final ReportService reports;
  private final JsonInput input;
  private final ContractValidator contracts;
  private static final Map<String, String> REASONS =
      Map.of(
          "허위 매물이에요",
          "FAKE_LISTING",
          "실제 공간과 사진·3D 가 달라요",
          "SPACE_MISMATCH",
          "가격 정보가 달라요",
          "WRONG_PRICE",
          "이미 거래된 매물이에요",
          "ALREADY_CONTRACTED",
          "기타",
          "OTHER");

  public ReportController(ReportService reports, JsonInput input, ContractValidator contracts) {
    this.reports = reports;
    this.input = input;
    this.contracts = contracts;
  }

  @PostMapping({"/api/listings/{id}/reports", "/api/v1/listings/{id}/reports"})
  public ResponseEntity<String> create(
      Principal actor,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    boolean mobile = !request.getRequestURI().startsWith("/api/v1/");
    var json =
        (ObjectNode)
            (mobile
                ? input.readMobile(body, type, "MobilecreateListingReport")
                : input.read(body, type, "ListingReportCreate"));
    if (mobile)
      json.put(
          "reason",
          REASONS.getOrDefault(json.path("reason").asText(), json.path("reason").asText()));
    if (json.path("detail").isNull()) json.remove("detail");
    contracts.validate("ListingReportCreate", StrictJson.stringify(json));
    return HttpResponses.stored(
        reports.create(UUID.fromString(actor.getName()), id, request.getRequestURI(), key, json));
  }
}
