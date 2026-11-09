package com.dulleobang.sam3d.service;

import com.dulleobang.sam3d.dto.Sam3dModel;

public interface Sam3dGateway {
  Sam3dModel fetch(String job);
}
