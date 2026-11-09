package com.dulleobang.common.contract;

import com.dulleobang.common.exception.ApiException;
import java.util.*;

final class QueryValueParser {
  private QueryValueParser() {}

  static Object parse(String type, List<String> values) {
    if (!type.equals("array") && values.size() != 1)
      throw new ApiException(400, "DUPLICATE_QUERY_PARAMETER");
    String text = values.get(0);
    return switch (type) {
      case "integer" -> integer(text);
      case "number" -> number(text);
      case "boolean" -> bool(text);
      case "array" -> new ArrayList<>(new TreeSet<>(values));
      default -> text;
    };
  }

  private static long integer(String text) {
    if (!text.matches("-?[0-9]+")) throw new IllegalArgumentException();
    return Long.parseLong(text);
  }

  private static double number(String text) {
    double number = Double.parseDouble(text);
    if (!Double.isFinite(number)) throw new IllegalArgumentException();
    return number;
  }

  private static boolean bool(String text) {
    if (!Set.of("true", "false").contains(text)) throw new IllegalArgumentException();
    return Boolean.parseBoolean(text);
  }
}
