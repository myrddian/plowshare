// Generated from Desktop Request; run the SDK schema generator with --desktop.
import type { Schema } from 'plowshare-client-ts/operations/schema'
export const DESKTOP_SCHEMA: {request: Schema; $defs: Record<string,Schema>} = {
  "request": {
    "$ref": "#/$defs/shape0"
  },
  "$defs": {
    "shape2": {
      "const": "usage"
    },
    "shape1": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape2"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape4": {
      "const": "context-snapshot"
    },
    "shape5": {
      "type": "string"
    },
    "shape7": {
      "const": false
    },
    "shape8": {
      "const": true
    },
    "shape6": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape7"
        },
        {
          "$ref": "#/$defs/shape8"
        }
      ]
    },
    "shape3": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape4"
        },
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "measure": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape10": {
      "const": "relay"
    },
    "shape9": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape10"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape12": {
      "const": "relay-read"
    },
    "shape15": {
      "type": "number"
    },
    "shape14": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "system": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "project"
      ],
      "additionalProperties": false
    },
    "shape17": {
      "type": "null"
    },
    "shape16": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "project": {
          "$ref": "#/$defs/shape17"
        },
        "system": {
          "$ref": "#/$defs/shape8"
        }
      },
      "required": [
        "system"
      ],
      "additionalProperties": false
    },
    "shape13": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape14"
        },
        {
          "$ref": "#/$defs/shape16"
        }
      ]
    },
    "shape18": {
      "const": "relay.topics"
    },
    "shape11": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape12"
        },
        "payload": {
          "$ref": "#/$defs/shape13"
        },
        "type": {
          "$ref": "#/$defs/shape18"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape21": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape5"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "system": {
          "$ref": "#/$defs/shape7"
        },
        "topic": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "project",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape22": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape5"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "project": {
          "$ref": "#/$defs/shape17"
        },
        "system": {
          "$ref": "#/$defs/shape8"
        },
        "topic": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "system",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape20": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape21"
        },
        {
          "$ref": "#/$defs/shape22"
        }
      ]
    },
    "shape23": {
      "const": "relay.log"
    },
    "shape19": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape12"
        },
        "payload": {
          "$ref": "#/$defs/shape20"
        },
        "type": {
          "$ref": "#/$defs/shape23"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape25": {
      "const": "relay-operate"
    },
    "shape28": {
      "const": "ACKNOWLEDGE_GAP"
    },
    "shape29": {
      "const": "RECONCILE"
    },
    "shape30": {
      "const": "ABANDON"
    },
    "shape31": {
      "const": "REMOVE_SUBSCRIPTION"
    },
    "shape32": {
      "const": "REMOVE_TOPIC"
    },
    "shape27": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape28"
        },
        {
          "$ref": "#/$defs/shape29"
        },
        {
          "$ref": "#/$defs/shape30"
        },
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape32"
        }
      ]
    },
    "shape33": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape17"
        },
        {
          "$ref": "#/$defs/shape5"
        }
      ]
    },
    "shape26": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape27"
        },
        "deliveryId": {
          "$ref": "#/$defs/shape33"
        },
        "expectedState": {
          "$ref": "#/$defs/shape33"
        },
        "expiredThrough": {
          "$ref": "#/$defs/shape33"
        },
        "fence": {
          "$ref": "#/$defs/shape33"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "reason": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "subscriber": {
          "$ref": "#/$defs/shape33"
        },
        "subscriptionGeneration": {
          "$ref": "#/$defs/shape33"
        },
        "topic": {
          "$ref": "#/$defs/shape5"
        },
        "topicGeneration": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "project",
        "reason",
        "requestId",
        "topic",
        "topicGeneration"
      ],
      "additionalProperties": false
    },
    "shape24": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape25"
        },
        "payload": {
          "$ref": "#/$defs/shape26"
        }
      },
      "required": [
        "action",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape35": {
      "const": "relay-trajectory"
    },
    "shape34": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape35"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape37": {
      "const": "usage-open"
    },
    "shape41": {
      "const": "day"
    },
    "shape42": {
      "const": "model"
    },
    "shape43": {
      "const": "pool"
    },
    "shape44": {
      "const": "agent"
    },
    "shape45": {
      "const": "operation"
    },
    "shape46": {
      "const": "project"
    },
    "shape47": {
      "const": "run"
    },
    "shape40": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape41"
        },
        {
          "$ref": "#/$defs/shape42"
        },
        {
          "$ref": "#/$defs/shape43"
        },
        {
          "$ref": "#/$defs/shape44"
        },
        {
          "$ref": "#/$defs/shape45"
        },
        {
          "$ref": "#/$defs/shape46"
        },
        {
          "$ref": "#/$defs/shape47"
        }
      ]
    },
    "shape39": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape40"
      }
    },
    "shape49": {
      "const": "direct"
    },
    "shape50": {
      "const": "subtree"
    },
    "shape48": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape49"
        },
        {
          "$ref": "#/$defs/shape50"
        }
      ]
    },
    "shape38": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "cursor": {
          "$ref": "#/$defs/shape5"
        },
        "from": {
          "$ref": "#/$defs/shape5"
        },
        "group_by": {
          "$ref": "#/$defs/shape39"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "model": {
          "$ref": "#/$defs/shape5"
        },
        "orchestration": {
          "$ref": "#/$defs/shape5"
        },
        "pool": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "route": {
          "$ref": "#/$defs/shape5"
        },
        "run": {
          "$ref": "#/$defs/shape5"
        },
        "scope": {
          "$ref": "#/$defs/shape48"
        },
        "to": {
          "$ref": "#/$defs/shape5"
        }
      },
      "additionalProperties": false
    },
    "shape52": {
      "const": "usage.conversation"
    },
    "shape53": {
      "const": "usage.project"
    },
    "shape54": {
      "const": "usage.agent"
    },
    "shape55": {
      "const": "usage.run"
    },
    "shape56": {
      "const": "usage.orchestration"
    },
    "shape57": {
      "const": "usage.models"
    },
    "shape58": {
      "const": "usage.pools"
    },
    "shape51": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape52"
        },
        {
          "$ref": "#/$defs/shape53"
        },
        {
          "$ref": "#/$defs/shape54"
        },
        {
          "$ref": "#/$defs/shape55"
        },
        {
          "$ref": "#/$defs/shape56"
        },
        {
          "$ref": "#/$defs/shape57"
        },
        {
          "$ref": "#/$defs/shape58"
        }
      ]
    },
    "shape36": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape37"
        },
        "filter": {
          "$ref": "#/$defs/shape38"
        },
        "type": {
          "$ref": "#/$defs/shape51"
        }
      },
      "required": [
        "action",
        "filter",
        "type"
      ],
      "additionalProperties": false
    },
    "shape60": {
      "const": "usage-read"
    },
    "shape61": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "cursor": {
          "$ref": "#/$defs/shape5"
        },
        "from": {
          "$ref": "#/$defs/shape5"
        },
        "group_by": {
          "$ref": "#/$defs/shape39"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "model": {
          "$ref": "#/$defs/shape5"
        },
        "orchestration": {
          "$ref": "#/$defs/shape5"
        },
        "pool": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "route": {
          "$ref": "#/$defs/shape5"
        },
        "run": {
          "$ref": "#/$defs/shape5"
        },
        "scope": {
          "$ref": "#/$defs/shape48"
        },
        "to": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape59": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
        },
        "payload": {
          "$ref": "#/$defs/shape61"
        },
        "type": {
          "$ref": "#/$defs/shape52"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape63": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "cursor": {
          "$ref": "#/$defs/shape5"
        },
        "from": {
          "$ref": "#/$defs/shape5"
        },
        "group_by": {
          "$ref": "#/$defs/shape39"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "model": {
          "$ref": "#/$defs/shape5"
        },
        "orchestration": {
          "$ref": "#/$defs/shape5"
        },
        "pool": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "route": {
          "$ref": "#/$defs/shape5"
        },
        "run": {
          "$ref": "#/$defs/shape5"
        },
        "scope": {
          "$ref": "#/$defs/shape48"
        },
        "to": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "project"
      ],
      "additionalProperties": false
    },
    "shape62": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
        },
        "payload": {
          "$ref": "#/$defs/shape63"
        },
        "type": {
          "$ref": "#/$defs/shape53"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape65": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "cursor": {
          "$ref": "#/$defs/shape5"
        },
        "from": {
          "$ref": "#/$defs/shape5"
        },
        "group_by": {
          "$ref": "#/$defs/shape39"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "model": {
          "$ref": "#/$defs/shape5"
        },
        "orchestration": {
          "$ref": "#/$defs/shape5"
        },
        "pool": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "route": {
          "$ref": "#/$defs/shape5"
        },
        "run": {
          "$ref": "#/$defs/shape5"
        },
        "scope": {
          "$ref": "#/$defs/shape48"
        },
        "to": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "agent"
      ],
      "additionalProperties": false
    },
    "shape64": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
        },
        "payload": {
          "$ref": "#/$defs/shape65"
        },
        "type": {
          "$ref": "#/$defs/shape54"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape67": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "cursor": {
          "$ref": "#/$defs/shape5"
        },
        "from": {
          "$ref": "#/$defs/shape5"
        },
        "group_by": {
          "$ref": "#/$defs/shape39"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "model": {
          "$ref": "#/$defs/shape5"
        },
        "orchestration": {
          "$ref": "#/$defs/shape5"
        },
        "pool": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "route": {
          "$ref": "#/$defs/shape5"
        },
        "run": {
          "$ref": "#/$defs/shape5"
        },
        "scope": {
          "$ref": "#/$defs/shape48"
        },
        "to": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "run"
      ],
      "additionalProperties": false
    },
    "shape66": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
        },
        "payload": {
          "$ref": "#/$defs/shape67"
        },
        "type": {
          "$ref": "#/$defs/shape55"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape69": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "cursor": {
          "$ref": "#/$defs/shape5"
        },
        "from": {
          "$ref": "#/$defs/shape5"
        },
        "group_by": {
          "$ref": "#/$defs/shape39"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "model": {
          "$ref": "#/$defs/shape5"
        },
        "orchestration": {
          "$ref": "#/$defs/shape5"
        },
        "pool": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "route": {
          "$ref": "#/$defs/shape5"
        },
        "run": {
          "$ref": "#/$defs/shape5"
        },
        "scope": {
          "$ref": "#/$defs/shape48"
        },
        "to": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "orchestration"
      ],
      "additionalProperties": false
    },
    "shape68": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
        },
        "payload": {
          "$ref": "#/$defs/shape69"
        },
        "type": {
          "$ref": "#/$defs/shape56"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape70": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
        },
        "payload": {
          "$ref": "#/$defs/shape38"
        },
        "type": {
          "$ref": "#/$defs/shape57"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape71": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
        },
        "payload": {
          "$ref": "#/$defs/shape38"
        },
        "type": {
          "$ref": "#/$defs/shape58"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape73": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "attempt_cursor": {
          "$ref": "#/$defs/shape5"
        },
        "call": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "cursor": {
          "$ref": "#/$defs/shape5"
        },
        "from": {
          "$ref": "#/$defs/shape5"
        },
        "group_by": {
          "$ref": "#/$defs/shape39"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "model": {
          "$ref": "#/$defs/shape5"
        },
        "orchestration": {
          "$ref": "#/$defs/shape5"
        },
        "pool": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "route": {
          "$ref": "#/$defs/shape5"
        },
        "run": {
          "$ref": "#/$defs/shape5"
        },
        "scope": {
          "$ref": "#/$defs/shape48"
        },
        "to": {
          "$ref": "#/$defs/shape5"
        }
      },
      "additionalProperties": false
    },
    "shape74": {
      "const": "usage.calls"
    },
    "shape72": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
        },
        "payload": {
          "$ref": "#/$defs/shape73"
        },
        "type": {
          "$ref": "#/$defs/shape74"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape76": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "cursor": {
          "$ref": "#/$defs/shape5"
        },
        "from": {
          "$ref": "#/$defs/shape5"
        },
        "group_by": {
          "$ref": "#/$defs/shape39"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "model": {
          "$ref": "#/$defs/shape5"
        },
        "orchestration": {
          "$ref": "#/$defs/shape5"
        },
        "pool": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "report_type": {
          "$ref": "#/$defs/shape51"
        },
        "route": {
          "$ref": "#/$defs/shape5"
        },
        "run": {
          "$ref": "#/$defs/shape5"
        },
        "scope": {
          "$ref": "#/$defs/shape48"
        },
        "to": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "report_type"
      ],
      "additionalProperties": false
    },
    "shape77": {
      "const": "usage.subscribe"
    },
    "shape75": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
        },
        "payload": {
          "$ref": "#/$defs/shape76"
        },
        "type": {
          "$ref": "#/$defs/shape77"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape79": {
      "type": "object",
      "properties": {
        "subscription": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "subscription"
      ],
      "additionalProperties": false
    },
    "shape80": {
      "const": "usage.unsubscribe"
    },
    "shape78": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
        },
        "payload": {
          "$ref": "#/$defs/shape79"
        },
        "type": {
          "$ref": "#/$defs/shape80"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape82": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape83": {
      "const": "conversation.context.count"
    },
    "shape81": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
        },
        "payload": {
          "$ref": "#/$defs/shape82"
        },
        "type": {
          "$ref": "#/$defs/shape83"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape85": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "measure": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape86": {
      "const": "conversation.context.snapshot"
    },
    "shape84": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
        },
        "payload": {
          "$ref": "#/$defs/shape85"
        },
        "type": {
          "$ref": "#/$defs/shape86"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape88": {
      "const": "usage-close"
    },
    "shape87": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape88"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape90": {
      "const": "operator-prepare"
    },
    "shape92": {
      "const": "memory-write"
    },
    "shape93": {
      "const": "memory-digest"
    },
    "shape94": {
      "const": "agent-curate"
    },
    "shape95": {
      "const": "conversation-lifecycle"
    },
    "shape96": {
      "const": "conversation-resume"
    },
    "shape97": {
      "const": "job-limits"
    },
    "shape98": {
      "const": "approval-grant"
    },
    "shape99": {
      "const": "approval-revoke"
    },
    "shape100": {
      "const": "board-topup"
    },
    "shape101": {
      "const": "message-deliveries"
    },
    "shape102": {
      "const": "message-open"
    },
    "shape103": {
      "const": "message-default"
    },
    "shape104": {
      "const": "message-stop"
    },
    "shape105": {
      "const": "message-archive"
    },
    "shape106": {
      "const": "caps"
    },
    "shape91": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape92"
        },
        {
          "$ref": "#/$defs/shape93"
        },
        {
          "$ref": "#/$defs/shape94"
        },
        {
          "$ref": "#/$defs/shape95"
        },
        {
          "$ref": "#/$defs/shape96"
        },
        {
          "$ref": "#/$defs/shape97"
        },
        {
          "$ref": "#/$defs/shape98"
        },
        {
          "$ref": "#/$defs/shape99"
        },
        {
          "$ref": "#/$defs/shape100"
        },
        {
          "$ref": "#/$defs/shape101"
        },
        {
          "$ref": "#/$defs/shape102"
        },
        {
          "$ref": "#/$defs/shape103"
        },
        {
          "$ref": "#/$defs/shape104"
        },
        {
          "$ref": "#/$defs/shape105"
        },
        {
          "$ref": "#/$defs/shape106"
        }
      ]
    },
    "shape89": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape90"
        },
        "kind": {
          "$ref": "#/$defs/shape91"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "kind"
      ],
      "additionalProperties": false
    },
    "shape108": {
      "const": "operator-messages"
    },
    "shape107": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape108"
        },
        "identity": {
          "$ref": "#/$defs/shape5"
        },
        "instance": {
          "$ref": "#/$defs/shape5"
        },
        "offset": {
          "$ref": "#/$defs/shape15"
        }
      },
      "required": [
        "action",
        "identity",
        "instance"
      ],
      "additionalProperties": false
    },
    "shape110": {
      "const": "operator-preview"
    },
    "shape113": {
      "const": "once"
    },
    "shape114": {
      "const": "conversation"
    },
    "shape115": {
      "const": "deny"
    },
    "shape112": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape46"
        },
        {
          "$ref": "#/$defs/shape113"
        },
        {
          "$ref": "#/$defs/shape114"
        },
        {
          "$ref": "#/$defs/shape115"
        }
      ]
    },
    "shape117": {
      "const": "steps"
    },
    "shape118": {
      "const": "budget"
    },
    "shape119": {
      "const": "auto-continue"
    },
    "shape120": {
      "const": "time"
    },
    "shape121": {
      "const": "failed-checks"
    },
    "shape122": {
      "const": "auto-increase"
    },
    "shape116": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape117"
        },
        {
          "$ref": "#/$defs/shape118"
        },
        {
          "$ref": "#/$defs/shape119"
        },
        {
          "$ref": "#/$defs/shape120"
        },
        {
          "$ref": "#/$defs/shape121"
        },
        {
          "$ref": "#/$defs/shape122"
        }
      ]
    },
    "shape124": {
      "const": "active"
    },
    "shape125": {
      "const": "archived"
    },
    "shape123": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape124"
        },
        {
          "$ref": "#/$defs/shape125"
        }
      ]
    },
    "shape127": {
      "const": "true"
    },
    "shape128": {
      "const": "false"
    },
    "shape126": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape127"
        },
        {
          "$ref": "#/$defs/shape128"
        }
      ]
    },
    "shape111": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "body": {
          "$ref": "#/$defs/shape5"
        },
        "decision": {
          "$ref": "#/$defs/shape112"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        },
        "key": {
          "$ref": "#/$defs/shape116"
        },
        "lifecycle": {
          "$ref": "#/$defs/shape123"
        },
        "makeDefault": {
          "$ref": "#/$defs/shape126"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape15"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape15"
        },
        "prefix": {
          "$ref": "#/$defs/shape5"
        },
        "scope": {
          "$ref": "#/$defs/shape5"
        },
        "summary": {
          "$ref": "#/$defs/shape5"
        },
        "value": {
          "$ref": "#/$defs/shape15"
        }
      },
      "additionalProperties": false
    },
    "shape109": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape110"
        },
        "identity": {
          "$ref": "#/$defs/shape5"
        },
        "input": {
          "$ref": "#/$defs/shape111"
        }
      },
      "required": [
        "action",
        "identity",
        "input"
      ],
      "additionalProperties": false
    },
    "shape130": {
      "const": "operator-apply"
    },
    "shape129": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape130"
        },
        "identity": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape132": {
      "const": "information"
    },
    "shape133": {
      "const": "upload"
    },
    "shape134": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "text": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape138": {
      "const": "personal"
    },
    "shape139": {
      "const": "shared"
    },
    "shape137": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape138"
        },
        {
          "$ref": "#/$defs/shape139"
        }
      ]
    },
    "shape136": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape137"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape140": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape46"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "kind",
        "project"
      ],
      "additionalProperties": false
    },
    "shape135": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape136"
        },
        {
          "$ref": "#/$defs/shape140"
        }
      ]
    },
    "shape131": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape133"
        },
        "payload": {
          "$ref": "#/$defs/shape134"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape142": {
      "const": "acquire"
    },
    "shape143": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "url": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "requestId",
        "url"
      ],
      "additionalProperties": false
    },
    "shape141": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape142"
        },
        "payload": {
          "$ref": "#/$defs/shape143"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape145": {
      "const": "refresh"
    },
    "shape146": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape144": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape145"
        },
        "payload": {
          "$ref": "#/$defs/shape146"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape148": {
      "const": "revise"
    },
    "shape149": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        },
        "text": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape147": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape148"
        },
        "payload": {
          "$ref": "#/$defs/shape149"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape151": {
      "const": "replace"
    },
    "shape152": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        },
        "text": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape150": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape151"
        },
        "payload": {
          "$ref": "#/$defs/shape152"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape154": {
      "const": "list"
    },
    "shape157": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape159": {
      "const": "source"
    },
    "shape160": {
      "const": "report"
    },
    "shape158": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape160"
        }
      ]
    },
    "shape156": {
      "type": "object",
      "properties": {
        "author": {
          "$ref": "#/$defs/shape5"
        },
        "autoTag": {
          "$ref": "#/$defs/shape157"
        },
        "documentAuthor": {
          "$ref": "#/$defs/shape5"
        },
        "kind": {
          "$ref": "#/$defs/shape158"
        },
        "search": {
          "$ref": "#/$defs/shape5"
        },
        "subtype": {
          "$ref": "#/$defs/shape5"
        },
        "tagGroup": {
          "$ref": "#/$defs/shape5"
        },
        "tags": {
          "$ref": "#/$defs/shape157"
        },
        "when": {
          "$ref": "#/$defs/shape5"
        }
      },
      "additionalProperties": false
    },
    "shape155": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape156"
        },
        "kind": {
          "$ref": "#/$defs/shape158"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "offset": {
          "$ref": "#/$defs/shape15"
        }
      },
      "additionalProperties": false
    },
    "shape153": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape154"
        },
        "payload": {
          "$ref": "#/$defs/shape155"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape162": {
      "const": "facets"
    },
    "shape163": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape156"
        },
        "kind": {
          "$ref": "#/$defs/shape158"
        }
      },
      "additionalProperties": false
    },
    "shape161": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape162"
        },
        "payload": {
          "$ref": "#/$defs/shape163"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape165": {
      "const": "tags"
    },
    "shape166": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        },
        "tags": {
          "$ref": "#/$defs/shape157"
        }
      },
      "required": [
        "requestId",
        "revision",
        "tags"
      ],
      "additionalProperties": false
    },
    "shape164": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape165"
        },
        "payload": {
          "$ref": "#/$defs/shape166"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape168": {
      "const": "tags.groups"
    },
    "shape171": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape157"
      }
    },
    "shape170": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape17"
        },
        {
          "$ref": "#/$defs/shape171"
        }
      ]
    },
    "shape169": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape170"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "groups",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape167": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape168"
        },
        "payload": {
          "$ref": "#/$defs/shape169"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape173": {
      "const": "inventory"
    },
    "shape174": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "offset": {
          "$ref": "#/$defs/shape15"
        }
      },
      "additionalProperties": false
    },
    "shape172": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape173"
        },
        "payload": {
          "$ref": "#/$defs/shape174"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape176": {
      "const": "acquisitions"
    },
    "shape177": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "offset": {
          "$ref": "#/$defs/shape15"
        }
      },
      "additionalProperties": false
    },
    "shape175": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape176"
        },
        "payload": {
          "$ref": "#/$defs/shape177"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape179": {
      "const": "status"
    },
    "shape180": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "additionalProperties": false
    },
    "shape178": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape179"
        },
        "payload": {
          "$ref": "#/$defs/shape180"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape182": {
      "const": "await"
    },
    "shape187": {
      "forbidden": true
    },
    "shape186": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape187"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape188": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape187"
        }
      },
      "required": [
        "acquisition"
      ],
      "additionalProperties": false
    },
    "shape185": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape186"
        },
        {
          "$ref": "#/$defs/shape188"
        }
      ]
    },
    "shape184": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape185"
      }
    },
    "shape183": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape184"
        },
        "waitMs": {
          "$ref": "#/$defs/shape15"
        }
      },
      "required": [
        "sources"
      ],
      "additionalProperties": false
    },
    "shape181": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape182"
        },
        "payload": {
          "$ref": "#/$defs/shape183"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape190": {
      "const": "read"
    },
    "shape191": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "offset": {
          "$ref": "#/$defs/shape15"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape189": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape190"
        },
        "payload": {
          "$ref": "#/$defs/shape191"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape193": {
      "const": "outline"
    },
    "shape194": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "offset": {
          "$ref": "#/$defs/shape15"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape192": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape193"
        },
        "payload": {
          "$ref": "#/$defs/shape194"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape196": {
      "const": "symbols"
    },
    "shape197": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "offset": {
          "$ref": "#/$defs/shape15"
        },
        "query": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape195": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape196"
        },
        "payload": {
          "$ref": "#/$defs/shape197"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape199": {
      "const": "search"
    },
    "shape200": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape156"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "query": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape198": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape199"
        },
        "payload": {
          "$ref": "#/$defs/shape200"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape202": {
      "const": "rank"
    },
    "shape203": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape156"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "query": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape201": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape202"
        },
        "payload": {
          "$ref": "#/$defs/shape203"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape205": {
      "const": "ask"
    },
    "shape206": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape15"
        },
        "question": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "question",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape204": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape205"
        },
        "payload": {
          "$ref": "#/$defs/shape206"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape208": {
      "const": "evidence.record"
    },
    "shape209": {
      "type": "object",
      "properties": {
        "end": {
          "$ref": "#/$defs/shape15"
        },
        "locator": {
          "$ref": "#/$defs/shape5"
        },
        "quote": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        },
        "start": {
          "$ref": "#/$defs/shape15"
        }
      },
      "required": [
        "end",
        "locator",
        "quote",
        "requestId",
        "revision",
        "start"
      ],
      "additionalProperties": false
    },
    "shape207": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape208"
        },
        "payload": {
          "$ref": "#/$defs/shape209"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape211": {
      "const": "evidence.read"
    },
    "shape212": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "evidence"
      ],
      "additionalProperties": false
    },
    "shape210": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape211"
        },
        "payload": {
          "$ref": "#/$defs/shape212"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape214": {
      "const": "record.report"
    },
    "shape219": {
      "const": "holds"
    },
    "shape220": {
      "const": "weakened"
    },
    "shape221": {
      "const": "refuted"
    },
    "shape222": {
      "const": "not_checked"
    },
    "shape218": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape219"
        },
        {
          "$ref": "#/$defs/shape220"
        },
        {
          "$ref": "#/$defs/shape221"
        },
        {
          "$ref": "#/$defs/shape222"
        }
      ]
    },
    "shape217": {
      "type": "object",
      "properties": {
        "claim": {
          "$ref": "#/$defs/shape5"
        },
        "counterEvidence": {
          "$ref": "#/$defs/shape157"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        },
        "objective": {
          "$ref": "#/$defs/shape5"
        },
        "rationale": {
          "$ref": "#/$defs/shape5"
        },
        "support": {
          "$ref": "#/$defs/shape157"
        },
        "verdict": {
          "$ref": "#/$defs/shape218"
        }
      },
      "required": [
        "claim",
        "id",
        "objective",
        "rationale",
        "verdict"
      ],
      "additionalProperties": false
    },
    "shape216": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape217"
      }
    },
    "shape224": {
      "type": "object",
      "properties": {
        "outcome": {
          "$ref": "#/$defs/shape5"
        },
        "stage": {
          "$ref": "#/$defs/shape5"
        },
        "text": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "outcome",
        "stage",
        "text"
      ],
      "additionalProperties": false
    },
    "shape223": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape224"
      }
    },
    "shape215": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape157"
        },
        "feedback": {
          "$ref": "#/$defs/shape5"
        },
        "findings": {
          "$ref": "#/$defs/shape216"
        },
        "inputs": {
          "$ref": "#/$defs/shape157"
        },
        "name": {
          "$ref": "#/$defs/shape5"
        },
        "objectives": {
          "$ref": "#/$defs/shape157"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "reviews": {
          "$ref": "#/$defs/shape223"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape157"
        },
        "text": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape213": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape214"
        },
        "payload": {
          "$ref": "#/$defs/shape215"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape226": {
      "const": "finalise"
    },
    "shape227": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape225": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape226"
        },
        "payload": {
          "$ref": "#/$defs/shape227"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape229": {
      "const": "link"
    },
    "shape230": {
      "type": "object",
      "properties": {
        "collectionProject": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape228": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape229"
        },
        "payload": {
          "$ref": "#/$defs/shape230"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape232": {
      "const": "unlink"
    },
    "shape233": {
      "type": "object",
      "properties": {
        "collectionProject": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape231": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape232"
        },
        "payload": {
          "$ref": "#/$defs/shape233"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape235": {
      "const": "share"
    },
    "shape236": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape234": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape235"
        },
        "payload": {
          "$ref": "#/$defs/shape236"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape238": {
      "const": "unshare"
    },
    "shape239": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape237": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape238"
        },
        "payload": {
          "$ref": "#/$defs/shape239"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape241": {
      "const": "withdraw"
    },
    "shape242": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape240": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape241"
        },
        "payload": {
          "$ref": "#/$defs/shape242"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape244": {
      "const": "exclude"
    },
    "shape245": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape243": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape244"
        },
        "payload": {
          "$ref": "#/$defs/shape245"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape247": {
      "const": "unexclude"
    },
    "shape248": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape246": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape247"
        },
        "payload": {
          "$ref": "#/$defs/shape248"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape250": {
      "const": "restore"
    },
    "shape251": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape249": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape250"
        },
        "payload": {
          "$ref": "#/$defs/shape251"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape253": {
      "const": "delete"
    },
    "shape254": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape252": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape253"
        },
        "payload": {
          "$ref": "#/$defs/shape254"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape256": {
      "const": "retry"
    },
    "shape257": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "additionalProperties": false
    },
    "shape255": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape256"
        },
        "payload": {
          "$ref": "#/$defs/shape257"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape259": {
      "const": "rebuild"
    },
    "shape260": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        },
        "stage": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "requestId",
        "revision",
        "stage"
      ],
      "additionalProperties": false
    },
    "shape258": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape259"
        },
        "payload": {
          "$ref": "#/$defs/shape260"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape262": {
      "const": "allowance"
    },
    "shape263": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape15"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "maxModelCalls",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape261": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape262"
        },
        "payload": {
          "$ref": "#/$defs/shape263"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape265": {
      "const": "events"
    },
    "shape266": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape15"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        }
      },
      "additionalProperties": false
    },
    "shape264": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape265"
        },
        "payload": {
          "$ref": "#/$defs/shape266"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape268": {
      "const": "migration.list"
    },
    "shape269": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "offset": {
          "$ref": "#/$defs/shape15"
        }
      },
      "additionalProperties": false
    },
    "shape267": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape268"
        },
        "payload": {
          "$ref": "#/$defs/shape269"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape271": {
      "const": "migration.adopt"
    },
    "shape272": {
      "type": "object",
      "properties": {
        "collectionProject": {
          "$ref": "#/$defs/shape5"
        },
        "owner": {
          "$ref": "#/$defs/shape5"
        },
        "reason": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        },
        "visibility": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "owner",
        "reason",
        "requestId",
        "revision",
        "visibility"
      ],
      "additionalProperties": false
    },
    "shape270": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape271"
        },
        "payload": {
          "$ref": "#/$defs/shape272"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape274": {
      "const": "migration.inspect"
    },
    "shape275": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape15"
        },
        "offset": {
          "$ref": "#/$defs/shape15"
        },
        "payload": {
          "$ref": "#/$defs/shape5"
        },
        "reason": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "payload",
        "reason"
      ],
      "additionalProperties": false
    },
    "shape273": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape274"
        },
        "payload": {
          "$ref": "#/$defs/shape275"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape277": {
      "const": "migration.release"
    },
    "shape278": {
      "type": "object",
      "properties": {
        "inputs": {
          "$ref": "#/$defs/shape157"
        },
        "owner": {
          "$ref": "#/$defs/shape5"
        },
        "payload": {
          "$ref": "#/$defs/shape5"
        },
        "reason": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "owner",
        "payload",
        "reason",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape276": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "operation": {
          "$ref": "#/$defs/shape277"
        },
        "payload": {
          "$ref": "#/$defs/shape278"
        },
        "scope": {
          "$ref": "#/$defs/shape135"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape281": {
      "const": "bootstrap"
    },
    "shape282": {
      "const": "demo"
    },
    "shape283": {
      "const": "disconnect"
    },
    "shape284": {
      "const": "approvals-refresh"
    },
    "shape280": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape145"
        },
        {
          "$ref": "#/$defs/shape281"
        },
        {
          "$ref": "#/$defs/shape282"
        },
        {
          "$ref": "#/$defs/shape283"
        },
        {
          "$ref": "#/$defs/shape284"
        }
      ]
    },
    "shape279": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape280"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape286": {
      "const": "project-access"
    },
    "shape288": {
      "const": "project.access"
    },
    "shape289": {
      "const": "project.member.add"
    },
    "shape290": {
      "const": "project.member.remove"
    },
    "shape291": {
      "const": "project.member.role"
    },
    "shape287": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape288"
        },
        {
          "$ref": "#/$defs/shape289"
        },
        {
          "$ref": "#/$defs/shape290"
        },
        {
          "$ref": "#/$defs/shape291"
        }
      ]
    },
    "shape293": {
      "const": "VIEWER"
    },
    "shape294": {
      "const": "CONTRIBUTOR"
    },
    "shape295": {
      "const": "MANAGER"
    },
    "shape292": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape293"
        },
        {
          "$ref": "#/$defs/shape294"
        },
        {
          "$ref": "#/$defs/shape295"
        }
      ]
    },
    "shape285": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape286"
        },
        "handle": {
          "$ref": "#/$defs/shape5"
        },
        "operation": {
          "$ref": "#/$defs/shape287"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "role": {
          "$ref": "#/$defs/shape292"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape297": {
      "const": "server-admin"
    },
    "shape298": {
      "const": "admin.pricing.list"
    },
    "shape299": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape187"
      }
    },
    "shape296": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape298"
        },
        "payload": {
          "$ref": "#/$defs/shape299"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape301": {
      "const": "admin.pricing.set"
    },
    "shape304": {
      "const": "TOKEN"
    },
    "shape305": {
      "const": "INCLUDED"
    },
    "shape306": {
      "const": "ZERO_RATE"
    },
    "shape307": {
      "const": "UNPRICED"
    },
    "shape303": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape304"
        },
        {
          "$ref": "#/$defs/shape305"
        },
        {
          "$ref": "#/$defs/shape306"
        },
        {
          "$ref": "#/$defs/shape307"
        }
      ]
    },
    "shape308": {
      "type": "object",
      "properties": {
        "cacheRead": {
          "$ref": "#/$defs/shape5"
        },
        "cacheWrite": {
          "$ref": "#/$defs/shape5"
        },
        "input": {
          "$ref": "#/$defs/shape5"
        },
        "output": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "input",
        "output"
      ],
      "additionalProperties": false
    },
    "shape311": {
      "type": "object",
      "properties": {
        "cacheRead": {
          "$ref": "#/$defs/shape5"
        },
        "cacheWrite": {
          "$ref": "#/$defs/shape5"
        },
        "input": {
          "$ref": "#/$defs/shape5"
        },
        "output": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "input",
        "output"
      ],
      "additionalProperties": false
    },
    "shape310": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape15"
        },
        "rates": {
          "$ref": "#/$defs/shape311"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape309": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape310"
      }
    },
    "shape302": {
      "type": "object",
      "properties": {
        "billingRoute": {
          "$ref": "#/$defs/shape5"
        },
        "currency": {
          "$ref": "#/$defs/shape5"
        },
        "expectedVersion": {
          "$ref": "#/$defs/shape5"
        },
        "mode": {
          "$ref": "#/$defs/shape303"
        },
        "model": {
          "$ref": "#/$defs/shape5"
        },
        "rates": {
          "$ref": "#/$defs/shape308"
        },
        "requestFee": {
          "$ref": "#/$defs/shape5"
        },
        "source": {
          "$ref": "#/$defs/shape5"
        },
        "tiers": {
          "$ref": "#/$defs/shape309"
        }
      },
      "required": [
        "billingRoute",
        "expectedVersion",
        "mode",
        "model"
      ],
      "additionalProperties": false
    },
    "shape300": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape301"
        },
        "payload": {
          "$ref": "#/$defs/shape302"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape313": {
      "const": "admin.accounts"
    },
    "shape312": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape313"
        },
        "payload": {
          "$ref": "#/$defs/shape299"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape315": {
      "const": "admin.account.create"
    },
    "shape316": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape5"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape314": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape315"
        },
        "payload": {
          "$ref": "#/$defs/shape316"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape318": {
      "const": "admin.account.update"
    },
    "shape319": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape6"
        },
        "handle": {
          "$ref": "#/$defs/shape5"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape317": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape318"
        },
        "payload": {
          "$ref": "#/$defs/shape319"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape321": {
      "const": "admin.account.reset"
    },
    "shape322": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape320": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape321"
        },
        "payload": {
          "$ref": "#/$defs/shape322"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape324": {
      "const": "admin.sessions"
    },
    "shape325": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape323": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape324"
        },
        "payload": {
          "$ref": "#/$defs/shape325"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape327": {
      "const": "admin.session.revoke"
    },
    "shape328": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape326": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape327"
        },
        "payload": {
          "$ref": "#/$defs/shape328"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape330": {
      "const": "admin.audit"
    },
    "shape331": {
      "type": "object",
      "properties": {
        "before": {
          "$ref": "#/$defs/shape15"
        },
        "handle": {
          "$ref": "#/$defs/shape5"
        },
        "limit": {
          "$ref": "#/$defs/shape15"
        }
      },
      "additionalProperties": false
    },
    "shape329": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape330"
        },
        "payload": {
          "$ref": "#/$defs/shape331"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape333": {
      "const": "admin.service.accounts"
    },
    "shape332": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape333"
        },
        "payload": {
          "$ref": "#/$defs/shape299"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape335": {
      "const": "admin.service.account.create"
    },
    "shape336": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape334": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape335"
        },
        "payload": {
          "$ref": "#/$defs/shape336"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape338": {
      "const": "admin.service.account.update"
    },
    "shape339": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape6"
        },
        "handle": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "enabled",
        "handle"
      ],
      "additionalProperties": false
    },
    "shape337": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape338"
        },
        "payload": {
          "$ref": "#/$defs/shape339"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape341": {
      "const": "admin.service.tokens"
    },
    "shape342": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape340": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape341"
        },
        "payload": {
          "$ref": "#/$defs/shape342"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape344": {
      "const": "admin.service.token.create"
    },
    "shape347": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "role": {
          "$ref": "#/$defs/shape292"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape346": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape347"
      }
    },
    "shape345": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape15"
        },
        "handle": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape5"
        },
        "scopes": {
          "$ref": "#/$defs/shape346"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape343": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape344"
        },
        "payload": {
          "$ref": "#/$defs/shape345"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape349": {
      "const": "admin.service.token.rotate"
    },
    "shape350": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape15"
        },
        "handle": {
          "$ref": "#/$defs/shape5"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "handle",
        "id"
      ],
      "additionalProperties": false
    },
    "shape348": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape349"
        },
        "payload": {
          "$ref": "#/$defs/shape350"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape352": {
      "const": "admin.service.token.revoke"
    },
    "shape353": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape5"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "handle",
        "id"
      ],
      "additionalProperties": false
    },
    "shape351": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape297"
        },
        "operation": {
          "$ref": "#/$defs/shape352"
        },
        "payload": {
          "$ref": "#/$defs/shape353"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape355": {
      "const": "server-project-create"
    },
    "shape357": {
      "const": "MANAGED"
    },
    "shape358": {
      "const": "DISJOINT"
    },
    "shape356": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape357"
        },
        {
          "$ref": "#/$defs/shape358"
        }
      ]
    },
    "shape359": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape354": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape355"
        },
        "name": {
          "$ref": "#/$defs/shape5"
        },
        "type": {
          "$ref": "#/$defs/shape356"
        },
        "workspace": {
          "$ref": "#/$defs/shape5"
        },
        "writePaths": {
          "$ref": "#/$defs/shape359"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape361": {
      "const": "server-setup"
    },
    "shape360": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape361"
        },
        "base": {
          "$ref": "#/$defs/shape5"
        },
        "handle": {
          "$ref": "#/$defs/shape5"
        },
        "password": {
          "$ref": "#/$defs/shape5"
        },
        "temporaryPassword": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "base",
        "handle",
        "password",
        "temporaryPassword"
      ],
      "additionalProperties": false
    },
    "shape363": {
      "const": "connect"
    },
    "shape362": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape363"
        },
        "base": {
          "$ref": "#/$defs/shape5"
        },
        "handle": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape5"
        },
        "password": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "base",
        "handle",
        "password"
      ],
      "additionalProperties": false
    },
    "shape365": {
      "const": "connection-select"
    },
    "shape364": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape365"
        },
        "name": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape367": {
      "const": "connection-preferences"
    },
    "shape369": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape370": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape368": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape369"
        },
        "drafts": {
          "$ref": "#/$defs/shape369"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape370"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape370"
        },
        "scope": {
          "$ref": "#/$defs/shape5"
        },
        "selected": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "drafts",
        "scope",
        "selected"
      ],
      "additionalProperties": false
    },
    "shape366": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape5"
        },
        "action": {
          "$ref": "#/$defs/shape367"
        },
        "preference": {
          "$ref": "#/$defs/shape368"
        },
        "server": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "account",
        "action",
        "preference",
        "server"
      ],
      "additionalProperties": false
    },
    "shape372": {
      "const": "connection-rename"
    },
    "shape371": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape372"
        },
        "name": {
          "$ref": "#/$defs/shape5"
        },
        "nextName": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "name",
        "nextName"
      ],
      "additionalProperties": false
    },
    "shape374": {
      "const": "connection-remove"
    },
    "shape373": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape374"
        },
        "name": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape376": {
      "const": "personal-section"
    },
    "shape378": {
      "const": "In"
    },
    "shape379": {
      "const": "Out"
    },
    "shape380": {
      "const": "Resources"
    },
    "shape381": {
      "const": "Archive"
    },
    "shape382": {
      "const": "Planning"
    },
    "shape383": {
      "const": "Bots"
    },
    "shape377": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape378"
        },
        {
          "$ref": "#/$defs/shape379"
        },
        {
          "$ref": "#/$defs/shape380"
        },
        {
          "$ref": "#/$defs/shape381"
        },
        {
          "$ref": "#/$defs/shape382"
        },
        {
          "$ref": "#/$defs/shape383"
        }
      ]
    },
    "shape375": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape376"
        },
        "path": {
          "$ref": "#/$defs/shape5"
        },
        "section": {
          "$ref": "#/$defs/shape377"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape385": {
      "const": "personal-bots"
    },
    "shape384": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape385"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape387": {
      "const": "personal-recreate"
    },
    "shape386": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape387"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape389": {
      "const": "files-choose"
    },
    "shape388": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape389"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape391": {
      "const": "files-withdraw"
    },
    "shape390": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape391"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape393": {
      "const": "sync-refresh"
    },
    "shape392": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape393"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape395": {
      "const": "sync-inspect"
    },
    "shape394": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape395"
        },
        "path": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "path",
        "project"
      ],
      "additionalProperties": false
    },
    "shape397": {
      "const": "sync-change"
    },
    "shape399": {
      "const": "on"
    },
    "shape400": {
      "const": "off"
    },
    "shape401": {
      "const": "now"
    },
    "shape398": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape399"
        },
        {
          "$ref": "#/$defs/shape400"
        },
        {
          "$ref": "#/$defs/shape401"
        }
      ]
    },
    "shape396": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape397"
        },
        "identity": {
          "$ref": "#/$defs/shape5"
        },
        "kind": {
          "$ref": "#/$defs/shape398"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "identity",
        "kind",
        "project"
      ],
      "additionalProperties": false
    },
    "shape403": {
      "const": "sync-resolve"
    },
    "shape405": {
      "const": "mine"
    },
    "shape406": {
      "const": "theirs"
    },
    "shape407": {
      "const": "done"
    },
    "shape404": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape405"
        },
        {
          "$ref": "#/$defs/shape406"
        },
        {
          "$ref": "#/$defs/shape407"
        }
      ]
    },
    "shape402": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape403"
        },
        "how": {
          "$ref": "#/$defs/shape404"
        },
        "identity": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "text": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "how",
        "identity",
        "project"
      ],
      "additionalProperties": false
    },
    "shape410": {
      "const": "project-open"
    },
    "shape411": {
      "const": "project-remove"
    },
    "shape409": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape410"
        },
        {
          "$ref": "#/$defs/shape411"
        }
      ]
    },
    "shape408": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape409"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape414": {
      "const": "scope"
    },
    "shape415": {
      "const": "create"
    },
    "shape413": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape414"
        },
        {
          "$ref": "#/$defs/shape415"
        }
      ]
    },
    "shape412": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape413"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape417": {
      "const": "history"
    },
    "shape416": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape417"
        },
        "before": {
          "$ref": "#/$defs/shape15"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape419": {
      "const": "select"
    },
    "shape418": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape419"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape421": {
      "const": "trajectory"
    },
    "shape420": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape421"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape423": {
      "const": "board-post-topics"
    },
    "shape422": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape423"
        },
        "more": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape425": {
      "const": "board-create"
    },
    "shape424": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape425"
        },
        "body": {
          "$ref": "#/$defs/shape5"
        },
        "label": {
          "$ref": "#/$defs/shape5"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape15"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "title": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "body",
        "label",
        "project",
        "requestId",
        "title"
      ],
      "additionalProperties": false
    },
    "shape427": {
      "const": "board-retry"
    },
    "shape426": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape427"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape15"
        },
        "member": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "reconcile": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "topic": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "maxTurns",
        "member",
        "project",
        "requestId",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape429": {
      "const": "board-post"
    },
    "shape428": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape429"
        },
        "body": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "topic": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "body",
        "project",
        "requestId",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape431": {
      "const": "workspace-chat"
    },
    "shape430": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape431"
        },
        "manage": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape433": {
      "const": "workspace-layout"
    },
    "shape432": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape433"
        },
        "height": {
          "$ref": "#/$defs/shape15"
        },
        "visible": {
          "$ref": "#/$defs/shape6"
        },
        "width": {
          "$ref": "#/$defs/shape15"
        },
        "x": {
          "$ref": "#/$defs/shape15"
        },
        "y": {
          "$ref": "#/$defs/shape15"
        }
      },
      "required": [
        "action",
        "height",
        "visible",
        "width",
        "x",
        "y"
      ],
      "additionalProperties": false
    },
    "shape435": {
      "const": "workspace-refresh"
    },
    "shape434": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape435"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape437": {
      "const": "library"
    },
    "shape439": {
      "const": "sources"
    },
    "shape440": {
      "const": "documents"
    },
    "shape441": {
      "const": "memories"
    },
    "shape442": {
      "const": "manual"
    },
    "shape438": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape199"
        },
        {
          "$ref": "#/$defs/shape439"
        },
        {
          "$ref": "#/$defs/shape440"
        },
        {
          "$ref": "#/$defs/shape441"
        },
        {
          "$ref": "#/$defs/shape442"
        }
      ]
    },
    "shape436": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape437"
        },
        "chapter": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape33"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        },
        "view": {
          "$ref": "#/$defs/shape438"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape444": {
      "const": "library-view"
    },
    "shape443": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape444"
        },
        "chapter": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape33"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        },
        "view": {
          "$ref": "#/$defs/shape438"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape447": {
      "const": "library-refresh"
    },
    "shape448": {
      "const": "library-citations"
    },
    "shape446": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape447"
        },
        {
          "$ref": "#/$defs/shape448"
        }
      ]
    },
    "shape445": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape446"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape450": {
      "const": "library-documents"
    },
    "shape449": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape450"
        },
        "more": {
          "$ref": "#/$defs/shape6"
        },
        "query": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "query"
      ],
      "additionalProperties": false
    },
    "shape453": {
      "const": "library-document"
    },
    "shape454": {
      "const": "library-memory"
    },
    "shape455": {
      "const": "library-chunk"
    },
    "shape456": {
      "const": "library-conversation"
    },
    "shape452": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape453"
        },
        {
          "$ref": "#/$defs/shape454"
        },
        {
          "$ref": "#/$defs/shape455"
        },
        {
          "$ref": "#/$defs/shape456"
        }
      ]
    },
    "shape451": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape452"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape458": {
      "const": "library-source-text"
    },
    "shape457": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape458"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        },
        "offset": {
          "$ref": "#/$defs/shape15"
        }
      },
      "required": [
        "action",
        "id",
        "offset"
      ],
      "additionalProperties": false
    },
    "shape460": {
      "const": "library-stance"
    },
    "shape459": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape460"
        },
        "claim": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "claim"
      ],
      "additionalProperties": false
    },
    "shape462": {
      "const": "library-search"
    },
    "shape464": {
      "const": "retrieve"
    },
    "shape465": {
      "const": "recall"
    },
    "shape466": {
      "const": "navigate"
    },
    "shape463": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape202"
        },
        {
          "$ref": "#/$defs/shape114"
        },
        {
          "$ref": "#/$defs/shape440"
        },
        {
          "$ref": "#/$defs/shape464"
        },
        {
          "$ref": "#/$defs/shape465"
        },
        {
          "$ref": "#/$defs/shape466"
        }
      ]
    },
    "shape468": {
      "const": "lexical"
    },
    "shape469": {
      "const": "semantic"
    },
    "shape470": {
      "const": "hybrid"
    },
    "shape467": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape468"
        },
        {
          "$ref": "#/$defs/shape469"
        },
        {
          "$ref": "#/$defs/shape470"
        }
      ]
    },
    "shape461": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape462"
        },
        "kind": {
          "$ref": "#/$defs/shape463"
        },
        "mode": {
          "$ref": "#/$defs/shape467"
        },
        "more": {
          "$ref": "#/$defs/shape6"
        },
        "query": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "kind",
        "mode",
        "query"
      ],
      "additionalProperties": false
    },
    "shape472": {
      "const": "library-maintain"
    },
    "shape474": {
      "const": "invalidate"
    },
    "shape475": {
      "const": "resolve"
    },
    "shape476": {
      "const": "reembed"
    },
    "shape477": {
      "const": "reconsider"
    },
    "shape473": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape474"
        },
        {
          "$ref": "#/$defs/shape475"
        },
        {
          "$ref": "#/$defs/shape476"
        },
        {
          "$ref": "#/$defs/shape477"
        }
      ]
    },
    "shape471": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape6"
        },
        "action": {
          "$ref": "#/$defs/shape472"
        },
        "identity": {
          "$ref": "#/$defs/shape5"
        },
        "kind": {
          "$ref": "#/$defs/shape473"
        },
        "reason": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "identity",
        "kind",
        "reason"
      ],
      "additionalProperties": false
    },
    "shape479": {
      "const": "builder-outputs"
    },
    "shape478": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape479"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape481": {
      "const": "builder-trajectory"
    },
    "shape480": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape481"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape483": {
      "const": "builder-prepare"
    },
    "shape482": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape483"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape485": {
      "const": "builder-start"
    },
    "shape484": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape485"
        },
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "intent": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "revision": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "agent",
        "intent",
        "project"
      ],
      "additionalProperties": false
    },
    "shape487": {
      "const": "activity"
    },
    "shape489": {
      "const": "inbox"
    },
    "shape490": {
      "const": "runs"
    },
    "shape491": {
      "const": "definitions"
    },
    "shape492": {
      "const": "schedules"
    },
    "shape493": {
      "const": "builder"
    },
    "shape488": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape489"
        },
        {
          "$ref": "#/$defs/shape490"
        },
        {
          "$ref": "#/$defs/shape491"
        },
        {
          "$ref": "#/$defs/shape492"
        },
        {
          "$ref": "#/$defs/shape493"
        }
      ]
    },
    "shape486": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape487"
        },
        "view": {
          "$ref": "#/$defs/shape488"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape495": {
      "const": "activity-view"
    },
    "shape494": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape495"
        },
        "view": {
          "$ref": "#/$defs/shape488"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape497": {
      "const": "activity-refresh"
    },
    "shape496": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape497"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape499": {
      "const": "question-refresh"
    },
    "shape498": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape499"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape501": {
      "const": "inbox-read"
    },
    "shape500": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape501"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape503": {
      "const": "inbox-more"
    },
    "shape502": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape503"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape505": {
      "const": "run-detail"
    },
    "shape504": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape505"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape507": {
      "const": "schedule-refresh"
    },
    "shape506": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape507"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape509": {
      "const": "schedule-preview"
    },
    "shape508": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "text": {
          "$ref": "#/$defs/shape5"
        },
        "zone": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "text",
        "zone"
      ],
      "additionalProperties": false
    },
    "shape511": {
      "const": "schedule-save"
    },
    "shape515": {
      "const": "skill"
    },
    "shape516": {
      "const": "orchestration"
    },
    "shape514": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape44"
        },
        {
          "$ref": "#/$defs/shape515"
        },
        {
          "$ref": "#/$defs/shape516"
        }
      ]
    },
    "shape518": {
      "const": "INHERITED"
    },
    "shape519": {
      "const": "SUMMARISED"
    },
    "shape520": {
      "const": "NEW"
    },
    "shape521": {
      "const": "DIRECT"
    },
    "shape517": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape17"
        },
        {
          "$ref": "#/$defs/shape518"
        },
        {
          "$ref": "#/$defs/shape519"
        },
        {
          "$ref": "#/$defs/shape520"
        },
        {
          "$ref": "#/$defs/shape521"
        }
      ]
    },
    "shape513": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "input": {
          "$ref": "#/$defs/shape5"
        },
        "kind": {
          "$ref": "#/$defs/shape514"
        },
        "mode": {
          "$ref": "#/$defs/shape517"
        },
        "name": {
          "$ref": "#/$defs/shape33"
        }
      },
      "required": [
        "agent",
        "input",
        "kind",
        "mode",
        "name"
      ],
      "additionalProperties": false
    },
    "shape523": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape17"
        },
        {
          "$ref": "#/$defs/shape15"
        }
      ]
    },
    "shape522": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape523"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape523"
        },
        "queueCap": {
          "$ref": "#/$defs/shape15"
        }
      },
      "required": [
        "maxModelCalls",
        "maxTurns",
        "queueCap"
      ],
      "additionalProperties": false
    },
    "shape526": {
      "const": "mailbox"
    },
    "shape527": {
      "const": "message"
    },
    "shape525": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape114"
        },
        {
          "$ref": "#/$defs/shape526"
        },
        {
          "$ref": "#/$defs/shape527"
        }
      ]
    },
    "shape524": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape33"
        },
        "kind": {
          "$ref": "#/$defs/shape525"
        },
        "project": {
          "$ref": "#/$defs/shape33"
        },
        "route": {
          "$ref": "#/$defs/shape33"
        },
        "to": {
          "$ref": "#/$defs/shape33"
        }
      },
      "required": [
        "conversation",
        "kind",
        "project",
        "route",
        "to"
      ],
      "additionalProperties": false
    },
    "shape528": {
      "const": 1
    },
    "shape512": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape513"
        },
        "cron": {
          "$ref": "#/$defs/shape5"
        },
        "limits": {
          "$ref": "#/$defs/shape522"
        },
        "paused": {
          "$ref": "#/$defs/shape6"
        },
        "target": {
          "$ref": "#/$defs/shape524"
        },
        "version": {
          "$ref": "#/$defs/shape528"
        },
        "zone": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "cron",
        "limits",
        "paused",
        "target",
        "version",
        "zone"
      ],
      "additionalProperties": false
    },
    "shape530": {
      "const": "server"
    },
    "shape531": {
      "const": "workspace"
    },
    "shape529": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape530"
        },
        {
          "$ref": "#/$defs/shape531"
        }
      ]
    },
    "shape510": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape511"
        },
        "definition": {
          "$ref": "#/$defs/shape512"
        },
        "identity": {
          "$ref": "#/$defs/shape5"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "source": {
          "$ref": "#/$defs/shape529"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape533": {
      "const": "schedule-file-save"
    },
    "shape532": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape533"
        },
        "definition": {
          "$ref": "#/$defs/shape512"
        },
        "identity": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape5"
        },
        "overwrite": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "source": {
          "$ref": "#/$defs/shape529"
        }
      },
      "required": [
        "action",
        "definition",
        "name",
        "overwrite",
        "source"
      ],
      "additionalProperties": false
    },
    "shape535": {
      "const": "schedule-sync"
    },
    "shape534": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape535"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "source": {
          "$ref": "#/$defs/shape529"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape537": {
      "const": "schedule-change"
    },
    "shape539": {
      "const": "schedule"
    },
    "shape540": {
      "const": "trigger"
    },
    "shape538": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape539"
        },
        {
          "$ref": "#/$defs/shape540"
        }
      ]
    },
    "shape536": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape537"
        },
        "identity": {
          "$ref": "#/$defs/shape5"
        },
        "kind": {
          "$ref": "#/$defs/shape538"
        },
        "name": {
          "$ref": "#/$defs/shape5"
        },
        "paused": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "identity",
        "kind",
        "name"
      ],
      "additionalProperties": false
    },
    "shape542": {
      "const": "schedule-fire"
    },
    "shape541": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape542"
        },
        "identity": {
          "$ref": "#/$defs/shape5"
        },
        "trigger": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "identity",
        "trigger"
      ],
      "additionalProperties": false
    },
    "shape544": {
      "const": "run-definitions"
    },
    "shape543": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape544"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape546": {
      "const": "run-record"
    },
    "shape545": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape546"
        },
        "before": {
          "$ref": "#/$defs/shape15"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        },
        "kinds": {
          "$ref": "#/$defs/shape359"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape548": {
      "const": "run-answer"
    },
    "shape550": {
      "type": "object",
      "properties": {
        "chosen": {
          "$ref": "#/$defs/shape157"
        },
        "header": {
          "$ref": "#/$defs/shape5"
        },
        "note": {
          "$ref": "#/$defs/shape5"
        },
        "other": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "chosen",
        "header"
      ],
      "additionalProperties": false
    },
    "shape549": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape550"
      }
    },
    "shape547": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape548"
        },
        "answer": {
          "$ref": "#/$defs/shape5"
        },
        "choices": {
          "$ref": "#/$defs/shape549"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        },
        "question": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "id",
        "question"
      ],
      "additionalProperties": false
    },
    "shape552": {
      "const": "run-cancel"
    },
    "shape551": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape552"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape554": {
      "const": "run-resume"
    },
    "shape553": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape554"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape556": {
      "const": "run-trajectory"
    },
    "shape558": {
      "const": "conductor"
    },
    "shape559": {
      "const": "caller"
    },
    "shape557": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape558"
        },
        {
          "$ref": "#/$defs/shape559"
        }
      ]
    },
    "shape555": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape556"
        },
        "actor": {
          "$ref": "#/$defs/shape557"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "actor",
        "id"
      ],
      "additionalProperties": false
    },
    "shape561": {
      "const": "run-stage-trajectory"
    },
    "shape560": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape561"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        },
        "stage": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "id",
        "stage"
      ],
      "additionalProperties": false
    },
    "shape563": {
      "const": "delegate-trajectory"
    },
    "shape562": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape563"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "step": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "conversation",
        "step"
      ],
      "additionalProperties": false
    },
    "shape566": {
      "const": "board-inspection"
    },
    "shape567": {
      "const": "board-view"
    },
    "shape565": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape566"
        },
        {
          "$ref": "#/$defs/shape567"
        }
      ]
    },
    "shape569": {
      "const": "board"
    },
    "shape570": {
      "const": "swarm"
    },
    "shape568": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape569"
        },
        {
          "$ref": "#/$defs/shape570"
        }
      ]
    },
    "shape564": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape565"
        },
        "project": {
          "$ref": "#/$defs/shape5"
        },
        "view": {
          "$ref": "#/$defs/shape568"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape573": {
      "const": "board-refresh"
    },
    "shape574": {
      "const": "board-more"
    },
    "shape572": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape573"
        },
        {
          "$ref": "#/$defs/shape574"
        }
      ]
    },
    "shape571": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape572"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape576": {
      "const": "board-topic"
    },
    "shape575": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape576"
        },
        "topic": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape578": {
      "const": "board-trajectory"
    },
    "shape577": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape578"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape580": {
      "const": "context"
    },
    "shape579": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape580"
        },
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape582": {
      "const": "open-link"
    },
    "shape581": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape582"
        },
        "url": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "url"
      ],
      "additionalProperties": false
    },
    "shape584": {
      "const": "copy-text"
    },
    "shape583": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape584"
        },
        "text": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "text"
      ],
      "additionalProperties": false
    },
    "shape586": {
      "const": "workflow-start"
    },
    "shape585": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape586"
        },
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "definition": {
          "$ref": "#/$defs/shape5"
        },
        "requestId": {
          "$ref": "#/$defs/shape5"
        },
        "text": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "agent",
        "conversation",
        "definition",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape587": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape47"
        },
        "agent": {
          "$ref": "#/$defs/shape5"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "text": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "agent",
        "conversation",
        "text"
      ],
      "additionalProperties": false
    },
    "shape589": {
      "const": "cancel"
    },
    "shape588": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape589"
        },
        "job": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "job"
      ],
      "additionalProperties": false
    },
    "shape591": {
      "const": "answer"
    },
    "shape592": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape113"
        },
        {
          "$ref": "#/$defs/shape115"
        }
      ]
    },
    "shape590": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape591"
        },
        "decision": {
          "$ref": "#/$defs/shape592"
        },
        "id": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "action",
        "decision",
        "id"
      ],
      "additionalProperties": false
    },
    "shape0": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape1"
        },
        {
          "$ref": "#/$defs/shape3"
        },
        {
          "$ref": "#/$defs/shape9"
        },
        {
          "$ref": "#/$defs/shape11"
        },
        {
          "$ref": "#/$defs/shape19"
        },
        {
          "$ref": "#/$defs/shape24"
        },
        {
          "$ref": "#/$defs/shape34"
        },
        {
          "$ref": "#/$defs/shape36"
        },
        {
          "$ref": "#/$defs/shape59"
        },
        {
          "$ref": "#/$defs/shape62"
        },
        {
          "$ref": "#/$defs/shape64"
        },
        {
          "$ref": "#/$defs/shape66"
        },
        {
          "$ref": "#/$defs/shape68"
        },
        {
          "$ref": "#/$defs/shape70"
        },
        {
          "$ref": "#/$defs/shape71"
        },
        {
          "$ref": "#/$defs/shape72"
        },
        {
          "$ref": "#/$defs/shape75"
        },
        {
          "$ref": "#/$defs/shape78"
        },
        {
          "$ref": "#/$defs/shape81"
        },
        {
          "$ref": "#/$defs/shape84"
        },
        {
          "$ref": "#/$defs/shape87"
        },
        {
          "$ref": "#/$defs/shape89"
        },
        {
          "$ref": "#/$defs/shape107"
        },
        {
          "$ref": "#/$defs/shape109"
        },
        {
          "$ref": "#/$defs/shape129"
        },
        {
          "$ref": "#/$defs/shape131"
        },
        {
          "$ref": "#/$defs/shape141"
        },
        {
          "$ref": "#/$defs/shape144"
        },
        {
          "$ref": "#/$defs/shape147"
        },
        {
          "$ref": "#/$defs/shape150"
        },
        {
          "$ref": "#/$defs/shape153"
        },
        {
          "$ref": "#/$defs/shape161"
        },
        {
          "$ref": "#/$defs/shape164"
        },
        {
          "$ref": "#/$defs/shape167"
        },
        {
          "$ref": "#/$defs/shape172"
        },
        {
          "$ref": "#/$defs/shape175"
        },
        {
          "$ref": "#/$defs/shape178"
        },
        {
          "$ref": "#/$defs/shape181"
        },
        {
          "$ref": "#/$defs/shape189"
        },
        {
          "$ref": "#/$defs/shape192"
        },
        {
          "$ref": "#/$defs/shape195"
        },
        {
          "$ref": "#/$defs/shape198"
        },
        {
          "$ref": "#/$defs/shape201"
        },
        {
          "$ref": "#/$defs/shape204"
        },
        {
          "$ref": "#/$defs/shape207"
        },
        {
          "$ref": "#/$defs/shape210"
        },
        {
          "$ref": "#/$defs/shape213"
        },
        {
          "$ref": "#/$defs/shape225"
        },
        {
          "$ref": "#/$defs/shape228"
        },
        {
          "$ref": "#/$defs/shape231"
        },
        {
          "$ref": "#/$defs/shape234"
        },
        {
          "$ref": "#/$defs/shape237"
        },
        {
          "$ref": "#/$defs/shape240"
        },
        {
          "$ref": "#/$defs/shape243"
        },
        {
          "$ref": "#/$defs/shape246"
        },
        {
          "$ref": "#/$defs/shape249"
        },
        {
          "$ref": "#/$defs/shape252"
        },
        {
          "$ref": "#/$defs/shape255"
        },
        {
          "$ref": "#/$defs/shape258"
        },
        {
          "$ref": "#/$defs/shape261"
        },
        {
          "$ref": "#/$defs/shape264"
        },
        {
          "$ref": "#/$defs/shape267"
        },
        {
          "$ref": "#/$defs/shape270"
        },
        {
          "$ref": "#/$defs/shape273"
        },
        {
          "$ref": "#/$defs/shape276"
        },
        {
          "$ref": "#/$defs/shape279"
        },
        {
          "$ref": "#/$defs/shape285"
        },
        {
          "$ref": "#/$defs/shape296"
        },
        {
          "$ref": "#/$defs/shape300"
        },
        {
          "$ref": "#/$defs/shape312"
        },
        {
          "$ref": "#/$defs/shape314"
        },
        {
          "$ref": "#/$defs/shape317"
        },
        {
          "$ref": "#/$defs/shape320"
        },
        {
          "$ref": "#/$defs/shape323"
        },
        {
          "$ref": "#/$defs/shape326"
        },
        {
          "$ref": "#/$defs/shape329"
        },
        {
          "$ref": "#/$defs/shape332"
        },
        {
          "$ref": "#/$defs/shape334"
        },
        {
          "$ref": "#/$defs/shape337"
        },
        {
          "$ref": "#/$defs/shape340"
        },
        {
          "$ref": "#/$defs/shape343"
        },
        {
          "$ref": "#/$defs/shape348"
        },
        {
          "$ref": "#/$defs/shape351"
        },
        {
          "$ref": "#/$defs/shape354"
        },
        {
          "$ref": "#/$defs/shape360"
        },
        {
          "$ref": "#/$defs/shape362"
        },
        {
          "$ref": "#/$defs/shape364"
        },
        {
          "$ref": "#/$defs/shape366"
        },
        {
          "$ref": "#/$defs/shape371"
        },
        {
          "$ref": "#/$defs/shape373"
        },
        {
          "$ref": "#/$defs/shape375"
        },
        {
          "$ref": "#/$defs/shape384"
        },
        {
          "$ref": "#/$defs/shape386"
        },
        {
          "$ref": "#/$defs/shape388"
        },
        {
          "$ref": "#/$defs/shape390"
        },
        {
          "$ref": "#/$defs/shape392"
        },
        {
          "$ref": "#/$defs/shape394"
        },
        {
          "$ref": "#/$defs/shape396"
        },
        {
          "$ref": "#/$defs/shape402"
        },
        {
          "$ref": "#/$defs/shape408"
        },
        {
          "$ref": "#/$defs/shape412"
        },
        {
          "$ref": "#/$defs/shape416"
        },
        {
          "$ref": "#/$defs/shape418"
        },
        {
          "$ref": "#/$defs/shape420"
        },
        {
          "$ref": "#/$defs/shape422"
        },
        {
          "$ref": "#/$defs/shape424"
        },
        {
          "$ref": "#/$defs/shape426"
        },
        {
          "$ref": "#/$defs/shape428"
        },
        {
          "$ref": "#/$defs/shape430"
        },
        {
          "$ref": "#/$defs/shape432"
        },
        {
          "$ref": "#/$defs/shape434"
        },
        {
          "$ref": "#/$defs/shape436"
        },
        {
          "$ref": "#/$defs/shape443"
        },
        {
          "$ref": "#/$defs/shape445"
        },
        {
          "$ref": "#/$defs/shape449"
        },
        {
          "$ref": "#/$defs/shape451"
        },
        {
          "$ref": "#/$defs/shape457"
        },
        {
          "$ref": "#/$defs/shape459"
        },
        {
          "$ref": "#/$defs/shape461"
        },
        {
          "$ref": "#/$defs/shape471"
        },
        {
          "$ref": "#/$defs/shape478"
        },
        {
          "$ref": "#/$defs/shape480"
        },
        {
          "$ref": "#/$defs/shape482"
        },
        {
          "$ref": "#/$defs/shape484"
        },
        {
          "$ref": "#/$defs/shape486"
        },
        {
          "$ref": "#/$defs/shape494"
        },
        {
          "$ref": "#/$defs/shape496"
        },
        {
          "$ref": "#/$defs/shape498"
        },
        {
          "$ref": "#/$defs/shape500"
        },
        {
          "$ref": "#/$defs/shape502"
        },
        {
          "$ref": "#/$defs/shape504"
        },
        {
          "$ref": "#/$defs/shape506"
        },
        {
          "$ref": "#/$defs/shape508"
        },
        {
          "$ref": "#/$defs/shape510"
        },
        {
          "$ref": "#/$defs/shape532"
        },
        {
          "$ref": "#/$defs/shape534"
        },
        {
          "$ref": "#/$defs/shape536"
        },
        {
          "$ref": "#/$defs/shape541"
        },
        {
          "$ref": "#/$defs/shape543"
        },
        {
          "$ref": "#/$defs/shape545"
        },
        {
          "$ref": "#/$defs/shape547"
        },
        {
          "$ref": "#/$defs/shape551"
        },
        {
          "$ref": "#/$defs/shape553"
        },
        {
          "$ref": "#/$defs/shape555"
        },
        {
          "$ref": "#/$defs/shape560"
        },
        {
          "$ref": "#/$defs/shape562"
        },
        {
          "$ref": "#/$defs/shape564"
        },
        {
          "$ref": "#/$defs/shape571"
        },
        {
          "$ref": "#/$defs/shape575"
        },
        {
          "$ref": "#/$defs/shape577"
        },
        {
          "$ref": "#/$defs/shape579"
        },
        {
          "$ref": "#/$defs/shape581"
        },
        {
          "$ref": "#/$defs/shape583"
        },
        {
          "$ref": "#/$defs/shape585"
        },
        {
          "$ref": "#/$defs/shape587"
        },
        {
          "$ref": "#/$defs/shape588"
        },
        {
          "$ref": "#/$defs/shape590"
        }
      ]
    }
  }
}
