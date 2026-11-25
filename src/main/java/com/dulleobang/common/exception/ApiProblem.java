package com.dulleobang.common.exception;

import java.util.List;

public record ApiProblem(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    String code,
    String requestId,
    List<Object> errors) {}
