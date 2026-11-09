package com.dulleobang.scrap.controller;

import com.dulleobang.common.contract.QueryInput;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.scrap.service.ScrapService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class ScrapController {
  private final ScrapService scraps;
  private final QueryInput parameters;

  public ScrapController(ScrapService scraps, QueryInput parameters) {
    this.scraps = scraps;
    this.parameters = parameters;
  }

  @PutMapping("/api/v1/listings/{id}/scrap")
  public ResponseEntity<Void> put(Principal actor, @PathVariable UUID id) {
    scraps.save(UUID.fromString(actor.getName()), id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/listings/{id}/scrap")
  public ResponseEntity<Void> post(Principal actor, @PathVariable UUID id) {
    return put(actor, id);
  }

  @DeleteMapping({"/api/listings/{id}/scrap", "/api/v1/listings/{id}/scrap"})
  public ResponseEntity<Void> delete(Principal actor, @PathVariable UUID id) {
    scraps.delete(UUID.fromString(actor.getName()), id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping({"/api/users/me/scraps", "/api/v1/users/me/scraps"})
  public ResponseEntity<String> list(
      Principal actor,
      HttpServletRequest request,
      @RequestParam MultiValueMap<String, String> raw) {
    boolean mobile = !request.getRequestURI().startsWith("/api/v1/");
    return ResponseEntity.ok()
        .header("Content-Type", "application/json")
        .body(
            StrictJson.stringify(
                scraps.list(
                    UUID.fromString(actor.getName()),
                    request.getRequestURI(),
                    parameters.read("getScraps", raw),
                    mobile,
                    mobile && !raw.containsKey("limit") && !raw.containsKey("cursor"))));
  }
}
