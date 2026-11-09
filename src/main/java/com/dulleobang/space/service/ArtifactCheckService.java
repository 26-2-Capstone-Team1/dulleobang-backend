package com.dulleobang.space.service;

import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.infrastructure.storage.AssetStorage;
import com.dulleobang.job.domain.JobRecord;
import com.dulleobang.job.repository.JobArtifactRepository;
import com.dulleobang.space.validation.*;
import com.dulleobang.upload.repository.UploadRepository;
import com.dulleobang.upload.service.SignedUrlService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ArtifactCheckService {
  private final JobArtifactRepository jobs;
  private final UploadRepository uploads;
  private final AssetStorage storage;
  private final SignedUrlService metadata;
  private final GlbValidator glb;
  private final ContractValidator contracts;
  private final BundleGeometryValidator geometry;

  public ArtifactCheckService(
      JobArtifactRepository jobs,
      UploadRepository uploads,
      AssetStorage storage,
      SignedUrlService metadata,
      GlbValidator glb,
      ContractValidator contracts,
      BundleGeometryValidator geometry) {
    this.jobs = jobs;
    this.uploads = uploads;
    this.storage = storage;
    this.metadata = metadata;
    this.glb = glb;
    this.contracts = contracts;
    this.geometry = geometry;
  }

  public Set<String> asset(JobRecord job, JsonNode reference, String role, JsonNode expectedBox) {
    UUID id = UUID.fromString(reference.path("fileId").asText());
    var file = uploads.asset(id, true).orElseThrow(() -> new ApiException(422, "INVALID_ARTIFACT"));
    if (!jobs.belongs(id, job, role)) throw new ApiException(422, "INVALID_ARTIFACT");
    for (var entry : metadata.asset(file).entrySet())
      if (!StrictJson.parse(StrictJson.stringify(entry.getValue()))
          .equals(reference.path(entry.getKey()))) throw new ApiException(422, "INVALID_ARTIFACT");
    if (!file.stored()) throw new ApiException(409, "UPLOAD_INCOMPLETE");
    byte[] raw = storage.read(file.sha256(), file.byteSize());
    Set<String> nodes = Set.of();
    if (role.equals("SCENE")) {
      if (file.format().equals("POINT_CLOUD_PLY_V1")) PointCloudValidator.validate(raw);
      else PlyValidator.validate(raw);
    } else if (role.equals("GEOMETRY")) {
      var value = StrictJson.parse(raw);
      contracts.validate("RoomGeometry", StrictJson.stringify(value));
    } else nodes = glb.validate(raw, role.equals("COLLISION"), expectedBox);
    uploads.verified(id, null);
    return nodes;
  }

  public Set<String> bundle(JobRecord job, JsonNode bundle) {
    asset(job, bundle.path("sceneAsset"), "SCENE", null);
    asset(job, bundle.path("geometryAsset"), "GEOMETRY", null);
    var file =
        uploads
            .asset(UUID.fromString(bundle.path("geometryAsset").path("fileId").asText()), false)
            .orElseThrow();
    var actual = StrictJson.parse(storage.read(file.sha256(), file.byteSize()));
    if (!StrictJson.canonical(StrictJson.stringify(actual))
        .equals(StrictJson.canonical(StrictJson.stringify(bundle.path("geometry")))))
      throw new ApiException(422, "GEOMETRY_MISMATCH");
    var nodes = asset(job, bundle.path("collisionAsset"), "COLLISION", null);
    geometry.validate(bundle, nodes);
    meshSource(job, bundle);
    return nodes;
  }

  private void meshSource(JobRecord job, JsonNode bundle) {
    if (!bundle.has("meshSource")) return;
    var source = bundle.path("meshSource");
    asset(job, source.path("roomAsset"), "SOURCE_MESH", null);
    asset(job, source.path("roomCollisionAsset"), "SOURCE_MESH", null);
    var expected = new HashSet<String>();
    for (var object : bundle.path("objects"))
      if (object.path("removable").asBoolean()) expected.add(object.path("objectId").asText());
    var seen = new HashSet<String>();
    for (var object : source.path("objectAssets")) {
      String id = object.path("objectId").asText();
      if (!seen.add(id) || !expected.contains(id))
        throw new ApiException(422, "INVALID_MESH_SOURCE");
      asset(job, object.path("asset"), "SOURCE_MESH", null);
    }
    if (!seen.equals(expected)) throw new ApiException(422, "INVALID_MESH_SOURCE");
  }
}
