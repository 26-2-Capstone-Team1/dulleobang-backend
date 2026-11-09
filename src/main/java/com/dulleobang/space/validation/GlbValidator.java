package com.dulleobang.space.validation;

import static com.dulleobang.space.validation.GlbContainerReader.*;

import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class GlbValidator {
  public Set<String> validate(byte[] raw, boolean collision, JsonNode box) {
    try {
      var file = GlbContainerReader.read(raw, collision);
      var doc = file.json();
      var accessors = new GlbAccessorReader(file);
      require(doc.path("meshes").isArray() && !doc.path("meshes").isEmpty());
      long triangles = 0;
      Set<Integer> checked = new HashSet<>();
      for (var mesh : doc.path("meshes")) {
        require(mesh.path("primitives").isArray() && !mesh.path("primitives").isEmpty());
        for (var primitive : mesh.path("primitives")) {
          require(
              (!primitive.has("mode") || integer(primitive.get("mode")) == 4)
                  && (!primitive.has("targets") || primitive.path("targets").isEmpty())
                  && primitive.path("attributes").isObject());
          for (var attribute : primitive.path("attributes")) index(attribute, accessors.accessors);
          if (primitive.has("material")) index(primitive.get("material"), doc.path("materials"));
          var position = accessors.get(primitive.path("attributes").path("POSITION"));
          int id = integer(primitive.path("attributes").path("POSITION"));
          if (checked.add(id)) accessors.positionBounds(position);
          int count = integer(position.path("count"));
          if (primitive.has("indices")) {
            var indices = accessors.get(primitive.get("indices"));
            accessors.indices(indices, count);
            count = integer(indices.path("count"));
          }
          require(count % 3 == 0);
          triangles += count / 3;
        }
      }
      require(!collision || triangles <= 200000);
      GlbTextureValidator.validate(file, accessors);
      return new GlbSceneValidator(doc, accessors, collision, box).validate();
    } catch (ApiException ex) {
      if (ex.status() == 400) throw new ApiException(422, "INVALID_ARTIFACT");
      throw ex;
    } catch (Exception ex) {
      throw new ApiException(422, "INVALID_ARTIFACT");
    }
  }
}
