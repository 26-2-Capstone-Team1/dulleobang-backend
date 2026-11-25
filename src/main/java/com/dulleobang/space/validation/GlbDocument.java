package com.dulleobang.space.validation;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;

record GlbDocument(JsonNode json, ByteBuffer binary) {}
