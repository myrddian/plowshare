// Generated from Desktop Request; run the SDK schema generator with --desktop.
import type { Schema } from 'plowshare-client-ts/operations/schema'
export const DESKTOP_SCHEMA: {request: Schema; $defs: Record<string,Schema>} = {
  "request": {
    "$ref": "#/$defs/shape0"
  },
  "$defs": {
    "shape2": {
      "const": "filestore-load"
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
      "const": "filestore-choose"
    },
    "shape3": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape4"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape6": {
      "const": "filestore-setup"
    },
    "shape7": {
      "type": "string"
    },
    "shape5": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape6"
        },
        "alias": {
          "$ref": "#/$defs/shape7"
        },
        "root": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "alias",
        "root"
      ],
      "additionalProperties": false
    },
    "shape9": {
      "const": "application-runtime"
    },
    "shape8": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape9"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape11": {
      "const": "application-files"
    },
    "shape12": {
      "type": "object",
      "properties": {
        "path": {
          "$ref": "#/$defs/shape7"
        },
        "store": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "path",
        "store"
      ],
      "additionalProperties": false
    },
    "shape10": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape11"
        },
        "location": {
          "$ref": "#/$defs/shape12"
        },
        "path": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape14": {
      "const": "application-file-read"
    },
    "shape13": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape14"
        },
        "location": {
          "$ref": "#/$defs/shape12"
        },
        "path": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "path",
        "project"
      ],
      "additionalProperties": false
    },
    "shape16": {
      "const": "application-file-save"
    },
    "shape15": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape16"
        },
        "location": {
          "$ref": "#/$defs/shape12"
        },
        "path": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        },
        "text": {
          "$ref": "#/$defs/shape7"
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
    "shape18": {
      "const": "usage"
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
      "const": "context-snapshot"
    },
    "shape22": {
      "const": false
    },
    "shape23": {
      "const": true
    },
    "shape21": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape22"
        },
        {
          "$ref": "#/$defs/shape23"
        }
      ]
    },
    "shape19": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape20"
        },
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "measure": {
          "$ref": "#/$defs/shape21"
        }
      },
      "required": [
        "action",
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape25": {
      "const": "relay"
    },
    "shape24": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape25"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape27": {
      "const": "relay-read"
    },
    "shape30": {
      "type": "number"
    },
    "shape29": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "system": {
          "$ref": "#/$defs/shape22"
        }
      },
      "required": [
        "project"
      ],
      "additionalProperties": false
    },
    "shape32": {
      "type": "null"
    },
    "shape31": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "project": {
          "$ref": "#/$defs/shape32"
        },
        "system": {
          "$ref": "#/$defs/shape23"
        }
      },
      "required": [
        "system"
      ],
      "additionalProperties": false
    },
    "shape28": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape29"
        },
        {
          "$ref": "#/$defs/shape31"
        }
      ]
    },
    "shape33": {
      "const": "relay.topics"
    },
    "shape26": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape27"
        },
        "payload": {
          "$ref": "#/$defs/shape28"
        },
        "type": {
          "$ref": "#/$defs/shape33"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape36": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape7"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "system": {
          "$ref": "#/$defs/shape22"
        },
        "topic": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "project",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape37": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape7"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "project": {
          "$ref": "#/$defs/shape32"
        },
        "system": {
          "$ref": "#/$defs/shape23"
        },
        "topic": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "system",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape35": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape36"
        },
        {
          "$ref": "#/$defs/shape37"
        }
      ]
    },
    "shape38": {
      "const": "relay.log"
    },
    "shape34": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape27"
        },
        "payload": {
          "$ref": "#/$defs/shape35"
        },
        "type": {
          "$ref": "#/$defs/shape38"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape40": {
      "const": "relay-operate"
    },
    "shape43": {
      "const": "ACKNOWLEDGE_GAP"
    },
    "shape44": {
      "const": "RECONCILE"
    },
    "shape45": {
      "const": "ABANDON"
    },
    "shape46": {
      "const": "REMOVE_SUBSCRIPTION"
    },
    "shape47": {
      "const": "REMOVE_TOPIC"
    },
    "shape42": {
      "anyOf": [
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
    "shape48": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape32"
        },
        {
          "$ref": "#/$defs/shape7"
        }
      ]
    },
    "shape41": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape42"
        },
        "deliveryId": {
          "$ref": "#/$defs/shape48"
        },
        "expectedState": {
          "$ref": "#/$defs/shape48"
        },
        "expiredThrough": {
          "$ref": "#/$defs/shape48"
        },
        "fence": {
          "$ref": "#/$defs/shape48"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "reason": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "subscriber": {
          "$ref": "#/$defs/shape48"
        },
        "subscriptionGeneration": {
          "$ref": "#/$defs/shape48"
        },
        "topic": {
          "$ref": "#/$defs/shape7"
        },
        "topicGeneration": {
          "$ref": "#/$defs/shape7"
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
    "shape39": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape40"
        },
        "payload": {
          "$ref": "#/$defs/shape41"
        }
      },
      "required": [
        "action",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape50": {
      "const": "relay-trajectory"
    },
    "shape49": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape50"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape52": {
      "const": "usage-open"
    },
    "shape56": {
      "const": "day"
    },
    "shape57": {
      "const": "model"
    },
    "shape58": {
      "const": "pool"
    },
    "shape59": {
      "const": "agent"
    },
    "shape60": {
      "const": "operation"
    },
    "shape61": {
      "const": "project"
    },
    "shape62": {
      "const": "run"
    },
    "shape55": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape56"
        },
        {
          "$ref": "#/$defs/shape57"
        },
        {
          "$ref": "#/$defs/shape58"
        },
        {
          "$ref": "#/$defs/shape59"
        },
        {
          "$ref": "#/$defs/shape60"
        },
        {
          "$ref": "#/$defs/shape61"
        },
        {
          "$ref": "#/$defs/shape62"
        }
      ]
    },
    "shape54": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape55"
      }
    },
    "shape64": {
      "const": "direct"
    },
    "shape65": {
      "const": "subtree"
    },
    "shape63": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape64"
        },
        {
          "$ref": "#/$defs/shape65"
        }
      ]
    },
    "shape53": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "cursor": {
          "$ref": "#/$defs/shape7"
        },
        "from": {
          "$ref": "#/$defs/shape7"
        },
        "group_by": {
          "$ref": "#/$defs/shape54"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "model": {
          "$ref": "#/$defs/shape7"
        },
        "orchestration": {
          "$ref": "#/$defs/shape7"
        },
        "pool": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "route": {
          "$ref": "#/$defs/shape7"
        },
        "run": {
          "$ref": "#/$defs/shape7"
        },
        "scope": {
          "$ref": "#/$defs/shape63"
        },
        "to": {
          "$ref": "#/$defs/shape7"
        }
      },
      "additionalProperties": false
    },
    "shape67": {
      "const": "usage.conversation"
    },
    "shape68": {
      "const": "usage.project"
    },
    "shape69": {
      "const": "usage.agent"
    },
    "shape70": {
      "const": "usage.run"
    },
    "shape71": {
      "const": "usage.orchestration"
    },
    "shape72": {
      "const": "usage.models"
    },
    "shape73": {
      "const": "usage.pools"
    },
    "shape66": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape67"
        },
        {
          "$ref": "#/$defs/shape68"
        },
        {
          "$ref": "#/$defs/shape69"
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
          "$ref": "#/$defs/shape73"
        }
      ]
    },
    "shape51": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape52"
        },
        "filter": {
          "$ref": "#/$defs/shape53"
        },
        "type": {
          "$ref": "#/$defs/shape66"
        }
      },
      "required": [
        "action",
        "filter",
        "type"
      ],
      "additionalProperties": false
    },
    "shape75": {
      "const": "usage-read"
    },
    "shape76": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "cursor": {
          "$ref": "#/$defs/shape7"
        },
        "from": {
          "$ref": "#/$defs/shape7"
        },
        "group_by": {
          "$ref": "#/$defs/shape54"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "model": {
          "$ref": "#/$defs/shape7"
        },
        "orchestration": {
          "$ref": "#/$defs/shape7"
        },
        "pool": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "route": {
          "$ref": "#/$defs/shape7"
        },
        "run": {
          "$ref": "#/$defs/shape7"
        },
        "scope": {
          "$ref": "#/$defs/shape63"
        },
        "to": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape74": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape75"
        },
        "payload": {
          "$ref": "#/$defs/shape76"
        },
        "type": {
          "$ref": "#/$defs/shape67"
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
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "cursor": {
          "$ref": "#/$defs/shape7"
        },
        "from": {
          "$ref": "#/$defs/shape7"
        },
        "group_by": {
          "$ref": "#/$defs/shape54"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "model": {
          "$ref": "#/$defs/shape7"
        },
        "orchestration": {
          "$ref": "#/$defs/shape7"
        },
        "pool": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "route": {
          "$ref": "#/$defs/shape7"
        },
        "run": {
          "$ref": "#/$defs/shape7"
        },
        "scope": {
          "$ref": "#/$defs/shape63"
        },
        "to": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "project"
      ],
      "additionalProperties": false
    },
    "shape77": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape75"
        },
        "payload": {
          "$ref": "#/$defs/shape78"
        },
        "type": {
          "$ref": "#/$defs/shape68"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape80": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "cursor": {
          "$ref": "#/$defs/shape7"
        },
        "from": {
          "$ref": "#/$defs/shape7"
        },
        "group_by": {
          "$ref": "#/$defs/shape54"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "model": {
          "$ref": "#/$defs/shape7"
        },
        "orchestration": {
          "$ref": "#/$defs/shape7"
        },
        "pool": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "route": {
          "$ref": "#/$defs/shape7"
        },
        "run": {
          "$ref": "#/$defs/shape7"
        },
        "scope": {
          "$ref": "#/$defs/shape63"
        },
        "to": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "agent"
      ],
      "additionalProperties": false
    },
    "shape79": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape75"
        },
        "payload": {
          "$ref": "#/$defs/shape80"
        },
        "type": {
          "$ref": "#/$defs/shape69"
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
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "cursor": {
          "$ref": "#/$defs/shape7"
        },
        "from": {
          "$ref": "#/$defs/shape7"
        },
        "group_by": {
          "$ref": "#/$defs/shape54"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "model": {
          "$ref": "#/$defs/shape7"
        },
        "orchestration": {
          "$ref": "#/$defs/shape7"
        },
        "pool": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "route": {
          "$ref": "#/$defs/shape7"
        },
        "run": {
          "$ref": "#/$defs/shape7"
        },
        "scope": {
          "$ref": "#/$defs/shape63"
        },
        "to": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "run"
      ],
      "additionalProperties": false
    },
    "shape81": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape75"
        },
        "payload": {
          "$ref": "#/$defs/shape82"
        },
        "type": {
          "$ref": "#/$defs/shape70"
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
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "cursor": {
          "$ref": "#/$defs/shape7"
        },
        "from": {
          "$ref": "#/$defs/shape7"
        },
        "group_by": {
          "$ref": "#/$defs/shape54"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "model": {
          "$ref": "#/$defs/shape7"
        },
        "orchestration": {
          "$ref": "#/$defs/shape7"
        },
        "pool": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "route": {
          "$ref": "#/$defs/shape7"
        },
        "run": {
          "$ref": "#/$defs/shape7"
        },
        "scope": {
          "$ref": "#/$defs/shape63"
        },
        "to": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "orchestration"
      ],
      "additionalProperties": false
    },
    "shape83": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape75"
        },
        "payload": {
          "$ref": "#/$defs/shape84"
        },
        "type": {
          "$ref": "#/$defs/shape71"
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
        "action": {
          "$ref": "#/$defs/shape75"
        },
        "payload": {
          "$ref": "#/$defs/shape53"
        },
        "type": {
          "$ref": "#/$defs/shape72"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape86": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape75"
        },
        "payload": {
          "$ref": "#/$defs/shape53"
        },
        "type": {
          "$ref": "#/$defs/shape73"
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
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "attempt_cursor": {
          "$ref": "#/$defs/shape7"
        },
        "call": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "cursor": {
          "$ref": "#/$defs/shape7"
        },
        "from": {
          "$ref": "#/$defs/shape7"
        },
        "group_by": {
          "$ref": "#/$defs/shape54"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "model": {
          "$ref": "#/$defs/shape7"
        },
        "orchestration": {
          "$ref": "#/$defs/shape7"
        },
        "pool": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "route": {
          "$ref": "#/$defs/shape7"
        },
        "run": {
          "$ref": "#/$defs/shape7"
        },
        "scope": {
          "$ref": "#/$defs/shape63"
        },
        "to": {
          "$ref": "#/$defs/shape7"
        }
      },
      "additionalProperties": false
    },
    "shape89": {
      "const": "usage.calls"
    },
    "shape87": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape75"
        },
        "payload": {
          "$ref": "#/$defs/shape88"
        },
        "type": {
          "$ref": "#/$defs/shape89"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape91": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "cursor": {
          "$ref": "#/$defs/shape7"
        },
        "from": {
          "$ref": "#/$defs/shape7"
        },
        "group_by": {
          "$ref": "#/$defs/shape54"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "model": {
          "$ref": "#/$defs/shape7"
        },
        "orchestration": {
          "$ref": "#/$defs/shape7"
        },
        "pool": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "report_type": {
          "$ref": "#/$defs/shape66"
        },
        "route": {
          "$ref": "#/$defs/shape7"
        },
        "run": {
          "$ref": "#/$defs/shape7"
        },
        "scope": {
          "$ref": "#/$defs/shape63"
        },
        "to": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "report_type"
      ],
      "additionalProperties": false
    },
    "shape92": {
      "const": "usage.subscribe"
    },
    "shape90": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape75"
        },
        "payload": {
          "$ref": "#/$defs/shape91"
        },
        "type": {
          "$ref": "#/$defs/shape92"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape94": {
      "type": "object",
      "properties": {
        "subscription": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "subscription"
      ],
      "additionalProperties": false
    },
    "shape95": {
      "const": "usage.unsubscribe"
    },
    "shape93": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape75"
        },
        "payload": {
          "$ref": "#/$defs/shape94"
        },
        "type": {
          "$ref": "#/$defs/shape95"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape97": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape98": {
      "const": "conversation.context.count"
    },
    "shape96": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape75"
        },
        "payload": {
          "$ref": "#/$defs/shape97"
        },
        "type": {
          "$ref": "#/$defs/shape98"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape100": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "measure": {
          "$ref": "#/$defs/shape21"
        }
      },
      "required": [
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape101": {
      "const": "conversation.context.snapshot"
    },
    "shape99": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape75"
        },
        "payload": {
          "$ref": "#/$defs/shape100"
        },
        "type": {
          "$ref": "#/$defs/shape101"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape103": {
      "const": "usage-close"
    },
    "shape102": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape103"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape105": {
      "const": "operator-prepare"
    },
    "shape107": {
      "const": "memory-write"
    },
    "shape108": {
      "const": "memory-digest"
    },
    "shape109": {
      "const": "agent-curate"
    },
    "shape110": {
      "const": "conversation-lifecycle"
    },
    "shape111": {
      "const": "conversation-resume"
    },
    "shape112": {
      "const": "job-limits"
    },
    "shape113": {
      "const": "approval-grant"
    },
    "shape114": {
      "const": "approval-revoke"
    },
    "shape115": {
      "const": "board-topup"
    },
    "shape116": {
      "const": "message-deliveries"
    },
    "shape117": {
      "const": "message-open"
    },
    "shape118": {
      "const": "message-default"
    },
    "shape119": {
      "const": "message-stop"
    },
    "shape120": {
      "const": "message-archive"
    },
    "shape121": {
      "const": "caps"
    },
    "shape106": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape115"
        },
        {
          "$ref": "#/$defs/shape116"
        },
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
        }
      ]
    },
    "shape104": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape105"
        },
        "kind": {
          "$ref": "#/$defs/shape106"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "kind"
      ],
      "additionalProperties": false
    },
    "shape123": {
      "const": "operator-messages"
    },
    "shape122": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape123"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "instance": {
          "$ref": "#/$defs/shape7"
        },
        "offset": {
          "$ref": "#/$defs/shape30"
        }
      },
      "required": [
        "action",
        "identity",
        "instance"
      ],
      "additionalProperties": false
    },
    "shape125": {
      "const": "operator-preview"
    },
    "shape128": {
      "const": "once"
    },
    "shape129": {
      "const": "conversation"
    },
    "shape130": {
      "const": "deny"
    },
    "shape127": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape61"
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
      "const": "steps"
    },
    "shape133": {
      "const": "budget"
    },
    "shape134": {
      "const": "auto-continue"
    },
    "shape135": {
      "const": "time"
    },
    "shape136": {
      "const": "failed-checks"
    },
    "shape137": {
      "const": "auto-increase"
    },
    "shape131": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape132"
        },
        {
          "$ref": "#/$defs/shape133"
        },
        {
          "$ref": "#/$defs/shape134"
        },
        {
          "$ref": "#/$defs/shape135"
        },
        {
          "$ref": "#/$defs/shape136"
        },
        {
          "$ref": "#/$defs/shape137"
        }
      ]
    },
    "shape139": {
      "const": "active"
    },
    "shape140": {
      "const": "archived"
    },
    "shape138": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape139"
        },
        {
          "$ref": "#/$defs/shape140"
        }
      ]
    },
    "shape142": {
      "const": "true"
    },
    "shape143": {
      "const": "false"
    },
    "shape141": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape142"
        },
        {
          "$ref": "#/$defs/shape143"
        }
      ]
    },
    "shape126": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "body": {
          "$ref": "#/$defs/shape7"
        },
        "decision": {
          "$ref": "#/$defs/shape127"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        },
        "key": {
          "$ref": "#/$defs/shape131"
        },
        "lifecycle": {
          "$ref": "#/$defs/shape138"
        },
        "makeDefault": {
          "$ref": "#/$defs/shape141"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape30"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape30"
        },
        "prefix": {
          "$ref": "#/$defs/shape7"
        },
        "scope": {
          "$ref": "#/$defs/shape7"
        },
        "summary": {
          "$ref": "#/$defs/shape7"
        },
        "value": {
          "$ref": "#/$defs/shape30"
        }
      },
      "additionalProperties": false
    },
    "shape124": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape125"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "input": {
          "$ref": "#/$defs/shape126"
        }
      },
      "required": [
        "action",
        "identity",
        "input"
      ],
      "additionalProperties": false
    },
    "shape145": {
      "const": "operator-apply"
    },
    "shape144": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape145"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape147": {
      "const": "information"
    },
    "shape148": {
      "const": "upload"
    },
    "shape149": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "text": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape153": {
      "const": "personal"
    },
    "shape154": {
      "const": "shared"
    },
    "shape152": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape153"
        },
        {
          "$ref": "#/$defs/shape154"
        }
      ]
    },
    "shape151": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape21"
        },
        "kind": {
          "$ref": "#/$defs/shape152"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape155": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape21"
        },
        "kind": {
          "$ref": "#/$defs/shape61"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "kind",
        "project"
      ],
      "additionalProperties": false
    },
    "shape150": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape151"
        },
        {
          "$ref": "#/$defs/shape155"
        }
      ]
    },
    "shape146": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape148"
        },
        "payload": {
          "$ref": "#/$defs/shape149"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape157": {
      "const": "acquire"
    },
    "shape158": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "url": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "requestId",
        "url"
      ],
      "additionalProperties": false
    },
    "shape156": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape157"
        },
        "payload": {
          "$ref": "#/$defs/shape158"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape160": {
      "const": "refresh"
    },
    "shape161": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape159": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape160"
        },
        "payload": {
          "$ref": "#/$defs/shape161"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape163": {
      "const": "revise"
    },
    "shape164": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        },
        "text": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape162": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape163"
        },
        "payload": {
          "$ref": "#/$defs/shape164"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape166": {
      "const": "replace"
    },
    "shape167": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        },
        "text": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape165": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape166"
        },
        "payload": {
          "$ref": "#/$defs/shape167"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape169": {
      "const": "list"
    },
    "shape172": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape7"
      }
    },
    "shape174": {
      "const": "source"
    },
    "shape175": {
      "const": "report"
    },
    "shape173": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape174"
        },
        {
          "$ref": "#/$defs/shape175"
        }
      ]
    },
    "shape171": {
      "type": "object",
      "properties": {
        "author": {
          "$ref": "#/$defs/shape7"
        },
        "autoTag": {
          "$ref": "#/$defs/shape172"
        },
        "documentAuthor": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape173"
        },
        "search": {
          "$ref": "#/$defs/shape7"
        },
        "subtype": {
          "$ref": "#/$defs/shape7"
        },
        "tagGroup": {
          "$ref": "#/$defs/shape7"
        },
        "tags": {
          "$ref": "#/$defs/shape172"
        },
        "when": {
          "$ref": "#/$defs/shape7"
        }
      },
      "additionalProperties": false
    },
    "shape170": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape171"
        },
        "kind": {
          "$ref": "#/$defs/shape173"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "offset": {
          "$ref": "#/$defs/shape30"
        }
      },
      "additionalProperties": false
    },
    "shape168": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape169"
        },
        "payload": {
          "$ref": "#/$defs/shape170"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape177": {
      "const": "facets"
    },
    "shape178": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape171"
        },
        "kind": {
          "$ref": "#/$defs/shape173"
        }
      },
      "additionalProperties": false
    },
    "shape176": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape177"
        },
        "payload": {
          "$ref": "#/$defs/shape178"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape180": {
      "const": "tags"
    },
    "shape181": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        },
        "tags": {
          "$ref": "#/$defs/shape172"
        }
      },
      "required": [
        "requestId",
        "revision",
        "tags"
      ],
      "additionalProperties": false
    },
    "shape179": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape180"
        },
        "payload": {
          "$ref": "#/$defs/shape181"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape183": {
      "const": "tags.groups"
    },
    "shape186": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape172"
      }
    },
    "shape185": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape32"
        },
        {
          "$ref": "#/$defs/shape186"
        }
      ]
    },
    "shape184": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape185"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "groups",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape182": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape183"
        },
        "payload": {
          "$ref": "#/$defs/shape184"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape188": {
      "const": "inventory"
    },
    "shape189": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "offset": {
          "$ref": "#/$defs/shape30"
        }
      },
      "additionalProperties": false
    },
    "shape187": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape188"
        },
        "payload": {
          "$ref": "#/$defs/shape189"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape191": {
      "const": "acquisitions"
    },
    "shape192": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "offset": {
          "$ref": "#/$defs/shape30"
        }
      },
      "additionalProperties": false
    },
    "shape190": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape191"
        },
        "payload": {
          "$ref": "#/$defs/shape192"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape194": {
      "const": "status"
    },
    "shape195": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "additionalProperties": false
    },
    "shape193": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape194"
        },
        "payload": {
          "$ref": "#/$defs/shape195"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape197": {
      "const": "await"
    },
    "shape202": {
      "forbidden": true
    },
    "shape201": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape202"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape203": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape202"
        }
      },
      "required": [
        "acquisition"
      ],
      "additionalProperties": false
    },
    "shape200": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape201"
        },
        {
          "$ref": "#/$defs/shape203"
        }
      ]
    },
    "shape199": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape200"
      }
    },
    "shape198": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape199"
        },
        "waitMs": {
          "$ref": "#/$defs/shape30"
        }
      },
      "required": [
        "sources"
      ],
      "additionalProperties": false
    },
    "shape196": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape197"
        },
        "payload": {
          "$ref": "#/$defs/shape198"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "read"
    },
    "shape206": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "offset": {
          "$ref": "#/$defs/shape30"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape204": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape205"
        },
        "payload": {
          "$ref": "#/$defs/shape206"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "outline"
    },
    "shape209": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "offset": {
          "$ref": "#/$defs/shape30"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape207": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape208"
        },
        "payload": {
          "$ref": "#/$defs/shape209"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "symbols"
    },
    "shape212": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "offset": {
          "$ref": "#/$defs/shape30"
        },
        "query": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape210": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape211"
        },
        "payload": {
          "$ref": "#/$defs/shape212"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "search"
    },
    "shape215": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape171"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "query": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape213": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape214"
        },
        "payload": {
          "$ref": "#/$defs/shape215"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape217": {
      "const": "rank"
    },
    "shape218": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape171"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "query": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape216": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape217"
        },
        "payload": {
          "$ref": "#/$defs/shape218"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape220": {
      "const": "ask"
    },
    "shape221": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape30"
        },
        "question": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "question",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape219": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape220"
        },
        "payload": {
          "$ref": "#/$defs/shape221"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape223": {
      "const": "evidence.record"
    },
    "shape224": {
      "type": "object",
      "properties": {
        "end": {
          "$ref": "#/$defs/shape30"
        },
        "locator": {
          "$ref": "#/$defs/shape7"
        },
        "quote": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        },
        "start": {
          "$ref": "#/$defs/shape30"
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
    "shape222": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape223"
        },
        "payload": {
          "$ref": "#/$defs/shape224"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "evidence.read"
    },
    "shape227": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "evidence"
      ],
      "additionalProperties": false
    },
    "shape225": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape226"
        },
        "payload": {
          "$ref": "#/$defs/shape227"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "record.report"
    },
    "shape234": {
      "const": "holds"
    },
    "shape235": {
      "const": "weakened"
    },
    "shape236": {
      "const": "refuted"
    },
    "shape237": {
      "const": "not_checked"
    },
    "shape233": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape234"
        },
        {
          "$ref": "#/$defs/shape235"
        },
        {
          "$ref": "#/$defs/shape236"
        },
        {
          "$ref": "#/$defs/shape237"
        }
      ]
    },
    "shape232": {
      "type": "object",
      "properties": {
        "claim": {
          "$ref": "#/$defs/shape7"
        },
        "counterEvidence": {
          "$ref": "#/$defs/shape172"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        },
        "objective": {
          "$ref": "#/$defs/shape7"
        },
        "rationale": {
          "$ref": "#/$defs/shape7"
        },
        "support": {
          "$ref": "#/$defs/shape172"
        },
        "verdict": {
          "$ref": "#/$defs/shape233"
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
    "shape231": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape232"
      }
    },
    "shape239": {
      "type": "object",
      "properties": {
        "outcome": {
          "$ref": "#/$defs/shape7"
        },
        "stage": {
          "$ref": "#/$defs/shape7"
        },
        "text": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "outcome",
        "stage",
        "text"
      ],
      "additionalProperties": false
    },
    "shape238": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape239"
      }
    },
    "shape230": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape172"
        },
        "feedback": {
          "$ref": "#/$defs/shape7"
        },
        "findings": {
          "$ref": "#/$defs/shape231"
        },
        "inputs": {
          "$ref": "#/$defs/shape172"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "objectives": {
          "$ref": "#/$defs/shape172"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "reviews": {
          "$ref": "#/$defs/shape238"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape172"
        },
        "text": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape228": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape229"
        },
        "payload": {
          "$ref": "#/$defs/shape230"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "finalise"
    },
    "shape242": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
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
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape241"
        },
        "payload": {
          "$ref": "#/$defs/shape242"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "link"
    },
    "shape245": {
      "type": "object",
      "properties": {
        "collectionProject": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape243": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape244"
        },
        "payload": {
          "$ref": "#/$defs/shape245"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "unlink"
    },
    "shape248": {
      "type": "object",
      "properties": {
        "collectionProject": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape246": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape247"
        },
        "payload": {
          "$ref": "#/$defs/shape248"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "share"
    },
    "shape251": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
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
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape250"
        },
        "payload": {
          "$ref": "#/$defs/shape251"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "unshare"
    },
    "shape254": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
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
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape253"
        },
        "payload": {
          "$ref": "#/$defs/shape254"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "withdraw"
    },
    "shape257": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape255": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape256"
        },
        "payload": {
          "$ref": "#/$defs/shape257"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "exclude"
    },
    "shape260": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape258": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape259"
        },
        "payload": {
          "$ref": "#/$defs/shape260"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "unexclude"
    },
    "shape263": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape261": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape262"
        },
        "payload": {
          "$ref": "#/$defs/shape263"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "restore"
    },
    "shape266": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape264": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape265"
        },
        "payload": {
          "$ref": "#/$defs/shape266"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "delete"
    },
    "shape269": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape267": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape268"
        },
        "payload": {
          "$ref": "#/$defs/shape269"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "retry"
    },
    "shape272": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "additionalProperties": false
    },
    "shape270": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape271"
        },
        "payload": {
          "$ref": "#/$defs/shape272"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "rebuild"
    },
    "shape275": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        },
        "stage": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "requestId",
        "revision",
        "stage"
      ],
      "additionalProperties": false
    },
    "shape273": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape274"
        },
        "payload": {
          "$ref": "#/$defs/shape275"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "allowance"
    },
    "shape278": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape30"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "maxModelCalls",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape276": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape277"
        },
        "payload": {
          "$ref": "#/$defs/shape278"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape280": {
      "const": "events"
    },
    "shape281": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape30"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        }
      },
      "additionalProperties": false
    },
    "shape279": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape280"
        },
        "payload": {
          "$ref": "#/$defs/shape281"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape283": {
      "const": "migration.list"
    },
    "shape284": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "offset": {
          "$ref": "#/$defs/shape30"
        }
      },
      "additionalProperties": false
    },
    "shape282": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape283"
        },
        "payload": {
          "$ref": "#/$defs/shape284"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape286": {
      "const": "migration.adopt"
    },
    "shape287": {
      "type": "object",
      "properties": {
        "collectionProject": {
          "$ref": "#/$defs/shape7"
        },
        "owner": {
          "$ref": "#/$defs/shape7"
        },
        "reason": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        },
        "visibility": {
          "$ref": "#/$defs/shape7"
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
    "shape285": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape286"
        },
        "payload": {
          "$ref": "#/$defs/shape287"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
      "const": "migration.inspect"
    },
    "shape290": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape30"
        },
        "offset": {
          "$ref": "#/$defs/shape30"
        },
        "payload": {
          "$ref": "#/$defs/shape7"
        },
        "reason": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "payload",
        "reason"
      ],
      "additionalProperties": false
    },
    "shape288": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape289"
        },
        "payload": {
          "$ref": "#/$defs/shape290"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape292": {
      "const": "migration.release"
    },
    "shape293": {
      "type": "object",
      "properties": {
        "inputs": {
          "$ref": "#/$defs/shape172"
        },
        "owner": {
          "$ref": "#/$defs/shape7"
        },
        "payload": {
          "$ref": "#/$defs/shape7"
        },
        "reason": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
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
    "shape291": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape147"
        },
        "operation": {
          "$ref": "#/$defs/shape292"
        },
        "payload": {
          "$ref": "#/$defs/shape293"
        },
        "scope": {
          "$ref": "#/$defs/shape150"
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
    "shape296": {
      "const": "bootstrap"
    },
    "shape297": {
      "const": "demo"
    },
    "shape298": {
      "const": "disconnect"
    },
    "shape299": {
      "const": "approvals-refresh"
    },
    "shape295": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape160"
        },
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
    "shape294": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape295"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape301": {
      "const": "project-access"
    },
    "shape303": {
      "const": "project.access"
    },
    "shape304": {
      "const": "project.member.add"
    },
    "shape305": {
      "const": "project.member.remove"
    },
    "shape306": {
      "const": "project.member.role"
    },
    "shape302": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape303"
        },
        {
          "$ref": "#/$defs/shape304"
        },
        {
          "$ref": "#/$defs/shape305"
        },
        {
          "$ref": "#/$defs/shape306"
        }
      ]
    },
    "shape308": {
      "const": "VIEWER"
    },
    "shape309": {
      "const": "CONTRIBUTOR"
    },
    "shape310": {
      "const": "MANAGER"
    },
    "shape307": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape308"
        },
        {
          "$ref": "#/$defs/shape309"
        },
        {
          "$ref": "#/$defs/shape310"
        }
      ]
    },
    "shape300": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape301"
        },
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape302"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "role": {
          "$ref": "#/$defs/shape307"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape312": {
      "const": "server-admin"
    },
    "shape313": {
      "const": "admin.pricing.list"
    },
    "shape314": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape202"
      }
    },
    "shape311": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape313"
        },
        "payload": {
          "$ref": "#/$defs/shape314"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape316": {
      "const": "admin.pricing.set"
    },
    "shape319": {
      "const": "TOKEN"
    },
    "shape320": {
      "const": "INCLUDED"
    },
    "shape321": {
      "const": "ZERO_RATE"
    },
    "shape322": {
      "const": "UNPRICED"
    },
    "shape318": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape319"
        },
        {
          "$ref": "#/$defs/shape320"
        },
        {
          "$ref": "#/$defs/shape321"
        },
        {
          "$ref": "#/$defs/shape322"
        }
      ]
    },
    "shape323": {
      "type": "object",
      "properties": {
        "cacheRead": {
          "$ref": "#/$defs/shape7"
        },
        "cacheWrite": {
          "$ref": "#/$defs/shape7"
        },
        "input": {
          "$ref": "#/$defs/shape7"
        },
        "output": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "input",
        "output"
      ],
      "additionalProperties": false
    },
    "shape326": {
      "type": "object",
      "properties": {
        "cacheRead": {
          "$ref": "#/$defs/shape7"
        },
        "cacheWrite": {
          "$ref": "#/$defs/shape7"
        },
        "input": {
          "$ref": "#/$defs/shape7"
        },
        "output": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "input",
        "output"
      ],
      "additionalProperties": false
    },
    "shape325": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape30"
        },
        "rates": {
          "$ref": "#/$defs/shape326"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape324": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape325"
      }
    },
    "shape317": {
      "type": "object",
      "properties": {
        "billingRoute": {
          "$ref": "#/$defs/shape7"
        },
        "currency": {
          "$ref": "#/$defs/shape7"
        },
        "expectedVersion": {
          "$ref": "#/$defs/shape7"
        },
        "mode": {
          "$ref": "#/$defs/shape318"
        },
        "model": {
          "$ref": "#/$defs/shape7"
        },
        "rates": {
          "$ref": "#/$defs/shape323"
        },
        "requestFee": {
          "$ref": "#/$defs/shape7"
        },
        "source": {
          "$ref": "#/$defs/shape7"
        },
        "tiers": {
          "$ref": "#/$defs/shape324"
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
    "shape315": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape316"
        },
        "payload": {
          "$ref": "#/$defs/shape317"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape328": {
      "const": "admin.accounts"
    },
    "shape327": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape328"
        },
        "payload": {
          "$ref": "#/$defs/shape314"
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
      "const": "admin.account.create"
    },
    "shape331": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape21"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape329": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
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
      "const": "admin.account.update"
    },
    "shape334": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape21"
        },
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape21"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape332": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape333"
        },
        "payload": {
          "$ref": "#/$defs/shape334"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape336": {
      "const": "admin.account.reset"
    },
    "shape337": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape335": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape336"
        },
        "payload": {
          "$ref": "#/$defs/shape337"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape339": {
      "const": "admin.sessions"
    },
    "shape340": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape338": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape339"
        },
        "payload": {
          "$ref": "#/$defs/shape340"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape342": {
      "const": "admin.session.revoke"
    },
    "shape343": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape341": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape342"
        },
        "payload": {
          "$ref": "#/$defs/shape343"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape345": {
      "const": "admin.audit"
    },
    "shape346": {
      "type": "object",
      "properties": {
        "before": {
          "$ref": "#/$defs/shape30"
        },
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "limit": {
          "$ref": "#/$defs/shape30"
        }
      },
      "additionalProperties": false
    },
    "shape344": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape345"
        },
        "payload": {
          "$ref": "#/$defs/shape346"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape348": {
      "const": "admin.service.accounts"
    },
    "shape347": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape348"
        },
        "payload": {
          "$ref": "#/$defs/shape314"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape350": {
      "const": "admin.service.account.create"
    },
    "shape351": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape349": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape350"
        },
        "payload": {
          "$ref": "#/$defs/shape351"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape353": {
      "const": "admin.service.account.update"
    },
    "shape354": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape21"
        },
        "handle": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "enabled",
        "handle"
      ],
      "additionalProperties": false
    },
    "shape352": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape353"
        },
        "payload": {
          "$ref": "#/$defs/shape354"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape356": {
      "const": "admin.service.tokens"
    },
    "shape357": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape355": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape356"
        },
        "payload": {
          "$ref": "#/$defs/shape357"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape359": {
      "const": "admin.service.token.create"
    },
    "shape362": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "role": {
          "$ref": "#/$defs/shape307"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape361": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape362"
      }
    },
    "shape360": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape30"
        },
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "scopes": {
          "$ref": "#/$defs/shape361"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape358": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape359"
        },
        "payload": {
          "$ref": "#/$defs/shape360"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape364": {
      "const": "admin.service.token.rotate"
    },
    "shape365": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape30"
        },
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "handle",
        "id"
      ],
      "additionalProperties": false
    },
    "shape363": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape364"
        },
        "payload": {
          "$ref": "#/$defs/shape365"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape367": {
      "const": "admin.service.token.revoke"
    },
    "shape368": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "handle",
        "id"
      ],
      "additionalProperties": false
    },
    "shape366": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape312"
        },
        "operation": {
          "$ref": "#/$defs/shape367"
        },
        "payload": {
          "$ref": "#/$defs/shape368"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape370": {
      "const": "server-project-create"
    },
    "shape372": {
      "const": "MANAGED"
    },
    "shape373": {
      "const": "DISJOINT"
    },
    "shape371": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape372"
        },
        {
          "$ref": "#/$defs/shape373"
        }
      ]
    },
    "shape374": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape12"
      }
    },
    "shape375": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape7"
      }
    },
    "shape369": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape370"
        },
        "applicationRoot": {
          "$ref": "#/$defs/shape12"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "type": {
          "$ref": "#/$defs/shape371"
        },
        "workspace": {
          "$ref": "#/$defs/shape7"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape374"
        },
        "writePaths": {
          "$ref": "#/$defs/shape375"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape377": {
      "const": "server-setup"
    },
    "shape376": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape377"
        },
        "base": {
          "$ref": "#/$defs/shape7"
        },
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "password": {
          "$ref": "#/$defs/shape7"
        },
        "temporaryPassword": {
          "$ref": "#/$defs/shape7"
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
    "shape379": {
      "const": "connect"
    },
    "shape378": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape379"
        },
        "base": {
          "$ref": "#/$defs/shape7"
        },
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "password": {
          "$ref": "#/$defs/shape7"
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
    "shape381": {
      "const": "connection-select"
    },
    "shape380": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape381"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape383": {
      "const": "connection-preferences"
    },
    "shape385": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape7"
      }
    },
    "shape386": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape21"
      }
    },
    "shape384": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape385"
        },
        "drafts": {
          "$ref": "#/$defs/shape385"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape386"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape386"
        },
        "scope": {
          "$ref": "#/$defs/shape7"
        },
        "selected": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "drafts",
        "scope",
        "selected"
      ],
      "additionalProperties": false
    },
    "shape382": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape7"
        },
        "action": {
          "$ref": "#/$defs/shape383"
        },
        "preference": {
          "$ref": "#/$defs/shape384"
        },
        "server": {
          "$ref": "#/$defs/shape7"
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
    "shape388": {
      "const": "connection-rename"
    },
    "shape387": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape388"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "nextName": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "name",
        "nextName"
      ],
      "additionalProperties": false
    },
    "shape390": {
      "const": "connection-remove"
    },
    "shape389": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape390"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape392": {
      "const": "personal-section"
    },
    "shape394": {
      "const": "In"
    },
    "shape395": {
      "const": "Out"
    },
    "shape396": {
      "const": "Resources"
    },
    "shape397": {
      "const": "Archive"
    },
    "shape398": {
      "const": "Planning"
    },
    "shape399": {
      "const": "Bots"
    },
    "shape393": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape394"
        },
        {
          "$ref": "#/$defs/shape395"
        },
        {
          "$ref": "#/$defs/shape396"
        },
        {
          "$ref": "#/$defs/shape397"
        },
        {
          "$ref": "#/$defs/shape398"
        },
        {
          "$ref": "#/$defs/shape399"
        }
      ]
    },
    "shape391": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape392"
        },
        "path": {
          "$ref": "#/$defs/shape7"
        },
        "section": {
          "$ref": "#/$defs/shape393"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape401": {
      "const": "personal-bots"
    },
    "shape400": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape401"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape403": {
      "const": "personal-recreate"
    },
    "shape402": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape403"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape405": {
      "const": "files-choose"
    },
    "shape404": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape407": {
      "const": "files-withdraw"
    },
    "shape406": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape407"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape409": {
      "const": "sync-refresh"
    },
    "shape408": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape409"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape411": {
      "const": "sync-inspect"
    },
    "shape410": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape411"
        },
        "path": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "path",
        "project"
      ],
      "additionalProperties": false
    },
    "shape413": {
      "const": "sync-change"
    },
    "shape415": {
      "const": "on"
    },
    "shape416": {
      "const": "off"
    },
    "shape417": {
      "const": "now"
    },
    "shape414": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape415"
        },
        {
          "$ref": "#/$defs/shape416"
        },
        {
          "$ref": "#/$defs/shape417"
        }
      ]
    },
    "shape412": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape413"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape414"
        },
        "project": {
          "$ref": "#/$defs/shape7"
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
    "shape419": {
      "const": "sync-resolve"
    },
    "shape421": {
      "const": "mine"
    },
    "shape422": {
      "const": "theirs"
    },
    "shape423": {
      "const": "done"
    },
    "shape420": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape421"
        },
        {
          "$ref": "#/$defs/shape422"
        },
        {
          "$ref": "#/$defs/shape423"
        }
      ]
    },
    "shape418": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape419"
        },
        "how": {
          "$ref": "#/$defs/shape420"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "text": {
          "$ref": "#/$defs/shape7"
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
    "shape426": {
      "const": "project-open"
    },
    "shape427": {
      "const": "project-remove"
    },
    "shape425": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape426"
        },
        {
          "$ref": "#/$defs/shape427"
        }
      ]
    },
    "shape424": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape425"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape430": {
      "const": "scope"
    },
    "shape431": {
      "const": "create"
    },
    "shape429": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape430"
        },
        {
          "$ref": "#/$defs/shape431"
        }
      ]
    },
    "shape428": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape429"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape433": {
      "const": "history"
    },
    "shape432": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape433"
        },
        "before": {
          "$ref": "#/$defs/shape30"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape435": {
      "const": "select"
    },
    "shape434": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape435"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape437": {
      "const": "trajectory"
    },
    "shape436": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape437"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape439": {
      "const": "board-post-topics"
    },
    "shape438": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape439"
        },
        "more": {
          "$ref": "#/$defs/shape21"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape441": {
      "const": "board-create"
    },
    "shape440": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape441"
        },
        "body": {
          "$ref": "#/$defs/shape7"
        },
        "label": {
          "$ref": "#/$defs/shape7"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape30"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "title": {
          "$ref": "#/$defs/shape7"
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
    "shape443": {
      "const": "board-retry"
    },
    "shape442": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape443"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape30"
        },
        "member": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "reconcile": {
          "$ref": "#/$defs/shape21"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "topic": {
          "$ref": "#/$defs/shape7"
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
    "shape445": {
      "const": "board-post"
    },
    "shape444": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape445"
        },
        "body": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "topic": {
          "$ref": "#/$defs/shape7"
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
    "shape447": {
      "const": "workspace-chat"
    },
    "shape446": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape447"
        },
        "manage": {
          "$ref": "#/$defs/shape21"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape449": {
      "const": "workspace-layout"
    },
    "shape448": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape449"
        },
        "height": {
          "$ref": "#/$defs/shape30"
        },
        "visible": {
          "$ref": "#/$defs/shape21"
        },
        "width": {
          "$ref": "#/$defs/shape30"
        },
        "x": {
          "$ref": "#/$defs/shape30"
        },
        "y": {
          "$ref": "#/$defs/shape30"
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
    "shape451": {
      "const": "workspace-refresh"
    },
    "shape450": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape451"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape453": {
      "const": "library"
    },
    "shape455": {
      "const": "sources"
    },
    "shape456": {
      "const": "documents"
    },
    "shape457": {
      "const": "memories"
    },
    "shape458": {
      "const": "manual"
    },
    "shape454": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape214"
        },
        {
          "$ref": "#/$defs/shape455"
        },
        {
          "$ref": "#/$defs/shape456"
        },
        {
          "$ref": "#/$defs/shape457"
        },
        {
          "$ref": "#/$defs/shape458"
        }
      ]
    },
    "shape452": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape453"
        },
        "chapter": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape48"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        },
        "view": {
          "$ref": "#/$defs/shape454"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape460": {
      "const": "library-view"
    },
    "shape459": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape460"
        },
        "chapter": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape48"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        },
        "view": {
          "$ref": "#/$defs/shape454"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape463": {
      "const": "library-refresh"
    },
    "shape464": {
      "const": "library-citations"
    },
    "shape462": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape463"
        },
        {
          "$ref": "#/$defs/shape464"
        }
      ]
    },
    "shape461": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape462"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape466": {
      "const": "library-documents"
    },
    "shape465": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "more": {
          "$ref": "#/$defs/shape21"
        },
        "query": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "query"
      ],
      "additionalProperties": false
    },
    "shape469": {
      "const": "library-document"
    },
    "shape470": {
      "const": "library-memory"
    },
    "shape471": {
      "const": "library-chunk"
    },
    "shape472": {
      "const": "library-conversation"
    },
    "shape468": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape469"
        },
        {
          "$ref": "#/$defs/shape470"
        },
        {
          "$ref": "#/$defs/shape471"
        },
        {
          "$ref": "#/$defs/shape472"
        }
      ]
    },
    "shape467": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape468"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape474": {
      "const": "library-source-text"
    },
    "shape473": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape474"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        },
        "offset": {
          "$ref": "#/$defs/shape30"
        }
      },
      "required": [
        "action",
        "id",
        "offset"
      ],
      "additionalProperties": false
    },
    "shape476": {
      "const": "library-stance"
    },
    "shape475": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape476"
        },
        "claim": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "claim"
      ],
      "additionalProperties": false
    },
    "shape478": {
      "const": "library-search"
    },
    "shape480": {
      "const": "retrieve"
    },
    "shape481": {
      "const": "recall"
    },
    "shape482": {
      "const": "navigate"
    },
    "shape479": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape217"
        },
        {
          "$ref": "#/$defs/shape129"
        },
        {
          "$ref": "#/$defs/shape456"
        },
        {
          "$ref": "#/$defs/shape480"
        },
        {
          "$ref": "#/$defs/shape481"
        },
        {
          "$ref": "#/$defs/shape482"
        }
      ]
    },
    "shape484": {
      "const": "lexical"
    },
    "shape485": {
      "const": "semantic"
    },
    "shape486": {
      "const": "hybrid"
    },
    "shape483": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape484"
        },
        {
          "$ref": "#/$defs/shape485"
        },
        {
          "$ref": "#/$defs/shape486"
        }
      ]
    },
    "shape477": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape478"
        },
        "kind": {
          "$ref": "#/$defs/shape479"
        },
        "mode": {
          "$ref": "#/$defs/shape483"
        },
        "more": {
          "$ref": "#/$defs/shape21"
        },
        "query": {
          "$ref": "#/$defs/shape7"
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
    "shape488": {
      "const": "library-maintain"
    },
    "shape490": {
      "const": "invalidate"
    },
    "shape491": {
      "const": "resolve"
    },
    "shape492": {
      "const": "reembed"
    },
    "shape493": {
      "const": "reconsider"
    },
    "shape489": {
      "anyOf": [
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
    "shape487": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape21"
        },
        "action": {
          "$ref": "#/$defs/shape488"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape489"
        },
        "reason": {
          "$ref": "#/$defs/shape7"
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
    "shape495": {
      "const": "builder-outputs"
    },
    "shape494": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape495"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape497": {
      "const": "builder-trajectory"
    },
    "shape496": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape497"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape499": {
      "const": "builder-prepare"
    },
    "shape498": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape499"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape501": {
      "const": "builder-start"
    },
    "shape500": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape501"
        },
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "intent": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
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
    "shape503": {
      "const": "activity"
    },
    "shape505": {
      "const": "inbox"
    },
    "shape506": {
      "const": "runs"
    },
    "shape507": {
      "const": "definitions"
    },
    "shape508": {
      "const": "schedules"
    },
    "shape509": {
      "const": "builder"
    },
    "shape504": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape505"
        },
        {
          "$ref": "#/$defs/shape506"
        },
        {
          "$ref": "#/$defs/shape507"
        },
        {
          "$ref": "#/$defs/shape508"
        },
        {
          "$ref": "#/$defs/shape509"
        }
      ]
    },
    "shape502": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape503"
        },
        "view": {
          "$ref": "#/$defs/shape504"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape511": {
      "const": "activity-view"
    },
    "shape510": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape511"
        },
        "view": {
          "$ref": "#/$defs/shape504"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape513": {
      "const": "activity-refresh"
    },
    "shape512": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape513"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape515": {
      "const": "question-refresh"
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
      "const": "inbox-read"
    },
    "shape516": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape517"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape519": {
      "const": "inbox-more"
    },
    "shape518": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape519"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape521": {
      "const": "run-detail"
    },
    "shape520": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape521"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape523": {
      "const": "schedule-refresh"
    },
    "shape522": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape523"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape525": {
      "const": "schedule-preview"
    },
    "shape524": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape525"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "text": {
          "$ref": "#/$defs/shape7"
        },
        "zone": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "text",
        "zone"
      ],
      "additionalProperties": false
    },
    "shape527": {
      "const": "schedule-save"
    },
    "shape531": {
      "const": "skill"
    },
    "shape532": {
      "const": "orchestration"
    },
    "shape530": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape59"
        },
        {
          "$ref": "#/$defs/shape531"
        },
        {
          "$ref": "#/$defs/shape532"
        }
      ]
    },
    "shape534": {
      "const": "INHERITED"
    },
    "shape535": {
      "const": "SUMMARISED"
    },
    "shape536": {
      "const": "NEW"
    },
    "shape537": {
      "const": "DIRECT"
    },
    "shape533": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape32"
        },
        {
          "$ref": "#/$defs/shape534"
        },
        {
          "$ref": "#/$defs/shape535"
        },
        {
          "$ref": "#/$defs/shape536"
        },
        {
          "$ref": "#/$defs/shape537"
        }
      ]
    },
    "shape529": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "input": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape530"
        },
        "mode": {
          "$ref": "#/$defs/shape533"
        },
        "name": {
          "$ref": "#/$defs/shape48"
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
    "shape539": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape32"
        },
        {
          "$ref": "#/$defs/shape30"
        }
      ]
    },
    "shape538": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape539"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape539"
        },
        "queueCap": {
          "$ref": "#/$defs/shape30"
        }
      },
      "required": [
        "maxModelCalls",
        "maxTurns",
        "queueCap"
      ],
      "additionalProperties": false
    },
    "shape542": {
      "const": "mailbox"
    },
    "shape543": {
      "const": "message"
    },
    "shape541": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape129"
        },
        {
          "$ref": "#/$defs/shape542"
        },
        {
          "$ref": "#/$defs/shape543"
        }
      ]
    },
    "shape540": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape48"
        },
        "kind": {
          "$ref": "#/$defs/shape541"
        },
        "project": {
          "$ref": "#/$defs/shape48"
        },
        "route": {
          "$ref": "#/$defs/shape48"
        },
        "to": {
          "$ref": "#/$defs/shape48"
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
    "shape544": {
      "const": 1
    },
    "shape528": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
        },
        "cron": {
          "$ref": "#/$defs/shape7"
        },
        "limits": {
          "$ref": "#/$defs/shape538"
        },
        "paused": {
          "$ref": "#/$defs/shape21"
        },
        "target": {
          "$ref": "#/$defs/shape540"
        },
        "version": {
          "$ref": "#/$defs/shape544"
        },
        "zone": {
          "$ref": "#/$defs/shape7"
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
    "shape546": {
      "const": "server"
    },
    "shape547": {
      "const": "workspace"
    },
    "shape545": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape546"
        },
        {
          "$ref": "#/$defs/shape547"
        }
      ]
    },
    "shape526": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape527"
        },
        "definition": {
          "$ref": "#/$defs/shape528"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "source": {
          "$ref": "#/$defs/shape545"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape549": {
      "const": "schedule-file-save"
    },
    "shape548": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape549"
        },
        "definition": {
          "$ref": "#/$defs/shape528"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "overwrite": {
          "$ref": "#/$defs/shape21"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "source": {
          "$ref": "#/$defs/shape545"
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
    "shape551": {
      "const": "schedule-sync"
    },
    "shape550": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape551"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "source": {
          "$ref": "#/$defs/shape545"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape553": {
      "const": "schedule-change"
    },
    "shape555": {
      "const": "schedule"
    },
    "shape556": {
      "const": "trigger"
    },
    "shape554": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape555"
        },
        {
          "$ref": "#/$defs/shape556"
        }
      ]
    },
    "shape552": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape553"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape554"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "paused": {
          "$ref": "#/$defs/shape21"
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
    "shape558": {
      "const": "schedule-fire"
    },
    "shape557": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape558"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "trigger": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "identity",
        "trigger"
      ],
      "additionalProperties": false
    },
    "shape560": {
      "const": "run-definitions"
    },
    "shape559": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape560"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape562": {
      "const": "run-record"
    },
    "shape561": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape562"
        },
        "before": {
          "$ref": "#/$defs/shape30"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        },
        "kinds": {
          "$ref": "#/$defs/shape375"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape564": {
      "const": "run-answer"
    },
    "shape566": {
      "type": "object",
      "properties": {
        "chosen": {
          "$ref": "#/$defs/shape172"
        },
        "header": {
          "$ref": "#/$defs/shape7"
        },
        "note": {
          "$ref": "#/$defs/shape7"
        },
        "other": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "chosen",
        "header"
      ],
      "additionalProperties": false
    },
    "shape565": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape566"
      }
    },
    "shape563": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape564"
        },
        "answer": {
          "$ref": "#/$defs/shape7"
        },
        "choices": {
          "$ref": "#/$defs/shape565"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        },
        "question": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "id",
        "question"
      ],
      "additionalProperties": false
    },
    "shape568": {
      "const": "run-cancel"
    },
    "shape567": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape568"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape570": {
      "const": "run-resume"
    },
    "shape569": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape570"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape572": {
      "const": "run-trajectory"
    },
    "shape574": {
      "const": "conductor"
    },
    "shape575": {
      "const": "caller"
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
    "shape571": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape572"
        },
        "actor": {
          "$ref": "#/$defs/shape573"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "actor",
        "id"
      ],
      "additionalProperties": false
    },
    "shape577": {
      "const": "run-stage-trajectory"
    },
    "shape576": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape577"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        },
        "stage": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "id",
        "stage"
      ],
      "additionalProperties": false
    },
    "shape579": {
      "const": "delegate-trajectory"
    },
    "shape578": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape579"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "step": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "conversation",
        "step"
      ],
      "additionalProperties": false
    },
    "shape582": {
      "const": "board-inspection"
    },
    "shape583": {
      "const": "board-view"
    },
    "shape581": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape582"
        },
        {
          "$ref": "#/$defs/shape583"
        }
      ]
    },
    "shape585": {
      "const": "board"
    },
    "shape586": {
      "const": "swarm"
    },
    "shape584": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape585"
        },
        {
          "$ref": "#/$defs/shape586"
        }
      ]
    },
    "shape580": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape581"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "view": {
          "$ref": "#/$defs/shape584"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape589": {
      "const": "board-refresh"
    },
    "shape590": {
      "const": "board-more"
    },
    "shape588": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape589"
        },
        {
          "$ref": "#/$defs/shape590"
        }
      ]
    },
    "shape587": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape588"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape592": {
      "const": "board-topic"
    },
    "shape591": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape592"
        },
        "topic": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape594": {
      "const": "board-trajectory"
    },
    "shape593": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape594"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape596": {
      "const": "context"
    },
    "shape595": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape596"
        },
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape598": {
      "const": "open-link"
    },
    "shape597": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape598"
        },
        "url": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "url"
      ],
      "additionalProperties": false
    },
    "shape600": {
      "const": "copy-text"
    },
    "shape599": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape600"
        },
        "text": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "text"
      ],
      "additionalProperties": false
    },
    "shape602": {
      "const": "workflow-start"
    },
    "shape601": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape602"
        },
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "definition": {
          "$ref": "#/$defs/shape7"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "text": {
          "$ref": "#/$defs/shape7"
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
    "shape603": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape62"
        },
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "text": {
          "$ref": "#/$defs/shape7"
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
    "shape605": {
      "const": "cancel"
    },
    "shape604": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape605"
        },
        "job": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "action",
        "job"
      ],
      "additionalProperties": false
    },
    "shape607": {
      "const": "answer"
    },
    "shape608": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape128"
        },
        {
          "$ref": "#/$defs/shape130"
        }
      ]
    },
    "shape606": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape607"
        },
        "decision": {
          "$ref": "#/$defs/shape608"
        },
        "id": {
          "$ref": "#/$defs/shape7"
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
          "$ref": "#/$defs/shape5"
        },
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape10"
        },
        {
          "$ref": "#/$defs/shape13"
        },
        {
          "$ref": "#/$defs/shape15"
        },
        {
          "$ref": "#/$defs/shape17"
        },
        {
          "$ref": "#/$defs/shape19"
        },
        {
          "$ref": "#/$defs/shape24"
        },
        {
          "$ref": "#/$defs/shape26"
        },
        {
          "$ref": "#/$defs/shape34"
        },
        {
          "$ref": "#/$defs/shape39"
        },
        {
          "$ref": "#/$defs/shape49"
        },
        {
          "$ref": "#/$defs/shape51"
        },
        {
          "$ref": "#/$defs/shape74"
        },
        {
          "$ref": "#/$defs/shape77"
        },
        {
          "$ref": "#/$defs/shape79"
        },
        {
          "$ref": "#/$defs/shape81"
        },
        {
          "$ref": "#/$defs/shape83"
        },
        {
          "$ref": "#/$defs/shape85"
        },
        {
          "$ref": "#/$defs/shape86"
        },
        {
          "$ref": "#/$defs/shape87"
        },
        {
          "$ref": "#/$defs/shape90"
        },
        {
          "$ref": "#/$defs/shape93"
        },
        {
          "$ref": "#/$defs/shape96"
        },
        {
          "$ref": "#/$defs/shape99"
        },
        {
          "$ref": "#/$defs/shape102"
        },
        {
          "$ref": "#/$defs/shape104"
        },
        {
          "$ref": "#/$defs/shape122"
        },
        {
          "$ref": "#/$defs/shape124"
        },
        {
          "$ref": "#/$defs/shape144"
        },
        {
          "$ref": "#/$defs/shape146"
        },
        {
          "$ref": "#/$defs/shape156"
        },
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape162"
        },
        {
          "$ref": "#/$defs/shape165"
        },
        {
          "$ref": "#/$defs/shape168"
        },
        {
          "$ref": "#/$defs/shape176"
        },
        {
          "$ref": "#/$defs/shape179"
        },
        {
          "$ref": "#/$defs/shape182"
        },
        {
          "$ref": "#/$defs/shape187"
        },
        {
          "$ref": "#/$defs/shape190"
        },
        {
          "$ref": "#/$defs/shape193"
        },
        {
          "$ref": "#/$defs/shape196"
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
          "$ref": "#/$defs/shape216"
        },
        {
          "$ref": "#/$defs/shape219"
        },
        {
          "$ref": "#/$defs/shape222"
        },
        {
          "$ref": "#/$defs/shape225"
        },
        {
          "$ref": "#/$defs/shape228"
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
          "$ref": "#/$defs/shape282"
        },
        {
          "$ref": "#/$defs/shape285"
        },
        {
          "$ref": "#/$defs/shape288"
        },
        {
          "$ref": "#/$defs/shape291"
        },
        {
          "$ref": "#/$defs/shape294"
        },
        {
          "$ref": "#/$defs/shape300"
        },
        {
          "$ref": "#/$defs/shape311"
        },
        {
          "$ref": "#/$defs/shape315"
        },
        {
          "$ref": "#/$defs/shape327"
        },
        {
          "$ref": "#/$defs/shape329"
        },
        {
          "$ref": "#/$defs/shape332"
        },
        {
          "$ref": "#/$defs/shape335"
        },
        {
          "$ref": "#/$defs/shape338"
        },
        {
          "$ref": "#/$defs/shape341"
        },
        {
          "$ref": "#/$defs/shape344"
        },
        {
          "$ref": "#/$defs/shape347"
        },
        {
          "$ref": "#/$defs/shape349"
        },
        {
          "$ref": "#/$defs/shape352"
        },
        {
          "$ref": "#/$defs/shape355"
        },
        {
          "$ref": "#/$defs/shape358"
        },
        {
          "$ref": "#/$defs/shape363"
        },
        {
          "$ref": "#/$defs/shape366"
        },
        {
          "$ref": "#/$defs/shape369"
        },
        {
          "$ref": "#/$defs/shape376"
        },
        {
          "$ref": "#/$defs/shape378"
        },
        {
          "$ref": "#/$defs/shape380"
        },
        {
          "$ref": "#/$defs/shape382"
        },
        {
          "$ref": "#/$defs/shape387"
        },
        {
          "$ref": "#/$defs/shape389"
        },
        {
          "$ref": "#/$defs/shape391"
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
          "$ref": "#/$defs/shape406"
        },
        {
          "$ref": "#/$defs/shape408"
        },
        {
          "$ref": "#/$defs/shape410"
        },
        {
          "$ref": "#/$defs/shape412"
        },
        {
          "$ref": "#/$defs/shape418"
        },
        {
          "$ref": "#/$defs/shape424"
        },
        {
          "$ref": "#/$defs/shape428"
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
          "$ref": "#/$defs/shape446"
        },
        {
          "$ref": "#/$defs/shape448"
        },
        {
          "$ref": "#/$defs/shape450"
        },
        {
          "$ref": "#/$defs/shape452"
        },
        {
          "$ref": "#/$defs/shape459"
        },
        {
          "$ref": "#/$defs/shape461"
        },
        {
          "$ref": "#/$defs/shape465"
        },
        {
          "$ref": "#/$defs/shape467"
        },
        {
          "$ref": "#/$defs/shape473"
        },
        {
          "$ref": "#/$defs/shape475"
        },
        {
          "$ref": "#/$defs/shape477"
        },
        {
          "$ref": "#/$defs/shape487"
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
          "$ref": "#/$defs/shape520"
        },
        {
          "$ref": "#/$defs/shape522"
        },
        {
          "$ref": "#/$defs/shape524"
        },
        {
          "$ref": "#/$defs/shape526"
        },
        {
          "$ref": "#/$defs/shape548"
        },
        {
          "$ref": "#/$defs/shape550"
        },
        {
          "$ref": "#/$defs/shape552"
        },
        {
          "$ref": "#/$defs/shape557"
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
          "$ref": "#/$defs/shape567"
        },
        {
          "$ref": "#/$defs/shape569"
        },
        {
          "$ref": "#/$defs/shape571"
        },
        {
          "$ref": "#/$defs/shape576"
        },
        {
          "$ref": "#/$defs/shape578"
        },
        {
          "$ref": "#/$defs/shape580"
        },
        {
          "$ref": "#/$defs/shape587"
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
          "$ref": "#/$defs/shape597"
        },
        {
          "$ref": "#/$defs/shape599"
        },
        {
          "$ref": "#/$defs/shape601"
        },
        {
          "$ref": "#/$defs/shape603"
        },
        {
          "$ref": "#/$defs/shape604"
        },
        {
          "$ref": "#/$defs/shape606"
        }
      ]
    }
  }
}
