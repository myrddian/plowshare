package io.aeyer.plowshare.server.documents;

/**
 * This upload is not something this server can turn into text.
 *
 * <p><b>A statement about the document, never about the server being broken</b>, which is why it is
 * its own type and not an {@code IllegalArgumentException} dressed up. {@code ApiExceptionHandler}
 * answers it with {@code 415} — the request was well formed and what it carried is a format this
 * server does not read — and the message is the whole of what the sender is shown, so every one of
 * them names the document and says what to do instead.
 *
 * <p>Nothing here is retryable. That is the difference from {@code EmbeddingException}, whose
 * {@code 503} tells a caller to ask again later: an upload refused by this type will be refused
 * identically for ever, and telling somebody to retry it would be advice that cannot come true.
 */
public class UnreadableDocumentException extends RuntimeException {

  public UnreadableDocumentException(String message) {
    super(message);
  }
}
