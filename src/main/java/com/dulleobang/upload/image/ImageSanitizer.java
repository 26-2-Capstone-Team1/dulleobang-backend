package com.dulleobang.upload.image;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.upload.validation.ImageValidator;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.*;
import org.springframework.stereotype.Component;

@Component
public class ImageSanitizer {
  private final ImageValidator images;

  public ImageSanitizer(ImageValidator images) {
    this.images = images;
  }

  public byte[] sanitize(byte[] raw, String mime) {
    var image = ImageTransforms.orient(images.decode(raw, mime), ExifOrientation.read(raw));
    return encode(ImageTransforms.resize(image));
  }

  private byte[] encode(BufferedImage clean) {
    var writer = ImageIO.getImageWritersByFormatName("JPEG").next();
    try (var out = new ByteArrayOutputStream();
        var stream = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(stream);
      var params = writer.getDefaultWriteParam();
      params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      params.setCompressionQuality(.9f);
      writer.write(null, new IIOImage(clean, null, null), params);
      stream.flush();
      return out.toByteArray();
    } catch (IOException ex) {
      throw new ApiException(422, "INVALID_IMAGE");
    } finally {
      writer.dispose();
    }
  }
}
