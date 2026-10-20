package io.aeyer.plowshare.sdk;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import io.aeyer.plowshare.protocol.search.SearchPage;
import io.aeyer.plowshare.sdk.ServerClient.*;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Typed asynchronous server capabilities. Completion never implies durable job completion. */
public interface AsyncServerClient extends AutoCloseable {
  String baseUrl();

  @Override
  void close();

  CompletionStage<Plowshare> connectionAsync();

  CompletionStage<Plowshare> connectionAsync(RequestOptions options);

  CompletionStage<Recall> recallAsync(
      String project, String question, Integer limit, RequestOptions options);

  CompletionStage<Recall> recallAsync(String project, String question, Integer limit);

  CompletionStage<io.aeyer.plowshare.protocol.InformationResponse> informationAsync(
      io.aeyer.plowshare.protocol.InformationRequest request, RequestOptions options);

  CompletionStage<io.aeyer.plowshare.protocol.InformationResponse> informationAsync(
      io.aeyer.plowshare.protocol.InformationRequest request);

  CompletionStage<WriteResult> writeAsync(
      String project, MemoryProposal proposal, RequestOptions options);

  CompletionStage<WriteResult> writeAsync(String project, MemoryProposal proposal);

  CompletionStage<Navigation> navigateMemoryAsync(
      String project, String question, RequestOptions options);

  CompletionStage<Navigation> navigateMemoryAsync(String project, String question);

  CompletionStage<StartedJob> digestMemoryAsync(String project, RequestOptions options);

  CompletionStage<StartedJob> digestMemoryAsync(String project);

  CompletionStage<Memory> readAsync(String id, RequestOptions options);

  CompletionStage<Memory> readAsync(String id);

  CompletionStage<List<IndexEntry>> indexAsync(String project, RequestOptions options);

  CompletionStage<List<IndexEntry>> indexAsync(String project);

  CompletionStage<SearchPage> searchAsync(
      String query, int pageSize, int max, int page, RequestOptions options);

  CompletionStage<SearchPage> searchAsync(String query, int pageSize, int max, int page);

  CompletionStage<FetchWindow> fetchAsync(String url, int offset, RequestOptions options);

  CompletionStage<FetchWindow> fetchAsync(String url, int offset);

  CompletionStage<DocumentSearch> searchDocumentsAsync(
      String query, Integer limit, RequestOptions options);

  CompletionStage<DocumentSearch> searchDocumentsAsync(String query, Integer limit);

  CompletionStage<Retrieved> retrieveAsync(
      String query, String documentId, Integer limit, RequestOptions options);

  CompletionStage<Retrieved> retrieveAsync(String query, String documentId, Integer limit);

  CompletionStage<Ranking> rankDocumentsAsync(String query, Integer limit, RequestOptions options);

  CompletionStage<Ranking> rankDocumentsAsync(String query, Integer limit);

  CompletionStage<Stance> documentStanceAsync(
      String documentId, String claim, RequestOptions options);

  CompletionStage<Stance> documentStanceAsync(String documentId, String claim);

  CompletionStage<DocumentPage> listDocumentsAsync(
      String naming, Integer limit, Integer offset, RequestOptions options);

  CompletionStage<DocumentPage> listDocumentsAsync(String naming, Integer limit, Integer offset);

  CompletionStage<DocumentOutline> describeDocumentAsync(String documentId, RequestOptions options);

  CompletionStage<DocumentOutline> describeDocumentAsync(String documentId);

  CompletionStage<DocumentOutline> describeDocumentAsync(
      String documentId,
      io.aeyer.plowshare.protocol.InformationRequest.Scope scope,
      RequestOptions options);

  CompletionStage<DocumentOutline> describeDocumentAsync(
      String documentId, io.aeyer.plowshare.protocol.InformationRequest.Scope scope);

  CompletionStage<Citations> citationsAsync(
      String conversationId, String documentId, Integer limit, RequestOptions options);

  CompletionStage<Citations> citationsAsync(
      String conversationId, String documentId, Integer limit);

  CompletionStage<StartedJob> runAsync(
      String agent,
      String task,
      String project,
      String session,
      String conversation,
      RequestOptions options);

  CompletionStage<StartedJob> runAsync(
      String agent, String task, String project, String session, String conversation);

  CompletionStage<Conversation> openConversationAsync(
      String project, Integer maxModelCalls, RequestOptions options);

  CompletionStage<Conversation> openConversationAsync(String project, Integer maxModelCalls);

  CompletionStage<List<Conversation>> conversationsAsync(String project, RequestOptions options);

  CompletionStage<List<Conversation>> conversationsAsync(String project);

  CompletionStage<Entries> chatAsync(
      String conversationId, Integer offset, Integer limit, RequestOptions options);

  CompletionStage<Entries> chatAsync(String conversationId, Integer offset, Integer limit);

  CompletionStage<Entries> trajectoryAsync(
      String conversationId, Integer offset, Integer limit, RequestOptions options);

  CompletionStage<Entries> trajectoryAsync(String conversationId, Integer offset, Integer limit);

  CompletionStage<LogHits> searchEntriesAsync(
      String project, String query, Integer offset, Integer limit, RequestOptions options);

  CompletionStage<LogHits> searchEntriesAsync(
      String project, String query, Integer offset, Integer limit);

  CompletionStage<Context> contextAsync(
      String conversationId, String agent, RequestOptions options);

  CompletionStage<Context> contextAsync(String conversationId, String agent);

  CompletionStage<List<Seam>> compactionsAsync(String conversationId, RequestOptions options);

  CompletionStage<List<Seam>> compactionsAsync(String conversationId);

  CompletionStage<StartedJob> curateAsync(
      String project, Integer maxModelCalls, RequestOptions options);

  CompletionStage<StartedJob> curateAsync(String project, Integer maxModelCalls);

  CompletionStage<StartedJob> askDocumentAsync(
      String documentId, String question, Integer maxModelCalls, RequestOptions options);

  CompletionStage<StartedJob> askDocumentAsync(
      String documentId, String question, Integer maxModelCalls);

  CompletionStage<JobStatus> jobAsync(String id, RequestOptions options);

  CompletionStage<JobStatus> jobAsync(String id);

  CompletionStage<JobStatus> cancelJobAsync(String id, RequestOptions options);

  CompletionStage<JobStatus> cancelJobAsync(String id);

  CompletionStage<ProjectView> defineProjectAsync(
      String name, String workspace, List<String> exclusions, RequestOptions options);

  CompletionStage<ProjectView> defineProjectAsync(
      String name, String workspace, List<String> exclusions);

  CompletionStage<ProjectView> lendProjectAsync(
      String name, List<String> roots, RequestOptions options);

  CompletionStage<ProjectView> lendProjectAsync(String name, List<String> roots);

  CompletionStage<ProjectView> unlendProjectAsync(
      String name, List<String> roots, RequestOptions options);

  CompletionStage<ProjectView> unlendProjectAsync(String name, List<String> roots);

  CompletionStage<ProjectView> setProjectWorkspaceAsync(
      String name, String workspace, RequestOptions options);

  CompletionStage<ProjectView> setProjectWorkspaceAsync(String name, String workspace);

  CompletionStage<Void> moveProjectAsync(String name, String to, RequestOptions options);

  CompletionStage<Void> moveProjectAsync(String name, String to);

  CompletionStage<Void> forgetProjectAsync(String name, RequestOptions options);

  CompletionStage<Void> forgetProjectAsync(String name);

  CompletionStage<List<ProposalRow>> proposalsAsync(String project, RequestOptions options);

  CompletionStage<List<ProposalRow>> proposalsAsync(String project);

  CompletionStage<Resolution> resolveAsync(
      String id, boolean accept, String reason, String by, RequestOptions options);

  CompletionStage<Resolution> resolveAsync(String id, boolean accept, String reason, String by);

  CompletionStage<UploadedImage> uploadImageAsync(String project, String filename, byte[] bytes);
}
