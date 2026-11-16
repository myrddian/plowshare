package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.approvals.ApprovalPolicy;
import io.aeyer.plowshare.server.approvals.ApprovalReviews;
import io.aeyer.plowshare.server.approvals.FileApproval;
import io.aeyer.plowshare.server.approvals.FileApprovalConsent;
import io.aeyer.plowshare.server.approvals.OperationApprovalReview;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.RemoteProvider;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.ToolPre;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Intent review around direct file operations. Provider routing and IO still enforce their own
 * fences. Retained metadata is server-authored and is removed before hooks or tools see arguments.
 * A changed hook, provider or policy refuses the retained operation without starting new work.
 */
final class ReviewedFileTool implements RetainedApprovalTool, AgentTool.ContentTool {
  record Question(Home home, String side, FileApproval file, String reason) {}

  private record Prepared(
      String arguments, String side, OperationApprovalReview.Request request, FileApproval file) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String RETAINED = "__plowshare_file_consent";
  private final AgentTool delegate;
  private final ProviderRouter router;
  private final OperationApprovalReview review;
  private final FileApprovalConsent store;
  private final Function<Question, RunApproval> questions;
  private String call = UUID.randomUUID().toString();
  private RunApproval waiting;
  private Prepared permitted;
  private boolean rejected;
  private String retainedId;

  @Override
  public void retainedApproval(String id) {
    retainedId = Objects.requireNonNull(id);
  }

  ReviewedFileTool(
      AgentTool delegate,
      ProviderRouter router,
      OperationApprovalReview review,
      FileApprovalConsent store,
      Function<Question, RunApproval> questions) {
    this.delegate = Objects.requireNonNull(delegate);
    this.router = Objects.requireNonNull(router);
    this.review = Objects.requireNonNull(review);
    this.store = Objects.requireNonNull(store);
    this.questions = Objects.requireNonNull(questions);
  }

  static boolean supports(String name) {
    return List.of(
            FileTools.READ_NAME, FileTools.EDIT_NAME, FileTools.DELETE_NAME, FileTools.MOVE_NAME)
        .contains(name);
  }

  @Override
  public ToolSchema schema() {
    return delegate.schema();
  }

  @Override
  public void calledAs(String id) {
    call = id;
    delegate.calledAs(id);
  }

  @Override
  public Optional<RunApproval> waitingApproval() {
    return Optional.ofNullable(waiting);
  }

  @Override
  public boolean securityRejected() {
    return rejected;
  }

  @Override
  public ToolPre gate(
      String arguments,
      Home home,
      HookContext context,
      BiFunction<HookContext, String, ToolPre> chain) {
    waiting = null;
    permitted = null;
    rejected = false;
    ToolPre checked = chain.apply(context, arguments);
    if (checked.isDenied()) return checked;
    try {
      Prepared prepared = prepare(checked.arguments(), home);
      var decision =
          review.review(
              prepared.request(),
              new ApprovalReviews.Consent(true, checked.asked() != null, false));
      if (decision.action() == ApprovalReviews.Action.REJECT) {
        rejected = true;
        return new ToolPre(
            checked.arguments(),
            "SECURITY rejected the operation: " + decision.reason(),
            checked.records(),
            false);
      }
      if (decision.action() == ApprovalReviews.Action.ASK_HUMAN) {
        waiting =
            questions.apply(
                new Question(
                    home,
                    prepared.side(),
                    prepared.file(),
                    (checked.asked() == null ? "" : checked.asked() + " ") + decision.reason()));
        permitted = prepared;
        return new ToolPre(
            checked.arguments(),
            "Awaiting human file-operation approval [" + waiting.id() + "]",
            checked.records(),
            false);
      }
      permitted = prepared;
      return new ToolPre(checked.arguments(), null, checked.records(), true);
    } catch (ToolArguments.BadArguments | WorkspaceRefusedException refused) {
      return new ToolPre(checked.arguments(), refused.getMessage(), checked.records(), false);
    }
  }

  @Override
  public String retainedArguments(String arguments, Home home) {
    if (waiting == null || permitted == null)
      throw new IllegalStateException("No file question is pending");
    ObjectNode frozen = JSON.createObjectNode();
    frozen.put("id", waiting.id());
    frozen.set("file", JSON.valueToTree(permitted.file()));
    ObjectNode envelope = JSON.createObjectNode();
    envelope.put("arguments", permitted.arguments());
    envelope.set(RETAINED, frozen);
    return envelope.toString();
  }

  @Override
  public ToolPre gateRetained(
      String arguments,
      String side,
      Home home,
      HookContext context,
      BiFunction<HookContext, String, ToolPre> chain) {
    permitted = null;
    waiting = null;
    JsonNode envelope = ToolArguments.parse(arguments, schema().name(), "retained file operation");
    String original =
        ToolArguments.requireExactText(
            envelope, "arguments", schema().name(), "the retained operation");
    JsonNode frozen = envelope.path(RETAINED);
    String id = ToolArguments.requireText(frozen, "id", schema().name(), "the retained question");
    if (retainedId != null && !retainedId.equals(id))
      throw new WorkspaceRefusedException("Retained file approval identity changed; nothing ran");
    retainedId = null;
    FileApproval expected;
    try {
      expected = JSON.treeToValue(frozen.path("file"), FileApproval.class);
    } catch (JsonProcessingException invalid) {
      throw new WorkspaceRefusedException("The retained file request is invalid; nothing ran");
    }
    ToolPre checked = chain.apply(context, original);
    if (checked.isDenied()) return checked;
    Prepared current = prepare(checked.arguments(), home);
    // Exact argument bytes are retained, including content and read windows. A rewriter must not
    // turn the human's original question into permission for another change.
    if (!original.equals(checked.arguments())
        || !side.equals(current.side())
        || !expected.equals(current.file()))
      return new ToolPre(
          original,
          "The retained file operation or provider changed; nothing ran",
          checked.records(),
          false);
    if (!store.consumeFile(id, expected))
      return new ToolPre(
          original,
          "The exact file approval is no longer available; nothing ran",
          checked.records(),
          false);
    try {
      review.humanOverride(current.request(), id);
    } catch (WorkspaceRefusedException refused) {
      return new ToolPre(original, refused.getMessage(), checked.records(), false);
    }
    permitted = current;
    return new ToolPre(original, null, checked.records(), true);
  }

  private Prepared prepare(String arguments, Home home) {
    JsonNode args = ToolArguments.parse(arguments, schema().name(), "file operation arguments");
    String from = ToolArguments.requireText(args, "path", schema().name(), "an absolute file path");
    Path path = Path.of(from);
    if (!path.isAbsolute())
      throw new WorkspaceRefusedException("File approval requires an absolute path");
    FileProvider provider = router.providerFor(home, path);
    ApprovalPolicy.Kind kind =
        switch (schema().name()) {
          case FileTools.READ_NAME -> ApprovalPolicy.Kind.FILE_READ;
          case FileTools.EDIT_NAME -> ApprovalPolicy.Kind.FILE_WRITE;
          case FileTools.DELETE_NAME -> ApprovalPolicy.Kind.FILE_DELETE;
          case FileTools.MOVE_NAME -> ApprovalPolicy.Kind.FILE_MOVE;
          default -> throw new IllegalStateException("Unsupported file review tool");
        };
    List<String> paths = List.of(from);
    if (kind == ApprovalPolicy.Kind.FILE_MOVE) {
      String to = ToolArguments.requireText(args, "to", schema().name(), "an absolute destination");
      Path destination = Path.of(to);
      if (!destination.isAbsolute()
          || !provider.identity().equals(router.providerFor(home, destination).identity()))
        throw new WorkspaceRefusedException(
            "File moves require one current provider for both targets");
      paths = List.of(from, to);
    }
    String cwd = path.getParent().toString();
    String digest = digest(arguments);
    var request =
        new OperationApprovalReview.Request(
            home.project(), provider.identity(), List.of(), cwd, arguments, call, kind, paths);
    var file =
        new FileApproval(
            kind,
            provider.identity(),
            paths,
            digest,
            arguments.length() <= 16000 ? arguments : null);
    return new Prepared(
        arguments, RemoteProvider.NAME.equals(provider.name()) ? "local" : "server", request, file);
  }

  private void require(String arguments, Home home) {
    Prepared grant = permitted;
    permitted = null;
    if (grant == null || !grant.arguments().equals(arguments))
      throw new WorkspaceRefusedException(
          "No review covers this exact file operation; nothing ran");
    Prepared now = prepare(arguments, home);
    if (!grant.file().equals(now.file()))
      throw new WorkspaceRefusedException("The file provider changed after review; nothing ran");
    review.requireCurrent(now.request());
  }

  @Override
  public String run(String arguments, Home home) {
    return run(arguments, home, UsageAttribution.LEGACY);
  }

  @Override
  public String run(String arguments, Home home, UsageAttribution usage) {
    try {
      require(arguments, home);
      return delegate.run(arguments, home, usage);
    } catch (WorkspaceRefusedException refused) {
      return refused.getMessage();
    }
  }

  @Override
  public boolean typedResults(Home home) {
    return delegate instanceof AgentTool.ContentTool content && content.typedResults(home);
  }

  @Override
  public AgentTool.Result runResult(String arguments, Home home, UsageAttribution usage) {
    try {
      require(arguments, home);
      return delegate instanceof AgentTool.ContentTool content
          ? content.runResult(arguments, home, usage)
          : new AgentTool.Result(delegate.run(arguments, home, usage), null);
    } catch (WorkspaceRefusedException refused) {
      return new AgentTool.Result(refused.getMessage(), null);
    }
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
