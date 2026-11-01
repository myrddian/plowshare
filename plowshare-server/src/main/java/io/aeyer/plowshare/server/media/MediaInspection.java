package io.aeyer.plowshare.server.media;

/**
 * Validates admitted media bytes without storing them or acquiring disclosure authority.
 *
 * <p>Supports single still PNG, JPEG and WebP images. This capability does not extract document
 * text, expand embedded files, fetch paths/URLs, resize images, or estimate model context cost.
 */
public interface MediaInspection {
  /**
   * Inspects a bounded, private snapshot of these bytes and returns server-derived properties.
   * Filename and caller MIME claims cannot influence detection. The caller must bound any upload
   * before allocating this array and must not mutate it during the call. No bytes are retained
   * after return; the descriptor neither identifies an owner nor authorizes storage/retrieval.
   *
   * @throws MediaInspectionException for empty, oversized, unsupported, invalid or excessive
   *     images, or when inspection capacity is busy (retryable, with no effects)
   */
  ImageDescriptor inspectImage(byte[] bytes);
}
