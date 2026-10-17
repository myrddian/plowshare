package io.aeyer.plowshare.server.archive;

/** Admits the existing Global copy-and-retire effect using authenticated authority, not by text. */
public interface PromotionAccess {
  /** Refuses before the queue claims a proposal unless source release and Global control exist. */
  void require(String account, String proposal);
}
