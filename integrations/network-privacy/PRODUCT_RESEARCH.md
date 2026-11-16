# Focused product security and privacy research

Privacy Watch's **Product questions** panel offers **Research this question**,
manual source links and Dismiss. Research is explicit owner work: clicking it
permits bounded discovery and acquisition of relevant public pages for that
question. This extends the original vendor-only, manually supplied reference
policy for this action; ordinary Ask turns still search retained sources and
queue missing-evidence requests without acquiring arbitrary URLs themselves.

The workflow is the Application's `research_product_question` orchestration,
with `privacy_product_researcher` and `privacy_product_reviewer`. It is separate
from `deep_research` and uses existing public SDK orchestration, information,
receipt, status, tagging and cancellation operations. No core APIs, schema,
shared SDK contracts or provider-tool declarations change.

## Evidence and review

The researcher searches appropriate sources across official documentation and
privacy policies, vulnerability advisories, privacy/consumer organisations,
original security/academic work and independent reporting. Reporting such as
Gamers Nexus investigations is relevant when it addresses the product/question;
official documentation is not the sole authority about tracking, ACR, consent,
advertising or data sharing. Search queries must use public product and feature
names, never household identifiers or raw scan records.

Discovery is limited to six search calls and five selected public HTTPS pages.
Relevant, already tagged Library sources for the product are offered for reuse;
selecting their URLs reuses the retained revisions without another acquisition.
The script acquires other selections, waits for extracted text, inspects up to
three matching passages of up to 6,000 characters per page (or an explicitly limited leading window), and
records exact UTF-16 evidence. Search snippets are discovery metadata only.
Unsupported, blocked or unextractable material remains a gap. A video without
accessible substantive text is a lead, not an invented transcript.

The researcher drafts claims, applicability, gaps and suggested checks. Every
finding must cite supplied retained evidence. The independent reviewer checks
claims against actual passages, including model, firmware, region, configuration,
date and source independence. A needed correction gets one correction and a
second review. A second rejection or invalid output writes no accepted report.
A candid gap answer can pass review without manufacturing findings.

Model prose uses word targets with 25% overrun tolerance: 200 words for source
titles, applicability, individual gaps and checks; 250 for claims; 400 for the
summary; and 600 for review notes. These are presentation targets rather than
character cutoffs. Beyond the tolerance, the script uses the existing model-only
recovery worker for one targeted shortening attempt per response. Only the
overlong prose is sent; source URLs, evidence IDs, verdicts, other valid fields
and completed discovery/acquisition work remain unchanged. The resulting draft
still goes through independent review. An unsuccessful shortening asks for review
and retains the original response rather than restarting research or writing an
accepted report. Raw reply, parser, source-window and delivery limits remain
separate resource bounds. Research titles keep the same word tolerance through
collector decoding and reference-library journal reloads.

The script retains a draft report with source/dossier inputs, exact evidence
citations and review metadata. The collector shows the answer on its request
card, registers accepted source revisions with the reference library and applies
`device-reference`, product and source-category tags through its existing loop.
This registration does not fetch the pages again. The ordinary library refresh
interval applies. Research does not finalise reports or edit device profiles,
issues, network settings or mitigations. The Ask assistant can subsequently find
these sources through its existing information-discovery skill. It does not
automatically send a new Ask turn when research completes.

## Optional device checks

**Include read-only HTTP checks** is available when the request names a monitored
device. It is off by default and selected separately on each research request.
For an older unlinked question, choose a current monitored device and use **Link device** before starting research. The collector resolves that device's current configured private IPv4 TCP target,
confirms its MAC through the existing fresh controller/Pi-hole identity capability
before and after the checks, and retains a dossier revision. Missing, ambiguous,
changed or out-of-scope identity prevents using the check.

This first version makes only fixed, unauthenticated requests on TCP 80:

- `GET /` records whether an HTTP response arrived.
- For Sony manufacturer evidence, `POST /sony/system` with the fixed
  `getPowerStatus` JSON-RPC request reads the power state. It does not change it.

The model cannot choose targets, ports, paths, methods, headers or payloads.
Requests have a three-second total timeout each and an 8 KiB response cap,
with redirects, environment proxies, cookies and credentials disabled. Only
check name, timestamp, HTTP status and a bounded outcome classification survive;
response bodies and identifying headers are discarded. Explicit root and Sony
API observations remain separate. An unauthenticated information response does
not establish control access. An authentication error applies only to that
request; a timeout does not establish closure. There are no exploit requests,
remote-control commands or arbitrary probe execution. Other products currently
receive the root HTTP check only. Further product-specific checks need their
own fixed collector implementation and tests.

## Admission, recovery and controls

The bearer/session-protected dashboard queues a durable request before SDK work.
Repeated clicks return the same job. One job advances per collector pass;
Plowshare owns model execution, accounting, authority and durable orchestration
state. The workflow has a 32-model-call ceiling; source ingestion has the
platform's separate processing allowance and gates.

The collector journals the admission UUID before starting. Unknown delivery is
resolved only by the account-owned receipt; even a receipt refusal cannot prove
that the start never committed. Interrupted device preparation is marked failed
rather than repeating probes on restart. Proven non-delivery or a terminal failed
run permits an explicit new attempt. Status reads verify run ID, project,
definition and caller. Permission or malformed-status failures preserve active
admission fences. Source-registration failures preserve the report and result.

**Stop research** cancels queued work locally or requests cancellation through the
existing SDK for an admitted run, after checking its scope. Polling confirms the
terminal server state. Unknown cancellation is not replayed automatically. While
admission is unresolved or research is active, Approve and Dismiss cannot discard
its tracking state. The original question stays visible after completion until
the owner dismisses it; the Library keeps its retained evidence and report.

The private, bounded `product-research.json` journal lives beside the collector's
other state. An unreadable journal disables product research without disabling
collection. A deployed installation needs both the updated collector package and
updated Application resources, with the normal search and information services
available. The existing native tool binding digest is unchanged. Installing
these files is not a deployment or a live TV validation.
The server must support `modelOutput.recover` before installing this workflow;
missing support is refused before research starts. New definitions do not
automatically resume already-failed, source-pinned runs.
