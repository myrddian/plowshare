package io.aeyer.plowshare.server.documents;

import java.util.List;
import java.util.UUID;

/**
 * Ordered, resumable summary checkpoints for a document. Implementations own persistence and the
 * caller's revision/generation fence; every attachment commits independently of model inference.
 * Literal short text is a valid lossless summary. Null means work is still owed, never "skip it".
 */
public interface DocumentSummaries {
  /** Missing paragraph labels in document order; completed labels are never replayed. */
  List<DocumentStore.UnsummarisedParagraph> unsummarised(UUID documentId);

  /** Retained document summary, or null before completion. */
  String documentSummary(UUID documentId);

  /** Number of nonempty sections and chapters whose summaries are still missing. */
  int unsummarisedUnits(UUID documentId);

  /** Attach a paragraph label through the revision's checkpoint fence. */
  void attachSummary(UUID paragraphId, String summary);

  /**
   * Count labelled paragraphs detached from the hierarchy; callers must refuse an incomplete tree.
   */
  int summarisedParagraphsInNoSection(UUID documentId);

  /** The complete ordered hierarchy and retained summaries, read as one typed snapshot. */
  List<DocumentStore.StoredChapter> hierarchy(UUID documentId);

  /** Completed labels for one section, in paragraph order. */
  List<String> sectionParagraphSummaries(UUID sectionId);

  /** Attach a section summary through the revision's checkpoint fence. */
  void attachSectionSummary(UUID sectionId, String summary);

  /** Attach a chapter summary through the revision's checkpoint fence. */
  void attachChapterSummary(UUID chapterId, String summary);

  /** Attach the document summary through the revision's checkpoint fence. */
  void attachDocumentSummary(UUID documentId, String summary);
}
