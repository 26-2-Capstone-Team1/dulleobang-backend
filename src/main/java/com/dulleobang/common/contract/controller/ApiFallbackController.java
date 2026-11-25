package com.dulleobang.common.contract.controller;

import com.dulleobang.common.contract.ApiInventory;
import com.dulleobang.common.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiFallbackController {
  private final ApiInventory inventory;

  public ApiFallbackController(ApiInventory inventory) {
    this.inventory = inventory;
  }

  @RequestMapping({"/api/**", "/storage/**"})
  public void fallback(HttpServletRequest request) {
    String path = request.getRequestURI();
    if (inventory.find(request.getMethod(), path) != null)
      throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE");
    throw new ApiException(
        inventory.hasPath(path) ? 405 : 404,
        inventory.hasPath(path) ? "METHOD_NOT_ALLOWED" : "RESOURCE_NOT_FOUND");
  }
}
