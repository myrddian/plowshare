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
    "shape10": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape11"
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
    "shape13": {
      "const": "application-file-read"
    },
    "shape12": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape13"
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
    "shape15": {
      "const": "application-file-save"
    },
    "shape14": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape15"
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
    "shape17": {
      "const": "usage"
    },
    "shape16": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape17"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape19": {
      "const": "context-snapshot"
    },
    "shape21": {
      "const": false
    },
    "shape22": {
      "const": true
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
    "shape18": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape19"
        },
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "measure": {
          "$ref": "#/$defs/shape20"
        }
      },
      "required": [
        "action",
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape24": {
      "const": "relay"
    },
    "shape23": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape24"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape26": {
      "const": "relay-read"
    },
    "shape29": {
      "type": "number"
    },
    "shape28": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape29"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "system": {
          "$ref": "#/$defs/shape21"
        }
      },
      "required": [
        "project"
      ],
      "additionalProperties": false
    },
    "shape31": {
      "type": "null"
    },
    "shape30": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape29"
        },
        "project": {
          "$ref": "#/$defs/shape31"
        },
        "system": {
          "$ref": "#/$defs/shape22"
        }
      },
      "required": [
        "system"
      ],
      "additionalProperties": false
    },
    "shape27": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape28"
        },
        {
          "$ref": "#/$defs/shape30"
        }
      ]
    },
    "shape32": {
      "const": "relay.topics"
    },
    "shape25": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape26"
        },
        "payload": {
          "$ref": "#/$defs/shape27"
        },
        "type": {
          "$ref": "#/$defs/shape32"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape35": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape7"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "system": {
          "$ref": "#/$defs/shape21"
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
    "shape36": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape7"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
        },
        "project": {
          "$ref": "#/$defs/shape31"
        },
        "system": {
          "$ref": "#/$defs/shape22"
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
    "shape34": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape35"
        },
        {
          "$ref": "#/$defs/shape36"
        }
      ]
    },
    "shape37": {
      "const": "relay.log"
    },
    "shape33": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape26"
        },
        "payload": {
          "$ref": "#/$defs/shape34"
        },
        "type": {
          "$ref": "#/$defs/shape37"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape39": {
      "const": "relay-operate"
    },
    "shape42": {
      "const": "ACKNOWLEDGE_GAP"
    },
    "shape43": {
      "const": "RECONCILE"
    },
    "shape44": {
      "const": "ABANDON"
    },
    "shape45": {
      "const": "REMOVE_SUBSCRIPTION"
    },
    "shape46": {
      "const": "REMOVE_TOPIC"
    },
    "shape41": {
      "anyOf": [
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
        }
      ]
    },
    "shape47": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape7"
        }
      ]
    },
    "shape40": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape41"
        },
        "deliveryId": {
          "$ref": "#/$defs/shape47"
        },
        "expectedState": {
          "$ref": "#/$defs/shape47"
        },
        "expiredThrough": {
          "$ref": "#/$defs/shape47"
        },
        "fence": {
          "$ref": "#/$defs/shape47"
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
          "$ref": "#/$defs/shape47"
        },
        "subscriptionGeneration": {
          "$ref": "#/$defs/shape47"
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
    "shape38": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape39"
        },
        "payload": {
          "$ref": "#/$defs/shape40"
        }
      },
      "required": [
        "action",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape49": {
      "const": "relay-trajectory"
    },
    "shape48": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape49"
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
    "shape51": {
      "const": "usage-open"
    },
    "shape55": {
      "const": "day"
    },
    "shape56": {
      "const": "model"
    },
    "shape57": {
      "const": "pool"
    },
    "shape58": {
      "const": "agent"
    },
    "shape59": {
      "const": "operation"
    },
    "shape60": {
      "const": "project"
    },
    "shape61": {
      "const": "run"
    },
    "shape54": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape59"
        },
        {
          "$ref": "#/$defs/shape60"
        },
        {
          "$ref": "#/$defs/shape61"
        }
      ]
    },
    "shape53": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape54"
      }
    },
    "shape63": {
      "const": "direct"
    },
    "shape64": {
      "const": "subtree"
    },
    "shape62": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape63"
        },
        {
          "$ref": "#/$defs/shape64"
        }
      ]
    },
    "shape52": {
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
          "$ref": "#/$defs/shape53"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
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
          "$ref": "#/$defs/shape62"
        },
        "to": {
          "$ref": "#/$defs/shape7"
        }
      },
      "additionalProperties": false
    },
    "shape66": {
      "const": "usage.conversation"
    },
    "shape67": {
      "const": "usage.project"
    },
    "shape68": {
      "const": "usage.agent"
    },
    "shape69": {
      "const": "usage.run"
    },
    "shape70": {
      "const": "usage.orchestration"
    },
    "shape71": {
      "const": "usage.models"
    },
    "shape72": {
      "const": "usage.pools"
    },
    "shape65": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape66"
        },
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
        }
      ]
    },
    "shape50": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape51"
        },
        "filter": {
          "$ref": "#/$defs/shape52"
        },
        "type": {
          "$ref": "#/$defs/shape65"
        }
      },
      "required": [
        "action",
        "filter",
        "type"
      ],
      "additionalProperties": false
    },
    "shape74": {
      "const": "usage-read"
    },
    "shape75": {
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
          "$ref": "#/$defs/shape53"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
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
          "$ref": "#/$defs/shape62"
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
    "shape73": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape74"
        },
        "payload": {
          "$ref": "#/$defs/shape75"
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
    "shape77": {
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
          "$ref": "#/$defs/shape53"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
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
          "$ref": "#/$defs/shape62"
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
    "shape76": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape74"
        },
        "payload": {
          "$ref": "#/$defs/shape77"
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
    "shape79": {
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
          "$ref": "#/$defs/shape53"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
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
          "$ref": "#/$defs/shape62"
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
    "shape78": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape74"
        },
        "payload": {
          "$ref": "#/$defs/shape79"
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
    "shape81": {
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
          "$ref": "#/$defs/shape53"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
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
          "$ref": "#/$defs/shape62"
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
    "shape80": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape74"
        },
        "payload": {
          "$ref": "#/$defs/shape81"
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
    "shape83": {
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
          "$ref": "#/$defs/shape53"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
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
          "$ref": "#/$defs/shape62"
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
    "shape82": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape74"
        },
        "payload": {
          "$ref": "#/$defs/shape83"
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
        "action": {
          "$ref": "#/$defs/shape74"
        },
        "payload": {
          "$ref": "#/$defs/shape52"
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
          "$ref": "#/$defs/shape74"
        },
        "payload": {
          "$ref": "#/$defs/shape52"
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
    "shape87": {
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
          "$ref": "#/$defs/shape53"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
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
          "$ref": "#/$defs/shape62"
        },
        "to": {
          "$ref": "#/$defs/shape7"
        }
      },
      "additionalProperties": false
    },
    "shape88": {
      "const": "usage.calls"
    },
    "shape86": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape74"
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
          "$ref": "#/$defs/shape53"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
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
          "$ref": "#/$defs/shape65"
        },
        "route": {
          "$ref": "#/$defs/shape7"
        },
        "run": {
          "$ref": "#/$defs/shape7"
        },
        "scope": {
          "$ref": "#/$defs/shape62"
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
    "shape91": {
      "const": "usage.subscribe"
    },
    "shape89": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape74"
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
        "subscription": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "subscription"
      ],
      "additionalProperties": false
    },
    "shape94": {
      "const": "usage.unsubscribe"
    },
    "shape92": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape74"
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
    "shape97": {
      "const": "conversation.context.count"
    },
    "shape95": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape74"
        },
        "payload": {
          "$ref": "#/$defs/shape96"
        },
        "type": {
          "$ref": "#/$defs/shape97"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape99": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "measure": {
          "$ref": "#/$defs/shape20"
        }
      },
      "required": [
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape100": {
      "const": "conversation.context.snapshot"
    },
    "shape98": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape74"
        },
        "payload": {
          "$ref": "#/$defs/shape99"
        },
        "type": {
          "$ref": "#/$defs/shape100"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape102": {
      "const": "usage-close"
    },
    "shape101": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape102"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape104": {
      "const": "operator-prepare"
    },
    "shape106": {
      "const": "memory-write"
    },
    "shape107": {
      "const": "memory-digest"
    },
    "shape108": {
      "const": "agent-curate"
    },
    "shape109": {
      "const": "conversation-lifecycle"
    },
    "shape110": {
      "const": "conversation-resume"
    },
    "shape111": {
      "const": "job-limits"
    },
    "shape112": {
      "const": "approval-grant"
    },
    "shape113": {
      "const": "approval-revoke"
    },
    "shape114": {
      "const": "board-topup"
    },
    "shape115": {
      "const": "message-deliveries"
    },
    "shape116": {
      "const": "message-open"
    },
    "shape117": {
      "const": "message-default"
    },
    "shape118": {
      "const": "message-stop"
    },
    "shape119": {
      "const": "message-archive"
    },
    "shape120": {
      "const": "caps"
    },
    "shape105": {
      "anyOf": [
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
        }
      ]
    },
    "shape103": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape104"
        },
        "kind": {
          "$ref": "#/$defs/shape105"
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
    "shape122": {
      "const": "operator-messages"
    },
    "shape121": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape122"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "instance": {
          "$ref": "#/$defs/shape7"
        },
        "offset": {
          "$ref": "#/$defs/shape29"
        }
      },
      "required": [
        "action",
        "identity",
        "instance"
      ],
      "additionalProperties": false
    },
    "shape124": {
      "const": "operator-preview"
    },
    "shape127": {
      "const": "once"
    },
    "shape128": {
      "const": "conversation"
    },
    "shape129": {
      "const": "deny"
    },
    "shape126": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape60"
        },
        {
          "$ref": "#/$defs/shape127"
        },
        {
          "$ref": "#/$defs/shape128"
        },
        {
          "$ref": "#/$defs/shape129"
        }
      ]
    },
    "shape131": {
      "const": "steps"
    },
    "shape132": {
      "const": "budget"
    },
    "shape133": {
      "const": "auto-continue"
    },
    "shape134": {
      "const": "time"
    },
    "shape135": {
      "const": "failed-checks"
    },
    "shape136": {
      "const": "auto-increase"
    },
    "shape130": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape131"
        },
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
        }
      ]
    },
    "shape138": {
      "const": "active"
    },
    "shape139": {
      "const": "archived"
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
    "shape141": {
      "const": "true"
    },
    "shape142": {
      "const": "false"
    },
    "shape140": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape141"
        },
        {
          "$ref": "#/$defs/shape142"
        }
      ]
    },
    "shape125": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "body": {
          "$ref": "#/$defs/shape7"
        },
        "decision": {
          "$ref": "#/$defs/shape126"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        },
        "key": {
          "$ref": "#/$defs/shape130"
        },
        "lifecycle": {
          "$ref": "#/$defs/shape137"
        },
        "makeDefault": {
          "$ref": "#/$defs/shape140"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape29"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape29"
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
          "$ref": "#/$defs/shape29"
        }
      },
      "additionalProperties": false
    },
    "shape123": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape124"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "input": {
          "$ref": "#/$defs/shape125"
        }
      },
      "required": [
        "action",
        "identity",
        "input"
      ],
      "additionalProperties": false
    },
    "shape144": {
      "const": "operator-apply"
    },
    "shape143": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape144"
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
    "shape146": {
      "const": "information"
    },
    "shape147": {
      "const": "upload"
    },
    "shape148": {
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
    "shape152": {
      "const": "personal"
    },
    "shape153": {
      "const": "shared"
    },
    "shape151": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape152"
        },
        {
          "$ref": "#/$defs/shape153"
        }
      ]
    },
    "shape150": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape20"
        },
        "kind": {
          "$ref": "#/$defs/shape151"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape154": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape20"
        },
        "kind": {
          "$ref": "#/$defs/shape60"
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
    "shape149": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape150"
        },
        {
          "$ref": "#/$defs/shape154"
        }
      ]
    },
    "shape145": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape147"
        },
        "payload": {
          "$ref": "#/$defs/shape148"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "acquire"
    },
    "shape157": {
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
    "shape155": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape156"
        },
        "payload": {
          "$ref": "#/$defs/shape157"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "refresh"
    },
    "shape160": {
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
    "shape158": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape159"
        },
        "payload": {
          "$ref": "#/$defs/shape160"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "revise"
    },
    "shape163": {
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
    "shape161": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape162"
        },
        "payload": {
          "$ref": "#/$defs/shape163"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "replace"
    },
    "shape166": {
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
    "shape164": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape165"
        },
        "payload": {
          "$ref": "#/$defs/shape166"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "list"
    },
    "shape171": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape7"
      }
    },
    "shape173": {
      "const": "source"
    },
    "shape174": {
      "const": "report"
    },
    "shape172": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape173"
        },
        {
          "$ref": "#/$defs/shape174"
        }
      ]
    },
    "shape170": {
      "type": "object",
      "properties": {
        "author": {
          "$ref": "#/$defs/shape7"
        },
        "autoTag": {
          "$ref": "#/$defs/shape171"
        },
        "documentAuthor": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape172"
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
          "$ref": "#/$defs/shape171"
        },
        "when": {
          "$ref": "#/$defs/shape7"
        }
      },
      "additionalProperties": false
    },
    "shape169": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape170"
        },
        "kind": {
          "$ref": "#/$defs/shape172"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
        },
        "offset": {
          "$ref": "#/$defs/shape29"
        }
      },
      "additionalProperties": false
    },
    "shape167": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape168"
        },
        "payload": {
          "$ref": "#/$defs/shape169"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "facets"
    },
    "shape177": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape170"
        },
        "kind": {
          "$ref": "#/$defs/shape172"
        }
      },
      "additionalProperties": false
    },
    "shape175": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape176"
        },
        "payload": {
          "$ref": "#/$defs/shape177"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "tags"
    },
    "shape180": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        },
        "tags": {
          "$ref": "#/$defs/shape171"
        }
      },
      "required": [
        "requestId",
        "revision",
        "tags"
      ],
      "additionalProperties": false
    },
    "shape178": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape179"
        },
        "payload": {
          "$ref": "#/$defs/shape180"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "tags.groups"
    },
    "shape185": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape171"
      }
    },
    "shape184": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape185"
        }
      ]
    },
    "shape183": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape184"
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
    "shape181": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape182"
        },
        "payload": {
          "$ref": "#/$defs/shape183"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "inventory"
    },
    "shape188": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape29"
        },
        "offset": {
          "$ref": "#/$defs/shape29"
        }
      },
      "additionalProperties": false
    },
    "shape186": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape187"
        },
        "payload": {
          "$ref": "#/$defs/shape188"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "acquisitions"
    },
    "shape191": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape29"
        },
        "offset": {
          "$ref": "#/$defs/shape29"
        }
      },
      "additionalProperties": false
    },
    "shape189": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape190"
        },
        "payload": {
          "$ref": "#/$defs/shape191"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "status"
    },
    "shape194": {
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
    "shape192": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape193"
        },
        "payload": {
          "$ref": "#/$defs/shape194"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "await"
    },
    "shape201": {
      "forbidden": true
    },
    "shape200": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape201"
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
    "shape202": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape201"
        }
      },
      "required": [
        "acquisition"
      ],
      "additionalProperties": false
    },
    "shape199": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape200"
        },
        {
          "$ref": "#/$defs/shape202"
        }
      ]
    },
    "shape198": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape199"
      }
    },
    "shape197": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape198"
        },
        "waitMs": {
          "$ref": "#/$defs/shape29"
        }
      },
      "required": [
        "sources"
      ],
      "additionalProperties": false
    },
    "shape195": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape196"
        },
        "payload": {
          "$ref": "#/$defs/shape197"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "read"
    },
    "shape205": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape29"
        },
        "offset": {
          "$ref": "#/$defs/shape29"
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
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape204"
        },
        "payload": {
          "$ref": "#/$defs/shape205"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "outline"
    },
    "shape208": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape29"
        },
        "offset": {
          "$ref": "#/$defs/shape29"
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
    "shape206": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape207"
        },
        "payload": {
          "$ref": "#/$defs/shape208"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "symbols"
    },
    "shape211": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape29"
        },
        "offset": {
          "$ref": "#/$defs/shape29"
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
    "shape209": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape210"
        },
        "payload": {
          "$ref": "#/$defs/shape211"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "search"
    },
    "shape214": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape170"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
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
    "shape212": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape213"
        },
        "payload": {
          "$ref": "#/$defs/shape214"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "rank"
    },
    "shape217": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape170"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
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
    "shape215": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape216"
        },
        "payload": {
          "$ref": "#/$defs/shape217"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "ask"
    },
    "shape220": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape29"
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
    "shape218": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape219"
        },
        "payload": {
          "$ref": "#/$defs/shape220"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "evidence.record"
    },
    "shape223": {
      "type": "object",
      "properties": {
        "end": {
          "$ref": "#/$defs/shape29"
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
          "$ref": "#/$defs/shape29"
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
    "shape221": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape222"
        },
        "payload": {
          "$ref": "#/$defs/shape223"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
    "shape225": {
      "const": "evidence.read"
    },
    "shape226": {
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
    "shape224": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape225"
        },
        "payload": {
          "$ref": "#/$defs/shape226"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
    "shape228": {
      "const": "record.report"
    },
    "shape233": {
      "const": "holds"
    },
    "shape234": {
      "const": "weakened"
    },
    "shape235": {
      "const": "refuted"
    },
    "shape236": {
      "const": "not_checked"
    },
    "shape232": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape233"
        },
        {
          "$ref": "#/$defs/shape234"
        },
        {
          "$ref": "#/$defs/shape235"
        },
        {
          "$ref": "#/$defs/shape236"
        }
      ]
    },
    "shape231": {
      "type": "object",
      "properties": {
        "claim": {
          "$ref": "#/$defs/shape7"
        },
        "counterEvidence": {
          "$ref": "#/$defs/shape171"
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
          "$ref": "#/$defs/shape171"
        },
        "verdict": {
          "$ref": "#/$defs/shape232"
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
    "shape230": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape231"
      }
    },
    "shape238": {
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
    "shape237": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape238"
      }
    },
    "shape229": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape171"
        },
        "feedback": {
          "$ref": "#/$defs/shape7"
        },
        "findings": {
          "$ref": "#/$defs/shape230"
        },
        "inputs": {
          "$ref": "#/$defs/shape171"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "objectives": {
          "$ref": "#/$defs/shape171"
        },
        "requestId": {
          "$ref": "#/$defs/shape7"
        },
        "reviews": {
          "$ref": "#/$defs/shape237"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape171"
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
    "shape227": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape228"
        },
        "payload": {
          "$ref": "#/$defs/shape229"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "finalise"
    },
    "shape241": {
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
    "shape239": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape240"
        },
        "payload": {
          "$ref": "#/$defs/shape241"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "link"
    },
    "shape244": {
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
    "shape242": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape243"
        },
        "payload": {
          "$ref": "#/$defs/shape244"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "unlink"
    },
    "shape247": {
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
    "shape245": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape246"
        },
        "payload": {
          "$ref": "#/$defs/shape247"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "share"
    },
    "shape250": {
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
    "shape248": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape249"
        },
        "payload": {
          "$ref": "#/$defs/shape250"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "unshare"
    },
    "shape253": {
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
    "shape251": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape252"
        },
        "payload": {
          "$ref": "#/$defs/shape253"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "withdraw"
    },
    "shape256": {
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
    "shape254": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape255"
        },
        "payload": {
          "$ref": "#/$defs/shape256"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "exclude"
    },
    "shape259": {
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
    "shape257": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape258"
        },
        "payload": {
          "$ref": "#/$defs/shape259"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "unexclude"
    },
    "shape262": {
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
    "shape260": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape261"
        },
        "payload": {
          "$ref": "#/$defs/shape262"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "restore"
    },
    "shape265": {
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
    "shape263": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape264"
        },
        "payload": {
          "$ref": "#/$defs/shape265"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "delete"
    },
    "shape268": {
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
    "shape266": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape267"
        },
        "payload": {
          "$ref": "#/$defs/shape268"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "retry"
    },
    "shape271": {
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
    "shape269": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape270"
        },
        "payload": {
          "$ref": "#/$defs/shape271"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "rebuild"
    },
    "shape274": {
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
    "shape272": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape273"
        },
        "payload": {
          "$ref": "#/$defs/shape274"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "allowance"
    },
    "shape277": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape29"
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
    "shape275": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape276"
        },
        "payload": {
          "$ref": "#/$defs/shape277"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "events"
    },
    "shape280": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape29"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
        }
      },
      "additionalProperties": false
    },
    "shape278": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape279"
        },
        "payload": {
          "$ref": "#/$defs/shape280"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "migration.list"
    },
    "shape283": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape29"
        },
        "offset": {
          "$ref": "#/$defs/shape29"
        }
      },
      "additionalProperties": false
    },
    "shape281": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape282"
        },
        "payload": {
          "$ref": "#/$defs/shape283"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
      "const": "migration.adopt"
    },
    "shape286": {
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
    "shape284": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape285"
        },
        "payload": {
          "$ref": "#/$defs/shape286"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
    "shape288": {
      "const": "migration.inspect"
    },
    "shape289": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape29"
        },
        "offset": {
          "$ref": "#/$defs/shape29"
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
    "shape287": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape288"
        },
        "payload": {
          "$ref": "#/$defs/shape289"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
    "shape291": {
      "const": "migration.release"
    },
    "shape292": {
      "type": "object",
      "properties": {
        "inputs": {
          "$ref": "#/$defs/shape171"
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
    "shape290": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape146"
        },
        "operation": {
          "$ref": "#/$defs/shape291"
        },
        "payload": {
          "$ref": "#/$defs/shape292"
        },
        "scope": {
          "$ref": "#/$defs/shape149"
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
    "shape295": {
      "const": "bootstrap"
    },
    "shape296": {
      "const": "demo"
    },
    "shape297": {
      "const": "disconnect"
    },
    "shape298": {
      "const": "approvals-refresh"
    },
    "shape294": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape295"
        },
        {
          "$ref": "#/$defs/shape296"
        },
        {
          "$ref": "#/$defs/shape297"
        },
        {
          "$ref": "#/$defs/shape298"
        }
      ]
    },
    "shape293": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape294"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape300": {
      "const": "project-access"
    },
    "shape302": {
      "const": "project.access"
    },
    "shape303": {
      "const": "project.member.add"
    },
    "shape304": {
      "const": "project.member.remove"
    },
    "shape305": {
      "const": "project.member.role"
    },
    "shape301": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape302"
        },
        {
          "$ref": "#/$defs/shape303"
        },
        {
          "$ref": "#/$defs/shape304"
        },
        {
          "$ref": "#/$defs/shape305"
        }
      ]
    },
    "shape307": {
      "const": "VIEWER"
    },
    "shape308": {
      "const": "CONTRIBUTOR"
    },
    "shape309": {
      "const": "MANAGER"
    },
    "shape306": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape307"
        },
        {
          "$ref": "#/$defs/shape308"
        },
        {
          "$ref": "#/$defs/shape309"
        }
      ]
    },
    "shape299": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape300"
        },
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape301"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "role": {
          "$ref": "#/$defs/shape306"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape311": {
      "const": "server-admin"
    },
    "shape312": {
      "const": "admin.pricing.list"
    },
    "shape313": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape201"
      }
    },
    "shape310": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
        },
        "operation": {
          "$ref": "#/$defs/shape312"
        },
        "payload": {
          "$ref": "#/$defs/shape313"
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
      "const": "admin.pricing.set"
    },
    "shape318": {
      "const": "TOKEN"
    },
    "shape319": {
      "const": "INCLUDED"
    },
    "shape320": {
      "const": "ZERO_RATE"
    },
    "shape321": {
      "const": "UNPRICED"
    },
    "shape317": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape318"
        },
        {
          "$ref": "#/$defs/shape319"
        },
        {
          "$ref": "#/$defs/shape320"
        },
        {
          "$ref": "#/$defs/shape321"
        }
      ]
    },
    "shape322": {
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
    "shape324": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape29"
        },
        "rates": {
          "$ref": "#/$defs/shape325"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape323": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape324"
      }
    },
    "shape316": {
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
          "$ref": "#/$defs/shape317"
        },
        "model": {
          "$ref": "#/$defs/shape7"
        },
        "rates": {
          "$ref": "#/$defs/shape322"
        },
        "requestFee": {
          "$ref": "#/$defs/shape7"
        },
        "source": {
          "$ref": "#/$defs/shape7"
        },
        "tiers": {
          "$ref": "#/$defs/shape323"
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
    "shape314": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
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
    "shape327": {
      "const": "admin.accounts"
    },
    "shape326": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
        },
        "operation": {
          "$ref": "#/$defs/shape327"
        },
        "payload": {
          "$ref": "#/$defs/shape313"
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
      "const": "admin.account.create"
    },
    "shape330": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape20"
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
          "$ref": "#/$defs/shape311"
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
      "const": "admin.account.update"
    },
    "shape333": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape20"
        },
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape20"
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
          "$ref": "#/$defs/shape311"
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
      "const": "admin.account.reset"
    },
    "shape336": {
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
    "shape334": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
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
      "const": "admin.sessions"
    },
    "shape339": {
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
    "shape337": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
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
      "const": "admin.session.revoke"
    },
    "shape342": {
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
    "shape340": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
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
      "const": "admin.audit"
    },
    "shape345": {
      "type": "object",
      "properties": {
        "before": {
          "$ref": "#/$defs/shape29"
        },
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "limit": {
          "$ref": "#/$defs/shape29"
        }
      },
      "additionalProperties": false
    },
    "shape343": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
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
    "shape347": {
      "const": "admin.service.accounts"
    },
    "shape346": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
        },
        "operation": {
          "$ref": "#/$defs/shape347"
        },
        "payload": {
          "$ref": "#/$defs/shape313"
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
      "const": "admin.service.account.create"
    },
    "shape350": {
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
    "shape348": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
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
      "const": "admin.service.account.update"
    },
    "shape353": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape20"
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
    "shape351": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
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
      "const": "admin.service.tokens"
    },
    "shape356": {
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
    "shape354": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
        },
        "operation": {
          "$ref": "#/$defs/shape355"
        },
        "payload": {
          "$ref": "#/$defs/shape356"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape358": {
      "const": "admin.service.token.create"
    },
    "shape361": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "role": {
          "$ref": "#/$defs/shape306"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape360": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape361"
      }
    },
    "shape359": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape29"
        },
        "handle": {
          "$ref": "#/$defs/shape7"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "scopes": {
          "$ref": "#/$defs/shape360"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape357": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
        },
        "operation": {
          "$ref": "#/$defs/shape358"
        },
        "payload": {
          "$ref": "#/$defs/shape359"
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
      "const": "admin.service.token.rotate"
    },
    "shape364": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape29"
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
    "shape362": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
        },
        "operation": {
          "$ref": "#/$defs/shape363"
        },
        "payload": {
          "$ref": "#/$defs/shape364"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape366": {
      "const": "admin.service.token.revoke"
    },
    "shape367": {
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
    "shape365": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape311"
        },
        "operation": {
          "$ref": "#/$defs/shape366"
        },
        "payload": {
          "$ref": "#/$defs/shape367"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape369": {
      "const": "server-project-create"
    },
    "shape371": {
      "const": "MANAGED"
    },
    "shape372": {
      "const": "DISJOINT"
    },
    "shape370": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape371"
        },
        {
          "$ref": "#/$defs/shape372"
        }
      ]
    },
    "shape373": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape7"
      }
    },
    "shape368": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape369"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "type": {
          "$ref": "#/$defs/shape370"
        },
        "workspace": {
          "$ref": "#/$defs/shape7"
        },
        "writePaths": {
          "$ref": "#/$defs/shape373"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape375": {
      "const": "server-setup"
    },
    "shape374": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape375"
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
    "shape377": {
      "const": "connect"
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
    "shape379": {
      "const": "connection-select"
    },
    "shape378": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape379"
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
    "shape381": {
      "const": "connection-preferences"
    },
    "shape383": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape7"
      }
    },
    "shape384": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape20"
      }
    },
    "shape382": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape383"
        },
        "drafts": {
          "$ref": "#/$defs/shape383"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape384"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape384"
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
    "shape380": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape7"
        },
        "action": {
          "$ref": "#/$defs/shape381"
        },
        "preference": {
          "$ref": "#/$defs/shape382"
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
    "shape386": {
      "const": "connection-rename"
    },
    "shape385": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape386"
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
    "shape388": {
      "const": "connection-remove"
    },
    "shape387": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape388"
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
    "shape390": {
      "const": "personal-section"
    },
    "shape392": {
      "const": "In"
    },
    "shape393": {
      "const": "Out"
    },
    "shape394": {
      "const": "Resources"
    },
    "shape395": {
      "const": "Archive"
    },
    "shape396": {
      "const": "Planning"
    },
    "shape397": {
      "const": "Bots"
    },
    "shape391": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape392"
        },
        {
          "$ref": "#/$defs/shape393"
        },
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
        }
      ]
    },
    "shape389": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape390"
        },
        "path": {
          "$ref": "#/$defs/shape7"
        },
        "section": {
          "$ref": "#/$defs/shape391"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape399": {
      "const": "personal-bots"
    },
    "shape398": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape399"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape401": {
      "const": "personal-recreate"
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
      "const": "files-choose"
    },
    "shape402": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape403"
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
    "shape405": {
      "const": "files-withdraw"
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
      "const": "sync-refresh"
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
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape409": {
      "const": "sync-inspect"
    },
    "shape408": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape409"
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
    "shape411": {
      "const": "sync-change"
    },
    "shape413": {
      "const": "on"
    },
    "shape414": {
      "const": "off"
    },
    "shape415": {
      "const": "now"
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
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape412"
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
    "shape417": {
      "const": "sync-resolve"
    },
    "shape419": {
      "const": "mine"
    },
    "shape420": {
      "const": "theirs"
    },
    "shape421": {
      "const": "done"
    },
    "shape418": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape419"
        },
        {
          "$ref": "#/$defs/shape420"
        },
        {
          "$ref": "#/$defs/shape421"
        }
      ]
    },
    "shape416": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape417"
        },
        "how": {
          "$ref": "#/$defs/shape418"
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
    "shape424": {
      "const": "project-open"
    },
    "shape425": {
      "const": "project-remove"
    },
    "shape423": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape424"
        },
        {
          "$ref": "#/$defs/shape425"
        }
      ]
    },
    "shape422": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape423"
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
    "shape428": {
      "const": "scope"
    },
    "shape429": {
      "const": "create"
    },
    "shape427": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape428"
        },
        {
          "$ref": "#/$defs/shape429"
        }
      ]
    },
    "shape426": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape427"
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
    "shape431": {
      "const": "history"
    },
    "shape430": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape431"
        },
        "before": {
          "$ref": "#/$defs/shape29"
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
    "shape433": {
      "const": "select"
    },
    "shape432": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape433"
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
    "shape435": {
      "const": "trajectory"
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
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape437": {
      "const": "board-post-topics"
    },
    "shape436": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape437"
        },
        "more": {
          "$ref": "#/$defs/shape20"
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
    "shape439": {
      "const": "board-create"
    },
    "shape438": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape439"
        },
        "body": {
          "$ref": "#/$defs/shape7"
        },
        "label": {
          "$ref": "#/$defs/shape7"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape29"
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
    "shape441": {
      "const": "board-retry"
    },
    "shape440": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape441"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape29"
        },
        "member": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "reconcile": {
          "$ref": "#/$defs/shape20"
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
    "shape443": {
      "const": "board-post"
    },
    "shape442": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape443"
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
    "shape445": {
      "const": "workspace-chat"
    },
    "shape444": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape445"
        },
        "manage": {
          "$ref": "#/$defs/shape20"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape447": {
      "const": "workspace-layout"
    },
    "shape446": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape447"
        },
        "height": {
          "$ref": "#/$defs/shape29"
        },
        "visible": {
          "$ref": "#/$defs/shape20"
        },
        "width": {
          "$ref": "#/$defs/shape29"
        },
        "x": {
          "$ref": "#/$defs/shape29"
        },
        "y": {
          "$ref": "#/$defs/shape29"
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
    "shape449": {
      "const": "workspace-refresh"
    },
    "shape448": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape449"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape451": {
      "const": "library"
    },
    "shape453": {
      "const": "sources"
    },
    "shape454": {
      "const": "documents"
    },
    "shape455": {
      "const": "memories"
    },
    "shape456": {
      "const": "manual"
    },
    "shape452": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape213"
        },
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
    "shape450": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape451"
        },
        "chapter": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape47"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        },
        "view": {
          "$ref": "#/$defs/shape452"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape458": {
      "const": "library-view"
    },
    "shape457": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape458"
        },
        "chapter": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape47"
        },
        "revision": {
          "$ref": "#/$defs/shape7"
        },
        "view": {
          "$ref": "#/$defs/shape452"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape461": {
      "const": "library-refresh"
    },
    "shape462": {
      "const": "library-citations"
    },
    "shape460": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape461"
        },
        {
          "$ref": "#/$defs/shape462"
        }
      ]
    },
    "shape459": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape460"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape464": {
      "const": "library-documents"
    },
    "shape463": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape464"
        },
        "more": {
          "$ref": "#/$defs/shape20"
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
    "shape467": {
      "const": "library-document"
    },
    "shape468": {
      "const": "library-memory"
    },
    "shape469": {
      "const": "library-chunk"
    },
    "shape470": {
      "const": "library-conversation"
    },
    "shape466": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape467"
        },
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
    "shape465": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
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
    "shape472": {
      "const": "library-source-text"
    },
    "shape471": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape472"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        },
        "offset": {
          "$ref": "#/$defs/shape29"
        }
      },
      "required": [
        "action",
        "id",
        "offset"
      ],
      "additionalProperties": false
    },
    "shape474": {
      "const": "library-stance"
    },
    "shape473": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape474"
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
    "shape476": {
      "const": "library-search"
    },
    "shape478": {
      "const": "retrieve"
    },
    "shape479": {
      "const": "recall"
    },
    "shape480": {
      "const": "navigate"
    },
    "shape477": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape216"
        },
        {
          "$ref": "#/$defs/shape128"
        },
        {
          "$ref": "#/$defs/shape454"
        },
        {
          "$ref": "#/$defs/shape478"
        },
        {
          "$ref": "#/$defs/shape479"
        },
        {
          "$ref": "#/$defs/shape480"
        }
      ]
    },
    "shape482": {
      "const": "lexical"
    },
    "shape483": {
      "const": "semantic"
    },
    "shape484": {
      "const": "hybrid"
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
        }
      ]
    },
    "shape475": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape476"
        },
        "kind": {
          "$ref": "#/$defs/shape477"
        },
        "mode": {
          "$ref": "#/$defs/shape481"
        },
        "more": {
          "$ref": "#/$defs/shape20"
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
    "shape486": {
      "const": "library-maintain"
    },
    "shape488": {
      "const": "invalidate"
    },
    "shape489": {
      "const": "resolve"
    },
    "shape490": {
      "const": "reembed"
    },
    "shape491": {
      "const": "reconsider"
    },
    "shape487": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape488"
        },
        {
          "$ref": "#/$defs/shape489"
        },
        {
          "$ref": "#/$defs/shape490"
        },
        {
          "$ref": "#/$defs/shape491"
        }
      ]
    },
    "shape485": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape20"
        },
        "action": {
          "$ref": "#/$defs/shape486"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape487"
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
    "shape493": {
      "const": "builder-outputs"
    },
    "shape492": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape493"
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
    "shape495": {
      "const": "builder-trajectory"
    },
    "shape494": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape495"
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
    "shape497": {
      "const": "builder-prepare"
    },
    "shape496": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape497"
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
    "shape499": {
      "const": "builder-start"
    },
    "shape498": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape499"
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
    "shape501": {
      "const": "activity"
    },
    "shape503": {
      "const": "inbox"
    },
    "shape504": {
      "const": "runs"
    },
    "shape505": {
      "const": "definitions"
    },
    "shape506": {
      "const": "schedules"
    },
    "shape507": {
      "const": "builder"
    },
    "shape502": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape503"
        },
        {
          "$ref": "#/$defs/shape504"
        },
        {
          "$ref": "#/$defs/shape505"
        },
        {
          "$ref": "#/$defs/shape506"
        },
        {
          "$ref": "#/$defs/shape507"
        }
      ]
    },
    "shape500": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape501"
        },
        "view": {
          "$ref": "#/$defs/shape502"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape509": {
      "const": "activity-view"
    },
    "shape508": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
        },
        "view": {
          "$ref": "#/$defs/shape502"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape511": {
      "const": "activity-refresh"
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
      "const": "question-refresh"
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
      "const": "inbox-read"
    },
    "shape514": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape515"
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
    "shape517": {
      "const": "inbox-more"
    },
    "shape516": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape517"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape519": {
      "const": "run-detail"
    },
    "shape518": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape519"
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
    "shape521": {
      "const": "schedule-refresh"
    },
    "shape520": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape521"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape523": {
      "const": "schedule-preview"
    },
    "shape522": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape523"
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
    "shape525": {
      "const": "schedule-save"
    },
    "shape529": {
      "const": "skill"
    },
    "shape530": {
      "const": "orchestration"
    },
    "shape528": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape58"
        },
        {
          "$ref": "#/$defs/shape529"
        },
        {
          "$ref": "#/$defs/shape530"
        }
      ]
    },
    "shape532": {
      "const": "INHERITED"
    },
    "shape533": {
      "const": "SUMMARISED"
    },
    "shape534": {
      "const": "NEW"
    },
    "shape535": {
      "const": "DIRECT"
    },
    "shape531": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape532"
        },
        {
          "$ref": "#/$defs/shape533"
        },
        {
          "$ref": "#/$defs/shape534"
        },
        {
          "$ref": "#/$defs/shape535"
        }
      ]
    },
    "shape527": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape7"
        },
        "input": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape528"
        },
        "mode": {
          "$ref": "#/$defs/shape531"
        },
        "name": {
          "$ref": "#/$defs/shape47"
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
    "shape537": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape29"
        }
      ]
    },
    "shape536": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape537"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape537"
        },
        "queueCap": {
          "$ref": "#/$defs/shape29"
        }
      },
      "required": [
        "maxModelCalls",
        "maxTurns",
        "queueCap"
      ],
      "additionalProperties": false
    },
    "shape540": {
      "const": "mailbox"
    },
    "shape541": {
      "const": "message"
    },
    "shape539": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape128"
        },
        {
          "$ref": "#/$defs/shape540"
        },
        {
          "$ref": "#/$defs/shape541"
        }
      ]
    },
    "shape538": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape47"
        },
        "kind": {
          "$ref": "#/$defs/shape539"
        },
        "project": {
          "$ref": "#/$defs/shape47"
        },
        "route": {
          "$ref": "#/$defs/shape47"
        },
        "to": {
          "$ref": "#/$defs/shape47"
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
    "shape542": {
      "const": 1
    },
    "shape526": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape527"
        },
        "cron": {
          "$ref": "#/$defs/shape7"
        },
        "limits": {
          "$ref": "#/$defs/shape536"
        },
        "paused": {
          "$ref": "#/$defs/shape20"
        },
        "target": {
          "$ref": "#/$defs/shape538"
        },
        "version": {
          "$ref": "#/$defs/shape542"
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
    "shape544": {
      "const": "server"
    },
    "shape545": {
      "const": "workspace"
    },
    "shape543": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape544"
        },
        {
          "$ref": "#/$defs/shape545"
        }
      ]
    },
    "shape524": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape525"
        },
        "definition": {
          "$ref": "#/$defs/shape526"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "source": {
          "$ref": "#/$defs/shape543"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape547": {
      "const": "schedule-file-save"
    },
    "shape546": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape547"
        },
        "definition": {
          "$ref": "#/$defs/shape526"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "overwrite": {
          "$ref": "#/$defs/shape20"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "source": {
          "$ref": "#/$defs/shape543"
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
    "shape549": {
      "const": "schedule-sync"
    },
    "shape548": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape549"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "source": {
          "$ref": "#/$defs/shape543"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape551": {
      "const": "schedule-change"
    },
    "shape553": {
      "const": "schedule"
    },
    "shape554": {
      "const": "trigger"
    },
    "shape552": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape553"
        },
        {
          "$ref": "#/$defs/shape554"
        }
      ]
    },
    "shape550": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape551"
        },
        "identity": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape552"
        },
        "name": {
          "$ref": "#/$defs/shape7"
        },
        "paused": {
          "$ref": "#/$defs/shape20"
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
    "shape556": {
      "const": "schedule-fire"
    },
    "shape555": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape556"
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
    "shape558": {
      "const": "run-definitions"
    },
    "shape557": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape558"
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
    "shape560": {
      "const": "run-record"
    },
    "shape559": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape560"
        },
        "before": {
          "$ref": "#/$defs/shape29"
        },
        "id": {
          "$ref": "#/$defs/shape7"
        },
        "kinds": {
          "$ref": "#/$defs/shape373"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape562": {
      "const": "run-answer"
    },
    "shape564": {
      "type": "object",
      "properties": {
        "chosen": {
          "$ref": "#/$defs/shape171"
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
    "shape563": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape564"
      }
    },
    "shape561": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape562"
        },
        "answer": {
          "$ref": "#/$defs/shape7"
        },
        "choices": {
          "$ref": "#/$defs/shape563"
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
    "shape566": {
      "const": "run-cancel"
    },
    "shape565": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape566"
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
    "shape568": {
      "const": "run-resume"
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
      "const": "run-trajectory"
    },
    "shape572": {
      "const": "conductor"
    },
    "shape573": {
      "const": "caller"
    },
    "shape571": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape572"
        },
        {
          "$ref": "#/$defs/shape573"
        }
      ]
    },
    "shape569": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape570"
        },
        "actor": {
          "$ref": "#/$defs/shape571"
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
    "shape575": {
      "const": "run-stage-trajectory"
    },
    "shape574": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape575"
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
    "shape577": {
      "const": "delegate-trajectory"
    },
    "shape576": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape577"
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
    "shape580": {
      "const": "board-inspection"
    },
    "shape581": {
      "const": "board-view"
    },
    "shape579": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape580"
        },
        {
          "$ref": "#/$defs/shape581"
        }
      ]
    },
    "shape583": {
      "const": "board"
    },
    "shape584": {
      "const": "swarm"
    },
    "shape582": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape583"
        },
        {
          "$ref": "#/$defs/shape584"
        }
      ]
    },
    "shape578": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape579"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "view": {
          "$ref": "#/$defs/shape582"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape587": {
      "const": "board-refresh"
    },
    "shape588": {
      "const": "board-more"
    },
    "shape586": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape587"
        },
        {
          "$ref": "#/$defs/shape588"
        }
      ]
    },
    "shape585": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape586"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape590": {
      "const": "board-topic"
    },
    "shape589": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape590"
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
    "shape592": {
      "const": "board-trajectory"
    },
    "shape591": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape592"
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
    "shape594": {
      "const": "context"
    },
    "shape593": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape594"
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
    "shape596": {
      "const": "open-link"
    },
    "shape595": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape596"
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
    "shape598": {
      "const": "copy-text"
    },
    "shape597": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape598"
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
    "shape600": {
      "const": "workflow-start"
    },
    "shape599": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape600"
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
    "shape601": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape61"
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
    "shape603": {
      "const": "cancel"
    },
    "shape602": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape603"
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
    "shape605": {
      "const": "answer"
    },
    "shape606": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape127"
        },
        {
          "$ref": "#/$defs/shape129"
        }
      ]
    },
    "shape604": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape605"
        },
        "decision": {
          "$ref": "#/$defs/shape606"
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
          "$ref": "#/$defs/shape12"
        },
        {
          "$ref": "#/$defs/shape14"
        },
        {
          "$ref": "#/$defs/shape16"
        },
        {
          "$ref": "#/$defs/shape18"
        },
        {
          "$ref": "#/$defs/shape23"
        },
        {
          "$ref": "#/$defs/shape25"
        },
        {
          "$ref": "#/$defs/shape33"
        },
        {
          "$ref": "#/$defs/shape38"
        },
        {
          "$ref": "#/$defs/shape48"
        },
        {
          "$ref": "#/$defs/shape50"
        },
        {
          "$ref": "#/$defs/shape73"
        },
        {
          "$ref": "#/$defs/shape76"
        },
        {
          "$ref": "#/$defs/shape78"
        },
        {
          "$ref": "#/$defs/shape80"
        },
        {
          "$ref": "#/$defs/shape82"
        },
        {
          "$ref": "#/$defs/shape84"
        },
        {
          "$ref": "#/$defs/shape85"
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
          "$ref": "#/$defs/shape98"
        },
        {
          "$ref": "#/$defs/shape101"
        },
        {
          "$ref": "#/$defs/shape103"
        },
        {
          "$ref": "#/$defs/shape121"
        },
        {
          "$ref": "#/$defs/shape123"
        },
        {
          "$ref": "#/$defs/shape143"
        },
        {
          "$ref": "#/$defs/shape145"
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
          "$ref": "#/$defs/shape164"
        },
        {
          "$ref": "#/$defs/shape167"
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
          "$ref": "#/$defs/shape186"
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
          "$ref": "#/$defs/shape224"
        },
        {
          "$ref": "#/$defs/shape227"
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
          "$ref": "#/$defs/shape290"
        },
        {
          "$ref": "#/$defs/shape293"
        },
        {
          "$ref": "#/$defs/shape299"
        },
        {
          "$ref": "#/$defs/shape310"
        },
        {
          "$ref": "#/$defs/shape314"
        },
        {
          "$ref": "#/$defs/shape326"
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
          "$ref": "#/$defs/shape343"
        },
        {
          "$ref": "#/$defs/shape346"
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
          "$ref": "#/$defs/shape357"
        },
        {
          "$ref": "#/$defs/shape362"
        },
        {
          "$ref": "#/$defs/shape365"
        },
        {
          "$ref": "#/$defs/shape368"
        },
        {
          "$ref": "#/$defs/shape374"
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
          "$ref": "#/$defs/shape385"
        },
        {
          "$ref": "#/$defs/shape387"
        },
        {
          "$ref": "#/$defs/shape389"
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
          "$ref": "#/$defs/shape406"
        },
        {
          "$ref": "#/$defs/shape408"
        },
        {
          "$ref": "#/$defs/shape410"
        },
        {
          "$ref": "#/$defs/shape416"
        },
        {
          "$ref": "#/$defs/shape422"
        },
        {
          "$ref": "#/$defs/shape426"
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
          "$ref": "#/$defs/shape446"
        },
        {
          "$ref": "#/$defs/shape448"
        },
        {
          "$ref": "#/$defs/shape450"
        },
        {
          "$ref": "#/$defs/shape457"
        },
        {
          "$ref": "#/$defs/shape459"
        },
        {
          "$ref": "#/$defs/shape463"
        },
        {
          "$ref": "#/$defs/shape465"
        },
        {
          "$ref": "#/$defs/shape471"
        },
        {
          "$ref": "#/$defs/shape473"
        },
        {
          "$ref": "#/$defs/shape475"
        },
        {
          "$ref": "#/$defs/shape485"
        },
        {
          "$ref": "#/$defs/shape492"
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
          "$ref": "#/$defs/shape520"
        },
        {
          "$ref": "#/$defs/shape522"
        },
        {
          "$ref": "#/$defs/shape524"
        },
        {
          "$ref": "#/$defs/shape546"
        },
        {
          "$ref": "#/$defs/shape548"
        },
        {
          "$ref": "#/$defs/shape550"
        },
        {
          "$ref": "#/$defs/shape555"
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
          "$ref": "#/$defs/shape565"
        },
        {
          "$ref": "#/$defs/shape567"
        },
        {
          "$ref": "#/$defs/shape569"
        },
        {
          "$ref": "#/$defs/shape574"
        },
        {
          "$ref": "#/$defs/shape576"
        },
        {
          "$ref": "#/$defs/shape578"
        },
        {
          "$ref": "#/$defs/shape585"
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
          "$ref": "#/$defs/shape597"
        },
        {
          "$ref": "#/$defs/shape599"
        },
        {
          "$ref": "#/$defs/shape601"
        },
        {
          "$ref": "#/$defs/shape602"
        },
        {
          "$ref": "#/$defs/shape604"
        }
      ]
    }
  }
}
