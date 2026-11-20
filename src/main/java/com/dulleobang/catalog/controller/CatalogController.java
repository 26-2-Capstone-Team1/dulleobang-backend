package com.dulleobang.catalog.controller;

import com.dulleobang.catalog.service.CatalogService;
import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.contract.HttpResponses;
import com.dulleobang.common.contract.JsonInput;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogController {
  private final CatalogService catalog;
  private final JsonInput input;
  private final ContractValidator contracts;

  public CatalogController(CatalogService catalog, JsonInput input, ContractValidator contracts) {
    this.catalog = catalog;
    this.input = input;
    this.contracts = contracts;
  }

  @GetMapping({"/api/v1/catalog/furniture", "/api/catalog/furniture"})
  public ResponseEntity<String> list(
      Principal actor,
      HttpServletRequest request,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    var page =
        catalog.getCatalogFurniture(
            UUID.fromString(actor.getName()), request.getRequestURI(), category, q, limit, cursor);
    return response("CatalogPage", page, mobile(request));
  }

  @GetMapping({"/api/v1/catalog/furniture/{catalogId}", "/api/catalog/furniture/{catalogId}"})
  public ResponseEntity<String> get(HttpServletRequest request, @PathVariable String catalogId) {
    return response("CatalogItemDetail", catalog.getCatalogItem(catalogId), mobile(request));
  }

  @PostMapping({"/api/v1/furniture/from-catalog", "/api/furniture/from-catalog"})
  public ResponseEntity<String> create(
      Principal actor,
      HttpServletRequest request,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    boolean mobile = mobile(request);
    var json =
        mobile
            ? input.readMobile(body, type, "CatalogFurnitureCreate")
            : input.read(body, type, "CatalogFurnitureCreate");
    var result =
        catalog.createFromCatalog(
            UUID.fromString(actor.getName()), request.getRequestURI(), key, json, mobile);
    contracts.validateResponse(mobile ? "MobileFurniture" : "Furniture", result.body(), mobile);
    return HttpResponses.stored(result);
  }

  private ResponseEntity<String> response(String schema, Object value, boolean mobile) {
    var body = StrictJson.stringify(value);
    contracts.validateResponse(schema, body, mobile);
    return ResponseEntity.ok().header("Content-Type", "application/json").body(body);
  }

  private static boolean mobile(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/v1/");
  }
}
