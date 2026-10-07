// Generated from Desktop Request; run the SDK schema generator with --desktop.
import type { Schema } from 'plowshare-client-ts/operations/schema'
export const DESKTOP_SCHEMA: {request: Schema; $defs: Record<string,Schema>} = {
  "request": {
    "$ref": "#/$defs/shape0"
  },
  "$defs": {
    "shape2": {
      "const": "application-runtime"
    },
    "shape3": {
      "type": "string"
    },
    "shape1": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape2"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape5": {
      "const": "application-files"
    },
    "shape4": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape5"
        },
        "path": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape7": {
      "const": "application-file-read"
    },
    "shape6": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape7"
        },
        "path": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "path",
        "project"
      ],
      "additionalProperties": false
    },
    "shape9": {
      "const": "application-file-save"
    },
    "shape8": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape9"
        },
        "path": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        },
        "text": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "path",
        "project",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape11": {
      "const": "usage"
    },
    "shape10": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape11"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape13": {
      "const": "context-snapshot"
    },
    "shape15": {
      "const": false
    },
    "shape16": {
      "const": true
    },
    "shape14": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape15"
        },
        {
          "$ref": "#/$defs/shape16"
        }
      ]
    },
    "shape12": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape13"
        },
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "measure": {
          "$ref": "#/$defs/shape14"
        }
      },
      "required": [
        "action",
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape18": {
      "const": "relay"
    },
    "shape17": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape18"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape20": {
      "const": "relay-read"
    },
    "shape23": {
      "type": "number"
    },
    "shape22": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "system": {
          "$ref": "#/$defs/shape15"
        }
      },
      "required": [
        "project"
      ],
      "additionalProperties": false
    },
    "shape25": {
      "type": "null"
    },
    "shape24": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "project": {
          "$ref": "#/$defs/shape25"
        },
        "system": {
          "$ref": "#/$defs/shape16"
        }
      },
      "required": [
        "system"
      ],
      "additionalProperties": false
    },
    "shape21": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape22"
        },
        {
          "$ref": "#/$defs/shape24"
        }
      ]
    },
    "shape26": {
      "const": "relay.topics"
    },
    "shape19": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape20"
        },
        "payload": {
          "$ref": "#/$defs/shape21"
        },
        "type": {
          "$ref": "#/$defs/shape26"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape29": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape3"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "system": {
          "$ref": "#/$defs/shape15"
        },
        "topic": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "project",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape30": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape3"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "project": {
          "$ref": "#/$defs/shape25"
        },
        "system": {
          "$ref": "#/$defs/shape16"
        },
        "topic": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "system",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape28": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape29"
        },
        {
          "$ref": "#/$defs/shape30"
        }
      ]
    },
    "shape31": {
      "const": "relay.log"
    },
    "shape27": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape20"
        },
        "payload": {
          "$ref": "#/$defs/shape28"
        },
        "type": {
          "$ref": "#/$defs/shape31"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape33": {
      "const": "relay-operate"
    },
    "shape36": {
      "const": "ACKNOWLEDGE_GAP"
    },
    "shape37": {
      "const": "RECONCILE"
    },
    "shape38": {
      "const": "ABANDON"
    },
    "shape39": {
      "const": "REMOVE_SUBSCRIPTION"
    },
    "shape40": {
      "const": "REMOVE_TOPIC"
    },
    "shape35": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape36"
        },
        {
          "$ref": "#/$defs/shape37"
        },
        {
          "$ref": "#/$defs/shape38"
        },
        {
          "$ref": "#/$defs/shape39"
        },
        {
          "$ref": "#/$defs/shape40"
        }
      ]
    },
    "shape41": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape25"
        },
        {
          "$ref": "#/$defs/shape3"
        }
      ]
    },
    "shape34": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape35"
        },
        "deliveryId": {
          "$ref": "#/$defs/shape41"
        },
        "expectedState": {
          "$ref": "#/$defs/shape41"
        },
        "expiredThrough": {
          "$ref": "#/$defs/shape41"
        },
        "fence": {
          "$ref": "#/$defs/shape41"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "reason": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "subscriber": {
          "$ref": "#/$defs/shape41"
        },
        "subscriptionGeneration": {
          "$ref": "#/$defs/shape41"
        },
        "topic": {
          "$ref": "#/$defs/shape3"
        },
        "topicGeneration": {
          "$ref": "#/$defs/shape3"
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
    "shape32": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape33"
        },
        "payload": {
          "$ref": "#/$defs/shape34"
        }
      },
      "required": [
        "action",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape43": {
      "const": "relay-trajectory"
    },
    "shape42": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape43"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape45": {
      "const": "usage-open"
    },
    "shape49": {
      "const": "day"
    },
    "shape50": {
      "const": "model"
    },
    "shape51": {
      "const": "pool"
    },
    "shape52": {
      "const": "agent"
    },
    "shape53": {
      "const": "operation"
    },
    "shape54": {
      "const": "project"
    },
    "shape55": {
      "const": "run"
    },
    "shape48": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape49"
        },
        {
          "$ref": "#/$defs/shape50"
        },
        {
          "$ref": "#/$defs/shape51"
        },
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
        }
      ]
    },
    "shape47": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape48"
      }
    },
    "shape57": {
      "const": "direct"
    },
    "shape58": {
      "const": "subtree"
    },
    "shape56": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape57"
        },
        {
          "$ref": "#/$defs/shape58"
        }
      ]
    },
    "shape46": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "cursor": {
          "$ref": "#/$defs/shape3"
        },
        "from": {
          "$ref": "#/$defs/shape3"
        },
        "group_by": {
          "$ref": "#/$defs/shape47"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "model": {
          "$ref": "#/$defs/shape3"
        },
        "orchestration": {
          "$ref": "#/$defs/shape3"
        },
        "pool": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "route": {
          "$ref": "#/$defs/shape3"
        },
        "run": {
          "$ref": "#/$defs/shape3"
        },
        "scope": {
          "$ref": "#/$defs/shape56"
        },
        "to": {
          "$ref": "#/$defs/shape3"
        }
      },
      "additionalProperties": false
    },
    "shape60": {
      "const": "usage.conversation"
    },
    "shape61": {
      "const": "usage.project"
    },
    "shape62": {
      "const": "usage.agent"
    },
    "shape63": {
      "const": "usage.run"
    },
    "shape64": {
      "const": "usage.orchestration"
    },
    "shape65": {
      "const": "usage.models"
    },
    "shape66": {
      "const": "usage.pools"
    },
    "shape59": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape60"
        },
        {
          "$ref": "#/$defs/shape61"
        },
        {
          "$ref": "#/$defs/shape62"
        },
        {
          "$ref": "#/$defs/shape63"
        },
        {
          "$ref": "#/$defs/shape64"
        },
        {
          "$ref": "#/$defs/shape65"
        },
        {
          "$ref": "#/$defs/shape66"
        }
      ]
    },
    "shape44": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape45"
        },
        "filter": {
          "$ref": "#/$defs/shape46"
        },
        "type": {
          "$ref": "#/$defs/shape59"
        }
      },
      "required": [
        "action",
        "filter",
        "type"
      ],
      "additionalProperties": false
    },
    "shape68": {
      "const": "usage-read"
    },
    "shape69": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "cursor": {
          "$ref": "#/$defs/shape3"
        },
        "from": {
          "$ref": "#/$defs/shape3"
        },
        "group_by": {
          "$ref": "#/$defs/shape47"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "model": {
          "$ref": "#/$defs/shape3"
        },
        "orchestration": {
          "$ref": "#/$defs/shape3"
        },
        "pool": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "route": {
          "$ref": "#/$defs/shape3"
        },
        "run": {
          "$ref": "#/$defs/shape3"
        },
        "scope": {
          "$ref": "#/$defs/shape56"
        },
        "to": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape67": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape68"
        },
        "payload": {
          "$ref": "#/$defs/shape69"
        },
        "type": {
          "$ref": "#/$defs/shape60"
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
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "cursor": {
          "$ref": "#/$defs/shape3"
        },
        "from": {
          "$ref": "#/$defs/shape3"
        },
        "group_by": {
          "$ref": "#/$defs/shape47"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "model": {
          "$ref": "#/$defs/shape3"
        },
        "orchestration": {
          "$ref": "#/$defs/shape3"
        },
        "pool": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "route": {
          "$ref": "#/$defs/shape3"
        },
        "run": {
          "$ref": "#/$defs/shape3"
        },
        "scope": {
          "$ref": "#/$defs/shape56"
        },
        "to": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "project"
      ],
      "additionalProperties": false
    },
    "shape70": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape68"
        },
        "payload": {
          "$ref": "#/$defs/shape71"
        },
        "type": {
          "$ref": "#/$defs/shape61"
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
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "cursor": {
          "$ref": "#/$defs/shape3"
        },
        "from": {
          "$ref": "#/$defs/shape3"
        },
        "group_by": {
          "$ref": "#/$defs/shape47"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "model": {
          "$ref": "#/$defs/shape3"
        },
        "orchestration": {
          "$ref": "#/$defs/shape3"
        },
        "pool": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "route": {
          "$ref": "#/$defs/shape3"
        },
        "run": {
          "$ref": "#/$defs/shape3"
        },
        "scope": {
          "$ref": "#/$defs/shape56"
        },
        "to": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "agent"
      ],
      "additionalProperties": false
    },
    "shape72": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape68"
        },
        "payload": {
          "$ref": "#/$defs/shape73"
        },
        "type": {
          "$ref": "#/$defs/shape62"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape75": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "cursor": {
          "$ref": "#/$defs/shape3"
        },
        "from": {
          "$ref": "#/$defs/shape3"
        },
        "group_by": {
          "$ref": "#/$defs/shape47"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "model": {
          "$ref": "#/$defs/shape3"
        },
        "orchestration": {
          "$ref": "#/$defs/shape3"
        },
        "pool": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "route": {
          "$ref": "#/$defs/shape3"
        },
        "run": {
          "$ref": "#/$defs/shape3"
        },
        "scope": {
          "$ref": "#/$defs/shape56"
        },
        "to": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "run"
      ],
      "additionalProperties": false
    },
    "shape74": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape68"
        },
        "payload": {
          "$ref": "#/$defs/shape75"
        },
        "type": {
          "$ref": "#/$defs/shape63"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape77": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "cursor": {
          "$ref": "#/$defs/shape3"
        },
        "from": {
          "$ref": "#/$defs/shape3"
        },
        "group_by": {
          "$ref": "#/$defs/shape47"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "model": {
          "$ref": "#/$defs/shape3"
        },
        "orchestration": {
          "$ref": "#/$defs/shape3"
        },
        "pool": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "route": {
          "$ref": "#/$defs/shape3"
        },
        "run": {
          "$ref": "#/$defs/shape3"
        },
        "scope": {
          "$ref": "#/$defs/shape56"
        },
        "to": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "orchestration"
      ],
      "additionalProperties": false
    },
    "shape76": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape68"
        },
        "payload": {
          "$ref": "#/$defs/shape77"
        },
        "type": {
          "$ref": "#/$defs/shape64"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape78": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape68"
        },
        "payload": {
          "$ref": "#/$defs/shape46"
        },
        "type": {
          "$ref": "#/$defs/shape65"
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
        "action": {
          "$ref": "#/$defs/shape68"
        },
        "payload": {
          "$ref": "#/$defs/shape46"
        },
        "type": {
          "$ref": "#/$defs/shape66"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape81": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "attempt_cursor": {
          "$ref": "#/$defs/shape3"
        },
        "call": {
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "cursor": {
          "$ref": "#/$defs/shape3"
        },
        "from": {
          "$ref": "#/$defs/shape3"
        },
        "group_by": {
          "$ref": "#/$defs/shape47"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "model": {
          "$ref": "#/$defs/shape3"
        },
        "orchestration": {
          "$ref": "#/$defs/shape3"
        },
        "pool": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "route": {
          "$ref": "#/$defs/shape3"
        },
        "run": {
          "$ref": "#/$defs/shape3"
        },
        "scope": {
          "$ref": "#/$defs/shape56"
        },
        "to": {
          "$ref": "#/$defs/shape3"
        }
      },
      "additionalProperties": false
    },
    "shape82": {
      "const": "usage.calls"
    },
    "shape80": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape68"
        },
        "payload": {
          "$ref": "#/$defs/shape81"
        },
        "type": {
          "$ref": "#/$defs/shape82"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape84": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "cursor": {
          "$ref": "#/$defs/shape3"
        },
        "from": {
          "$ref": "#/$defs/shape3"
        },
        "group_by": {
          "$ref": "#/$defs/shape47"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "model": {
          "$ref": "#/$defs/shape3"
        },
        "orchestration": {
          "$ref": "#/$defs/shape3"
        },
        "pool": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "report_type": {
          "$ref": "#/$defs/shape59"
        },
        "route": {
          "$ref": "#/$defs/shape3"
        },
        "run": {
          "$ref": "#/$defs/shape3"
        },
        "scope": {
          "$ref": "#/$defs/shape56"
        },
        "to": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "report_type"
      ],
      "additionalProperties": false
    },
    "shape85": {
      "const": "usage.subscribe"
    },
    "shape83": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape68"
        },
        "payload": {
          "$ref": "#/$defs/shape84"
        },
        "type": {
          "$ref": "#/$defs/shape85"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape87": {
      "type": "object",
      "properties": {
        "subscription": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "subscription"
      ],
      "additionalProperties": false
    },
    "shape88": {
      "const": "usage.unsubscribe"
    },
    "shape86": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape68"
        },
        "payload": {
          "$ref": "#/$defs/shape87"
        },
        "type": {
          "$ref": "#/$defs/shape88"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape90": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape91": {
      "const": "conversation.context.count"
    },
    "shape89": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape68"
        },
        "payload": {
          "$ref": "#/$defs/shape90"
        },
        "type": {
          "$ref": "#/$defs/shape91"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape93": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "measure": {
          "$ref": "#/$defs/shape14"
        }
      },
      "required": [
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape94": {
      "const": "conversation.context.snapshot"
    },
    "shape92": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape68"
        },
        "payload": {
          "$ref": "#/$defs/shape93"
        },
        "type": {
          "$ref": "#/$defs/shape94"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape96": {
      "const": "usage-close"
    },
    "shape95": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape96"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape98": {
      "const": "operator-prepare"
    },
    "shape100": {
      "const": "memory-write"
    },
    "shape101": {
      "const": "memory-digest"
    },
    "shape102": {
      "const": "agent-curate"
    },
    "shape103": {
      "const": "conversation-lifecycle"
    },
    "shape104": {
      "const": "conversation-resume"
    },
    "shape105": {
      "const": "job-limits"
    },
    "shape106": {
      "const": "approval-grant"
    },
    "shape107": {
      "const": "approval-revoke"
    },
    "shape108": {
      "const": "board-topup"
    },
    "shape109": {
      "const": "message-deliveries"
    },
    "shape110": {
      "const": "message-open"
    },
    "shape111": {
      "const": "message-default"
    },
    "shape112": {
      "const": "message-stop"
    },
    "shape113": {
      "const": "message-archive"
    },
    "shape114": {
      "const": "caps"
    },
    "shape99": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape107"
        },
        {
          "$ref": "#/$defs/shape108"
        },
        {
          "$ref": "#/$defs/shape109"
        },
        {
          "$ref": "#/$defs/shape110"
        },
        {
          "$ref": "#/$defs/shape111"
        },
        {
          "$ref": "#/$defs/shape112"
        },
        {
          "$ref": "#/$defs/shape113"
        },
        {
          "$ref": "#/$defs/shape114"
        }
      ]
    },
    "shape97": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape98"
        },
        "kind": {
          "$ref": "#/$defs/shape99"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "kind"
      ],
      "additionalProperties": false
    },
    "shape116": {
      "const": "operator-messages"
    },
    "shape115": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape116"
        },
        "identity": {
          "$ref": "#/$defs/shape3"
        },
        "instance": {
          "$ref": "#/$defs/shape3"
        },
        "offset": {
          "$ref": "#/$defs/shape23"
        }
      },
      "required": [
        "action",
        "identity",
        "instance"
      ],
      "additionalProperties": false
    },
    "shape118": {
      "const": "operator-preview"
    },
    "shape121": {
      "const": "once"
    },
    "shape122": {
      "const": "conversation"
    },
    "shape123": {
      "const": "deny"
    },
    "shape120": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape54"
        },
        {
          "$ref": "#/$defs/shape121"
        },
        {
          "$ref": "#/$defs/shape122"
        },
        {
          "$ref": "#/$defs/shape123"
        }
      ]
    },
    "shape125": {
      "const": "steps"
    },
    "shape126": {
      "const": "budget"
    },
    "shape127": {
      "const": "auto-continue"
    },
    "shape128": {
      "const": "time"
    },
    "shape129": {
      "const": "failed-checks"
    },
    "shape130": {
      "const": "auto-increase"
    },
    "shape124": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape125"
        },
        {
          "$ref": "#/$defs/shape126"
        },
        {
          "$ref": "#/$defs/shape127"
        },
        {
          "$ref": "#/$defs/shape128"
        },
        {
          "$ref": "#/$defs/shape129"
        },
        {
          "$ref": "#/$defs/shape130"
        }
      ]
    },
    "shape132": {
      "const": "active"
    },
    "shape133": {
      "const": "archived"
    },
    "shape131": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape132"
        },
        {
          "$ref": "#/$defs/shape133"
        }
      ]
    },
    "shape135": {
      "const": "true"
    },
    "shape136": {
      "const": "false"
    },
    "shape134": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape135"
        },
        {
          "$ref": "#/$defs/shape136"
        }
      ]
    },
    "shape119": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "body": {
          "$ref": "#/$defs/shape3"
        },
        "decision": {
          "$ref": "#/$defs/shape120"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        },
        "key": {
          "$ref": "#/$defs/shape124"
        },
        "lifecycle": {
          "$ref": "#/$defs/shape131"
        },
        "makeDefault": {
          "$ref": "#/$defs/shape134"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape23"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape23"
        },
        "prefix": {
          "$ref": "#/$defs/shape3"
        },
        "scope": {
          "$ref": "#/$defs/shape3"
        },
        "summary": {
          "$ref": "#/$defs/shape3"
        },
        "value": {
          "$ref": "#/$defs/shape23"
        }
      },
      "additionalProperties": false
    },
    "shape117": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape118"
        },
        "identity": {
          "$ref": "#/$defs/shape3"
        },
        "input": {
          "$ref": "#/$defs/shape119"
        }
      },
      "required": [
        "action",
        "identity",
        "input"
      ],
      "additionalProperties": false
    },
    "shape138": {
      "const": "operator-apply"
    },
    "shape137": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape138"
        },
        "identity": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape140": {
      "const": "information"
    },
    "shape141": {
      "const": "upload"
    },
    "shape142": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "text": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape146": {
      "const": "personal"
    },
    "shape147": {
      "const": "shared"
    },
    "shape145": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape146"
        },
        {
          "$ref": "#/$defs/shape147"
        }
      ]
    },
    "shape144": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape14"
        },
        "kind": {
          "$ref": "#/$defs/shape145"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape148": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape14"
        },
        "kind": {
          "$ref": "#/$defs/shape54"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "kind",
        "project"
      ],
      "additionalProperties": false
    },
    "shape143": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape144"
        },
        {
          "$ref": "#/$defs/shape148"
        }
      ]
    },
    "shape139": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape141"
        },
        "payload": {
          "$ref": "#/$defs/shape142"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape150": {
      "const": "acquire"
    },
    "shape151": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "url": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "requestId",
        "url"
      ],
      "additionalProperties": false
    },
    "shape149": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape150"
        },
        "payload": {
          "$ref": "#/$defs/shape151"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape153": {
      "const": "refresh"
    },
    "shape154": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape152": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape153"
        },
        "payload": {
          "$ref": "#/$defs/shape154"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape156": {
      "const": "revise"
    },
    "shape157": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        },
        "text": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape155": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape156"
        },
        "payload": {
          "$ref": "#/$defs/shape157"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape159": {
      "const": "replace"
    },
    "shape160": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        },
        "text": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape158": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape159"
        },
        "payload": {
          "$ref": "#/$defs/shape160"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
      "const": "list"
    },
    "shape165": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape3"
      }
    },
    "shape167": {
      "const": "source"
    },
    "shape168": {
      "const": "report"
    },
    "shape166": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape167"
        },
        {
          "$ref": "#/$defs/shape168"
        }
      ]
    },
    "shape164": {
      "type": "object",
      "properties": {
        "author": {
          "$ref": "#/$defs/shape3"
        },
        "autoTag": {
          "$ref": "#/$defs/shape165"
        },
        "documentAuthor": {
          "$ref": "#/$defs/shape3"
        },
        "kind": {
          "$ref": "#/$defs/shape166"
        },
        "search": {
          "$ref": "#/$defs/shape3"
        },
        "subtype": {
          "$ref": "#/$defs/shape3"
        },
        "tagGroup": {
          "$ref": "#/$defs/shape3"
        },
        "tags": {
          "$ref": "#/$defs/shape165"
        },
        "when": {
          "$ref": "#/$defs/shape3"
        }
      },
      "additionalProperties": false
    },
    "shape163": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape164"
        },
        "kind": {
          "$ref": "#/$defs/shape166"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "offset": {
          "$ref": "#/$defs/shape23"
        }
      },
      "additionalProperties": false
    },
    "shape161": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape162"
        },
        "payload": {
          "$ref": "#/$defs/shape163"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape170": {
      "const": "facets"
    },
    "shape171": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape164"
        },
        "kind": {
          "$ref": "#/$defs/shape166"
        }
      },
      "additionalProperties": false
    },
    "shape169": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape170"
        },
        "payload": {
          "$ref": "#/$defs/shape171"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
      "const": "tags"
    },
    "shape174": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        },
        "tags": {
          "$ref": "#/$defs/shape165"
        }
      },
      "required": [
        "requestId",
        "revision",
        "tags"
      ],
      "additionalProperties": false
    },
    "shape172": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape173"
        },
        "payload": {
          "$ref": "#/$defs/shape174"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
      "const": "tags.groups"
    },
    "shape179": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape165"
      }
    },
    "shape178": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape25"
        },
        {
          "$ref": "#/$defs/shape179"
        }
      ]
    },
    "shape177": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape178"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "groups",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape175": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape176"
        },
        "payload": {
          "$ref": "#/$defs/shape177"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape181": {
      "const": "inventory"
    },
    "shape182": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "offset": {
          "$ref": "#/$defs/shape23"
        }
      },
      "additionalProperties": false
    },
    "shape180": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape181"
        },
        "payload": {
          "$ref": "#/$defs/shape182"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape184": {
      "const": "acquisitions"
    },
    "shape185": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "offset": {
          "$ref": "#/$defs/shape23"
        }
      },
      "additionalProperties": false
    },
    "shape183": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape184"
        },
        "payload": {
          "$ref": "#/$defs/shape185"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape187": {
      "const": "status"
    },
    "shape188": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "additionalProperties": false
    },
    "shape186": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape187"
        },
        "payload": {
          "$ref": "#/$defs/shape188"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
      "const": "await"
    },
    "shape195": {
      "forbidden": true
    },
    "shape194": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape195"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape196": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape195"
        }
      },
      "required": [
        "acquisition"
      ],
      "additionalProperties": false
    },
    "shape193": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape194"
        },
        {
          "$ref": "#/$defs/shape196"
        }
      ]
    },
    "shape192": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape193"
      }
    },
    "shape191": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape192"
        },
        "waitMs": {
          "$ref": "#/$defs/shape23"
        }
      },
      "required": [
        "sources"
      ],
      "additionalProperties": false
    },
    "shape189": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape190"
        },
        "payload": {
          "$ref": "#/$defs/shape191"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape198": {
      "const": "read"
    },
    "shape199": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "offset": {
          "$ref": "#/$defs/shape23"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape197": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape198"
        },
        "payload": {
          "$ref": "#/$defs/shape199"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape201": {
      "const": "outline"
    },
    "shape202": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "offset": {
          "$ref": "#/$defs/shape23"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape200": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape201"
        },
        "payload": {
          "$ref": "#/$defs/shape202"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape204": {
      "const": "symbols"
    },
    "shape205": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "offset": {
          "$ref": "#/$defs/shape23"
        },
        "query": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape203": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape204"
        },
        "payload": {
          "$ref": "#/$defs/shape205"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape207": {
      "const": "search"
    },
    "shape208": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape164"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "query": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape206": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape207"
        },
        "payload": {
          "$ref": "#/$defs/shape208"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape210": {
      "const": "rank"
    },
    "shape211": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape164"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "query": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape209": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape210"
        },
        "payload": {
          "$ref": "#/$defs/shape211"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape213": {
      "const": "ask"
    },
    "shape214": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape23"
        },
        "question": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "question",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape212": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape213"
        },
        "payload": {
          "$ref": "#/$defs/shape214"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape216": {
      "const": "evidence.record"
    },
    "shape217": {
      "type": "object",
      "properties": {
        "end": {
          "$ref": "#/$defs/shape23"
        },
        "locator": {
          "$ref": "#/$defs/shape3"
        },
        "quote": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        },
        "start": {
          "$ref": "#/$defs/shape23"
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
    "shape215": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape216"
        },
        "payload": {
          "$ref": "#/$defs/shape217"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape219": {
      "const": "evidence.read"
    },
    "shape220": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "evidence"
      ],
      "additionalProperties": false
    },
    "shape218": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape219"
        },
        "payload": {
          "$ref": "#/$defs/shape220"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape222": {
      "const": "record.report"
    },
    "shape227": {
      "const": "holds"
    },
    "shape228": {
      "const": "weakened"
    },
    "shape229": {
      "const": "refuted"
    },
    "shape230": {
      "const": "not_checked"
    },
    "shape226": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape227"
        },
        {
          "$ref": "#/$defs/shape228"
        },
        {
          "$ref": "#/$defs/shape229"
        },
        {
          "$ref": "#/$defs/shape230"
        }
      ]
    },
    "shape225": {
      "type": "object",
      "properties": {
        "claim": {
          "$ref": "#/$defs/shape3"
        },
        "counterEvidence": {
          "$ref": "#/$defs/shape165"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        },
        "objective": {
          "$ref": "#/$defs/shape3"
        },
        "rationale": {
          "$ref": "#/$defs/shape3"
        },
        "support": {
          "$ref": "#/$defs/shape165"
        },
        "verdict": {
          "$ref": "#/$defs/shape226"
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
    "shape224": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape225"
      }
    },
    "shape232": {
      "type": "object",
      "properties": {
        "outcome": {
          "$ref": "#/$defs/shape3"
        },
        "stage": {
          "$ref": "#/$defs/shape3"
        },
        "text": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "outcome",
        "stage",
        "text"
      ],
      "additionalProperties": false
    },
    "shape231": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape232"
      }
    },
    "shape223": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape165"
        },
        "feedback": {
          "$ref": "#/$defs/shape3"
        },
        "findings": {
          "$ref": "#/$defs/shape224"
        },
        "inputs": {
          "$ref": "#/$defs/shape165"
        },
        "name": {
          "$ref": "#/$defs/shape3"
        },
        "objectives": {
          "$ref": "#/$defs/shape165"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "reviews": {
          "$ref": "#/$defs/shape231"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape165"
        },
        "text": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape221": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape222"
        },
        "payload": {
          "$ref": "#/$defs/shape223"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape234": {
      "const": "finalise"
    },
    "shape235": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape233": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape234"
        },
        "payload": {
          "$ref": "#/$defs/shape235"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape237": {
      "const": "link"
    },
    "shape238": {
      "type": "object",
      "properties": {
        "collectionProject": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape236": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape237"
        },
        "payload": {
          "$ref": "#/$defs/shape238"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape240": {
      "const": "unlink"
    },
    "shape241": {
      "type": "object",
      "properties": {
        "collectionProject": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape239": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape240"
        },
        "payload": {
          "$ref": "#/$defs/shape241"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape243": {
      "const": "share"
    },
    "shape244": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape242": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape243"
        },
        "payload": {
          "$ref": "#/$defs/shape244"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape246": {
      "const": "unshare"
    },
    "shape247": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape245": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape246"
        },
        "payload": {
          "$ref": "#/$defs/shape247"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape249": {
      "const": "withdraw"
    },
    "shape250": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape248": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape249"
        },
        "payload": {
          "$ref": "#/$defs/shape250"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape252": {
      "const": "exclude"
    },
    "shape253": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape251": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape252"
        },
        "payload": {
          "$ref": "#/$defs/shape253"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape255": {
      "const": "unexclude"
    },
    "shape256": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape254": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape255"
        },
        "payload": {
          "$ref": "#/$defs/shape256"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape258": {
      "const": "restore"
    },
    "shape259": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape257": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape258"
        },
        "payload": {
          "$ref": "#/$defs/shape259"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape261": {
      "const": "delete"
    },
    "shape262": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape260": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape261"
        },
        "payload": {
          "$ref": "#/$defs/shape262"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape264": {
      "const": "retry"
    },
    "shape265": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "additionalProperties": false
    },
    "shape263": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape264"
        },
        "payload": {
          "$ref": "#/$defs/shape265"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape267": {
      "const": "rebuild"
    },
    "shape268": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        },
        "stage": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "requestId",
        "revision",
        "stage"
      ],
      "additionalProperties": false
    },
    "shape266": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape267"
        },
        "payload": {
          "$ref": "#/$defs/shape268"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape270": {
      "const": "allowance"
    },
    "shape271": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape23"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "maxModelCalls",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape269": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape270"
        },
        "payload": {
          "$ref": "#/$defs/shape271"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape273": {
      "const": "events"
    },
    "shape274": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape23"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        }
      },
      "additionalProperties": false
    },
    "shape272": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape273"
        },
        "payload": {
          "$ref": "#/$defs/shape274"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape276": {
      "const": "migration.list"
    },
    "shape277": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "offset": {
          "$ref": "#/$defs/shape23"
        }
      },
      "additionalProperties": false
    },
    "shape275": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape276"
        },
        "payload": {
          "$ref": "#/$defs/shape277"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape279": {
      "const": "migration.adopt"
    },
    "shape280": {
      "type": "object",
      "properties": {
        "collectionProject": {
          "$ref": "#/$defs/shape3"
        },
        "owner": {
          "$ref": "#/$defs/shape3"
        },
        "reason": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        },
        "visibility": {
          "$ref": "#/$defs/shape3"
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
    "shape278": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape279"
        },
        "payload": {
          "$ref": "#/$defs/shape280"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape282": {
      "const": "migration.inspect"
    },
    "shape283": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape23"
        },
        "offset": {
          "$ref": "#/$defs/shape23"
        },
        "payload": {
          "$ref": "#/$defs/shape3"
        },
        "reason": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "payload",
        "reason"
      ],
      "additionalProperties": false
    },
    "shape281": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape282"
        },
        "payload": {
          "$ref": "#/$defs/shape283"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape285": {
      "const": "migration.release"
    },
    "shape286": {
      "type": "object",
      "properties": {
        "inputs": {
          "$ref": "#/$defs/shape165"
        },
        "owner": {
          "$ref": "#/$defs/shape3"
        },
        "payload": {
          "$ref": "#/$defs/shape3"
        },
        "reason": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
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
    "shape284": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape140"
        },
        "operation": {
          "$ref": "#/$defs/shape285"
        },
        "payload": {
          "$ref": "#/$defs/shape286"
        },
        "scope": {
          "$ref": "#/$defs/shape143"
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
    "shape289": {
      "const": "bootstrap"
    },
    "shape290": {
      "const": "demo"
    },
    "shape291": {
      "const": "disconnect"
    },
    "shape292": {
      "const": "approvals-refresh"
    },
    "shape288": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape153"
        },
        {
          "$ref": "#/$defs/shape289"
        },
        {
          "$ref": "#/$defs/shape290"
        },
        {
          "$ref": "#/$defs/shape291"
        },
        {
          "$ref": "#/$defs/shape292"
        }
      ]
    },
    "shape287": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape288"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape294": {
      "const": "project-access"
    },
    "shape296": {
      "const": "project.access"
    },
    "shape297": {
      "const": "project.member.add"
    },
    "shape298": {
      "const": "project.member.remove"
    },
    "shape299": {
      "const": "project.member.role"
    },
    "shape295": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape296"
        },
        {
          "$ref": "#/$defs/shape297"
        },
        {
          "$ref": "#/$defs/shape298"
        },
        {
          "$ref": "#/$defs/shape299"
        }
      ]
    },
    "shape301": {
      "const": "VIEWER"
    },
    "shape302": {
      "const": "CONTRIBUTOR"
    },
    "shape303": {
      "const": "MANAGER"
    },
    "shape300": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape301"
        },
        {
          "$ref": "#/$defs/shape302"
        },
        {
          "$ref": "#/$defs/shape303"
        }
      ]
    },
    "shape293": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape294"
        },
        "handle": {
          "$ref": "#/$defs/shape3"
        },
        "operation": {
          "$ref": "#/$defs/shape295"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "role": {
          "$ref": "#/$defs/shape300"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape305": {
      "const": "server-admin"
    },
    "shape306": {
      "const": "admin.pricing.list"
    },
    "shape307": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape195"
      }
    },
    "shape304": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
        },
        "operation": {
          "$ref": "#/$defs/shape306"
        },
        "payload": {
          "$ref": "#/$defs/shape307"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape309": {
      "const": "admin.pricing.set"
    },
    "shape312": {
      "const": "TOKEN"
    },
    "shape313": {
      "const": "INCLUDED"
    },
    "shape314": {
      "const": "ZERO_RATE"
    },
    "shape315": {
      "const": "UNPRICED"
    },
    "shape311": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape312"
        },
        {
          "$ref": "#/$defs/shape313"
        },
        {
          "$ref": "#/$defs/shape314"
        },
        {
          "$ref": "#/$defs/shape315"
        }
      ]
    },
    "shape316": {
      "type": "object",
      "properties": {
        "cacheRead": {
          "$ref": "#/$defs/shape3"
        },
        "cacheWrite": {
          "$ref": "#/$defs/shape3"
        },
        "input": {
          "$ref": "#/$defs/shape3"
        },
        "output": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "input",
        "output"
      ],
      "additionalProperties": false
    },
    "shape319": {
      "type": "object",
      "properties": {
        "cacheRead": {
          "$ref": "#/$defs/shape3"
        },
        "cacheWrite": {
          "$ref": "#/$defs/shape3"
        },
        "input": {
          "$ref": "#/$defs/shape3"
        },
        "output": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "input",
        "output"
      ],
      "additionalProperties": false
    },
    "shape318": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape23"
        },
        "rates": {
          "$ref": "#/$defs/shape319"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape317": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape318"
      }
    },
    "shape310": {
      "type": "object",
      "properties": {
        "billingRoute": {
          "$ref": "#/$defs/shape3"
        },
        "currency": {
          "$ref": "#/$defs/shape3"
        },
        "expectedVersion": {
          "$ref": "#/$defs/shape3"
        },
        "mode": {
          "$ref": "#/$defs/shape311"
        },
        "model": {
          "$ref": "#/$defs/shape3"
        },
        "rates": {
          "$ref": "#/$defs/shape316"
        },
        "requestFee": {
          "$ref": "#/$defs/shape3"
        },
        "source": {
          "$ref": "#/$defs/shape3"
        },
        "tiers": {
          "$ref": "#/$defs/shape317"
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
    "shape308": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
        },
        "operation": {
          "$ref": "#/$defs/shape309"
        },
        "payload": {
          "$ref": "#/$defs/shape310"
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
      "const": "admin.accounts"
    },
    "shape320": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
        },
        "operation": {
          "$ref": "#/$defs/shape321"
        },
        "payload": {
          "$ref": "#/$defs/shape307"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape323": {
      "const": "admin.account.create"
    },
    "shape324": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape3"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape14"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape322": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
        },
        "operation": {
          "$ref": "#/$defs/shape323"
        },
        "payload": {
          "$ref": "#/$defs/shape324"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape326": {
      "const": "admin.account.update"
    },
    "shape327": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape14"
        },
        "handle": {
          "$ref": "#/$defs/shape3"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape14"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape325": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
        },
        "operation": {
          "$ref": "#/$defs/shape326"
        },
        "payload": {
          "$ref": "#/$defs/shape327"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape329": {
      "const": "admin.account.reset"
    },
    "shape330": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape328": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
        },
        "operation": {
          "$ref": "#/$defs/shape329"
        },
        "payload": {
          "$ref": "#/$defs/shape330"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape332": {
      "const": "admin.sessions"
    },
    "shape333": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape331": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
        },
        "operation": {
          "$ref": "#/$defs/shape332"
        },
        "payload": {
          "$ref": "#/$defs/shape333"
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
      "const": "admin.session.revoke"
    },
    "shape336": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape3"
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
          "$ref": "#/$defs/shape305"
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
      "const": "admin.audit"
    },
    "shape339": {
      "type": "object",
      "properties": {
        "before": {
          "$ref": "#/$defs/shape23"
        },
        "handle": {
          "$ref": "#/$defs/shape3"
        },
        "limit": {
          "$ref": "#/$defs/shape23"
        }
      },
      "additionalProperties": false
    },
    "shape337": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
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
      "const": "admin.service.accounts"
    },
    "shape340": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
        },
        "operation": {
          "$ref": "#/$defs/shape341"
        },
        "payload": {
          "$ref": "#/$defs/shape307"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape343": {
      "const": "admin.service.account.create"
    },
    "shape344": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape342": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
        },
        "operation": {
          "$ref": "#/$defs/shape343"
        },
        "payload": {
          "$ref": "#/$defs/shape344"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape346": {
      "const": "admin.service.account.update"
    },
    "shape347": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape14"
        },
        "handle": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "enabled",
        "handle"
      ],
      "additionalProperties": false
    },
    "shape345": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
        },
        "operation": {
          "$ref": "#/$defs/shape346"
        },
        "payload": {
          "$ref": "#/$defs/shape347"
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
      "const": "admin.service.tokens"
    },
    "shape350": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape348": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
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
      "const": "admin.service.token.create"
    },
    "shape355": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "role": {
          "$ref": "#/$defs/shape300"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape354": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape355"
      }
    },
    "shape353": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape23"
        },
        "handle": {
          "$ref": "#/$defs/shape3"
        },
        "name": {
          "$ref": "#/$defs/shape3"
        },
        "scopes": {
          "$ref": "#/$defs/shape354"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape351": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
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
    "shape357": {
      "const": "admin.service.token.rotate"
    },
    "shape358": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape23"
        },
        "handle": {
          "$ref": "#/$defs/shape3"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "handle",
        "id"
      ],
      "additionalProperties": false
    },
    "shape356": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
        },
        "operation": {
          "$ref": "#/$defs/shape357"
        },
        "payload": {
          "$ref": "#/$defs/shape358"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape360": {
      "const": "admin.service.token.revoke"
    },
    "shape361": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape3"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "handle",
        "id"
      ],
      "additionalProperties": false
    },
    "shape359": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape305"
        },
        "operation": {
          "$ref": "#/$defs/shape360"
        },
        "payload": {
          "$ref": "#/$defs/shape361"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape363": {
      "const": "server-project-create"
    },
    "shape365": {
      "const": "MANAGED"
    },
    "shape366": {
      "const": "DISJOINT"
    },
    "shape364": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape365"
        },
        {
          "$ref": "#/$defs/shape366"
        }
      ]
    },
    "shape367": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape3"
      }
    },
    "shape362": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape363"
        },
        "name": {
          "$ref": "#/$defs/shape3"
        },
        "type": {
          "$ref": "#/$defs/shape364"
        },
        "workspace": {
          "$ref": "#/$defs/shape3"
        },
        "writePaths": {
          "$ref": "#/$defs/shape367"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape369": {
      "const": "server-setup"
    },
    "shape368": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape369"
        },
        "base": {
          "$ref": "#/$defs/shape3"
        },
        "handle": {
          "$ref": "#/$defs/shape3"
        },
        "password": {
          "$ref": "#/$defs/shape3"
        },
        "temporaryPassword": {
          "$ref": "#/$defs/shape3"
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
    "shape371": {
      "const": "connect"
    },
    "shape370": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape371"
        },
        "base": {
          "$ref": "#/$defs/shape3"
        },
        "handle": {
          "$ref": "#/$defs/shape3"
        },
        "name": {
          "$ref": "#/$defs/shape3"
        },
        "password": {
          "$ref": "#/$defs/shape3"
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
    "shape373": {
      "const": "connection-select"
    },
    "shape372": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape373"
        },
        "name": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape375": {
      "const": "connection-preferences"
    },
    "shape377": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape3"
      }
    },
    "shape378": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape14"
      }
    },
    "shape376": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape377"
        },
        "drafts": {
          "$ref": "#/$defs/shape377"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape378"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape378"
        },
        "scope": {
          "$ref": "#/$defs/shape3"
        },
        "selected": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "drafts",
        "scope",
        "selected"
      ],
      "additionalProperties": false
    },
    "shape374": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape3"
        },
        "action": {
          "$ref": "#/$defs/shape375"
        },
        "preference": {
          "$ref": "#/$defs/shape376"
        },
        "server": {
          "$ref": "#/$defs/shape3"
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
    "shape380": {
      "const": "connection-rename"
    },
    "shape379": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape380"
        },
        "name": {
          "$ref": "#/$defs/shape3"
        },
        "nextName": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "name",
        "nextName"
      ],
      "additionalProperties": false
    },
    "shape382": {
      "const": "connection-remove"
    },
    "shape381": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape382"
        },
        "name": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape384": {
      "const": "personal-section"
    },
    "shape386": {
      "const": "In"
    },
    "shape387": {
      "const": "Out"
    },
    "shape388": {
      "const": "Resources"
    },
    "shape389": {
      "const": "Archive"
    },
    "shape390": {
      "const": "Planning"
    },
    "shape391": {
      "const": "Bots"
    },
    "shape385": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape386"
        },
        {
          "$ref": "#/$defs/shape387"
        },
        {
          "$ref": "#/$defs/shape388"
        },
        {
          "$ref": "#/$defs/shape389"
        },
        {
          "$ref": "#/$defs/shape390"
        },
        {
          "$ref": "#/$defs/shape391"
        }
      ]
    },
    "shape383": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape384"
        },
        "path": {
          "$ref": "#/$defs/shape3"
        },
        "section": {
          "$ref": "#/$defs/shape385"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape393": {
      "const": "personal-bots"
    },
    "shape392": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape393"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape395": {
      "const": "personal-recreate"
    },
    "shape394": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape395"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape397": {
      "const": "files-choose"
    },
    "shape396": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape397"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape399": {
      "const": "files-withdraw"
    },
    "shape398": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape399"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape401": {
      "const": "sync-refresh"
    },
    "shape400": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape401"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape403": {
      "const": "sync-inspect"
    },
    "shape402": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape403"
        },
        "path": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "path",
        "project"
      ],
      "additionalProperties": false
    },
    "shape405": {
      "const": "sync-change"
    },
    "shape407": {
      "const": "on"
    },
    "shape408": {
      "const": "off"
    },
    "shape409": {
      "const": "now"
    },
    "shape406": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape407"
        },
        {
          "$ref": "#/$defs/shape408"
        },
        {
          "$ref": "#/$defs/shape409"
        }
      ]
    },
    "shape404": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "identity": {
          "$ref": "#/$defs/shape3"
        },
        "kind": {
          "$ref": "#/$defs/shape406"
        },
        "project": {
          "$ref": "#/$defs/shape3"
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
    "shape411": {
      "const": "sync-resolve"
    },
    "shape413": {
      "const": "mine"
    },
    "shape414": {
      "const": "theirs"
    },
    "shape415": {
      "const": "done"
    },
    "shape412": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape413"
        },
        {
          "$ref": "#/$defs/shape414"
        },
        {
          "$ref": "#/$defs/shape415"
        }
      ]
    },
    "shape410": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape411"
        },
        "how": {
          "$ref": "#/$defs/shape412"
        },
        "identity": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "text": {
          "$ref": "#/$defs/shape3"
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
    "shape418": {
      "const": "project-open"
    },
    "shape419": {
      "const": "project-remove"
    },
    "shape417": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape418"
        },
        {
          "$ref": "#/$defs/shape419"
        }
      ]
    },
    "shape416": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape417"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape422": {
      "const": "scope"
    },
    "shape423": {
      "const": "create"
    },
    "shape421": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape422"
        },
        {
          "$ref": "#/$defs/shape423"
        }
      ]
    },
    "shape420": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape421"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape425": {
      "const": "history"
    },
    "shape424": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape425"
        },
        "before": {
          "$ref": "#/$defs/shape23"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape427": {
      "const": "select"
    },
    "shape426": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape427"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape429": {
      "const": "trajectory"
    },
    "shape428": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape429"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape431": {
      "const": "board-post-topics"
    },
    "shape430": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape431"
        },
        "more": {
          "$ref": "#/$defs/shape14"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape433": {
      "const": "board-create"
    },
    "shape432": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape433"
        },
        "body": {
          "$ref": "#/$defs/shape3"
        },
        "label": {
          "$ref": "#/$defs/shape3"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape23"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "title": {
          "$ref": "#/$defs/shape3"
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
    "shape435": {
      "const": "board-retry"
    },
    "shape434": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape435"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape23"
        },
        "member": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "reconcile": {
          "$ref": "#/$defs/shape14"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "topic": {
          "$ref": "#/$defs/shape3"
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
    "shape437": {
      "const": "board-post"
    },
    "shape436": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape437"
        },
        "body": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "topic": {
          "$ref": "#/$defs/shape3"
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
    "shape439": {
      "const": "workspace-chat"
    },
    "shape438": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape439"
        },
        "manage": {
          "$ref": "#/$defs/shape14"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape441": {
      "const": "workspace-layout"
    },
    "shape440": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape441"
        },
        "height": {
          "$ref": "#/$defs/shape23"
        },
        "visible": {
          "$ref": "#/$defs/shape14"
        },
        "width": {
          "$ref": "#/$defs/shape23"
        },
        "x": {
          "$ref": "#/$defs/shape23"
        },
        "y": {
          "$ref": "#/$defs/shape23"
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
    "shape443": {
      "const": "workspace-refresh"
    },
    "shape442": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape443"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape445": {
      "const": "library"
    },
    "shape447": {
      "const": "sources"
    },
    "shape448": {
      "const": "documents"
    },
    "shape449": {
      "const": "memories"
    },
    "shape450": {
      "const": "manual"
    },
    "shape446": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape207"
        },
        {
          "$ref": "#/$defs/shape447"
        },
        {
          "$ref": "#/$defs/shape448"
        },
        {
          "$ref": "#/$defs/shape449"
        },
        {
          "$ref": "#/$defs/shape450"
        }
      ]
    },
    "shape444": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape445"
        },
        "chapter": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape41"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        },
        "view": {
          "$ref": "#/$defs/shape446"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape452": {
      "const": "library-view"
    },
    "shape451": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape452"
        },
        "chapter": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape41"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
        },
        "view": {
          "$ref": "#/$defs/shape446"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape455": {
      "const": "library-refresh"
    },
    "shape456": {
      "const": "library-citations"
    },
    "shape454": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape455"
        },
        {
          "$ref": "#/$defs/shape456"
        }
      ]
    },
    "shape453": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape454"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape458": {
      "const": "library-documents"
    },
    "shape457": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape458"
        },
        "more": {
          "$ref": "#/$defs/shape14"
        },
        "query": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "query"
      ],
      "additionalProperties": false
    },
    "shape461": {
      "const": "library-document"
    },
    "shape462": {
      "const": "library-memory"
    },
    "shape463": {
      "const": "library-chunk"
    },
    "shape464": {
      "const": "library-conversation"
    },
    "shape460": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape461"
        },
        {
          "$ref": "#/$defs/shape462"
        },
        {
          "$ref": "#/$defs/shape463"
        },
        {
          "$ref": "#/$defs/shape464"
        }
      ]
    },
    "shape459": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape460"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape466": {
      "const": "library-source-text"
    },
    "shape465": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        },
        "offset": {
          "$ref": "#/$defs/shape23"
        }
      },
      "required": [
        "action",
        "id",
        "offset"
      ],
      "additionalProperties": false
    },
    "shape468": {
      "const": "library-stance"
    },
    "shape467": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape468"
        },
        "claim": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "claim"
      ],
      "additionalProperties": false
    },
    "shape470": {
      "const": "library-search"
    },
    "shape472": {
      "const": "retrieve"
    },
    "shape473": {
      "const": "recall"
    },
    "shape474": {
      "const": "navigate"
    },
    "shape471": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape210"
        },
        {
          "$ref": "#/$defs/shape122"
        },
        {
          "$ref": "#/$defs/shape448"
        },
        {
          "$ref": "#/$defs/shape472"
        },
        {
          "$ref": "#/$defs/shape473"
        },
        {
          "$ref": "#/$defs/shape474"
        }
      ]
    },
    "shape476": {
      "const": "lexical"
    },
    "shape477": {
      "const": "semantic"
    },
    "shape478": {
      "const": "hybrid"
    },
    "shape475": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape476"
        },
        {
          "$ref": "#/$defs/shape477"
        },
        {
          "$ref": "#/$defs/shape478"
        }
      ]
    },
    "shape469": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape470"
        },
        "kind": {
          "$ref": "#/$defs/shape471"
        },
        "mode": {
          "$ref": "#/$defs/shape475"
        },
        "more": {
          "$ref": "#/$defs/shape14"
        },
        "query": {
          "$ref": "#/$defs/shape3"
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
    "shape480": {
      "const": "library-maintain"
    },
    "shape482": {
      "const": "invalidate"
    },
    "shape483": {
      "const": "resolve"
    },
    "shape484": {
      "const": "reembed"
    },
    "shape485": {
      "const": "reconsider"
    },
    "shape481": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape482"
        },
        {
          "$ref": "#/$defs/shape483"
        },
        {
          "$ref": "#/$defs/shape484"
        },
        {
          "$ref": "#/$defs/shape485"
        }
      ]
    },
    "shape479": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape14"
        },
        "action": {
          "$ref": "#/$defs/shape480"
        },
        "identity": {
          "$ref": "#/$defs/shape3"
        },
        "kind": {
          "$ref": "#/$defs/shape481"
        },
        "reason": {
          "$ref": "#/$defs/shape3"
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
    "shape487": {
      "const": "builder-outputs"
    },
    "shape486": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape487"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape489": {
      "const": "builder-trajectory"
    },
    "shape488": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape489"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape491": {
      "const": "builder-prepare"
    },
    "shape490": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape491"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape493": {
      "const": "builder-start"
    },
    "shape492": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape493"
        },
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "intent": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "revision": {
          "$ref": "#/$defs/shape3"
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
    "shape495": {
      "const": "activity"
    },
    "shape497": {
      "const": "inbox"
    },
    "shape498": {
      "const": "runs"
    },
    "shape499": {
      "const": "definitions"
    },
    "shape500": {
      "const": "schedules"
    },
    "shape501": {
      "const": "builder"
    },
    "shape496": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape497"
        },
        {
          "$ref": "#/$defs/shape498"
        },
        {
          "$ref": "#/$defs/shape499"
        },
        {
          "$ref": "#/$defs/shape500"
        },
        {
          "$ref": "#/$defs/shape501"
        }
      ]
    },
    "shape494": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape495"
        },
        "view": {
          "$ref": "#/$defs/shape496"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape503": {
      "const": "activity-view"
    },
    "shape502": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape503"
        },
        "view": {
          "$ref": "#/$defs/shape496"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape505": {
      "const": "activity-refresh"
    },
    "shape504": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape505"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape507": {
      "const": "question-refresh"
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
      "const": "inbox-read"
    },
    "shape508": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape511": {
      "const": "inbox-more"
    },
    "shape510": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape511"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape513": {
      "const": "run-detail"
    },
    "shape512": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape513"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape515": {
      "const": "schedule-refresh"
    },
    "shape514": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape515"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape517": {
      "const": "schedule-preview"
    },
    "shape516": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape517"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "text": {
          "$ref": "#/$defs/shape3"
        },
        "zone": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "text",
        "zone"
      ],
      "additionalProperties": false
    },
    "shape519": {
      "const": "schedule-save"
    },
    "shape523": {
      "const": "skill"
    },
    "shape524": {
      "const": "orchestration"
    },
    "shape522": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape52"
        },
        {
          "$ref": "#/$defs/shape523"
        },
        {
          "$ref": "#/$defs/shape524"
        }
      ]
    },
    "shape526": {
      "const": "INHERITED"
    },
    "shape527": {
      "const": "SUMMARISED"
    },
    "shape528": {
      "const": "NEW"
    },
    "shape529": {
      "const": "DIRECT"
    },
    "shape525": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape25"
        },
        {
          "$ref": "#/$defs/shape526"
        },
        {
          "$ref": "#/$defs/shape527"
        },
        {
          "$ref": "#/$defs/shape528"
        },
        {
          "$ref": "#/$defs/shape529"
        }
      ]
    },
    "shape521": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "input": {
          "$ref": "#/$defs/shape3"
        },
        "kind": {
          "$ref": "#/$defs/shape522"
        },
        "mode": {
          "$ref": "#/$defs/shape525"
        },
        "name": {
          "$ref": "#/$defs/shape41"
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
    "shape531": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape25"
        },
        {
          "$ref": "#/$defs/shape23"
        }
      ]
    },
    "shape530": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape531"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape531"
        },
        "queueCap": {
          "$ref": "#/$defs/shape23"
        }
      },
      "required": [
        "maxModelCalls",
        "maxTurns",
        "queueCap"
      ],
      "additionalProperties": false
    },
    "shape534": {
      "const": "mailbox"
    },
    "shape535": {
      "const": "message"
    },
    "shape533": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape122"
        },
        {
          "$ref": "#/$defs/shape534"
        },
        {
          "$ref": "#/$defs/shape535"
        }
      ]
    },
    "shape532": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape41"
        },
        "kind": {
          "$ref": "#/$defs/shape533"
        },
        "project": {
          "$ref": "#/$defs/shape41"
        },
        "route": {
          "$ref": "#/$defs/shape41"
        },
        "to": {
          "$ref": "#/$defs/shape41"
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
    "shape536": {
      "const": 1
    },
    "shape520": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape521"
        },
        "cron": {
          "$ref": "#/$defs/shape3"
        },
        "limits": {
          "$ref": "#/$defs/shape530"
        },
        "paused": {
          "$ref": "#/$defs/shape14"
        },
        "target": {
          "$ref": "#/$defs/shape532"
        },
        "version": {
          "$ref": "#/$defs/shape536"
        },
        "zone": {
          "$ref": "#/$defs/shape3"
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
    "shape538": {
      "const": "server"
    },
    "shape539": {
      "const": "workspace"
    },
    "shape537": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape538"
        },
        {
          "$ref": "#/$defs/shape539"
        }
      ]
    },
    "shape518": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape519"
        },
        "definition": {
          "$ref": "#/$defs/shape520"
        },
        "identity": {
          "$ref": "#/$defs/shape3"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "source": {
          "$ref": "#/$defs/shape537"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape541": {
      "const": "schedule-file-save"
    },
    "shape540": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape541"
        },
        "definition": {
          "$ref": "#/$defs/shape520"
        },
        "identity": {
          "$ref": "#/$defs/shape3"
        },
        "name": {
          "$ref": "#/$defs/shape3"
        },
        "overwrite": {
          "$ref": "#/$defs/shape14"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "source": {
          "$ref": "#/$defs/shape537"
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
    "shape543": {
      "const": "schedule-sync"
    },
    "shape542": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "source": {
          "$ref": "#/$defs/shape537"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape545": {
      "const": "schedule-change"
    },
    "shape547": {
      "const": "schedule"
    },
    "shape548": {
      "const": "trigger"
    },
    "shape546": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape547"
        },
        {
          "$ref": "#/$defs/shape548"
        }
      ]
    },
    "shape544": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape545"
        },
        "identity": {
          "$ref": "#/$defs/shape3"
        },
        "kind": {
          "$ref": "#/$defs/shape546"
        },
        "name": {
          "$ref": "#/$defs/shape3"
        },
        "paused": {
          "$ref": "#/$defs/shape14"
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
    "shape550": {
      "const": "schedule-fire"
    },
    "shape549": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape550"
        },
        "identity": {
          "$ref": "#/$defs/shape3"
        },
        "trigger": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "identity",
        "trigger"
      ],
      "additionalProperties": false
    },
    "shape552": {
      "const": "run-definitions"
    },
    "shape551": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape552"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape554": {
      "const": "run-record"
    },
    "shape553": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape554"
        },
        "before": {
          "$ref": "#/$defs/shape23"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        },
        "kinds": {
          "$ref": "#/$defs/shape367"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape556": {
      "const": "run-answer"
    },
    "shape558": {
      "type": "object",
      "properties": {
        "chosen": {
          "$ref": "#/$defs/shape165"
        },
        "header": {
          "$ref": "#/$defs/shape3"
        },
        "note": {
          "$ref": "#/$defs/shape3"
        },
        "other": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "chosen",
        "header"
      ],
      "additionalProperties": false
    },
    "shape557": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape558"
      }
    },
    "shape555": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape556"
        },
        "answer": {
          "$ref": "#/$defs/shape3"
        },
        "choices": {
          "$ref": "#/$defs/shape557"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        },
        "question": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "id",
        "question"
      ],
      "additionalProperties": false
    },
    "shape560": {
      "const": "run-cancel"
    },
    "shape559": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape560"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape562": {
      "const": "run-resume"
    },
    "shape561": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape562"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape564": {
      "const": "run-trajectory"
    },
    "shape566": {
      "const": "conductor"
    },
    "shape567": {
      "const": "caller"
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
    "shape563": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape564"
        },
        "actor": {
          "$ref": "#/$defs/shape565"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "actor",
        "id"
      ],
      "additionalProperties": false
    },
    "shape569": {
      "const": "run-stage-trajectory"
    },
    "shape568": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape569"
        },
        "id": {
          "$ref": "#/$defs/shape3"
        },
        "stage": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "id",
        "stage"
      ],
      "additionalProperties": false
    },
    "shape571": {
      "const": "delegate-trajectory"
    },
    "shape570": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape571"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "step": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "conversation",
        "step"
      ],
      "additionalProperties": false
    },
    "shape574": {
      "const": "board-inspection"
    },
    "shape575": {
      "const": "board-view"
    },
    "shape573": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape574"
        },
        {
          "$ref": "#/$defs/shape575"
        }
      ]
    },
    "shape577": {
      "const": "board"
    },
    "shape578": {
      "const": "swarm"
    },
    "shape576": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape577"
        },
        {
          "$ref": "#/$defs/shape578"
        }
      ]
    },
    "shape572": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape573"
        },
        "project": {
          "$ref": "#/$defs/shape3"
        },
        "view": {
          "$ref": "#/$defs/shape576"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape581": {
      "const": "board-refresh"
    },
    "shape582": {
      "const": "board-more"
    },
    "shape580": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape581"
        },
        {
          "$ref": "#/$defs/shape582"
        }
      ]
    },
    "shape579": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape580"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape584": {
      "const": "board-topic"
    },
    "shape583": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape584"
        },
        "topic": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape586": {
      "const": "board-trajectory"
    },
    "shape585": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape586"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape588": {
      "const": "context"
    },
    "shape587": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape588"
        },
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape590": {
      "const": "open-link"
    },
    "shape589": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape590"
        },
        "url": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "url"
      ],
      "additionalProperties": false
    },
    "shape592": {
      "const": "copy-text"
    },
    "shape591": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape592"
        },
        "text": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "text"
      ],
      "additionalProperties": false
    },
    "shape594": {
      "const": "workflow-start"
    },
    "shape593": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape594"
        },
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "definition": {
          "$ref": "#/$defs/shape3"
        },
        "requestId": {
          "$ref": "#/$defs/shape3"
        },
        "text": {
          "$ref": "#/$defs/shape3"
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
    "shape595": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape55"
        },
        "agent": {
          "$ref": "#/$defs/shape3"
        },
        "conversation": {
          "$ref": "#/$defs/shape3"
        },
        "text": {
          "$ref": "#/$defs/shape3"
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
    "shape597": {
      "const": "cancel"
    },
    "shape596": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape597"
        },
        "job": {
          "$ref": "#/$defs/shape3"
        }
      },
      "required": [
        "action",
        "job"
      ],
      "additionalProperties": false
    },
    "shape599": {
      "const": "answer"
    },
    "shape600": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape121"
        },
        {
          "$ref": "#/$defs/shape123"
        }
      ]
    },
    "shape598": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape599"
        },
        "decision": {
          "$ref": "#/$defs/shape600"
        },
        "id": {
          "$ref": "#/$defs/shape3"
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
          "$ref": "#/$defs/shape4"
        },
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape10"
        },
        {
          "$ref": "#/$defs/shape12"
        },
        {
          "$ref": "#/$defs/shape17"
        },
        {
          "$ref": "#/$defs/shape19"
        },
        {
          "$ref": "#/$defs/shape27"
        },
        {
          "$ref": "#/$defs/shape32"
        },
        {
          "$ref": "#/$defs/shape42"
        },
        {
          "$ref": "#/$defs/shape44"
        },
        {
          "$ref": "#/$defs/shape67"
        },
        {
          "$ref": "#/$defs/shape70"
        },
        {
          "$ref": "#/$defs/shape72"
        },
        {
          "$ref": "#/$defs/shape74"
        },
        {
          "$ref": "#/$defs/shape76"
        },
        {
          "$ref": "#/$defs/shape78"
        },
        {
          "$ref": "#/$defs/shape79"
        },
        {
          "$ref": "#/$defs/shape80"
        },
        {
          "$ref": "#/$defs/shape83"
        },
        {
          "$ref": "#/$defs/shape86"
        },
        {
          "$ref": "#/$defs/shape89"
        },
        {
          "$ref": "#/$defs/shape92"
        },
        {
          "$ref": "#/$defs/shape95"
        },
        {
          "$ref": "#/$defs/shape97"
        },
        {
          "$ref": "#/$defs/shape115"
        },
        {
          "$ref": "#/$defs/shape117"
        },
        {
          "$ref": "#/$defs/shape137"
        },
        {
          "$ref": "#/$defs/shape139"
        },
        {
          "$ref": "#/$defs/shape149"
        },
        {
          "$ref": "#/$defs/shape152"
        },
        {
          "$ref": "#/$defs/shape155"
        },
        {
          "$ref": "#/$defs/shape158"
        },
        {
          "$ref": "#/$defs/shape161"
        },
        {
          "$ref": "#/$defs/shape169"
        },
        {
          "$ref": "#/$defs/shape172"
        },
        {
          "$ref": "#/$defs/shape175"
        },
        {
          "$ref": "#/$defs/shape180"
        },
        {
          "$ref": "#/$defs/shape183"
        },
        {
          "$ref": "#/$defs/shape186"
        },
        {
          "$ref": "#/$defs/shape189"
        },
        {
          "$ref": "#/$defs/shape197"
        },
        {
          "$ref": "#/$defs/shape200"
        },
        {
          "$ref": "#/$defs/shape203"
        },
        {
          "$ref": "#/$defs/shape206"
        },
        {
          "$ref": "#/$defs/shape209"
        },
        {
          "$ref": "#/$defs/shape212"
        },
        {
          "$ref": "#/$defs/shape215"
        },
        {
          "$ref": "#/$defs/shape218"
        },
        {
          "$ref": "#/$defs/shape221"
        },
        {
          "$ref": "#/$defs/shape233"
        },
        {
          "$ref": "#/$defs/shape236"
        },
        {
          "$ref": "#/$defs/shape239"
        },
        {
          "$ref": "#/$defs/shape242"
        },
        {
          "$ref": "#/$defs/shape245"
        },
        {
          "$ref": "#/$defs/shape248"
        },
        {
          "$ref": "#/$defs/shape251"
        },
        {
          "$ref": "#/$defs/shape254"
        },
        {
          "$ref": "#/$defs/shape257"
        },
        {
          "$ref": "#/$defs/shape260"
        },
        {
          "$ref": "#/$defs/shape263"
        },
        {
          "$ref": "#/$defs/shape266"
        },
        {
          "$ref": "#/$defs/shape269"
        },
        {
          "$ref": "#/$defs/shape272"
        },
        {
          "$ref": "#/$defs/shape275"
        },
        {
          "$ref": "#/$defs/shape278"
        },
        {
          "$ref": "#/$defs/shape281"
        },
        {
          "$ref": "#/$defs/shape284"
        },
        {
          "$ref": "#/$defs/shape287"
        },
        {
          "$ref": "#/$defs/shape293"
        },
        {
          "$ref": "#/$defs/shape304"
        },
        {
          "$ref": "#/$defs/shape308"
        },
        {
          "$ref": "#/$defs/shape320"
        },
        {
          "$ref": "#/$defs/shape322"
        },
        {
          "$ref": "#/$defs/shape325"
        },
        {
          "$ref": "#/$defs/shape328"
        },
        {
          "$ref": "#/$defs/shape331"
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
          "$ref": "#/$defs/shape342"
        },
        {
          "$ref": "#/$defs/shape345"
        },
        {
          "$ref": "#/$defs/shape348"
        },
        {
          "$ref": "#/$defs/shape351"
        },
        {
          "$ref": "#/$defs/shape356"
        },
        {
          "$ref": "#/$defs/shape359"
        },
        {
          "$ref": "#/$defs/shape362"
        },
        {
          "$ref": "#/$defs/shape368"
        },
        {
          "$ref": "#/$defs/shape370"
        },
        {
          "$ref": "#/$defs/shape372"
        },
        {
          "$ref": "#/$defs/shape374"
        },
        {
          "$ref": "#/$defs/shape379"
        },
        {
          "$ref": "#/$defs/shape381"
        },
        {
          "$ref": "#/$defs/shape383"
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
          "$ref": "#/$defs/shape398"
        },
        {
          "$ref": "#/$defs/shape400"
        },
        {
          "$ref": "#/$defs/shape402"
        },
        {
          "$ref": "#/$defs/shape404"
        },
        {
          "$ref": "#/$defs/shape410"
        },
        {
          "$ref": "#/$defs/shape416"
        },
        {
          "$ref": "#/$defs/shape420"
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
          "$ref": "#/$defs/shape438"
        },
        {
          "$ref": "#/$defs/shape440"
        },
        {
          "$ref": "#/$defs/shape442"
        },
        {
          "$ref": "#/$defs/shape444"
        },
        {
          "$ref": "#/$defs/shape451"
        },
        {
          "$ref": "#/$defs/shape453"
        },
        {
          "$ref": "#/$defs/shape457"
        },
        {
          "$ref": "#/$defs/shape459"
        },
        {
          "$ref": "#/$defs/shape465"
        },
        {
          "$ref": "#/$defs/shape467"
        },
        {
          "$ref": "#/$defs/shape469"
        },
        {
          "$ref": "#/$defs/shape479"
        },
        {
          "$ref": "#/$defs/shape486"
        },
        {
          "$ref": "#/$defs/shape488"
        },
        {
          "$ref": "#/$defs/shape490"
        },
        {
          "$ref": "#/$defs/shape492"
        },
        {
          "$ref": "#/$defs/shape494"
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
          "$ref": "#/$defs/shape512"
        },
        {
          "$ref": "#/$defs/shape514"
        },
        {
          "$ref": "#/$defs/shape516"
        },
        {
          "$ref": "#/$defs/shape518"
        },
        {
          "$ref": "#/$defs/shape540"
        },
        {
          "$ref": "#/$defs/shape542"
        },
        {
          "$ref": "#/$defs/shape544"
        },
        {
          "$ref": "#/$defs/shape549"
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
          "$ref": "#/$defs/shape559"
        },
        {
          "$ref": "#/$defs/shape561"
        },
        {
          "$ref": "#/$defs/shape563"
        },
        {
          "$ref": "#/$defs/shape568"
        },
        {
          "$ref": "#/$defs/shape570"
        },
        {
          "$ref": "#/$defs/shape572"
        },
        {
          "$ref": "#/$defs/shape579"
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
          "$ref": "#/$defs/shape589"
        },
        {
          "$ref": "#/$defs/shape591"
        },
        {
          "$ref": "#/$defs/shape593"
        },
        {
          "$ref": "#/$defs/shape595"
        },
        {
          "$ref": "#/$defs/shape596"
        },
        {
          "$ref": "#/$defs/shape598"
        }
      ]
    }
  }
}
