package io.aeyer.plowshare.server.archive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.files.FileReadWindow;

/** Storage boundary for metadata-only lossless file windows. */
public final class FileReadWindowCodec {
  private static final ObjectMapper JSON = new ObjectMapper();

  private FileReadWindowCodec() {}

  public static String write(FileReadWindow window) {
    if (window == null) return null;
    try {
      return JSON.writeValueAsString(window);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Could not encode the file read window", invalid);
    }
  }

  public static FileReadWindow read(String stored) {
    if (stored == null) return null;
    try {
      return JSON.readValue(stored, FileReadWindow.class);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Invalid retained file read window", invalid);
    }
  }
}
