// Generated from Desktop Request; run the SDK schema generator with --desktop.
import type { Schema } from 'plowshare-client-ts/operations/schema'
export const DESKTOP_SCHEMA: {request: Schema; $defs: Record<string,Schema>} = {
  "request": {
    "$ref": "#/$defs/shape0"
  },
  "$defs": {
    "shape2": {
      "const": "application-deployment"
    },
    "shape3": {
      "const": "application.deploy"
    },
    "shape6": {
      "type": "string"
    },
    "shape5": {
      "type": "object",
      "properties": {
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "store": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "path",
        "store"
      ],
      "additionalProperties": false
    },
    "shape8": {
      "type": "null"
    },
    "shape7": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape6"
        }
      ]
    },
    "shape10": {
      "type": "object",
      "properties": {
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "path",
        "text"
      ],
      "additionalProperties": false
    },
    "shape9": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape10"
      }
    },
    "shape11": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape4": {
      "type": "object",
      "properties": {
        "destination": {
          "$ref": "#/$defs/shape5"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape7"
        },
        "files": {
          "$ref": "#/$defs/shape9"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape11"
        }
      },
      "required": [
        "destination",
        "expectedRevision",
        "files",
        "project",
        "requestId",
        "writableAreas"
      ],
      "additionalProperties": false
    },
    "shape1": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape2"
        },
        "operation": {
          "$ref": "#/$defs/shape3"
        },
        "payload": {
          "$ref": "#/$defs/shape4"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape13": {
      "const": "application.activate"
    },
    "shape14": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "expectedRevision",
        "project",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape12": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape2"
        },
        "operation": {
          "$ref": "#/$defs/shape13"
        },
        "payload": {
          "$ref": "#/$defs/shape14"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape16": {
      "const": "application.deployment.status"
    },
    "shape17": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "project"
      ],
      "additionalProperties": false
    },
    "shape15": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape2"
        },
        "operation": {
          "$ref": "#/$defs/shape16"
        },
        "payload": {
          "$ref": "#/$defs/shape17"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape19": {
      "const": "application.deployment.receipt"
    },
    "shape20": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "project",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape18": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape2"
        },
        "operation": {
          "$ref": "#/$defs/shape19"
        },
        "payload": {
          "$ref": "#/$defs/shape20"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape22": {
      "const": "application-package"
    },
    "shape21": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape22"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape24": {
      "const": "server-filestore-list"
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
      "const": "filestore-load"
    },
    "shape25": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape26"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape28": {
      "const": "filestore-choose"
    },
    "shape27": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape28"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape30": {
      "const": "filestore-setup"
    },
    "shape29": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape30"
        },
        "alias": {
          "$ref": "#/$defs/shape6"
        },
        "root": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "alias",
        "root"
      ],
      "additionalProperties": false
    },
    "shape32": {
      "const": "application-runtime"
    },
    "shape31": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape32"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape34": {
      "const": "application-files"
    },
    "shape33": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape34"
        },
        "location": {
          "$ref": "#/$defs/shape5"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape36": {
      "const": "application-file-read"
    },
    "shape35": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape36"
        },
        "location": {
          "$ref": "#/$defs/shape5"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "path",
        "project"
      ],
      "additionalProperties": false
    },
    "shape38": {
      "const": "application-file-save"
    },
    "shape37": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape38"
        },
        "location": {
          "$ref": "#/$defs/shape5"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "text": {
          "$ref": "#/$defs/shape6"
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
    "shape40": {
      "const": "usage"
    },
    "shape39": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape40"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape42": {
      "const": "context-snapshot"
    },
    "shape44": {
      "const": false
    },
    "shape45": {
      "const": true
    },
    "shape43": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape44"
        },
        {
          "$ref": "#/$defs/shape45"
        }
      ]
    },
    "shape41": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape42"
        },
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "measure": {
          "$ref": "#/$defs/shape43"
        }
      },
      "required": [
        "action",
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape47": {
      "const": "relay"
    },
    "shape46": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape47"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape49": {
      "const": "relay-read"
    },
    "shape52": {
      "type": "number"
    },
    "shape51": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "system": {
          "$ref": "#/$defs/shape44"
        }
      },
      "required": [
        "project"
      ],
      "additionalProperties": false
    },
    "shape53": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "project": {
          "$ref": "#/$defs/shape8"
        },
        "system": {
          "$ref": "#/$defs/shape45"
        }
      },
      "required": [
        "system"
      ],
      "additionalProperties": false
    },
    "shape50": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape51"
        },
        {
          "$ref": "#/$defs/shape53"
        }
      ]
    },
    "shape54": {
      "const": "relay.topics"
    },
    "shape48": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape49"
        },
        "payload": {
          "$ref": "#/$defs/shape50"
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
    "shape57": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape6"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "system": {
          "$ref": "#/$defs/shape44"
        },
        "topic": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "project",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape58": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape6"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "project": {
          "$ref": "#/$defs/shape8"
        },
        "system": {
          "$ref": "#/$defs/shape45"
        },
        "topic": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "system",
        "topic"
      ],
      "additionalProperties": false
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
    "shape59": {
      "const": "relay.log"
    },
    "shape55": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape49"
        },
        "payload": {
          "$ref": "#/$defs/shape56"
        },
        "type": {
          "$ref": "#/$defs/shape59"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape61": {
      "const": "relay-operate"
    },
    "shape64": {
      "const": "ACKNOWLEDGE_GAP"
    },
    "shape65": {
      "const": "RECONCILE"
    },
    "shape66": {
      "const": "ABANDON"
    },
    "shape67": {
      "const": "REMOVE_SUBSCRIPTION"
    },
    "shape68": {
      "const": "REMOVE_TOPIC"
    },
    "shape63": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape64"
        },
        {
          "$ref": "#/$defs/shape65"
        },
        {
          "$ref": "#/$defs/shape66"
        },
        {
          "$ref": "#/$defs/shape67"
        },
        {
          "$ref": "#/$defs/shape68"
        }
      ]
    },
    "shape62": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape63"
        },
        "deliveryId": {
          "$ref": "#/$defs/shape7"
        },
        "expectedState": {
          "$ref": "#/$defs/shape7"
        },
        "expiredThrough": {
          "$ref": "#/$defs/shape7"
        },
        "fence": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "reason": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "subscriber": {
          "$ref": "#/$defs/shape7"
        },
        "subscriptionGeneration": {
          "$ref": "#/$defs/shape7"
        },
        "topic": {
          "$ref": "#/$defs/shape6"
        },
        "topicGeneration": {
          "$ref": "#/$defs/shape6"
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
    "shape60": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape61"
        },
        "payload": {
          "$ref": "#/$defs/shape62"
        }
      },
      "required": [
        "action",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape70": {
      "const": "relay-trajectory"
    },
    "shape69": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape70"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape72": {
      "const": "usage-open"
    },
    "shape76": {
      "const": "day"
    },
    "shape77": {
      "const": "model"
    },
    "shape78": {
      "const": "pool"
    },
    "shape79": {
      "const": "agent"
    },
    "shape80": {
      "const": "operation"
    },
    "shape81": {
      "const": "project"
    },
    "shape82": {
      "const": "run"
    },
    "shape75": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape76"
        },
        {
          "$ref": "#/$defs/shape77"
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
          "$ref": "#/$defs/shape81"
        },
        {
          "$ref": "#/$defs/shape82"
        }
      ]
    },
    "shape74": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape75"
      }
    },
    "shape84": {
      "const": "direct"
    },
    "shape85": {
      "const": "subtree"
    },
    "shape83": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape84"
        },
        {
          "$ref": "#/$defs/shape85"
        }
      ]
    },
    "shape73": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "cursor": {
          "$ref": "#/$defs/shape6"
        },
        "from": {
          "$ref": "#/$defs/shape6"
        },
        "group_by": {
          "$ref": "#/$defs/shape74"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "orchestration": {
          "$ref": "#/$defs/shape6"
        },
        "pool": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "route": {
          "$ref": "#/$defs/shape6"
        },
        "run": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape83"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape87": {
      "const": "usage.conversation"
    },
    "shape88": {
      "const": "usage.project"
    },
    "shape89": {
      "const": "usage.agent"
    },
    "shape90": {
      "const": "usage.run"
    },
    "shape91": {
      "const": "usage.orchestration"
    },
    "shape92": {
      "const": "usage.models"
    },
    "shape93": {
      "const": "usage.pools"
    },
    "shape86": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape87"
        },
        {
          "$ref": "#/$defs/shape88"
        },
        {
          "$ref": "#/$defs/shape89"
        },
        {
          "$ref": "#/$defs/shape90"
        },
        {
          "$ref": "#/$defs/shape91"
        },
        {
          "$ref": "#/$defs/shape92"
        },
        {
          "$ref": "#/$defs/shape93"
        }
      ]
    },
    "shape71": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape72"
        },
        "filter": {
          "$ref": "#/$defs/shape73"
        },
        "type": {
          "$ref": "#/$defs/shape86"
        }
      },
      "required": [
        "action",
        "filter",
        "type"
      ],
      "additionalProperties": false
    },
    "shape95": {
      "const": "usage-read"
    },
    "shape96": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "cursor": {
          "$ref": "#/$defs/shape6"
        },
        "from": {
          "$ref": "#/$defs/shape6"
        },
        "group_by": {
          "$ref": "#/$defs/shape74"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "orchestration": {
          "$ref": "#/$defs/shape6"
        },
        "pool": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "route": {
          "$ref": "#/$defs/shape6"
        },
        "run": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape83"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape94": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape95"
        },
        "payload": {
          "$ref": "#/$defs/shape96"
        },
        "type": {
          "$ref": "#/$defs/shape87"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape98": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "cursor": {
          "$ref": "#/$defs/shape6"
        },
        "from": {
          "$ref": "#/$defs/shape6"
        },
        "group_by": {
          "$ref": "#/$defs/shape74"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "orchestration": {
          "$ref": "#/$defs/shape6"
        },
        "pool": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "route": {
          "$ref": "#/$defs/shape6"
        },
        "run": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape83"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "project"
      ],
      "additionalProperties": false
    },
    "shape97": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape95"
        },
        "payload": {
          "$ref": "#/$defs/shape98"
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
    "shape100": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "cursor": {
          "$ref": "#/$defs/shape6"
        },
        "from": {
          "$ref": "#/$defs/shape6"
        },
        "group_by": {
          "$ref": "#/$defs/shape74"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "orchestration": {
          "$ref": "#/$defs/shape6"
        },
        "pool": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "route": {
          "$ref": "#/$defs/shape6"
        },
        "run": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape83"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "agent"
      ],
      "additionalProperties": false
    },
    "shape99": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape95"
        },
        "payload": {
          "$ref": "#/$defs/shape100"
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
    "shape102": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "cursor": {
          "$ref": "#/$defs/shape6"
        },
        "from": {
          "$ref": "#/$defs/shape6"
        },
        "group_by": {
          "$ref": "#/$defs/shape74"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "orchestration": {
          "$ref": "#/$defs/shape6"
        },
        "pool": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "route": {
          "$ref": "#/$defs/shape6"
        },
        "run": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape83"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "run"
      ],
      "additionalProperties": false
    },
    "shape101": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape95"
        },
        "payload": {
          "$ref": "#/$defs/shape102"
        },
        "type": {
          "$ref": "#/$defs/shape90"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape104": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "cursor": {
          "$ref": "#/$defs/shape6"
        },
        "from": {
          "$ref": "#/$defs/shape6"
        },
        "group_by": {
          "$ref": "#/$defs/shape74"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "orchestration": {
          "$ref": "#/$defs/shape6"
        },
        "pool": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "route": {
          "$ref": "#/$defs/shape6"
        },
        "run": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape83"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "orchestration"
      ],
      "additionalProperties": false
    },
    "shape103": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape95"
        },
        "payload": {
          "$ref": "#/$defs/shape104"
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
    "shape105": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape95"
        },
        "payload": {
          "$ref": "#/$defs/shape73"
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
    "shape106": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape95"
        },
        "payload": {
          "$ref": "#/$defs/shape73"
        },
        "type": {
          "$ref": "#/$defs/shape93"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape108": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "attempt_cursor": {
          "$ref": "#/$defs/shape6"
        },
        "call": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "cursor": {
          "$ref": "#/$defs/shape6"
        },
        "from": {
          "$ref": "#/$defs/shape6"
        },
        "group_by": {
          "$ref": "#/$defs/shape74"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "orchestration": {
          "$ref": "#/$defs/shape6"
        },
        "pool": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "route": {
          "$ref": "#/$defs/shape6"
        },
        "run": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape83"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape109": {
      "const": "usage.calls"
    },
    "shape107": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape95"
        },
        "payload": {
          "$ref": "#/$defs/shape108"
        },
        "type": {
          "$ref": "#/$defs/shape109"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape111": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "cursor": {
          "$ref": "#/$defs/shape6"
        },
        "from": {
          "$ref": "#/$defs/shape6"
        },
        "group_by": {
          "$ref": "#/$defs/shape74"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "orchestration": {
          "$ref": "#/$defs/shape6"
        },
        "pool": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "report_type": {
          "$ref": "#/$defs/shape86"
        },
        "route": {
          "$ref": "#/$defs/shape6"
        },
        "run": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape83"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "report_type"
      ],
      "additionalProperties": false
    },
    "shape112": {
      "const": "usage.subscribe"
    },
    "shape110": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape95"
        },
        "payload": {
          "$ref": "#/$defs/shape111"
        },
        "type": {
          "$ref": "#/$defs/shape112"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape114": {
      "type": "object",
      "properties": {
        "subscription": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "subscription"
      ],
      "additionalProperties": false
    },
    "shape115": {
      "const": "usage.unsubscribe"
    },
    "shape113": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape95"
        },
        "payload": {
          "$ref": "#/$defs/shape114"
        },
        "type": {
          "$ref": "#/$defs/shape115"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape117": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape118": {
      "const": "conversation.context.count"
    },
    "shape116": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape95"
        },
        "payload": {
          "$ref": "#/$defs/shape117"
        },
        "type": {
          "$ref": "#/$defs/shape118"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape120": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "measure": {
          "$ref": "#/$defs/shape43"
        }
      },
      "required": [
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape121": {
      "const": "conversation.context.snapshot"
    },
    "shape119": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape95"
        },
        "payload": {
          "$ref": "#/$defs/shape120"
        },
        "type": {
          "$ref": "#/$defs/shape121"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape123": {
      "const": "usage-close"
    },
    "shape122": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape123"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape125": {
      "const": "operator-prepare"
    },
    "shape127": {
      "const": "memory-write"
    },
    "shape128": {
      "const": "memory-digest"
    },
    "shape129": {
      "const": "agent-curate"
    },
    "shape130": {
      "const": "conversation-lifecycle"
    },
    "shape131": {
      "const": "conversation-resume"
    },
    "shape132": {
      "const": "job-limits"
    },
    "shape133": {
      "const": "approval-grant"
    },
    "shape134": {
      "const": "approval-revoke"
    },
    "shape135": {
      "const": "board-topup"
    },
    "shape136": {
      "const": "message-deliveries"
    },
    "shape137": {
      "const": "message-open"
    },
    "shape138": {
      "const": "message-default"
    },
    "shape139": {
      "const": "message-stop"
    },
    "shape140": {
      "const": "message-archive"
    },
    "shape141": {
      "const": "caps"
    },
    "shape126": {
      "anyOf": [
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
        },
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
        },
        {
          "$ref": "#/$defs/shape137"
        },
        {
          "$ref": "#/$defs/shape138"
        },
        {
          "$ref": "#/$defs/shape139"
        },
        {
          "$ref": "#/$defs/shape140"
        },
        {
          "$ref": "#/$defs/shape141"
        }
      ]
    },
    "shape124": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape125"
        },
        "kind": {
          "$ref": "#/$defs/shape126"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "kind"
      ],
      "additionalProperties": false
    },
    "shape143": {
      "const": "operator-messages"
    },
    "shape142": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape143"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "instance": {
          "$ref": "#/$defs/shape6"
        },
        "offset": {
          "$ref": "#/$defs/shape52"
        }
      },
      "required": [
        "action",
        "identity",
        "instance"
      ],
      "additionalProperties": false
    },
    "shape145": {
      "const": "operator-preview"
    },
    "shape148": {
      "const": "once"
    },
    "shape149": {
      "const": "conversation"
    },
    "shape150": {
      "const": "deny"
    },
    "shape147": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape81"
        },
        {
          "$ref": "#/$defs/shape148"
        },
        {
          "$ref": "#/$defs/shape149"
        },
        {
          "$ref": "#/$defs/shape150"
        }
      ]
    },
    "shape152": {
      "const": "steps"
    },
    "shape153": {
      "const": "budget"
    },
    "shape154": {
      "const": "auto-continue"
    },
    "shape155": {
      "const": "time"
    },
    "shape156": {
      "const": "failed-checks"
    },
    "shape157": {
      "const": "auto-increase"
    },
    "shape151": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape152"
        },
        {
          "$ref": "#/$defs/shape153"
        },
        {
          "$ref": "#/$defs/shape154"
        },
        {
          "$ref": "#/$defs/shape155"
        },
        {
          "$ref": "#/$defs/shape156"
        },
        {
          "$ref": "#/$defs/shape157"
        }
      ]
    },
    "shape159": {
      "const": "active"
    },
    "shape160": {
      "const": "archived"
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
    "shape162": {
      "const": "true"
    },
    "shape163": {
      "const": "false"
    },
    "shape161": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape162"
        },
        {
          "$ref": "#/$defs/shape163"
        }
      ]
    },
    "shape146": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "body": {
          "$ref": "#/$defs/shape6"
        },
        "decision": {
          "$ref": "#/$defs/shape147"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "key": {
          "$ref": "#/$defs/shape151"
        },
        "lifecycle": {
          "$ref": "#/$defs/shape158"
        },
        "makeDefault": {
          "$ref": "#/$defs/shape161"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape52"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape52"
        },
        "prefix": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape6"
        },
        "summary": {
          "$ref": "#/$defs/shape6"
        },
        "value": {
          "$ref": "#/$defs/shape52"
        }
      },
      "additionalProperties": false
    },
    "shape144": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape145"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape146"
        }
      },
      "required": [
        "action",
        "identity",
        "input"
      ],
      "additionalProperties": false
    },
    "shape165": {
      "const": "operator-apply"
    },
    "shape164": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape167": {
      "const": "information"
    },
    "shape168": {
      "const": "upload"
    },
    "shape169": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape173": {
      "const": "personal"
    },
    "shape174": {
      "const": "shared"
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
    "shape171": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape43"
        },
        "kind": {
          "$ref": "#/$defs/shape172"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape175": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape43"
        },
        "kind": {
          "$ref": "#/$defs/shape81"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "kind",
        "project"
      ],
      "additionalProperties": false
    },
    "shape170": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape171"
        },
        {
          "$ref": "#/$defs/shape175"
        }
      ]
    },
    "shape166": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape168"
        },
        "payload": {
          "$ref": "#/$defs/shape169"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "acquire"
    },
    "shape178": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "url": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "requestId",
        "url"
      ],
      "additionalProperties": false
    },
    "shape176": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape177"
        },
        "payload": {
          "$ref": "#/$defs/shape178"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "refresh"
    },
    "shape181": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape179": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape180"
        },
        "payload": {
          "$ref": "#/$defs/shape181"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "revise"
    },
    "shape184": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape182": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape183"
        },
        "payload": {
          "$ref": "#/$defs/shape184"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
    "shape186": {
      "const": "replace"
    },
    "shape187": {
      "type": "object",
      "properties": {
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape185": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape186"
        },
        "payload": {
          "$ref": "#/$defs/shape187"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
    "shape189": {
      "const": "list"
    },
    "shape192": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape194": {
      "const": "source"
    },
    "shape195": {
      "const": "report"
    },
    "shape193": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape194"
        },
        {
          "$ref": "#/$defs/shape195"
        }
      ]
    },
    "shape191": {
      "type": "object",
      "properties": {
        "author": {
          "$ref": "#/$defs/shape6"
        },
        "autoTag": {
          "$ref": "#/$defs/shape192"
        },
        "documentAuthor": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape193"
        },
        "search": {
          "$ref": "#/$defs/shape6"
        },
        "subtype": {
          "$ref": "#/$defs/shape6"
        },
        "tagGroup": {
          "$ref": "#/$defs/shape6"
        },
        "tags": {
          "$ref": "#/$defs/shape192"
        },
        "when": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape190": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape191"
        },
        "kind": {
          "$ref": "#/$defs/shape193"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "offset": {
          "$ref": "#/$defs/shape52"
        }
      },
      "additionalProperties": false
    },
    "shape188": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape189"
        },
        "payload": {
          "$ref": "#/$defs/shape190"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "facets"
    },
    "shape198": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape191"
        },
        "kind": {
          "$ref": "#/$defs/shape193"
        }
      },
      "additionalProperties": false
    },
    "shape196": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape197"
        },
        "payload": {
          "$ref": "#/$defs/shape198"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
    "shape200": {
      "const": "tags"
    },
    "shape201": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "tags": {
          "$ref": "#/$defs/shape192"
        }
      },
      "required": [
        "requestId",
        "revision",
        "tags"
      ],
      "additionalProperties": false
    },
    "shape199": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape200"
        },
        "payload": {
          "$ref": "#/$defs/shape201"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
    "shape203": {
      "const": "tags.groups"
    },
    "shape206": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape192"
      }
    },
    "shape205": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape206"
        }
      ]
    },
    "shape204": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape205"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "groups",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape202": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape203"
        },
        "payload": {
          "$ref": "#/$defs/shape204"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "inventory"
    },
    "shape209": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "offset": {
          "$ref": "#/$defs/shape52"
        }
      },
      "additionalProperties": false
    },
    "shape207": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape208"
        },
        "payload": {
          "$ref": "#/$defs/shape209"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "acquisitions"
    },
    "shape212": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "offset": {
          "$ref": "#/$defs/shape52"
        }
      },
      "additionalProperties": false
    },
    "shape210": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape211"
        },
        "payload": {
          "$ref": "#/$defs/shape212"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "status"
    },
    "shape215": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape213": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape214"
        },
        "payload": {
          "$ref": "#/$defs/shape215"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "await"
    },
    "shape222": {
      "forbidden": true
    },
    "shape221": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape222"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape223": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape222"
        }
      },
      "required": [
        "acquisition"
      ],
      "additionalProperties": false
    },
    "shape220": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape221"
        },
        {
          "$ref": "#/$defs/shape223"
        }
      ]
    },
    "shape219": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape220"
      }
    },
    "shape218": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape219"
        },
        "waitMs": {
          "$ref": "#/$defs/shape52"
        }
      },
      "required": [
        "sources"
      ],
      "additionalProperties": false
    },
    "shape216": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape217"
        },
        "payload": {
          "$ref": "#/$defs/shape218"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "read"
    },
    "shape226": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "offset": {
          "$ref": "#/$defs/shape52"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape224": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape225"
        },
        "payload": {
          "$ref": "#/$defs/shape226"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "outline"
    },
    "shape229": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "offset": {
          "$ref": "#/$defs/shape52"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape227": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape228"
        },
        "payload": {
          "$ref": "#/$defs/shape229"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
    "shape231": {
      "const": "symbols"
    },
    "shape232": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "offset": {
          "$ref": "#/$defs/shape52"
        },
        "query": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape230": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape231"
        },
        "payload": {
          "$ref": "#/$defs/shape232"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "search"
    },
    "shape235": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape191"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "query": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape233": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape234"
        },
        "payload": {
          "$ref": "#/$defs/shape235"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "rank"
    },
    "shape238": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape191"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "query": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape236": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape237"
        },
        "payload": {
          "$ref": "#/$defs/shape238"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "ask"
    },
    "shape241": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape52"
        },
        "question": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "question",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape239": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape240"
        },
        "payload": {
          "$ref": "#/$defs/shape241"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "evidence.record"
    },
    "shape244": {
      "type": "object",
      "properties": {
        "end": {
          "$ref": "#/$defs/shape52"
        },
        "locator": {
          "$ref": "#/$defs/shape6"
        },
        "quote": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "start": {
          "$ref": "#/$defs/shape52"
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
    "shape242": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape243"
        },
        "payload": {
          "$ref": "#/$defs/shape244"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "evidence.read"
    },
    "shape247": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "evidence"
      ],
      "additionalProperties": false
    },
    "shape245": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape246"
        },
        "payload": {
          "$ref": "#/$defs/shape247"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "record.report"
    },
    "shape254": {
      "const": "holds"
    },
    "shape255": {
      "const": "weakened"
    },
    "shape256": {
      "const": "refuted"
    },
    "shape257": {
      "const": "not_checked"
    },
    "shape253": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape254"
        },
        {
          "$ref": "#/$defs/shape255"
        },
        {
          "$ref": "#/$defs/shape256"
        },
        {
          "$ref": "#/$defs/shape257"
        }
      ]
    },
    "shape252": {
      "type": "object",
      "properties": {
        "claim": {
          "$ref": "#/$defs/shape6"
        },
        "counterEvidence": {
          "$ref": "#/$defs/shape192"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "objective": {
          "$ref": "#/$defs/shape6"
        },
        "rationale": {
          "$ref": "#/$defs/shape6"
        },
        "support": {
          "$ref": "#/$defs/shape192"
        },
        "verdict": {
          "$ref": "#/$defs/shape253"
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
    "shape251": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape252"
      }
    },
    "shape259": {
      "type": "object",
      "properties": {
        "outcome": {
          "$ref": "#/$defs/shape6"
        },
        "stage": {
          "$ref": "#/$defs/shape6"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "outcome",
        "stage",
        "text"
      ],
      "additionalProperties": false
    },
    "shape258": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape259"
      }
    },
    "shape250": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape192"
        },
        "feedback": {
          "$ref": "#/$defs/shape6"
        },
        "findings": {
          "$ref": "#/$defs/shape251"
        },
        "inputs": {
          "$ref": "#/$defs/shape192"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "objectives": {
          "$ref": "#/$defs/shape192"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "reviews": {
          "$ref": "#/$defs/shape258"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape192"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape248": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape249"
        },
        "payload": {
          "$ref": "#/$defs/shape250"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "finalise"
    },
    "shape262": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
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
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape261"
        },
        "payload": {
          "$ref": "#/$defs/shape262"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "link"
    },
    "shape265": {
      "type": "object",
      "properties": {
        "collectionProject": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape263": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape264"
        },
        "payload": {
          "$ref": "#/$defs/shape265"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "unlink"
    },
    "shape268": {
      "type": "object",
      "properties": {
        "collectionProject": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape266": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape267"
        },
        "payload": {
          "$ref": "#/$defs/shape268"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "share"
    },
    "shape271": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape269": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape270"
        },
        "payload": {
          "$ref": "#/$defs/shape271"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "unshare"
    },
    "shape274": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape272": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape273"
        },
        "payload": {
          "$ref": "#/$defs/shape274"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "withdraw"
    },
    "shape277": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape275": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape276"
        },
        "payload": {
          "$ref": "#/$defs/shape277"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "exclude"
    },
    "shape280": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape278": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape279"
        },
        "payload": {
          "$ref": "#/$defs/shape280"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "unexclude"
    },
    "shape283": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape281": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape282"
        },
        "payload": {
          "$ref": "#/$defs/shape283"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "restore"
    },
    "shape286": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape284": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape285"
        },
        "payload": {
          "$ref": "#/$defs/shape286"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "delete"
    },
    "shape289": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape287": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape288"
        },
        "payload": {
          "$ref": "#/$defs/shape289"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
      "const": "retry"
    },
    "shape292": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape290": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape291"
        },
        "payload": {
          "$ref": "#/$defs/shape292"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
    "shape294": {
      "const": "rebuild"
    },
    "shape295": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "stage": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "requestId",
        "revision",
        "stage"
      ],
      "additionalProperties": false
    },
    "shape293": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape294"
        },
        "payload": {
          "$ref": "#/$defs/shape295"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
    "shape297": {
      "const": "allowance"
    },
    "shape298": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape52"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "maxModelCalls",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape296": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape297"
        },
        "payload": {
          "$ref": "#/$defs/shape298"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
    "shape300": {
      "const": "events"
    },
    "shape301": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape52"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        }
      },
      "additionalProperties": false
    },
    "shape299": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape300"
        },
        "payload": {
          "$ref": "#/$defs/shape301"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
    "shape303": {
      "const": "migration.list"
    },
    "shape304": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "offset": {
          "$ref": "#/$defs/shape52"
        }
      },
      "additionalProperties": false
    },
    "shape302": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape303"
        },
        "payload": {
          "$ref": "#/$defs/shape304"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
    "shape306": {
      "const": "migration.adopt"
    },
    "shape307": {
      "type": "object",
      "properties": {
        "collectionProject": {
          "$ref": "#/$defs/shape6"
        },
        "owner": {
          "$ref": "#/$defs/shape6"
        },
        "reason": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "visibility": {
          "$ref": "#/$defs/shape6"
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
    "shape305": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape306"
        },
        "payload": {
          "$ref": "#/$defs/shape307"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
    "shape309": {
      "const": "migration.inspect"
    },
    "shape310": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape52"
        },
        "offset": {
          "$ref": "#/$defs/shape52"
        },
        "payload": {
          "$ref": "#/$defs/shape6"
        },
        "reason": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "payload",
        "reason"
      ],
      "additionalProperties": false
    },
    "shape308": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape309"
        },
        "payload": {
          "$ref": "#/$defs/shape310"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
    "shape312": {
      "const": "migration.release"
    },
    "shape313": {
      "type": "object",
      "properties": {
        "inputs": {
          "$ref": "#/$defs/shape192"
        },
        "owner": {
          "$ref": "#/$defs/shape6"
        },
        "payload": {
          "$ref": "#/$defs/shape6"
        },
        "reason": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
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
    "shape311": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape167"
        },
        "operation": {
          "$ref": "#/$defs/shape312"
        },
        "payload": {
          "$ref": "#/$defs/shape313"
        },
        "scope": {
          "$ref": "#/$defs/shape170"
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
    "shape316": {
      "const": "bootstrap"
    },
    "shape317": {
      "const": "demo"
    },
    "shape318": {
      "const": "disconnect"
    },
    "shape319": {
      "const": "approvals-refresh"
    },
    "shape315": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape180"
        },
        {
          "$ref": "#/$defs/shape316"
        },
        {
          "$ref": "#/$defs/shape317"
        },
        {
          "$ref": "#/$defs/shape318"
        },
        {
          "$ref": "#/$defs/shape319"
        }
      ]
    },
    "shape314": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape315"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape321": {
      "const": "project-access"
    },
    "shape323": {
      "const": "project.access"
    },
    "shape324": {
      "const": "project.member.add"
    },
    "shape325": {
      "const": "project.member.remove"
    },
    "shape326": {
      "const": "project.member.role"
    },
    "shape322": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape323"
        },
        {
          "$ref": "#/$defs/shape324"
        },
        {
          "$ref": "#/$defs/shape325"
        },
        {
          "$ref": "#/$defs/shape326"
        }
      ]
    },
    "shape328": {
      "const": "VIEWER"
    },
    "shape329": {
      "const": "CONTRIBUTOR"
    },
    "shape330": {
      "const": "MANAGER"
    },
    "shape327": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape328"
        },
        {
          "$ref": "#/$defs/shape329"
        },
        {
          "$ref": "#/$defs/shape330"
        }
      ]
    },
    "shape320": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape321"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape322"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape327"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape332": {
      "const": "server-admin"
    },
    "shape333": {
      "const": "admin.pricing.list"
    },
    "shape334": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape222"
      }
    },
    "shape331": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
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
      "const": "admin.pricing.set"
    },
    "shape339": {
      "const": "TOKEN"
    },
    "shape340": {
      "const": "INCLUDED"
    },
    "shape341": {
      "const": "ZERO_RATE"
    },
    "shape342": {
      "const": "UNPRICED"
    },
    "shape338": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape339"
        },
        {
          "$ref": "#/$defs/shape340"
        },
        {
          "$ref": "#/$defs/shape341"
        },
        {
          "$ref": "#/$defs/shape342"
        }
      ]
    },
    "shape343": {
      "type": "object",
      "properties": {
        "cacheRead": {
          "$ref": "#/$defs/shape6"
        },
        "cacheWrite": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "output": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "input",
        "output"
      ],
      "additionalProperties": false
    },
    "shape346": {
      "type": "object",
      "properties": {
        "cacheRead": {
          "$ref": "#/$defs/shape6"
        },
        "cacheWrite": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "output": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "input",
        "output"
      ],
      "additionalProperties": false
    },
    "shape345": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape52"
        },
        "rates": {
          "$ref": "#/$defs/shape346"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape344": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape345"
      }
    },
    "shape337": {
      "type": "object",
      "properties": {
        "billingRoute": {
          "$ref": "#/$defs/shape6"
        },
        "currency": {
          "$ref": "#/$defs/shape6"
        },
        "expectedVersion": {
          "$ref": "#/$defs/shape6"
        },
        "mode": {
          "$ref": "#/$defs/shape338"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "rates": {
          "$ref": "#/$defs/shape343"
        },
        "requestFee": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "tiers": {
          "$ref": "#/$defs/shape344"
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
    "shape335": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
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
    "shape348": {
      "const": "admin.accounts"
    },
    "shape347": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
        },
        "operation": {
          "$ref": "#/$defs/shape348"
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
    "shape350": {
      "const": "admin.account.create"
    },
    "shape351": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape43"
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
          "$ref": "#/$defs/shape332"
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
      "const": "admin.account.update"
    },
    "shape354": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape43"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape43"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape352": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
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
      "const": "admin.account.reset"
    },
    "shape357": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape6"
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
          "$ref": "#/$defs/shape332"
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
      "const": "admin.sessions"
    },
    "shape360": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape358": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
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
    "shape362": {
      "const": "admin.session.revoke"
    },
    "shape363": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape361": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
        },
        "operation": {
          "$ref": "#/$defs/shape362"
        },
        "payload": {
          "$ref": "#/$defs/shape363"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape365": {
      "const": "admin.audit"
    },
    "shape366": {
      "type": "object",
      "properties": {
        "before": {
          "$ref": "#/$defs/shape52"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "limit": {
          "$ref": "#/$defs/shape52"
        }
      },
      "additionalProperties": false
    },
    "shape364": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
        },
        "operation": {
          "$ref": "#/$defs/shape365"
        },
        "payload": {
          "$ref": "#/$defs/shape366"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape368": {
      "const": "admin.service.accounts"
    },
    "shape367": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
        },
        "operation": {
          "$ref": "#/$defs/shape368"
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
    "shape370": {
      "const": "admin.service.account.create"
    },
    "shape371": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape369": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
        },
        "operation": {
          "$ref": "#/$defs/shape370"
        },
        "payload": {
          "$ref": "#/$defs/shape371"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape373": {
      "const": "admin.service.account.update"
    },
    "shape374": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape43"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "enabled",
        "handle"
      ],
      "additionalProperties": false
    },
    "shape372": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
        },
        "operation": {
          "$ref": "#/$defs/shape373"
        },
        "payload": {
          "$ref": "#/$defs/shape374"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape376": {
      "const": "admin.service.tokens"
    },
    "shape377": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape375": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
        },
        "operation": {
          "$ref": "#/$defs/shape376"
        },
        "payload": {
          "$ref": "#/$defs/shape377"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape379": {
      "const": "admin.service.token.create"
    },
    "shape382": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape327"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape381": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape382"
      }
    },
    "shape380": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape52"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "scopes": {
          "$ref": "#/$defs/shape381"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape378": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
        },
        "operation": {
          "$ref": "#/$defs/shape379"
        },
        "payload": {
          "$ref": "#/$defs/shape380"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape384": {
      "const": "admin.service.token.rotate"
    },
    "shape385": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape52"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "handle",
        "id"
      ],
      "additionalProperties": false
    },
    "shape383": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
        },
        "operation": {
          "$ref": "#/$defs/shape384"
        },
        "payload": {
          "$ref": "#/$defs/shape385"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape387": {
      "const": "admin.service.token.revoke"
    },
    "shape388": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "handle",
        "id"
      ],
      "additionalProperties": false
    },
    "shape386": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape332"
        },
        "operation": {
          "$ref": "#/$defs/shape387"
        },
        "payload": {
          "$ref": "#/$defs/shape388"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape390": {
      "const": "server-project-create"
    },
    "shape392": {
      "const": "MANAGED"
    },
    "shape393": {
      "const": "DISJOINT"
    },
    "shape391": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape392"
        },
        {
          "$ref": "#/$defs/shape393"
        }
      ]
    },
    "shape394": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape395": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape389": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape390"
        },
        "applicationRoot": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape391"
        },
        "workspace": {
          "$ref": "#/$defs/shape6"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape394"
        },
        "writePaths": {
          "$ref": "#/$defs/shape395"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape397": {
      "const": "server-setup"
    },
    "shape396": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape397"
        },
        "base": {
          "$ref": "#/$defs/shape6"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "password": {
          "$ref": "#/$defs/shape6"
        },
        "temporaryPassword": {
          "$ref": "#/$defs/shape6"
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
    "shape399": {
      "const": "connect"
    },
    "shape398": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape399"
        },
        "base": {
          "$ref": "#/$defs/shape6"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "password": {
          "$ref": "#/$defs/shape6"
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
    "shape401": {
      "const": "connection-select"
    },
    "shape400": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape401"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape403": {
      "const": "connection-preferences"
    },
    "shape405": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape406": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape43"
      }
    },
    "shape404": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape405"
        },
        "drafts": {
          "$ref": "#/$defs/shape405"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape406"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape406"
        },
        "scope": {
          "$ref": "#/$defs/shape6"
        },
        "selected": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "drafts",
        "scope",
        "selected"
      ],
      "additionalProperties": false
    },
    "shape402": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape6"
        },
        "action": {
          "$ref": "#/$defs/shape403"
        },
        "preference": {
          "$ref": "#/$defs/shape404"
        },
        "server": {
          "$ref": "#/$defs/shape6"
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
    "shape408": {
      "const": "connection-rename"
    },
    "shape407": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape408"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "nextName": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "name",
        "nextName"
      ],
      "additionalProperties": false
    },
    "shape410": {
      "const": "connection-remove"
    },
    "shape409": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape410"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape412": {
      "const": "personal-section"
    },
    "shape414": {
      "const": "In"
    },
    "shape415": {
      "const": "Out"
    },
    "shape416": {
      "const": "Resources"
    },
    "shape417": {
      "const": "Archive"
    },
    "shape418": {
      "const": "Planning"
    },
    "shape419": {
      "const": "Bots"
    },
    "shape413": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape414"
        },
        {
          "$ref": "#/$defs/shape415"
        },
        {
          "$ref": "#/$defs/shape416"
        },
        {
          "$ref": "#/$defs/shape417"
        },
        {
          "$ref": "#/$defs/shape418"
        },
        {
          "$ref": "#/$defs/shape419"
        }
      ]
    },
    "shape411": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape412"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "section": {
          "$ref": "#/$defs/shape413"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape421": {
      "const": "personal-bots"
    },
    "shape420": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape421"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape423": {
      "const": "personal-recreate"
    },
    "shape422": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape423"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape425": {
      "const": "files-choose"
    },
    "shape424": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape425"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape427": {
      "const": "files-withdraw"
    },
    "shape426": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape427"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape429": {
      "const": "sync-refresh"
    },
    "shape428": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape429"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape431": {
      "const": "sync-inspect"
    },
    "shape430": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape431"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "path",
        "project"
      ],
      "additionalProperties": false
    },
    "shape433": {
      "const": "sync-change"
    },
    "shape435": {
      "const": "on"
    },
    "shape436": {
      "const": "off"
    },
    "shape437": {
      "const": "now"
    },
    "shape434": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape435"
        },
        {
          "$ref": "#/$defs/shape436"
        },
        {
          "$ref": "#/$defs/shape437"
        }
      ]
    },
    "shape432": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape433"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape434"
        },
        "project": {
          "$ref": "#/$defs/shape6"
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
    "shape439": {
      "const": "sync-resolve"
    },
    "shape441": {
      "const": "mine"
    },
    "shape442": {
      "const": "theirs"
    },
    "shape443": {
      "const": "done"
    },
    "shape440": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape441"
        },
        {
          "$ref": "#/$defs/shape442"
        },
        {
          "$ref": "#/$defs/shape443"
        }
      ]
    },
    "shape438": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape439"
        },
        "how": {
          "$ref": "#/$defs/shape440"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "text": {
          "$ref": "#/$defs/shape6"
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
    "shape446": {
      "const": "project-open"
    },
    "shape447": {
      "const": "project-remove"
    },
    "shape445": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape446"
        },
        {
          "$ref": "#/$defs/shape447"
        }
      ]
    },
    "shape444": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape445"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape450": {
      "const": "scope"
    },
    "shape451": {
      "const": "create"
    },
    "shape449": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape450"
        },
        {
          "$ref": "#/$defs/shape451"
        }
      ]
    },
    "shape448": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape449"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape453": {
      "const": "history"
    },
    "shape452": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape453"
        },
        "before": {
          "$ref": "#/$defs/shape52"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape455": {
      "const": "select"
    },
    "shape454": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape455"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape457": {
      "const": "trajectory"
    },
    "shape456": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape457"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape459": {
      "const": "board-swarm-types"
    },
    "shape458": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape459"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape461": {
      "const": "board-post-topics"
    },
    "shape460": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape461"
        },
        "more": {
          "$ref": "#/$defs/shape43"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape463": {
      "const": "board-create"
    },
    "shape462": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape463"
        },
        "body": {
          "$ref": "#/$defs/shape6"
        },
        "label": {
          "$ref": "#/$defs/shape6"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape52"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "swarm": {
          "$ref": "#/$defs/shape6"
        },
        "title": {
          "$ref": "#/$defs/shape6"
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
    "shape465": {
      "const": "board-retry"
    },
    "shape464": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape465"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape52"
        },
        "member": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "reconcile": {
          "$ref": "#/$defs/shape43"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "topic": {
          "$ref": "#/$defs/shape6"
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
    "shape467": {
      "const": "board-post"
    },
    "shape466": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape467"
        },
        "body": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "topic": {
          "$ref": "#/$defs/shape6"
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
    "shape469": {
      "const": "workspace-chat"
    },
    "shape468": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape469"
        },
        "manage": {
          "$ref": "#/$defs/shape43"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape471": {
      "const": "workspace-layout"
    },
    "shape470": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape471"
        },
        "height": {
          "$ref": "#/$defs/shape52"
        },
        "visible": {
          "$ref": "#/$defs/shape43"
        },
        "width": {
          "$ref": "#/$defs/shape52"
        },
        "x": {
          "$ref": "#/$defs/shape52"
        },
        "y": {
          "$ref": "#/$defs/shape52"
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
    "shape473": {
      "const": "workspace-refresh"
    },
    "shape472": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape473"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape475": {
      "const": "library"
    },
    "shape477": {
      "const": "sources"
    },
    "shape478": {
      "const": "documents"
    },
    "shape479": {
      "const": "memories"
    },
    "shape480": {
      "const": "manual"
    },
    "shape476": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape234"
        },
        {
          "$ref": "#/$defs/shape477"
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
    "shape474": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape475"
        },
        "chapter": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape476"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape482": {
      "const": "library-view"
    },
    "shape481": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape482"
        },
        "chapter": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape476"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape485": {
      "const": "library-refresh"
    },
    "shape486": {
      "const": "library-citations"
    },
    "shape484": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape485"
        },
        {
          "$ref": "#/$defs/shape486"
        }
      ]
    },
    "shape483": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape484"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape488": {
      "const": "library-documents"
    },
    "shape487": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape488"
        },
        "more": {
          "$ref": "#/$defs/shape43"
        },
        "query": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "query"
      ],
      "additionalProperties": false
    },
    "shape491": {
      "const": "library-document"
    },
    "shape492": {
      "const": "library-memory"
    },
    "shape493": {
      "const": "library-chunk"
    },
    "shape494": {
      "const": "library-conversation"
    },
    "shape490": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape491"
        },
        {
          "$ref": "#/$defs/shape492"
        },
        {
          "$ref": "#/$defs/shape493"
        },
        {
          "$ref": "#/$defs/shape494"
        }
      ]
    },
    "shape489": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape490"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape496": {
      "const": "library-source-text"
    },
    "shape495": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape496"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "offset": {
          "$ref": "#/$defs/shape52"
        }
      },
      "required": [
        "action",
        "id",
        "offset"
      ],
      "additionalProperties": false
    },
    "shape498": {
      "const": "library-stance"
    },
    "shape497": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape498"
        },
        "claim": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "claim"
      ],
      "additionalProperties": false
    },
    "shape500": {
      "const": "library-search"
    },
    "shape502": {
      "const": "retrieve"
    },
    "shape503": {
      "const": "recall"
    },
    "shape504": {
      "const": "navigate"
    },
    "shape501": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape237"
        },
        {
          "$ref": "#/$defs/shape149"
        },
        {
          "$ref": "#/$defs/shape478"
        },
        {
          "$ref": "#/$defs/shape502"
        },
        {
          "$ref": "#/$defs/shape503"
        },
        {
          "$ref": "#/$defs/shape504"
        }
      ]
    },
    "shape506": {
      "const": "lexical"
    },
    "shape507": {
      "const": "semantic"
    },
    "shape508": {
      "const": "hybrid"
    },
    "shape505": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape506"
        },
        {
          "$ref": "#/$defs/shape507"
        },
        {
          "$ref": "#/$defs/shape508"
        }
      ]
    },
    "shape499": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape500"
        },
        "kind": {
          "$ref": "#/$defs/shape501"
        },
        "mode": {
          "$ref": "#/$defs/shape505"
        },
        "more": {
          "$ref": "#/$defs/shape43"
        },
        "query": {
          "$ref": "#/$defs/shape6"
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
    "shape510": {
      "const": "library-maintain"
    },
    "shape512": {
      "const": "invalidate"
    },
    "shape513": {
      "const": "resolve"
    },
    "shape514": {
      "const": "reembed"
    },
    "shape515": {
      "const": "reconsider"
    },
    "shape511": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape512"
        },
        {
          "$ref": "#/$defs/shape513"
        },
        {
          "$ref": "#/$defs/shape514"
        },
        {
          "$ref": "#/$defs/shape515"
        }
      ]
    },
    "shape509": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape43"
        },
        "action": {
          "$ref": "#/$defs/shape510"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape511"
        },
        "reason": {
          "$ref": "#/$defs/shape6"
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
    "shape517": {
      "const": "builder-outputs"
    },
    "shape516": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape517"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape519": {
      "const": "builder-trajectory"
    },
    "shape518": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape519"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape521": {
      "const": "builder-prepare"
    },
    "shape520": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape521"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape523": {
      "const": "builder-start"
    },
    "shape522": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape523"
        },
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "intent": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
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
    "shape525": {
      "const": "activity"
    },
    "shape527": {
      "const": "inbox"
    },
    "shape528": {
      "const": "runs"
    },
    "shape529": {
      "const": "definitions"
    },
    "shape530": {
      "const": "schedules"
    },
    "shape531": {
      "const": "builder"
    },
    "shape526": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape527"
        },
        {
          "$ref": "#/$defs/shape528"
        },
        {
          "$ref": "#/$defs/shape529"
        },
        {
          "$ref": "#/$defs/shape530"
        },
        {
          "$ref": "#/$defs/shape531"
        }
      ]
    },
    "shape524": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape525"
        },
        "view": {
          "$ref": "#/$defs/shape526"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape533": {
      "const": "activity-view"
    },
    "shape532": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape533"
        },
        "view": {
          "$ref": "#/$defs/shape526"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape535": {
      "const": "activity-refresh"
    },
    "shape534": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape535"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape537": {
      "const": "question-refresh"
    },
    "shape536": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape537"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape539": {
      "const": "inbox-read"
    },
    "shape538": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape539"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape541": {
      "const": "inbox-more"
    },
    "shape540": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape541"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape543": {
      "const": "run-detail"
    },
    "shape542": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape545": {
      "const": "schedule-refresh"
    },
    "shape544": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape545"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape547": {
      "const": "schedule-preview"
    },
    "shape546": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape547"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        },
        "zone": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "text",
        "zone"
      ],
      "additionalProperties": false
    },
    "shape549": {
      "const": "schedule-save"
    },
    "shape553": {
      "const": "skill"
    },
    "shape554": {
      "const": "orchestration"
    },
    "shape552": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape79"
        },
        {
          "$ref": "#/$defs/shape553"
        },
        {
          "$ref": "#/$defs/shape554"
        }
      ]
    },
    "shape556": {
      "const": "INHERITED"
    },
    "shape557": {
      "const": "SUMMARISED"
    },
    "shape558": {
      "const": "NEW"
    },
    "shape559": {
      "const": "DIRECT"
    },
    "shape555": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape556"
        },
        {
          "$ref": "#/$defs/shape557"
        },
        {
          "$ref": "#/$defs/shape558"
        },
        {
          "$ref": "#/$defs/shape559"
        }
      ]
    },
    "shape551": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape552"
        },
        "mode": {
          "$ref": "#/$defs/shape555"
        },
        "name": {
          "$ref": "#/$defs/shape7"
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
    "shape561": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape52"
        }
      ]
    },
    "shape560": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape561"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape561"
        },
        "queueCap": {
          "$ref": "#/$defs/shape52"
        }
      },
      "required": [
        "maxModelCalls",
        "maxTurns",
        "queueCap"
      ],
      "additionalProperties": false
    },
    "shape564": {
      "const": "mailbox"
    },
    "shape565": {
      "const": "message"
    },
    "shape563": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape149"
        },
        {
          "$ref": "#/$defs/shape564"
        },
        {
          "$ref": "#/$defs/shape565"
        }
      ]
    },
    "shape562": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape563"
        },
        "project": {
          "$ref": "#/$defs/shape7"
        },
        "route": {
          "$ref": "#/$defs/shape7"
        },
        "to": {
          "$ref": "#/$defs/shape7"
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
    "shape566": {
      "const": 1
    },
    "shape550": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape551"
        },
        "cron": {
          "$ref": "#/$defs/shape6"
        },
        "limits": {
          "$ref": "#/$defs/shape560"
        },
        "paused": {
          "$ref": "#/$defs/shape43"
        },
        "target": {
          "$ref": "#/$defs/shape562"
        },
        "version": {
          "$ref": "#/$defs/shape566"
        },
        "zone": {
          "$ref": "#/$defs/shape6"
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
    "shape568": {
      "const": "server"
    },
    "shape569": {
      "const": "workspace"
    },
    "shape567": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape568"
        },
        {
          "$ref": "#/$defs/shape569"
        }
      ]
    },
    "shape548": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape549"
        },
        "definition": {
          "$ref": "#/$defs/shape550"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape567"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape571": {
      "const": "schedule-file-save"
    },
    "shape570": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape571"
        },
        "definition": {
          "$ref": "#/$defs/shape550"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "overwrite": {
          "$ref": "#/$defs/shape43"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape567"
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
    "shape573": {
      "const": "schedule-sync"
    },
    "shape572": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape573"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape567"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape575": {
      "const": "schedule-change"
    },
    "shape577": {
      "const": "schedule"
    },
    "shape578": {
      "const": "trigger"
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
    "shape574": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape575"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape576"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "paused": {
          "$ref": "#/$defs/shape43"
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
    "shape580": {
      "const": "schedule-fire"
    },
    "shape579": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape580"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "trigger": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "identity",
        "trigger"
      ],
      "additionalProperties": false
    },
    "shape582": {
      "const": "run-definitions"
    },
    "shape581": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape582"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape584": {
      "const": "run-record"
    },
    "shape583": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape584"
        },
        "before": {
          "$ref": "#/$defs/shape52"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "kinds": {
          "$ref": "#/$defs/shape395"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape586": {
      "const": "run-answer"
    },
    "shape588": {
      "type": "object",
      "properties": {
        "chosen": {
          "$ref": "#/$defs/shape192"
        },
        "header": {
          "$ref": "#/$defs/shape6"
        },
        "note": {
          "$ref": "#/$defs/shape6"
        },
        "other": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "chosen",
        "header"
      ],
      "additionalProperties": false
    },
    "shape587": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape588"
      }
    },
    "shape585": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape586"
        },
        "answer": {
          "$ref": "#/$defs/shape6"
        },
        "choices": {
          "$ref": "#/$defs/shape587"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "question": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "id",
        "question"
      ],
      "additionalProperties": false
    },
    "shape590": {
      "const": "run-cancel"
    },
    "shape589": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape590"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape592": {
      "const": "run-resume"
    },
    "shape591": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape592"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape594": {
      "const": "run-trajectory"
    },
    "shape596": {
      "const": "conductor"
    },
    "shape597": {
      "const": "caller"
    },
    "shape595": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape596"
        },
        {
          "$ref": "#/$defs/shape597"
        }
      ]
    },
    "shape593": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape594"
        },
        "actor": {
          "$ref": "#/$defs/shape595"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "actor",
        "id"
      ],
      "additionalProperties": false
    },
    "shape599": {
      "const": "run-stage-trajectory"
    },
    "shape598": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape599"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "stage": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "id",
        "stage"
      ],
      "additionalProperties": false
    },
    "shape601": {
      "const": "delegate-trajectory"
    },
    "shape600": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape601"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "step": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "conversation",
        "step"
      ],
      "additionalProperties": false
    },
    "shape604": {
      "const": "board-inspection"
    },
    "shape605": {
      "const": "board-view"
    },
    "shape603": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape604"
        },
        {
          "$ref": "#/$defs/shape605"
        }
      ]
    },
    "shape607": {
      "const": "board"
    },
    "shape608": {
      "const": "swarm"
    },
    "shape606": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape607"
        },
        {
          "$ref": "#/$defs/shape608"
        }
      ]
    },
    "shape602": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape603"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape606"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape611": {
      "const": "board-refresh"
    },
    "shape612": {
      "const": "board-more"
    },
    "shape610": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape611"
        },
        {
          "$ref": "#/$defs/shape612"
        }
      ]
    },
    "shape609": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape610"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape614": {
      "const": "board-topic"
    },
    "shape613": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape614"
        },
        "topic": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape616": {
      "const": "board-trajectory"
    },
    "shape615": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape616"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape618": {
      "const": "context"
    },
    "shape617": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape618"
        },
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
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
    "shape620": {
      "const": "open-link"
    },
    "shape619": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape620"
        },
        "url": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "url"
      ],
      "additionalProperties": false
    },
    "shape622": {
      "const": "copy-text"
    },
    "shape621": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape622"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "text"
      ],
      "additionalProperties": false
    },
    "shape624": {
      "const": "workflow-start"
    },
    "shape623": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape624"
        },
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "text": {
          "$ref": "#/$defs/shape6"
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
    "shape625": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape82"
        },
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "text": {
          "$ref": "#/$defs/shape6"
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
    "shape627": {
      "const": "cancel"
    },
    "shape626": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape627"
        },
        "job": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "job"
      ],
      "additionalProperties": false
    },
    "shape629": {
      "const": "answer"
    },
    "shape630": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape148"
        },
        {
          "$ref": "#/$defs/shape150"
        }
      ]
    },
    "shape628": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape629"
        },
        "decision": {
          "$ref": "#/$defs/shape630"
        },
        "id": {
          "$ref": "#/$defs/shape6"
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
          "$ref": "#/$defs/shape12"
        },
        {
          "$ref": "#/$defs/shape15"
        },
        {
          "$ref": "#/$defs/shape18"
        },
        {
          "$ref": "#/$defs/shape21"
        },
        {
          "$ref": "#/$defs/shape23"
        },
        {
          "$ref": "#/$defs/shape25"
        },
        {
          "$ref": "#/$defs/shape27"
        },
        {
          "$ref": "#/$defs/shape29"
        },
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape33"
        },
        {
          "$ref": "#/$defs/shape35"
        },
        {
          "$ref": "#/$defs/shape37"
        },
        {
          "$ref": "#/$defs/shape39"
        },
        {
          "$ref": "#/$defs/shape41"
        },
        {
          "$ref": "#/$defs/shape46"
        },
        {
          "$ref": "#/$defs/shape48"
        },
        {
          "$ref": "#/$defs/shape55"
        },
        {
          "$ref": "#/$defs/shape60"
        },
        {
          "$ref": "#/$defs/shape69"
        },
        {
          "$ref": "#/$defs/shape71"
        },
        {
          "$ref": "#/$defs/shape94"
        },
        {
          "$ref": "#/$defs/shape97"
        },
        {
          "$ref": "#/$defs/shape99"
        },
        {
          "$ref": "#/$defs/shape101"
        },
        {
          "$ref": "#/$defs/shape103"
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
          "$ref": "#/$defs/shape110"
        },
        {
          "$ref": "#/$defs/shape113"
        },
        {
          "$ref": "#/$defs/shape116"
        },
        {
          "$ref": "#/$defs/shape119"
        },
        {
          "$ref": "#/$defs/shape122"
        },
        {
          "$ref": "#/$defs/shape124"
        },
        {
          "$ref": "#/$defs/shape142"
        },
        {
          "$ref": "#/$defs/shape144"
        },
        {
          "$ref": "#/$defs/shape164"
        },
        {
          "$ref": "#/$defs/shape166"
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
          "$ref": "#/$defs/shape185"
        },
        {
          "$ref": "#/$defs/shape188"
        },
        {
          "$ref": "#/$defs/shape196"
        },
        {
          "$ref": "#/$defs/shape199"
        },
        {
          "$ref": "#/$defs/shape202"
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
          "$ref": "#/$defs/shape224"
        },
        {
          "$ref": "#/$defs/shape227"
        },
        {
          "$ref": "#/$defs/shape230"
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
          "$ref": "#/$defs/shape296"
        },
        {
          "$ref": "#/$defs/shape299"
        },
        {
          "$ref": "#/$defs/shape302"
        },
        {
          "$ref": "#/$defs/shape305"
        },
        {
          "$ref": "#/$defs/shape308"
        },
        {
          "$ref": "#/$defs/shape311"
        },
        {
          "$ref": "#/$defs/shape314"
        },
        {
          "$ref": "#/$defs/shape320"
        },
        {
          "$ref": "#/$defs/shape331"
        },
        {
          "$ref": "#/$defs/shape335"
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
          "$ref": "#/$defs/shape361"
        },
        {
          "$ref": "#/$defs/shape364"
        },
        {
          "$ref": "#/$defs/shape367"
        },
        {
          "$ref": "#/$defs/shape369"
        },
        {
          "$ref": "#/$defs/shape372"
        },
        {
          "$ref": "#/$defs/shape375"
        },
        {
          "$ref": "#/$defs/shape378"
        },
        {
          "$ref": "#/$defs/shape383"
        },
        {
          "$ref": "#/$defs/shape386"
        },
        {
          "$ref": "#/$defs/shape389"
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
          "$ref": "#/$defs/shape407"
        },
        {
          "$ref": "#/$defs/shape409"
        },
        {
          "$ref": "#/$defs/shape411"
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
          "$ref": "#/$defs/shape438"
        },
        {
          "$ref": "#/$defs/shape444"
        },
        {
          "$ref": "#/$defs/shape448"
        },
        {
          "$ref": "#/$defs/shape452"
        },
        {
          "$ref": "#/$defs/shape454"
        },
        {
          "$ref": "#/$defs/shape456"
        },
        {
          "$ref": "#/$defs/shape458"
        },
        {
          "$ref": "#/$defs/shape460"
        },
        {
          "$ref": "#/$defs/shape462"
        },
        {
          "$ref": "#/$defs/shape464"
        },
        {
          "$ref": "#/$defs/shape466"
        },
        {
          "$ref": "#/$defs/shape468"
        },
        {
          "$ref": "#/$defs/shape470"
        },
        {
          "$ref": "#/$defs/shape472"
        },
        {
          "$ref": "#/$defs/shape474"
        },
        {
          "$ref": "#/$defs/shape481"
        },
        {
          "$ref": "#/$defs/shape483"
        },
        {
          "$ref": "#/$defs/shape487"
        },
        {
          "$ref": "#/$defs/shape489"
        },
        {
          "$ref": "#/$defs/shape495"
        },
        {
          "$ref": "#/$defs/shape497"
        },
        {
          "$ref": "#/$defs/shape499"
        },
        {
          "$ref": "#/$defs/shape509"
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
          "$ref": "#/$defs/shape532"
        },
        {
          "$ref": "#/$defs/shape534"
        },
        {
          "$ref": "#/$defs/shape536"
        },
        {
          "$ref": "#/$defs/shape538"
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
          "$ref": "#/$defs/shape546"
        },
        {
          "$ref": "#/$defs/shape548"
        },
        {
          "$ref": "#/$defs/shape570"
        },
        {
          "$ref": "#/$defs/shape572"
        },
        {
          "$ref": "#/$defs/shape574"
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
          "$ref": "#/$defs/shape589"
        },
        {
          "$ref": "#/$defs/shape591"
        },
        {
          "$ref": "#/$defs/shape593"
        },
        {
          "$ref": "#/$defs/shape598"
        },
        {
          "$ref": "#/$defs/shape600"
        },
        {
          "$ref": "#/$defs/shape602"
        },
        {
          "$ref": "#/$defs/shape609"
        },
        {
          "$ref": "#/$defs/shape613"
        },
        {
          "$ref": "#/$defs/shape615"
        },
        {
          "$ref": "#/$defs/shape617"
        },
        {
          "$ref": "#/$defs/shape619"
        },
        {
          "$ref": "#/$defs/shape621"
        },
        {
          "$ref": "#/$defs/shape623"
        },
        {
          "$ref": "#/$defs/shape625"
        },
        {
          "$ref": "#/$defs/shape626"
        },
        {
          "$ref": "#/$defs/shape628"
        }
      ]
    }
  }
}
