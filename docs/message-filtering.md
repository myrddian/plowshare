# Message filtering

The server checks chat messages before model routing, token counting and
transport dispatch through `ChatFiltering`. This covers ordinary, named-pool and
fold chat paths. Embeddings have a different contract and are not inspected by
this feature. This implementation is a supplemental text filter: model output,
tool authorization, workspace fences and approvals retain their owning controls.

## Configuration format

The examples below use JSON. These settings belong to the server's deployment
configuration, which supports YAML files and JSON through
`SPRING_APPLICATION_JSON` (or the `spring.application.json` property). Save the
chosen JSON object outside the workspace and load it when starting the server:

```sh
: "${PLOWSHARE_FILTER_CONFIG:?Set the path to your server configuration JSON file}"
SPRING_APPLICATION_JSON="$(cat "$PLOWSHARE_FILTER_CONFIG")" &&
  export SPRING_APPLICATION_JSON &&
  plowshare-server
```

A standalone `application.json` is not automatically loaded by the server launcher.
The examples are configuration fragments: combine their `plowshare` settings into
one JSON object when enabling both local disclosure rules and external review,
and merge with any existing `SPRING_APPLICATION_JSON` configuration. Normal
`application.yml`/`application.yaml` configuration remains supported. Restart the
server to apply these deployment settings. Unknown fields in filtering and Relay
port configuration fail startup, including misspelled fields inside bindings.

Project `Relay/active.json` and `Relay/topics.json` keep their existing JSON
contracts. They do not grant deployment-level SDK ports or select external
reviewer accounts.

## Local rules

Basic prompt injection filtering is enabled by default. It normalizes untrusted
USER and TOOL text using Unicode NFKC and case folding and blocks high-severity
keyword phrases from the bundled jailbreak and system-prompt categories. For
example, explicit instructions to ignore previous instructions or print the
system prompt are refused before an inference call. SYSTEM and ASSISTANT text
skip injection keyword checks. Images are preserved without inspection.

The server bundles prompt-injection keyword rules and a sensitive-pattern catalog.
The original rule data, [license](../plowshare-server/src/main/resources/security/rules/LICENSE)
and [data notice](../plowshare-server/src/main/resources/security/rules/NOTICE) are
retained with the resources. The Java evaluator performs local keyword and regex
checks; external classifiers use the SDK review protocol below.

```json
{
  "plowshare": {
    "security": {
      "filtering": {
        "enabled": true,
        "conditional-matches": false,
        "categories": [
          "prompt_injection_jailbreak",
          "prompt_injection_system_prompt"
        ],
        "patterns": [
          {
            "name": "github_token",
            "action": "BLOCK"
          },
          {
            "name": "email",
            "action": "MASK"
          }
        ]
      }
    }
  }
}
```

Sensitive disclosure rules are opt-in. Choose names from the bundled
[pattern catalog](../plowshare-server/src/main/resources/security/rules/patterns.json).
Unknown names fail configuration. Matching credential or personal-data formats
can BLOCK or MASK. Catalog context keywords are required when a pattern defines
them; spelled-out number interpretation is not implemented. These regexes do not
classify all secrets or personal information, validate identity, or guarantee
injection prevention. Quoting an attack for legitimate security work can also
match; select categories for that workload or disable local rules explicitly.

All BLOCK policies inspect the original text before masking, so overlapping MASK
rules cannot hide a blocked value. Masks replace matches in text only. A match in
structured tool arguments is refused even with MASK, preserving JSON semantics.
Roles, tool identities, images and accounting metadata are preserved. Text checks
are bounded to 524,288 UTF-16 units. Broad conditional identifier/target pairs are
opt-in; their exceptions apply per sentence and never exempt an explicit
high-severity phrase.

When sensitive patterns are configured, model output is inspected too. Streaming
answer and reasoning deltas are withheld until final inspection; the full approved
answer is released once, and reasoning remains withheld. Actual provider usage is
still recorded if the final answer is refused. Filtering governs the text sent to
the model and returned to the caller; it does not rewrite source messages,
trajectories, retained review topics or diagnostic inference captures.

## External review over Relay

External input detectors run outside core using public SDK topic ports. Configure
an exact subject account/project and a distinct reviewer account. Both must retain
project work membership. The reviewer needs EGRESS on requests and INGRESS on
responses; project membership alone opens neither port. Use an ordinary shared
project; Personal ownership cannot be granted to a second reviewer.

```json
{
  "plowshare": {
    "relay": {
      "ports": {
        "bindings": [
          {
            "project": "sample-project",
            "topic": "security.requests",
            "account": "detector-account",
            "direction": "EGRESS",
            "groups": [
              "detectors"
            ]
          },
          {
            "project": "sample-project",
            "topic": "security.responses",
            "account": "detector-account",
            "direction": "INGRESS"
          }
        ]
      }
    },
    "security": {
      "filtering": {
        "external": [
          {
            "project": "sample-project",
            "account": "workload-account",
            "request-topic": "security.requests",
            "response-topic": "security.responses",
            "reviewer": "detector-account",
            "timeout-seconds": 15
          }
        ]
      }
    }
  }
}
```

These topics carry application JSON inside existing TEXT publications. They need
no lifecycle event name or custom Relay payload kind. The server registers them
with normal default retention when first used. Configure topic policies through
the existing broker administration; access to retained project topic logs also
gives access to review content.

`FilterReviewRequest` contains `version: 1`, a UUID `requestId`, a `sourceHash`
(SHA-256 of UTF-8 `role + NUL + message`), `role` and the complete text `message`.
The SDK detector consumes the request topic, evaluates the text, and publishes a
response containing the same version, ID and hash, `accepted`, `message` and
`reason`. Acceptance requires the entire approved replacement message. Refusal
requires `message: null`; `reason` is a bounded code. The server never exposes a
reviewer's arbitrary reason as a diagnostic containing rejected content.

The response publication sets `correlationId` to the request ID and
`parentTopic`/`parentEventId` to the held request's topic/event. Publisher identity
comes from authentication. A configured reviewer cannot approve unrelated text,
a wrong hash, a malformed response or a response without matching ancestry.
Approved text is rechecked locally. A batch acknowledgement only records delivery.

Each untrusted nonblank text part in the current model conversation is reviewed
on each call; there is no approval cache. External review does not inspect images,
structured tool arguments or generated model output. Local disclosure policies
still check structured arguments and generated output. The `enabled` switch
controls local rules; removing the external binding disables external review.
A reviewer cannot itself be a filtered subject in the same project.

The request commits before a bounded wait of 1–60 seconds, without holding a model
pool slot or database transaction. Cancellation, authority loss, rejection,
invalid verdict, a response retention gap or timeout refuses the call. Restart
does not resume a waiting call or automatically republish it. The detector should
journal its stable response UUID and timestamp before publishing, reconcile an
uncertain send through retained topic inspection, and acknowledge the request
batch only after settling every response. See [SDK topic ports](relay.md#sdk-topic-ports).

## JSON parsing

TypeScript's ordinary `JSON.parse` remains native JavaScript parsing. The SDK
validates operation DTOs explicitly; the filter server uses strict typed JSON with
unknown fields, scalar coercion, duplicate properties and trailing tokens refused.
Plowshare's separate LLM JSON recovery parser is exposed as `llmJson.parse` in
scripted orchestrations. It does not replace SDK `JSON.parse` or validate detector
messages automatically.
