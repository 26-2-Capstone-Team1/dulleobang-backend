package com.dulleobang.space.validation;

import static com.dulleobang.space.validation.GlbContainerReader.*;
import static com.dulleobang.space.validation.GlbNodeTransform.*;

import com.dulleobang.capture.validation.CaptureCoordinates;
import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Checks scene reachability and mesh membership; delegates transforms and bounds. */
final class GlbSceneValidator {
  private final JsonNode doc;
  private final boolean collision;
  private final GlbBoundsValidator bounds;

  GlbSceneValidator(
      JsonNode doc, GlbAccessorReader accessors, boolean collision, JsonNode expected) {
    this.doc = doc;
    this.collision = collision;
    this.bounds = new GlbBoundsValidator(accessors, expected);
  }

  Set<String> validate() {
    checkNames();
    var meshes = walk();
    bounds.validate();
    require(meshes.count() > 0);
    return meshes.names();
  }

  private void checkNames() {
    var names = new HashSet<String>();
    for (var node : doc.path("nodes"))
      if (node.has("name") && !node.path("name").asText().isEmpty())
        require(node.path("name").isTextual() && names.add(node.path("name").asText()));
  }

  private Deque<Visit> roots() {
    var nodes = doc.path("nodes");
    var scenes = doc.path("scenes");
    int scene = doc.has("scene") ? index(doc.get("scene"), scenes) : 0;
    require(scene < scenes.size());
    var roots = scenes.get(scene).path("nodes");
    require(roots.isArray() && !roots.isEmpty());
    var pending = new ArrayDeque<Visit>();
    for (var root : roots) {
      int id = index(root, nodes);
      require(identity(transform(nodes.get(id))));
      pending.push(new Visit(id, CaptureCoordinates.identity()));
    }
    return pending;
  }

  private SceneMeshes walk() {
    var pending = roots();
    var visited = new HashSet<Integer>();
    var names = new HashSet<String>();
    int count = 0;
    while (!pending.isEmpty()) {
      var item = pending.pop();
      require(visited.add(item.index()));
      count += visit(item, names, pending);
    }
    return new SceneMeshes(names, count);
  }

  private int visit(Visit item, Set<String> names, Deque<Visit> pending) {
    var nodes = doc.path("nodes");
    var node = nodes.get(item.index());
    require(!node.has("skin"));
    double[] local = transform(node);
    if (collision && !identity(local)) throw new ApiException(422, "COLLISION_TRANSFORM_NOT_BAKED");
    double[] matrix = CaptureCoordinates.multiply(item.parent(), local);
    int count = 0;
    if (node.has("mesh")) {
      var meshes = doc.path("meshes");
      var mesh = meshes.get(index(node.get("mesh"), meshes));
      if (node.has("name")) names.add(node.path("name").asText());
      bounds.include(mesh, matrix);
      count = 1;
    }
    for (var child : node.path("children")) pending.push(new Visit(index(child, nodes), matrix));
    return count;
  }

  private record Visit(int index, double[] parent) {}

  private record SceneMeshes(Set<String> names, int count) {}
}
