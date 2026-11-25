package com.dulleobang.upload.controller;

import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.upload.dto.UploadedFile;
import java.io.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@Component
public class MultipartInput {
  public List<UploadedFile> files(MultipartHttpServletRequest request, String part, int maximum) {
    if (!request.getMultiFileMap().keySet().equals(Set.of(part)))
      throw new ApiException(422, "INVALID_UPLOAD_ROLE");
    var input = request.getFiles(part);
    if (input.isEmpty() || input.size() > maximum)
      throw new ApiException(422, "INVALID_CAPTURE_FILES");
    var result = new ArrayList<UploadedFile>();
    for (var file : input) {
      if (file.getSize() < 1 || file.getSize() > 20L * 1024 * 1024)
        throw new ApiException(413, "UPLOAD_LIMIT_EXCEEDED");
      if (!Set.of("image/jpeg", "image/png")
          .contains(Objects.requireNonNullElse(file.getContentType(), "")))
        throw new ApiException(422, "INVALID_UPLOAD_ROLE");
      try (var stream = file.getInputStream()) {
        String hash = Hashes.sha256(stream);
        result.add(
            new UploadedFile(
                file.getOriginalFilename() == null ? "image.jpg" : file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                hash,
                file::getInputStream));
      } catch (Exception ex) {
        throw new ApiException(503, "STORAGE_UNAVAILABLE");
      }
    }
    return result;
  }

  public Map<String, String> fields(MultipartHttpServletRequest request, Set<String> allowed) {
    if (!allowed.containsAll(request.getParameterMap().keySet()))
      throw new ApiException(422, "INVALID_PARAMETER");
    var result = new LinkedHashMap<String, String>();
    request
        .getParameterMap()
        .forEach(
            (key, value) -> {
              if (value.length != 1) throw new ApiException(422, "DUPLICATE_FIELD");
              if (value[0].length() > 1048576) throw new ApiException(413, "BODY_TOO_LARGE");
              result.put(key, value[0]);
            });
    return result;
  }
}
