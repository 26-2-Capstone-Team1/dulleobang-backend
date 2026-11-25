package com.dulleobang.sam3d.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record Sam3dModel(byte[] bytes, JsonNode dimensions, JsonNode bounds) {
  public Sam3dModel {
    bytes = bytes.clone();
    dimensions = dimensions.deepCopy();
    bounds = bounds.deepCopy();
  }

  public byte[] bytes() {
    return bytes.clone();
  }
}
