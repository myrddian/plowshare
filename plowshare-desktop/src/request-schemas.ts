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
      "const": "filestore-load"
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
      "const": "filestore-choose"
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
      "const": "filestore-setup"
    },
    "shape27": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape28"
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
    "shape30": {
      "const": "application-runtime"
    },
    "shape29": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape30"
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
    "shape32": {
      "const": "application-files"
    },
    "shape31": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape32"
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
    "shape34": {
      "const": "application-file-read"
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
        "path",
        "project"
      ],
      "additionalProperties": false
    },
    "shape36": {
      "const": "application-file-save"
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
    "shape38": {
      "const": "usage"
    },
    "shape37": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape38"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape40": {
      "const": "context-snapshot"
    },
    "shape42": {
      "const": false
    },
    "shape43": {
      "const": true
    },
    "shape41": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape42"
        },
        {
          "$ref": "#/$defs/shape43"
        }
      ]
    },
    "shape39": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape40"
        },
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "measure": {
          "$ref": "#/$defs/shape41"
        }
      },
      "required": [
        "action",
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape45": {
      "const": "relay"
    },
    "shape44": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape45"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape47": {
      "const": "relay-read"
    },
    "shape50": {
      "type": "number"
    },
    "shape49": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape50"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "system": {
          "$ref": "#/$defs/shape42"
        }
      },
      "required": [
        "project"
      ],
      "additionalProperties": false
    },
    "shape51": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape50"
        },
        "project": {
          "$ref": "#/$defs/shape8"
        },
        "system": {
          "$ref": "#/$defs/shape43"
        }
      },
      "required": [
        "system"
      ],
      "additionalProperties": false
    },
    "shape48": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape49"
        },
        {
          "$ref": "#/$defs/shape51"
        }
      ]
    },
    "shape52": {
      "const": "relay.topics"
    },
    "shape46": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape47"
        },
        "payload": {
          "$ref": "#/$defs/shape48"
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
    "shape55": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape6"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "system": {
          "$ref": "#/$defs/shape42"
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
    "shape56": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape6"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
        },
        "project": {
          "$ref": "#/$defs/shape8"
        },
        "system": {
          "$ref": "#/$defs/shape43"
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
    "shape54": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape55"
        },
        {
          "$ref": "#/$defs/shape56"
        }
      ]
    },
    "shape57": {
      "const": "relay.log"
    },
    "shape53": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape47"
        },
        "payload": {
          "$ref": "#/$defs/shape54"
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
    "shape59": {
      "const": "relay-operate"
    },
    "shape62": {
      "const": "ACKNOWLEDGE_GAP"
    },
    "shape63": {
      "const": "RECONCILE"
    },
    "shape64": {
      "const": "ABANDON"
    },
    "shape65": {
      "const": "REMOVE_SUBSCRIPTION"
    },
    "shape66": {
      "const": "REMOVE_TOPIC"
    },
    "shape61": {
      "anyOf": [
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
    "shape60": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape61"
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
    "shape58": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape59"
        },
        "payload": {
          "$ref": "#/$defs/shape60"
        }
      },
      "required": [
        "action",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape68": {
      "const": "relay-trajectory"
    },
    "shape67": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape68"
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
    "shape70": {
      "const": "usage-open"
    },
    "shape74": {
      "const": "day"
    },
    "shape75": {
      "const": "model"
    },
    "shape76": {
      "const": "pool"
    },
    "shape77": {
      "const": "agent"
    },
    "shape78": {
      "const": "operation"
    },
    "shape79": {
      "const": "project"
    },
    "shape80": {
      "const": "run"
    },
    "shape73": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape74"
        },
        {
          "$ref": "#/$defs/shape75"
        },
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
        }
      ]
    },
    "shape72": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape73"
      }
    },
    "shape82": {
      "const": "direct"
    },
    "shape83": {
      "const": "subtree"
    },
    "shape81": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape82"
        },
        {
          "$ref": "#/$defs/shape83"
        }
      ]
    },
    "shape71": {
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
          "$ref": "#/$defs/shape72"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
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
          "$ref": "#/$defs/shape81"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape85": {
      "const": "usage.conversation"
    },
    "shape86": {
      "const": "usage.project"
    },
    "shape87": {
      "const": "usage.agent"
    },
    "shape88": {
      "const": "usage.run"
    },
    "shape89": {
      "const": "usage.orchestration"
    },
    "shape90": {
      "const": "usage.models"
    },
    "shape91": {
      "const": "usage.pools"
    },
    "shape84": {
      "anyOf": [
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
        }
      ]
    },
    "shape69": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape70"
        },
        "filter": {
          "$ref": "#/$defs/shape71"
        },
        "type": {
          "$ref": "#/$defs/shape84"
        }
      },
      "required": [
        "action",
        "filter",
        "type"
      ],
      "additionalProperties": false
    },
    "shape93": {
      "const": "usage-read"
    },
    "shape94": {
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
          "$ref": "#/$defs/shape72"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
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
          "$ref": "#/$defs/shape81"
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
    "shape92": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape93"
        },
        "payload": {
          "$ref": "#/$defs/shape94"
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
          "$ref": "#/$defs/shape72"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
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
          "$ref": "#/$defs/shape81"
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
    "shape95": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape93"
        },
        "payload": {
          "$ref": "#/$defs/shape96"
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
          "$ref": "#/$defs/shape72"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
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
          "$ref": "#/$defs/shape81"
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
    "shape97": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape93"
        },
        "payload": {
          "$ref": "#/$defs/shape98"
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
          "$ref": "#/$defs/shape72"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
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
          "$ref": "#/$defs/shape81"
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
    "shape99": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape93"
        },
        "payload": {
          "$ref": "#/$defs/shape100"
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
          "$ref": "#/$defs/shape72"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
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
          "$ref": "#/$defs/shape81"
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
    "shape101": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape93"
        },
        "payload": {
          "$ref": "#/$defs/shape102"
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
    "shape103": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape93"
        },
        "payload": {
          "$ref": "#/$defs/shape71"
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
        "action": {
          "$ref": "#/$defs/shape93"
        },
        "payload": {
          "$ref": "#/$defs/shape71"
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
    "shape106": {
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
          "$ref": "#/$defs/shape72"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
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
          "$ref": "#/$defs/shape81"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape107": {
      "const": "usage.calls"
    },
    "shape105": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape93"
        },
        "payload": {
          "$ref": "#/$defs/shape106"
        },
        "type": {
          "$ref": "#/$defs/shape107"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape109": {
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
          "$ref": "#/$defs/shape72"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
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
          "$ref": "#/$defs/shape84"
        },
        "route": {
          "$ref": "#/$defs/shape6"
        },
        "run": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape81"
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
    "shape110": {
      "const": "usage.subscribe"
    },
    "shape108": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape93"
        },
        "payload": {
          "$ref": "#/$defs/shape109"
        },
        "type": {
          "$ref": "#/$defs/shape110"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape112": {
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
    "shape113": {
      "const": "usage.unsubscribe"
    },
    "shape111": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape93"
        },
        "payload": {
          "$ref": "#/$defs/shape112"
        },
        "type": {
          "$ref": "#/$defs/shape113"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape115": {
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
    "shape116": {
      "const": "conversation.context.count"
    },
    "shape114": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape93"
        },
        "payload": {
          "$ref": "#/$defs/shape115"
        },
        "type": {
          "$ref": "#/$defs/shape116"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape118": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "measure": {
          "$ref": "#/$defs/shape41"
        }
      },
      "required": [
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape119": {
      "const": "conversation.context.snapshot"
    },
    "shape117": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape93"
        },
        "payload": {
          "$ref": "#/$defs/shape118"
        },
        "type": {
          "$ref": "#/$defs/shape119"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape121": {
      "const": "usage-close"
    },
    "shape120": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape121"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape123": {
      "const": "operator-prepare"
    },
    "shape125": {
      "const": "memory-write"
    },
    "shape126": {
      "const": "memory-digest"
    },
    "shape127": {
      "const": "agent-curate"
    },
    "shape128": {
      "const": "conversation-lifecycle"
    },
    "shape129": {
      "const": "conversation-resume"
    },
    "shape130": {
      "const": "job-limits"
    },
    "shape131": {
      "const": "approval-grant"
    },
    "shape132": {
      "const": "approval-revoke"
    },
    "shape133": {
      "const": "board-topup"
    },
    "shape134": {
      "const": "message-deliveries"
    },
    "shape135": {
      "const": "message-open"
    },
    "shape136": {
      "const": "message-default"
    },
    "shape137": {
      "const": "message-stop"
    },
    "shape138": {
      "const": "message-archive"
    },
    "shape139": {
      "const": "caps"
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
        }
      ]
    },
    "shape122": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape123"
        },
        "kind": {
          "$ref": "#/$defs/shape124"
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
    "shape141": {
      "const": "operator-messages"
    },
    "shape140": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape141"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "instance": {
          "$ref": "#/$defs/shape6"
        },
        "offset": {
          "$ref": "#/$defs/shape50"
        }
      },
      "required": [
        "action",
        "identity",
        "instance"
      ],
      "additionalProperties": false
    },
    "shape143": {
      "const": "operator-preview"
    },
    "shape146": {
      "const": "once"
    },
    "shape147": {
      "const": "conversation"
    },
    "shape148": {
      "const": "deny"
    },
    "shape145": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape79"
        },
        {
          "$ref": "#/$defs/shape146"
        },
        {
          "$ref": "#/$defs/shape147"
        },
        {
          "$ref": "#/$defs/shape148"
        }
      ]
    },
    "shape150": {
      "const": "steps"
    },
    "shape151": {
      "const": "budget"
    },
    "shape152": {
      "const": "auto-continue"
    },
    "shape153": {
      "const": "time"
    },
    "shape154": {
      "const": "failed-checks"
    },
    "shape155": {
      "const": "auto-increase"
    },
    "shape149": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape150"
        },
        {
          "$ref": "#/$defs/shape151"
        },
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
        }
      ]
    },
    "shape157": {
      "const": "active"
    },
    "shape158": {
      "const": "archived"
    },
    "shape156": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape157"
        },
        {
          "$ref": "#/$defs/shape158"
        }
      ]
    },
    "shape160": {
      "const": "true"
    },
    "shape161": {
      "const": "false"
    },
    "shape159": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape160"
        },
        {
          "$ref": "#/$defs/shape161"
        }
      ]
    },
    "shape144": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "body": {
          "$ref": "#/$defs/shape6"
        },
        "decision": {
          "$ref": "#/$defs/shape145"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "key": {
          "$ref": "#/$defs/shape149"
        },
        "lifecycle": {
          "$ref": "#/$defs/shape156"
        },
        "makeDefault": {
          "$ref": "#/$defs/shape159"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape50"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape50"
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
          "$ref": "#/$defs/shape50"
        }
      },
      "additionalProperties": false
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
        "input": {
          "$ref": "#/$defs/shape144"
        }
      },
      "required": [
        "action",
        "identity",
        "input"
      ],
      "additionalProperties": false
    },
    "shape163": {
      "const": "operator-apply"
    },
    "shape162": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape163"
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
    "shape165": {
      "const": "information"
    },
    "shape166": {
      "const": "upload"
    },
    "shape167": {
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
    "shape171": {
      "const": "personal"
    },
    "shape172": {
      "const": "shared"
    },
    "shape170": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape171"
        },
        {
          "$ref": "#/$defs/shape172"
        }
      ]
    },
    "shape169": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape41"
        },
        "kind": {
          "$ref": "#/$defs/shape170"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape173": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape41"
        },
        "kind": {
          "$ref": "#/$defs/shape79"
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
    "shape168": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape169"
        },
        {
          "$ref": "#/$defs/shape173"
        }
      ]
    },
    "shape164": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape166"
        },
        "payload": {
          "$ref": "#/$defs/shape167"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
    "shape175": {
      "const": "acquire"
    },
    "shape176": {
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
    "shape174": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape175"
        },
        "payload": {
          "$ref": "#/$defs/shape176"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
    "shape178": {
      "const": "refresh"
    },
    "shape179": {
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
    "shape177": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape178"
        },
        "payload": {
          "$ref": "#/$defs/shape179"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "revise"
    },
    "shape182": {
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
    "shape180": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape181"
        },
        "payload": {
          "$ref": "#/$defs/shape182"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "replace"
    },
    "shape185": {
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
    "shape183": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape184"
        },
        "payload": {
          "$ref": "#/$defs/shape185"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "list"
    },
    "shape190": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape192": {
      "const": "source"
    },
    "shape193": {
      "const": "report"
    },
    "shape191": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape192"
        },
        {
          "$ref": "#/$defs/shape193"
        }
      ]
    },
    "shape189": {
      "type": "object",
      "properties": {
        "author": {
          "$ref": "#/$defs/shape6"
        },
        "autoTag": {
          "$ref": "#/$defs/shape190"
        },
        "documentAuthor": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape191"
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
          "$ref": "#/$defs/shape190"
        },
        "when": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape188": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape189"
        },
        "kind": {
          "$ref": "#/$defs/shape191"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
        },
        "offset": {
          "$ref": "#/$defs/shape50"
        }
      },
      "additionalProperties": false
    },
    "shape186": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape187"
        },
        "payload": {
          "$ref": "#/$defs/shape188"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
    "shape195": {
      "const": "facets"
    },
    "shape196": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape189"
        },
        "kind": {
          "$ref": "#/$defs/shape191"
        }
      },
      "additionalProperties": false
    },
    "shape194": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape195"
        },
        "payload": {
          "$ref": "#/$defs/shape196"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "tags"
    },
    "shape199": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "tags": {
          "$ref": "#/$defs/shape190"
        }
      },
      "required": [
        "requestId",
        "revision",
        "tags"
      ],
      "additionalProperties": false
    },
    "shape197": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape198"
        },
        "payload": {
          "$ref": "#/$defs/shape199"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "tags.groups"
    },
    "shape204": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape190"
      }
    },
    "shape203": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape204"
        }
      ]
    },
    "shape202": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape203"
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
    "shape200": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape201"
        },
        "payload": {
          "$ref": "#/$defs/shape202"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
    "shape206": {
      "const": "inventory"
    },
    "shape207": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape50"
        },
        "offset": {
          "$ref": "#/$defs/shape50"
        }
      },
      "additionalProperties": false
    },
    "shape205": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape206"
        },
        "payload": {
          "$ref": "#/$defs/shape207"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
    "shape209": {
      "const": "acquisitions"
    },
    "shape210": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape50"
        },
        "offset": {
          "$ref": "#/$defs/shape50"
        }
      },
      "additionalProperties": false
    },
    "shape208": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape209"
        },
        "payload": {
          "$ref": "#/$defs/shape210"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
    "shape212": {
      "const": "status"
    },
    "shape213": {
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
    "shape211": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape212"
        },
        "payload": {
          "$ref": "#/$defs/shape213"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
    "shape215": {
      "const": "await"
    },
    "shape220": {
      "forbidden": true
    },
    "shape219": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape220"
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
    "shape221": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape220"
        }
      },
      "required": [
        "acquisition"
      ],
      "additionalProperties": false
    },
    "shape218": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape219"
        },
        {
          "$ref": "#/$defs/shape221"
        }
      ]
    },
    "shape217": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape218"
      }
    },
    "shape216": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape217"
        },
        "waitMs": {
          "$ref": "#/$defs/shape50"
        }
      },
      "required": [
        "sources"
      ],
      "additionalProperties": false
    },
    "shape214": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape215"
        },
        "payload": {
          "$ref": "#/$defs/shape216"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "read"
    },
    "shape224": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape50"
        },
        "offset": {
          "$ref": "#/$defs/shape50"
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
    "shape222": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape223"
        },
        "payload": {
          "$ref": "#/$defs/shape224"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "outline"
    },
    "shape227": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape50"
        },
        "offset": {
          "$ref": "#/$defs/shape50"
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
    "shape225": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape226"
        },
        "payload": {
          "$ref": "#/$defs/shape227"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "symbols"
    },
    "shape230": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape50"
        },
        "offset": {
          "$ref": "#/$defs/shape50"
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
    "shape228": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape229"
        },
        "payload": {
          "$ref": "#/$defs/shape230"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "search"
    },
    "shape233": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape189"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
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
    "shape231": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape232"
        },
        "payload": {
          "$ref": "#/$defs/shape233"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "rank"
    },
    "shape236": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape189"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
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
    "shape234": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape235"
        },
        "payload": {
          "$ref": "#/$defs/shape236"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "ask"
    },
    "shape239": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape50"
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
    "shape237": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape238"
        },
        "payload": {
          "$ref": "#/$defs/shape239"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "evidence.record"
    },
    "shape242": {
      "type": "object",
      "properties": {
        "end": {
          "$ref": "#/$defs/shape50"
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
          "$ref": "#/$defs/shape50"
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
    "shape240": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape241"
        },
        "payload": {
          "$ref": "#/$defs/shape242"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "evidence.read"
    },
    "shape245": {
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
    "shape243": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape244"
        },
        "payload": {
          "$ref": "#/$defs/shape245"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "record.report"
    },
    "shape252": {
      "const": "holds"
    },
    "shape253": {
      "const": "weakened"
    },
    "shape254": {
      "const": "refuted"
    },
    "shape255": {
      "const": "not_checked"
    },
    "shape251": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape252"
        },
        {
          "$ref": "#/$defs/shape253"
        },
        {
          "$ref": "#/$defs/shape254"
        },
        {
          "$ref": "#/$defs/shape255"
        }
      ]
    },
    "shape250": {
      "type": "object",
      "properties": {
        "claim": {
          "$ref": "#/$defs/shape6"
        },
        "counterEvidence": {
          "$ref": "#/$defs/shape190"
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
          "$ref": "#/$defs/shape190"
        },
        "verdict": {
          "$ref": "#/$defs/shape251"
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
    "shape249": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape250"
      }
    },
    "shape257": {
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
    "shape256": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape257"
      }
    },
    "shape248": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape190"
        },
        "feedback": {
          "$ref": "#/$defs/shape6"
        },
        "findings": {
          "$ref": "#/$defs/shape249"
        },
        "inputs": {
          "$ref": "#/$defs/shape190"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "objectives": {
          "$ref": "#/$defs/shape190"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "reviews": {
          "$ref": "#/$defs/shape256"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape190"
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
    "shape246": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape247"
        },
        "payload": {
          "$ref": "#/$defs/shape248"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "finalise"
    },
    "shape260": {
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
    "shape258": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape259"
        },
        "payload": {
          "$ref": "#/$defs/shape260"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "link"
    },
    "shape263": {
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
    "shape261": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape262"
        },
        "payload": {
          "$ref": "#/$defs/shape263"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "unlink"
    },
    "shape266": {
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
    "shape264": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape265"
        },
        "payload": {
          "$ref": "#/$defs/shape266"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "share"
    },
    "shape269": {
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
    "shape267": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape268"
        },
        "payload": {
          "$ref": "#/$defs/shape269"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "unshare"
    },
    "shape272": {
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
    "shape270": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape271"
        },
        "payload": {
          "$ref": "#/$defs/shape272"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "withdraw"
    },
    "shape275": {
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
    "shape273": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape274"
        },
        "payload": {
          "$ref": "#/$defs/shape275"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "exclude"
    },
    "shape278": {
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
    "shape276": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape277"
        },
        "payload": {
          "$ref": "#/$defs/shape278"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "unexclude"
    },
    "shape281": {
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
    "shape279": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape280"
        },
        "payload": {
          "$ref": "#/$defs/shape281"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "restore"
    },
    "shape284": {
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
    "shape282": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape283"
        },
        "payload": {
          "$ref": "#/$defs/shape284"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "delete"
    },
    "shape287": {
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
    "shape285": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape286"
        },
        "payload": {
          "$ref": "#/$defs/shape287"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "retry"
    },
    "shape290": {
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
    "shape288": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape289"
        },
        "payload": {
          "$ref": "#/$defs/shape290"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "rebuild"
    },
    "shape293": {
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
    "shape291": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape292"
        },
        "payload": {
          "$ref": "#/$defs/shape293"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
      "const": "allowance"
    },
    "shape296": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape50"
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
    "shape294": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape295"
        },
        "payload": {
          "$ref": "#/$defs/shape296"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
    "shape298": {
      "const": "events"
    },
    "shape299": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape50"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
        }
      },
      "additionalProperties": false
    },
    "shape297": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape298"
        },
        "payload": {
          "$ref": "#/$defs/shape299"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
    "shape301": {
      "const": "migration.list"
    },
    "shape302": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape50"
        },
        "offset": {
          "$ref": "#/$defs/shape50"
        }
      },
      "additionalProperties": false
    },
    "shape300": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape301"
        },
        "payload": {
          "$ref": "#/$defs/shape302"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
    "shape304": {
      "const": "migration.adopt"
    },
    "shape305": {
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
    "shape303": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape304"
        },
        "payload": {
          "$ref": "#/$defs/shape305"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
    "shape307": {
      "const": "migration.inspect"
    },
    "shape308": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape50"
        },
        "offset": {
          "$ref": "#/$defs/shape50"
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
    "shape306": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape307"
        },
        "payload": {
          "$ref": "#/$defs/shape308"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
    "shape310": {
      "const": "migration.release"
    },
    "shape311": {
      "type": "object",
      "properties": {
        "inputs": {
          "$ref": "#/$defs/shape190"
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
    "shape309": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "operation": {
          "$ref": "#/$defs/shape310"
        },
        "payload": {
          "$ref": "#/$defs/shape311"
        },
        "scope": {
          "$ref": "#/$defs/shape168"
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
    "shape314": {
      "const": "bootstrap"
    },
    "shape315": {
      "const": "demo"
    },
    "shape316": {
      "const": "disconnect"
    },
    "shape317": {
      "const": "approvals-refresh"
    },
    "shape313": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape178"
        },
        {
          "$ref": "#/$defs/shape314"
        },
        {
          "$ref": "#/$defs/shape315"
        },
        {
          "$ref": "#/$defs/shape316"
        },
        {
          "$ref": "#/$defs/shape317"
        }
      ]
    },
    "shape312": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape313"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape319": {
      "const": "project-access"
    },
    "shape321": {
      "const": "project.access"
    },
    "shape322": {
      "const": "project.member.add"
    },
    "shape323": {
      "const": "project.member.remove"
    },
    "shape324": {
      "const": "project.member.role"
    },
    "shape320": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape321"
        },
        {
          "$ref": "#/$defs/shape322"
        },
        {
          "$ref": "#/$defs/shape323"
        },
        {
          "$ref": "#/$defs/shape324"
        }
      ]
    },
    "shape326": {
      "const": "VIEWER"
    },
    "shape327": {
      "const": "CONTRIBUTOR"
    },
    "shape328": {
      "const": "MANAGER"
    },
    "shape325": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape326"
        },
        {
          "$ref": "#/$defs/shape327"
        },
        {
          "$ref": "#/$defs/shape328"
        }
      ]
    },
    "shape318": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape319"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape320"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape325"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape330": {
      "const": "server-admin"
    },
    "shape331": {
      "const": "admin.pricing.list"
    },
    "shape332": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape220"
      }
    },
    "shape329": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
        },
        "operation": {
          "$ref": "#/$defs/shape331"
        },
        "payload": {
          "$ref": "#/$defs/shape332"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape334": {
      "const": "admin.pricing.set"
    },
    "shape337": {
      "const": "TOKEN"
    },
    "shape338": {
      "const": "INCLUDED"
    },
    "shape339": {
      "const": "ZERO_RATE"
    },
    "shape340": {
      "const": "UNPRICED"
    },
    "shape336": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape337"
        },
        {
          "$ref": "#/$defs/shape338"
        },
        {
          "$ref": "#/$defs/shape339"
        },
        {
          "$ref": "#/$defs/shape340"
        }
      ]
    },
    "shape341": {
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
    "shape344": {
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
    "shape343": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape50"
        },
        "rates": {
          "$ref": "#/$defs/shape344"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape342": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape343"
      }
    },
    "shape335": {
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
          "$ref": "#/$defs/shape336"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "rates": {
          "$ref": "#/$defs/shape341"
        },
        "requestFee": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "tiers": {
          "$ref": "#/$defs/shape342"
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
    "shape333": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
        },
        "operation": {
          "$ref": "#/$defs/shape334"
        },
        "payload": {
          "$ref": "#/$defs/shape335"
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
      "const": "admin.accounts"
    },
    "shape345": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
        },
        "operation": {
          "$ref": "#/$defs/shape346"
        },
        "payload": {
          "$ref": "#/$defs/shape332"
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
      "const": "admin.account.create"
    },
    "shape349": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape41"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape347": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
        },
        "operation": {
          "$ref": "#/$defs/shape348"
        },
        "payload": {
          "$ref": "#/$defs/shape349"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape351": {
      "const": "admin.account.update"
    },
    "shape352": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape41"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape41"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape350": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
        },
        "operation": {
          "$ref": "#/$defs/shape351"
        },
        "payload": {
          "$ref": "#/$defs/shape352"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape354": {
      "const": "admin.account.reset"
    },
    "shape355": {
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
    "shape353": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
        },
        "operation": {
          "$ref": "#/$defs/shape354"
        },
        "payload": {
          "$ref": "#/$defs/shape355"
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
      "const": "admin.sessions"
    },
    "shape358": {
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
    "shape356": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
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
      "const": "admin.session.revoke"
    },
    "shape361": {
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
    "shape359": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
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
      "const": "admin.audit"
    },
    "shape364": {
      "type": "object",
      "properties": {
        "before": {
          "$ref": "#/$defs/shape50"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "limit": {
          "$ref": "#/$defs/shape50"
        }
      },
      "additionalProperties": false
    },
    "shape362": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
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
      "const": "admin.service.accounts"
    },
    "shape365": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
        },
        "operation": {
          "$ref": "#/$defs/shape366"
        },
        "payload": {
          "$ref": "#/$defs/shape332"
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
      "const": "admin.service.account.create"
    },
    "shape369": {
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
    "shape367": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
        },
        "operation": {
          "$ref": "#/$defs/shape368"
        },
        "payload": {
          "$ref": "#/$defs/shape369"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape371": {
      "const": "admin.service.account.update"
    },
    "shape372": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape41"
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
    "shape370": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
        },
        "operation": {
          "$ref": "#/$defs/shape371"
        },
        "payload": {
          "$ref": "#/$defs/shape372"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape374": {
      "const": "admin.service.tokens"
    },
    "shape375": {
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
    "shape373": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
        },
        "operation": {
          "$ref": "#/$defs/shape374"
        },
        "payload": {
          "$ref": "#/$defs/shape375"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape377": {
      "const": "admin.service.token.create"
    },
    "shape380": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape325"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape379": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape380"
      }
    },
    "shape378": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape50"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "scopes": {
          "$ref": "#/$defs/shape379"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape376": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
        },
        "operation": {
          "$ref": "#/$defs/shape377"
        },
        "payload": {
          "$ref": "#/$defs/shape378"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape382": {
      "const": "admin.service.token.rotate"
    },
    "shape383": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape50"
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
    "shape381": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
        },
        "operation": {
          "$ref": "#/$defs/shape382"
        },
        "payload": {
          "$ref": "#/$defs/shape383"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape385": {
      "const": "admin.service.token.revoke"
    },
    "shape386": {
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
    "shape384": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape330"
        },
        "operation": {
          "$ref": "#/$defs/shape385"
        },
        "payload": {
          "$ref": "#/$defs/shape386"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape388": {
      "const": "server-project-create"
    },
    "shape390": {
      "const": "MANAGED"
    },
    "shape391": {
      "const": "DISJOINT"
    },
    "shape389": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape390"
        },
        {
          "$ref": "#/$defs/shape391"
        }
      ]
    },
    "shape392": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape393": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape387": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape388"
        },
        "applicationRoot": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape389"
        },
        "workspace": {
          "$ref": "#/$defs/shape6"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape392"
        },
        "writePaths": {
          "$ref": "#/$defs/shape393"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape395": {
      "const": "server-setup"
    },
    "shape394": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape395"
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
    "shape397": {
      "const": "connect"
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
    "shape399": {
      "const": "connection-select"
    },
    "shape398": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape399"
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
    "shape401": {
      "const": "connection-preferences"
    },
    "shape403": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape404": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape41"
      }
    },
    "shape402": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape403"
        },
        "drafts": {
          "$ref": "#/$defs/shape403"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape404"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape404"
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
    "shape400": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape6"
        },
        "action": {
          "$ref": "#/$defs/shape401"
        },
        "preference": {
          "$ref": "#/$defs/shape402"
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
    "shape406": {
      "const": "connection-rename"
    },
    "shape405": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape406"
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
    "shape408": {
      "const": "connection-remove"
    },
    "shape407": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape408"
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
    "shape410": {
      "const": "personal-section"
    },
    "shape412": {
      "const": "In"
    },
    "shape413": {
      "const": "Out"
    },
    "shape414": {
      "const": "Resources"
    },
    "shape415": {
      "const": "Archive"
    },
    "shape416": {
      "const": "Planning"
    },
    "shape417": {
      "const": "Bots"
    },
    "shape411": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape412"
        },
        {
          "$ref": "#/$defs/shape413"
        },
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
        }
      ]
    },
    "shape409": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape410"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "section": {
          "$ref": "#/$defs/shape411"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape419": {
      "const": "personal-bots"
    },
    "shape418": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape419"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape421": {
      "const": "personal-recreate"
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
      "const": "files-choose"
    },
    "shape422": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape423"
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
    "shape425": {
      "const": "files-withdraw"
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
      "const": "sync-refresh"
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
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape429": {
      "const": "sync-inspect"
    },
    "shape428": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape429"
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
    "shape431": {
      "const": "sync-change"
    },
    "shape433": {
      "const": "on"
    },
    "shape434": {
      "const": "off"
    },
    "shape435": {
      "const": "now"
    },
    "shape432": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape433"
        },
        {
          "$ref": "#/$defs/shape434"
        },
        {
          "$ref": "#/$defs/shape435"
        }
      ]
    },
    "shape430": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape431"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape432"
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
    "shape437": {
      "const": "sync-resolve"
    },
    "shape439": {
      "const": "mine"
    },
    "shape440": {
      "const": "theirs"
    },
    "shape441": {
      "const": "done"
    },
    "shape438": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape439"
        },
        {
          "$ref": "#/$defs/shape440"
        },
        {
          "$ref": "#/$defs/shape441"
        }
      ]
    },
    "shape436": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape437"
        },
        "how": {
          "$ref": "#/$defs/shape438"
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
    "shape444": {
      "const": "project-open"
    },
    "shape445": {
      "const": "project-remove"
    },
    "shape443": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape444"
        },
        {
          "$ref": "#/$defs/shape445"
        }
      ]
    },
    "shape442": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape443"
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
    "shape448": {
      "const": "scope"
    },
    "shape449": {
      "const": "create"
    },
    "shape447": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape448"
        },
        {
          "$ref": "#/$defs/shape449"
        }
      ]
    },
    "shape446": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape447"
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
    "shape451": {
      "const": "history"
    },
    "shape450": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape451"
        },
        "before": {
          "$ref": "#/$defs/shape50"
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
    "shape453": {
      "const": "select"
    },
    "shape452": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape453"
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
    "shape455": {
      "const": "trajectory"
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
        "action",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape457": {
      "const": "board-swarm-types"
    },
    "shape456": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape457"
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
    "shape459": {
      "const": "board-post-topics"
    },
    "shape458": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape459"
        },
        "more": {
          "$ref": "#/$defs/shape41"
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
      "const": "board-create"
    },
    "shape460": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape461"
        },
        "body": {
          "$ref": "#/$defs/shape6"
        },
        "label": {
          "$ref": "#/$defs/shape6"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape50"
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
    "shape463": {
      "const": "board-retry"
    },
    "shape462": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape463"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape50"
        },
        "member": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "reconcile": {
          "$ref": "#/$defs/shape41"
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
    "shape465": {
      "const": "board-post"
    },
    "shape464": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape465"
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
    "shape467": {
      "const": "workspace-chat"
    },
    "shape466": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape467"
        },
        "manage": {
          "$ref": "#/$defs/shape41"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape469": {
      "const": "workspace-layout"
    },
    "shape468": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape469"
        },
        "height": {
          "$ref": "#/$defs/shape50"
        },
        "visible": {
          "$ref": "#/$defs/shape41"
        },
        "width": {
          "$ref": "#/$defs/shape50"
        },
        "x": {
          "$ref": "#/$defs/shape50"
        },
        "y": {
          "$ref": "#/$defs/shape50"
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
    "shape471": {
      "const": "workspace-refresh"
    },
    "shape470": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape471"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape473": {
      "const": "library"
    },
    "shape475": {
      "const": "sources"
    },
    "shape476": {
      "const": "documents"
    },
    "shape477": {
      "const": "memories"
    },
    "shape478": {
      "const": "manual"
    },
    "shape474": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape232"
        },
        {
          "$ref": "#/$defs/shape475"
        },
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
    "shape472": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape473"
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
          "$ref": "#/$defs/shape474"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape480": {
      "const": "library-view"
    },
    "shape479": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape480"
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
          "$ref": "#/$defs/shape474"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape483": {
      "const": "library-refresh"
    },
    "shape484": {
      "const": "library-citations"
    },
    "shape482": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape483"
        },
        {
          "$ref": "#/$defs/shape484"
        }
      ]
    },
    "shape481": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape482"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape486": {
      "const": "library-documents"
    },
    "shape485": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape486"
        },
        "more": {
          "$ref": "#/$defs/shape41"
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
    "shape489": {
      "const": "library-document"
    },
    "shape490": {
      "const": "library-memory"
    },
    "shape491": {
      "const": "library-chunk"
    },
    "shape492": {
      "const": "library-conversation"
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
        }
      ]
    },
    "shape487": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape488"
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
    "shape494": {
      "const": "library-source-text"
    },
    "shape493": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape494"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "offset": {
          "$ref": "#/$defs/shape50"
        }
      },
      "required": [
        "action",
        "id",
        "offset"
      ],
      "additionalProperties": false
    },
    "shape496": {
      "const": "library-stance"
    },
    "shape495": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape496"
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
    "shape498": {
      "const": "library-search"
    },
    "shape500": {
      "const": "retrieve"
    },
    "shape501": {
      "const": "recall"
    },
    "shape502": {
      "const": "navigate"
    },
    "shape499": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape235"
        },
        {
          "$ref": "#/$defs/shape147"
        },
        {
          "$ref": "#/$defs/shape476"
        },
        {
          "$ref": "#/$defs/shape500"
        },
        {
          "$ref": "#/$defs/shape501"
        },
        {
          "$ref": "#/$defs/shape502"
        }
      ]
    },
    "shape504": {
      "const": "lexical"
    },
    "shape505": {
      "const": "semantic"
    },
    "shape506": {
      "const": "hybrid"
    },
    "shape503": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape504"
        },
        {
          "$ref": "#/$defs/shape505"
        },
        {
          "$ref": "#/$defs/shape506"
        }
      ]
    },
    "shape497": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape498"
        },
        "kind": {
          "$ref": "#/$defs/shape499"
        },
        "mode": {
          "$ref": "#/$defs/shape503"
        },
        "more": {
          "$ref": "#/$defs/shape41"
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
    "shape508": {
      "const": "library-maintain"
    },
    "shape510": {
      "const": "invalidate"
    },
    "shape511": {
      "const": "resolve"
    },
    "shape512": {
      "const": "reembed"
    },
    "shape513": {
      "const": "reconsider"
    },
    "shape509": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape510"
        },
        {
          "$ref": "#/$defs/shape511"
        },
        {
          "$ref": "#/$defs/shape512"
        },
        {
          "$ref": "#/$defs/shape513"
        }
      ]
    },
    "shape507": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape41"
        },
        "action": {
          "$ref": "#/$defs/shape508"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape509"
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
    "shape515": {
      "const": "builder-outputs"
    },
    "shape514": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape515"
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
    "shape517": {
      "const": "builder-trajectory"
    },
    "shape516": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape517"
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
    "shape519": {
      "const": "builder-prepare"
    },
    "shape518": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape519"
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
    "shape521": {
      "const": "builder-start"
    },
    "shape520": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape521"
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
    "shape523": {
      "const": "activity"
    },
    "shape525": {
      "const": "inbox"
    },
    "shape526": {
      "const": "runs"
    },
    "shape527": {
      "const": "definitions"
    },
    "shape528": {
      "const": "schedules"
    },
    "shape529": {
      "const": "builder"
    },
    "shape524": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape525"
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
    "shape522": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape523"
        },
        "view": {
          "$ref": "#/$defs/shape524"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape531": {
      "const": "activity-view"
    },
    "shape530": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape531"
        },
        "view": {
          "$ref": "#/$defs/shape524"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape533": {
      "const": "activity-refresh"
    },
    "shape532": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape533"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape535": {
      "const": "question-refresh"
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
      "const": "inbox-read"
    },
    "shape536": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape537"
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
    "shape539": {
      "const": "inbox-more"
    },
    "shape538": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape539"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape541": {
      "const": "run-detail"
    },
    "shape540": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape541"
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
    "shape543": {
      "const": "schedule-refresh"
    },
    "shape542": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape545": {
      "const": "schedule-preview"
    },
    "shape544": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape545"
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
    "shape547": {
      "const": "schedule-save"
    },
    "shape551": {
      "const": "skill"
    },
    "shape552": {
      "const": "orchestration"
    },
    "shape550": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape77"
        },
        {
          "$ref": "#/$defs/shape551"
        },
        {
          "$ref": "#/$defs/shape552"
        }
      ]
    },
    "shape554": {
      "const": "INHERITED"
    },
    "shape555": {
      "const": "SUMMARISED"
    },
    "shape556": {
      "const": "NEW"
    },
    "shape557": {
      "const": "DIRECT"
    },
    "shape553": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape554"
        },
        {
          "$ref": "#/$defs/shape555"
        },
        {
          "$ref": "#/$defs/shape556"
        },
        {
          "$ref": "#/$defs/shape557"
        }
      ]
    },
    "shape549": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape550"
        },
        "mode": {
          "$ref": "#/$defs/shape553"
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
    "shape559": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape50"
        }
      ]
    },
    "shape558": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape559"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape559"
        },
        "queueCap": {
          "$ref": "#/$defs/shape50"
        }
      },
      "required": [
        "maxModelCalls",
        "maxTurns",
        "queueCap"
      ],
      "additionalProperties": false
    },
    "shape562": {
      "const": "mailbox"
    },
    "shape563": {
      "const": "message"
    },
    "shape561": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape147"
        },
        {
          "$ref": "#/$defs/shape562"
        },
        {
          "$ref": "#/$defs/shape563"
        }
      ]
    },
    "shape560": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape561"
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
    "shape564": {
      "const": 1
    },
    "shape548": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape549"
        },
        "cron": {
          "$ref": "#/$defs/shape6"
        },
        "limits": {
          "$ref": "#/$defs/shape558"
        },
        "paused": {
          "$ref": "#/$defs/shape41"
        },
        "target": {
          "$ref": "#/$defs/shape560"
        },
        "version": {
          "$ref": "#/$defs/shape564"
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
    "shape566": {
      "const": "server"
    },
    "shape567": {
      "const": "workspace"
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
    "shape546": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape547"
        },
        "definition": {
          "$ref": "#/$defs/shape548"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape565"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape569": {
      "const": "schedule-file-save"
    },
    "shape568": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape569"
        },
        "definition": {
          "$ref": "#/$defs/shape548"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "overwrite": {
          "$ref": "#/$defs/shape41"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape565"
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
    "shape571": {
      "const": "schedule-sync"
    },
    "shape570": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape571"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape565"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape573": {
      "const": "schedule-change"
    },
    "shape575": {
      "const": "schedule"
    },
    "shape576": {
      "const": "trigger"
    },
    "shape574": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape575"
        },
        {
          "$ref": "#/$defs/shape576"
        }
      ]
    },
    "shape572": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape573"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape574"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "paused": {
          "$ref": "#/$defs/shape41"
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
    "shape578": {
      "const": "schedule-fire"
    },
    "shape577": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape578"
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
    "shape580": {
      "const": "run-definitions"
    },
    "shape579": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape580"
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
    "shape582": {
      "const": "run-record"
    },
    "shape581": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape582"
        },
        "before": {
          "$ref": "#/$defs/shape50"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "kinds": {
          "$ref": "#/$defs/shape393"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape584": {
      "const": "run-answer"
    },
    "shape586": {
      "type": "object",
      "properties": {
        "chosen": {
          "$ref": "#/$defs/shape190"
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
    "shape585": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape586"
      }
    },
    "shape583": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape584"
        },
        "answer": {
          "$ref": "#/$defs/shape6"
        },
        "choices": {
          "$ref": "#/$defs/shape585"
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
    "shape588": {
      "const": "run-cancel"
    },
    "shape587": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape588"
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
    "shape590": {
      "const": "run-resume"
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
      "const": "run-trajectory"
    },
    "shape594": {
      "const": "conductor"
    },
    "shape595": {
      "const": "caller"
    },
    "shape593": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape594"
        },
        {
          "$ref": "#/$defs/shape595"
        }
      ]
    },
    "shape591": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape592"
        },
        "actor": {
          "$ref": "#/$defs/shape593"
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
    "shape597": {
      "const": "run-stage-trajectory"
    },
    "shape596": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape597"
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
    "shape599": {
      "const": "delegate-trajectory"
    },
    "shape598": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape599"
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
    "shape602": {
      "const": "board-inspection"
    },
    "shape603": {
      "const": "board-view"
    },
    "shape601": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape602"
        },
        {
          "$ref": "#/$defs/shape603"
        }
      ]
    },
    "shape605": {
      "const": "board"
    },
    "shape606": {
      "const": "swarm"
    },
    "shape604": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape605"
        },
        {
          "$ref": "#/$defs/shape606"
        }
      ]
    },
    "shape600": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape601"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape604"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape609": {
      "const": "board-refresh"
    },
    "shape610": {
      "const": "board-more"
    },
    "shape608": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape609"
        },
        {
          "$ref": "#/$defs/shape610"
        }
      ]
    },
    "shape607": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape608"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape612": {
      "const": "board-topic"
    },
    "shape611": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape612"
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
    "shape614": {
      "const": "board-trajectory"
    },
    "shape613": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape614"
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
    "shape616": {
      "const": "context"
    },
    "shape615": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape616"
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
    "shape618": {
      "const": "open-link"
    },
    "shape617": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape618"
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
    "shape620": {
      "const": "copy-text"
    },
    "shape619": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape620"
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
    "shape622": {
      "const": "workflow-start"
    },
    "shape621": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape622"
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
    "shape623": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape80"
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
    "shape625": {
      "const": "cancel"
    },
    "shape624": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape625"
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
    "shape627": {
      "const": "answer"
    },
    "shape628": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape146"
        },
        {
          "$ref": "#/$defs/shape148"
        }
      ]
    },
    "shape626": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape627"
        },
        "decision": {
          "$ref": "#/$defs/shape628"
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
          "$ref": "#/$defs/shape44"
        },
        {
          "$ref": "#/$defs/shape46"
        },
        {
          "$ref": "#/$defs/shape53"
        },
        {
          "$ref": "#/$defs/shape58"
        },
        {
          "$ref": "#/$defs/shape67"
        },
        {
          "$ref": "#/$defs/shape69"
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
          "$ref": "#/$defs/shape99"
        },
        {
          "$ref": "#/$defs/shape101"
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
          "$ref": "#/$defs/shape108"
        },
        {
          "$ref": "#/$defs/shape111"
        },
        {
          "$ref": "#/$defs/shape114"
        },
        {
          "$ref": "#/$defs/shape117"
        },
        {
          "$ref": "#/$defs/shape120"
        },
        {
          "$ref": "#/$defs/shape122"
        },
        {
          "$ref": "#/$defs/shape140"
        },
        {
          "$ref": "#/$defs/shape142"
        },
        {
          "$ref": "#/$defs/shape162"
        },
        {
          "$ref": "#/$defs/shape164"
        },
        {
          "$ref": "#/$defs/shape174"
        },
        {
          "$ref": "#/$defs/shape177"
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
          "$ref": "#/$defs/shape194"
        },
        {
          "$ref": "#/$defs/shape197"
        },
        {
          "$ref": "#/$defs/shape200"
        },
        {
          "$ref": "#/$defs/shape205"
        },
        {
          "$ref": "#/$defs/shape208"
        },
        {
          "$ref": "#/$defs/shape211"
        },
        {
          "$ref": "#/$defs/shape214"
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
          "$ref": "#/$defs/shape297"
        },
        {
          "$ref": "#/$defs/shape300"
        },
        {
          "$ref": "#/$defs/shape303"
        },
        {
          "$ref": "#/$defs/shape306"
        },
        {
          "$ref": "#/$defs/shape309"
        },
        {
          "$ref": "#/$defs/shape312"
        },
        {
          "$ref": "#/$defs/shape318"
        },
        {
          "$ref": "#/$defs/shape329"
        },
        {
          "$ref": "#/$defs/shape333"
        },
        {
          "$ref": "#/$defs/shape345"
        },
        {
          "$ref": "#/$defs/shape347"
        },
        {
          "$ref": "#/$defs/shape350"
        },
        {
          "$ref": "#/$defs/shape353"
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
          "$ref": "#/$defs/shape365"
        },
        {
          "$ref": "#/$defs/shape367"
        },
        {
          "$ref": "#/$defs/shape370"
        },
        {
          "$ref": "#/$defs/shape373"
        },
        {
          "$ref": "#/$defs/shape376"
        },
        {
          "$ref": "#/$defs/shape381"
        },
        {
          "$ref": "#/$defs/shape384"
        },
        {
          "$ref": "#/$defs/shape387"
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
          "$ref": "#/$defs/shape405"
        },
        {
          "$ref": "#/$defs/shape407"
        },
        {
          "$ref": "#/$defs/shape409"
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
          "$ref": "#/$defs/shape436"
        },
        {
          "$ref": "#/$defs/shape442"
        },
        {
          "$ref": "#/$defs/shape446"
        },
        {
          "$ref": "#/$defs/shape450"
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
          "$ref": "#/$defs/shape479"
        },
        {
          "$ref": "#/$defs/shape481"
        },
        {
          "$ref": "#/$defs/shape485"
        },
        {
          "$ref": "#/$defs/shape487"
        },
        {
          "$ref": "#/$defs/shape493"
        },
        {
          "$ref": "#/$defs/shape495"
        },
        {
          "$ref": "#/$defs/shape497"
        },
        {
          "$ref": "#/$defs/shape507"
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
          "$ref": "#/$defs/shape530"
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
          "$ref": "#/$defs/shape568"
        },
        {
          "$ref": "#/$defs/shape570"
        },
        {
          "$ref": "#/$defs/shape572"
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
          "$ref": "#/$defs/shape587"
        },
        {
          "$ref": "#/$defs/shape589"
        },
        {
          "$ref": "#/$defs/shape591"
        },
        {
          "$ref": "#/$defs/shape596"
        },
        {
          "$ref": "#/$defs/shape598"
        },
        {
          "$ref": "#/$defs/shape600"
        },
        {
          "$ref": "#/$defs/shape607"
        },
        {
          "$ref": "#/$defs/shape611"
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
          "$ref": "#/$defs/shape624"
        },
        {
          "$ref": "#/$defs/shape626"
        }
      ]
    }
  }
}
