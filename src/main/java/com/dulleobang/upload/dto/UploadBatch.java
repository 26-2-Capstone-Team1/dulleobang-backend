package com.dulleobang.upload.dto;

import com.dulleobang.upload.domain.*;
import java.util.List;

public record UploadBatch(UploadSession session, List<AssetFile> files) {
  public UploadBatch {
    files = List.copyOf(files);
  }
}
