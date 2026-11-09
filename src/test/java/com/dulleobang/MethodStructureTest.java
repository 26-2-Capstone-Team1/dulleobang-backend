package com.dulleobang;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.MethodTree;
import com.sun.source.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class MethodStructureTest {
  private record Method(String file, String name, long lines) {}

  @Test
  void keepsNamesConcise() throws Exception {
    assertThat(methods()).filteredOn(m -> m.name().length() > 20).isEmpty();
  }

  @Test
  void keepsMethodsFocused() throws Exception {
    assertThat(methods()).filteredOn(m -> m.lines() > 60).isEmpty();
  }

  private List<Method> methods() throws Exception {
    Path root = Path.of("src/main/java");
    var result = new ArrayList<Method>();
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).as("Run structural checks with JDK 21").isNotNull();
    try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        var paths = Files.walk(root)) {
      var files = paths.filter(p -> p.toString().endsWith(".java")).sorted().toList();
      assertThat(files).isNotEmpty();
      var task =
          (JavacTask)
              compiler.getTask(
                  null,
                  manager,
                  null,
                  List.of("-proc:none"),
                  null,
                  manager.getJavaFileObjectsFromPaths(files));
      var trees = Trees.instance(task);
      for (var unit : task.parse()) {
        var positions = trees.getSourcePositions();
        new TreeScanner<Void, Void>() {
          public Void visitMethod(MethodTree method, Void unused) {
            if (method.getBody() != null && !method.getName().contentEquals("<init>")) {
              long first =
                  unit.getLineMap().getLineNumber(positions.getStartPosition(unit, method));
              long last = unit.getLineMap().getLineNumber(positions.getEndPosition(unit, method));
              result.add(
                  new Method(
                      unit.getSourceFile().getName(),
                      method.getName().toString(),
                      last - first + 1));
            }
            return super.visitMethod(method, unused);
          }
        }.scan(unit, null);
      }
    }
    return result;
  }
}
