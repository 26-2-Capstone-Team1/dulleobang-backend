package com.dulleobang.sam3d.service;

import com.dulleobang.sam3d.dto.Sam3dJob;
import com.dulleobang.sam3d.dto.Sam3dModel;
import com.fasterxml.jackson.databind.JsonNode;

public interface Sam3dGateway {
  Sam3dJob generate(JsonNode input);

  Sam3dJob status(String id);

  Sam3dModel fetch(String job);
}
