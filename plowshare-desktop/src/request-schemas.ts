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
      "const": "application-identity"
    },
    "shape23": {
      "const": "application.identity.status"
    },
    "shape24": {
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
    "shape21": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape22"
        },
        "operation": {
          "$ref": "#/$defs/shape23"
        },
        "payload": {
          "$ref": "#/$defs/shape24"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape26": {
      "const": "application.token.issue"
    },
    "shape28": {
      "type": "number"
    },
    "shape30": {
      "const": false
    },
    "shape31": {
      "const": true
    },
    "shape29": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape30"
        },
        {
          "$ref": "#/$defs/shape31"
        }
      ]
    },
    "shape27": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape28"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "rotate": {
          "$ref": "#/$defs/shape29"
        }
      },
      "required": [
        "expiresInDays",
        "project",
        "requestId",
        "rotate"
      ],
      "additionalProperties": false
    },
    "shape25": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape22"
        },
        "operation": {
          "$ref": "#/$defs/shape26"
        },
        "payload": {
          "$ref": "#/$defs/shape27"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape33": {
      "const": "application.token.receipt"
    },
    "shape34": {
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
    "shape32": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape22"
        },
        "operation": {
          "$ref": "#/$defs/shape33"
        },
        "payload": {
          "$ref": "#/$defs/shape34"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape36": {
      "const": "application-package"
    },
    "shape35": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape36"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape38": {
      "const": "server-filestore-list"
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
      "const": "filestore-load"
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
      "const": "filestore-choose"
    },
    "shape41": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape42"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape44": {
      "const": "filestore-setup"
    },
    "shape43": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape44"
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
    "shape46": {
      "const": "application-runtime"
    },
    "shape45": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape46"
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
    "shape48": {
      "const": "application-files"
    },
    "shape47": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape48"
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
    "shape50": {
      "const": "application-file-read"
    },
    "shape49": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape50"
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
    "shape52": {
      "const": "application-file-save"
    },
    "shape51": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape52"
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
    "shape54": {
      "const": "usage"
    },
    "shape53": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape54"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape56": {
      "const": "context-snapshot"
    },
    "shape55": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape56"
        },
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "measure": {
          "$ref": "#/$defs/shape29"
        }
      },
      "required": [
        "action",
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape58": {
      "const": "relay"
    },
    "shape57": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape58"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape60": {
      "const": "relay-read"
    },
    "shape62": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "system": {
          "$ref": "#/$defs/shape30"
        }
      },
      "required": [
        "project"
      ],
      "additionalProperties": false
    },
    "shape63": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "project": {
          "$ref": "#/$defs/shape8"
        },
        "system": {
          "$ref": "#/$defs/shape31"
        }
      },
      "required": [
        "system"
      ],
      "additionalProperties": false
    },
    "shape61": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape62"
        },
        {
          "$ref": "#/$defs/shape63"
        }
      ]
    },
    "shape64": {
      "const": "relay.topics"
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
    "shape67": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape6"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "system": {
          "$ref": "#/$defs/shape30"
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
    "shape68": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape6"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "project": {
          "$ref": "#/$defs/shape8"
        },
        "system": {
          "$ref": "#/$defs/shape31"
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
    "shape66": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape67"
        },
        {
          "$ref": "#/$defs/shape68"
        }
      ]
    },
    "shape69": {
      "const": "relay.log"
    },
    "shape65": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
        },
        "payload": {
          "$ref": "#/$defs/shape66"
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
    "shape71": {
      "const": "relay-operate"
    },
    "shape74": {
      "const": "ACKNOWLEDGE_GAP"
    },
    "shape75": {
      "const": "RECONCILE"
    },
    "shape76": {
      "const": "ABANDON"
    },
    "shape77": {
      "const": "REMOVE_SUBSCRIPTION"
    },
    "shape78": {
      "const": "REMOVE_TOPIC"
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
        }
      ]
    },
    "shape72": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape73"
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
    "shape70": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape71"
        },
        "payload": {
          "$ref": "#/$defs/shape72"
        }
      },
      "required": [
        "action",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape80": {
      "const": "relay-trajectory"
    },
    "shape79": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape80"
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
    "shape82": {
      "const": "usage-open"
    },
    "shape86": {
      "const": "day"
    },
    "shape87": {
      "const": "model"
    },
    "shape88": {
      "const": "pool"
    },
    "shape89": {
      "const": "agent"
    },
    "shape90": {
      "const": "operation"
    },
    "shape91": {
      "const": "project"
    },
    "shape92": {
      "const": "run"
    },
    "shape85": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape92"
        }
      ]
    },
    "shape84": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape85"
      }
    },
    "shape94": {
      "const": "direct"
    },
    "shape95": {
      "const": "subtree"
    },
    "shape93": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape94"
        },
        {
          "$ref": "#/$defs/shape95"
        }
      ]
    },
    "shape83": {
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
          "$ref": "#/$defs/shape84"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
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
          "$ref": "#/$defs/shape93"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape97": {
      "const": "usage.conversation"
    },
    "shape98": {
      "const": "usage.project"
    },
    "shape99": {
      "const": "usage.agent"
    },
    "shape100": {
      "const": "usage.run"
    },
    "shape101": {
      "const": "usage.orchestration"
    },
    "shape102": {
      "const": "usage.models"
    },
    "shape103": {
      "const": "usage.pools"
    },
    "shape96": {
      "anyOf": [
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
        }
      ]
    },
    "shape81": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape82"
        },
        "filter": {
          "$ref": "#/$defs/shape83"
        },
        "type": {
          "$ref": "#/$defs/shape96"
        }
      },
      "required": [
        "action",
        "filter",
        "type"
      ],
      "additionalProperties": false
    },
    "shape105": {
      "const": "usage-read"
    },
    "shape106": {
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
          "$ref": "#/$defs/shape84"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
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
          "$ref": "#/$defs/shape93"
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
    "shape104": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape105"
        },
        "payload": {
          "$ref": "#/$defs/shape106"
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
    "shape108": {
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
          "$ref": "#/$defs/shape84"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
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
          "$ref": "#/$defs/shape93"
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
    "shape107": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape105"
        },
        "payload": {
          "$ref": "#/$defs/shape108"
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
    "shape110": {
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
          "$ref": "#/$defs/shape84"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
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
          "$ref": "#/$defs/shape93"
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
    "shape109": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape105"
        },
        "payload": {
          "$ref": "#/$defs/shape110"
        },
        "type": {
          "$ref": "#/$defs/shape99"
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
          "$ref": "#/$defs/shape84"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
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
          "$ref": "#/$defs/shape93"
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
    "shape111": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape105"
        },
        "payload": {
          "$ref": "#/$defs/shape112"
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
    "shape114": {
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
          "$ref": "#/$defs/shape84"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
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
          "$ref": "#/$defs/shape93"
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
    "shape113": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape105"
        },
        "payload": {
          "$ref": "#/$defs/shape114"
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
    "shape115": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape105"
        },
        "payload": {
          "$ref": "#/$defs/shape83"
        },
        "type": {
          "$ref": "#/$defs/shape102"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape116": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape105"
        },
        "payload": {
          "$ref": "#/$defs/shape83"
        },
        "type": {
          "$ref": "#/$defs/shape103"
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
          "$ref": "#/$defs/shape84"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
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
          "$ref": "#/$defs/shape93"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape119": {
      "const": "usage.calls"
    },
    "shape117": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape105"
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
          "$ref": "#/$defs/shape84"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
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
          "$ref": "#/$defs/shape96"
        },
        "route": {
          "$ref": "#/$defs/shape6"
        },
        "run": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape93"
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
    "shape122": {
      "const": "usage.subscribe"
    },
    "shape120": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape105"
        },
        "payload": {
          "$ref": "#/$defs/shape121"
        },
        "type": {
          "$ref": "#/$defs/shape122"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape124": {
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
    "shape125": {
      "const": "usage.unsubscribe"
    },
    "shape123": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape105"
        },
        "payload": {
          "$ref": "#/$defs/shape124"
        },
        "type": {
          "$ref": "#/$defs/shape125"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape127": {
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
    "shape128": {
      "const": "conversation.context.count"
    },
    "shape126": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape105"
        },
        "payload": {
          "$ref": "#/$defs/shape127"
        },
        "type": {
          "$ref": "#/$defs/shape128"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape130": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "measure": {
          "$ref": "#/$defs/shape29"
        }
      },
      "required": [
        "agent",
        "conversation"
      ],
      "additionalProperties": false
    },
    "shape131": {
      "const": "conversation.context.snapshot"
    },
    "shape129": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape105"
        },
        "payload": {
          "$ref": "#/$defs/shape130"
        },
        "type": {
          "$ref": "#/$defs/shape131"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape133": {
      "const": "usage-close"
    },
    "shape132": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape133"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape135": {
      "const": "operator-prepare"
    },
    "shape137": {
      "const": "memory-write"
    },
    "shape138": {
      "const": "memory-digest"
    },
    "shape139": {
      "const": "agent-curate"
    },
    "shape140": {
      "const": "conversation-lifecycle"
    },
    "shape141": {
      "const": "conversation-resume"
    },
    "shape142": {
      "const": "job-limits"
    },
    "shape143": {
      "const": "approval-grant"
    },
    "shape144": {
      "const": "approval-revoke"
    },
    "shape145": {
      "const": "board-topup"
    },
    "shape146": {
      "const": "message-deliveries"
    },
    "shape147": {
      "const": "message-open"
    },
    "shape148": {
      "const": "message-default"
    },
    "shape149": {
      "const": "message-stop"
    },
    "shape150": {
      "const": "message-archive"
    },
    "shape151": {
      "const": "caps"
    },
    "shape136": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape142"
        },
        {
          "$ref": "#/$defs/shape143"
        },
        {
          "$ref": "#/$defs/shape144"
        },
        {
          "$ref": "#/$defs/shape145"
        },
        {
          "$ref": "#/$defs/shape146"
        },
        {
          "$ref": "#/$defs/shape147"
        },
        {
          "$ref": "#/$defs/shape148"
        },
        {
          "$ref": "#/$defs/shape149"
        },
        {
          "$ref": "#/$defs/shape150"
        },
        {
          "$ref": "#/$defs/shape151"
        }
      ]
    },
    "shape134": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape135"
        },
        "kind": {
          "$ref": "#/$defs/shape136"
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
    "shape153": {
      "const": "operator-messages"
    },
    "shape152": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape153"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "instance": {
          "$ref": "#/$defs/shape6"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        }
      },
      "required": [
        "action",
        "identity",
        "instance"
      ],
      "additionalProperties": false
    },
    "shape155": {
      "const": "operator-preview"
    },
    "shape158": {
      "const": "once"
    },
    "shape159": {
      "const": "conversation"
    },
    "shape160": {
      "const": "deny"
    },
    "shape157": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape91"
        },
        {
          "$ref": "#/$defs/shape158"
        },
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape160"
        }
      ]
    },
    "shape162": {
      "const": "steps"
    },
    "shape163": {
      "const": "budget"
    },
    "shape164": {
      "const": "auto-continue"
    },
    "shape165": {
      "const": "time"
    },
    "shape166": {
      "const": "failed-checks"
    },
    "shape167": {
      "const": "auto-increase"
    },
    "shape161": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape162"
        },
        {
          "$ref": "#/$defs/shape163"
        },
        {
          "$ref": "#/$defs/shape164"
        },
        {
          "$ref": "#/$defs/shape165"
        },
        {
          "$ref": "#/$defs/shape166"
        },
        {
          "$ref": "#/$defs/shape167"
        }
      ]
    },
    "shape169": {
      "const": "active"
    },
    "shape170": {
      "const": "archived"
    },
    "shape168": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape169"
        },
        {
          "$ref": "#/$defs/shape170"
        }
      ]
    },
    "shape172": {
      "const": "true"
    },
    "shape173": {
      "const": "false"
    },
    "shape171": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape172"
        },
        {
          "$ref": "#/$defs/shape173"
        }
      ]
    },
    "shape156": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "body": {
          "$ref": "#/$defs/shape6"
        },
        "decision": {
          "$ref": "#/$defs/shape157"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "key": {
          "$ref": "#/$defs/shape161"
        },
        "lifecycle": {
          "$ref": "#/$defs/shape168"
        },
        "makeDefault": {
          "$ref": "#/$defs/shape171"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape28"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape28"
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
          "$ref": "#/$defs/shape28"
        }
      },
      "additionalProperties": false
    },
    "shape154": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape155"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape156"
        }
      },
      "required": [
        "action",
        "identity",
        "input"
      ],
      "additionalProperties": false
    },
    "shape175": {
      "const": "operator-apply"
    },
    "shape174": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape175"
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
    "shape177": {
      "const": "information"
    },
    "shape178": {
      "const": "types.capabilities"
    },
    "shape180": {
      "const": "content-types-v1"
    },
    "shape179": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape184": {
      "const": "personal"
    },
    "shape185": {
      "const": "shared"
    },
    "shape183": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape184"
        },
        {
          "$ref": "#/$defs/shape185"
        }
      ]
    },
    "shape182": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape29"
        },
        "kind": {
          "$ref": "#/$defs/shape183"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape186": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape29"
        },
        "kind": {
          "$ref": "#/$defs/shape91"
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
    "shape181": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape182"
        },
        {
          "$ref": "#/$defs/shape186"
        }
      ]
    },
    "shape176": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape178"
        },
        "payload": {
          "$ref": "#/$defs/shape179"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "types.list"
    },
    "shape189": {
      "type": "object",
      "properties": {
        "drafts": {
          "$ref": "#/$defs/shape29"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "view"
      ],
      "additionalProperties": false
    },
    "shape187": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape188"
        },
        "payload": {
          "$ref": "#/$defs/shape189"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "types.get"
    },
    "shape192": {
      "type": "object",
      "properties": {
        "attachedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "publishedOnly": {
          "$ref": "#/$defs/shape29"
        },
        "version": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "version",
        "view"
      ],
      "additionalProperties": false
    },
    "shape190": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape191"
        },
        "payload": {
          "$ref": "#/$defs/shape192"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "types.validate"
    },
    "shape198": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape197"
      }
    },
    "shape197": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape28"
        },
        {
          "$ref": "#/$defs/shape30"
        },
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape196"
        },
        {
          "$ref": "#/$defs/shape198"
        }
      ]
    },
    "shape196": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape197"
      }
    },
    "shape195": {
      "type": "object",
      "properties": {
        "attachedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "metadata": {
          "$ref": "#/$defs/shape196"
        },
        "publishedOnly": {
          "$ref": "#/$defs/shape29"
        },
        "version": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "metadata",
        "version",
        "view"
      ],
      "additionalProperties": false
    },
    "shape193": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape194"
        },
        "payload": {
          "$ref": "#/$defs/shape195"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "types.version.create"
    },
    "shape203": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape202"
      }
    },
    "shape204": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape30"
        },
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape202"
        }
      ]
    },
    "shape205": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape202"
      }
    },
    "shape206": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape28"
        },
        {
          "$ref": "#/$defs/shape30"
        },
        {
          "$ref": "#/$defs/shape31"
        }
      ]
    },
    "shape207": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape206"
      }
    },
    "shape208": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape210": {
      "const": "string"
    },
    "shape211": {
      "const": "number"
    },
    "shape212": {
      "const": "boolean"
    },
    "shape213": {
      "const": "object"
    },
    "shape214": {
      "const": "array"
    },
    "shape215": {
      "const": "integer"
    },
    "shape216": {
      "const": "null"
    },
    "shape218": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape210"
        },
        {
          "$ref": "#/$defs/shape211"
        },
        {
          "$ref": "#/$defs/shape212"
        },
        {
          "$ref": "#/$defs/shape213"
        },
        {
          "$ref": "#/$defs/shape214"
        },
        {
          "$ref": "#/$defs/shape215"
        },
        {
          "$ref": "#/$defs/shape216"
        }
      ]
    },
    "shape217": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape218"
      }
    },
    "shape209": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape210"
        },
        {
          "$ref": "#/$defs/shape211"
        },
        {
          "$ref": "#/$defs/shape212"
        },
        {
          "$ref": "#/$defs/shape213"
        },
        {
          "$ref": "#/$defs/shape214"
        },
        {
          "$ref": "#/$defs/shape215"
        },
        {
          "$ref": "#/$defs/shape216"
        },
        {
          "$ref": "#/$defs/shape217"
        }
      ]
    },
    "shape202": {
      "type": "object",
      "properties": {
        "$anchor": {
          "$ref": "#/$defs/shape6"
        },
        "$defs": {
          "$ref": "#/$defs/shape203"
        },
        "$id": {
          "$ref": "#/$defs/shape6"
        },
        "$ref": {
          "$ref": "#/$defs/shape6"
        },
        "$schema": {
          "$ref": "#/$defs/shape6"
        },
        "additionalProperties": {
          "$ref": "#/$defs/shape204"
        },
        "allOf": {
          "$ref": "#/$defs/shape205"
        },
        "anyOf": {
          "$ref": "#/$defs/shape205"
        },
        "const": {
          "$ref": "#/$defs/shape206"
        },
        "contains": {
          "$ref": "#/$defs/shape202"
        },
        "default": {
          "$ref": "#/$defs/shape206"
        },
        "deprecated": {
          "$ref": "#/$defs/shape29"
        },
        "description": {
          "$ref": "#/$defs/shape6"
        },
        "else": {
          "$ref": "#/$defs/shape202"
        },
        "enum": {
          "$ref": "#/$defs/shape207"
        },
        "examples": {
          "$ref": "#/$defs/shape207"
        },
        "exclusiveMaximum": {
          "$ref": "#/$defs/shape28"
        },
        "exclusiveMinimum": {
          "$ref": "#/$defs/shape28"
        },
        "format": {
          "$ref": "#/$defs/shape6"
        },
        "if": {
          "$ref": "#/$defs/shape202"
        },
        "items": {
          "$ref": "#/$defs/shape202"
        },
        "maximum": {
          "$ref": "#/$defs/shape28"
        },
        "maxItems": {
          "$ref": "#/$defs/shape28"
        },
        "maxLength": {
          "$ref": "#/$defs/shape28"
        },
        "maxProperties": {
          "$ref": "#/$defs/shape28"
        },
        "minimum": {
          "$ref": "#/$defs/shape28"
        },
        "minItems": {
          "$ref": "#/$defs/shape28"
        },
        "minLength": {
          "$ref": "#/$defs/shape28"
        },
        "minProperties": {
          "$ref": "#/$defs/shape28"
        },
        "multipleOf": {
          "$ref": "#/$defs/shape28"
        },
        "not": {
          "$ref": "#/$defs/shape202"
        },
        "oneOf": {
          "$ref": "#/$defs/shape205"
        },
        "pattern": {
          "$ref": "#/$defs/shape6"
        },
        "patternProperties": {
          "$ref": "#/$defs/shape203"
        },
        "prefixItems": {
          "$ref": "#/$defs/shape205"
        },
        "properties": {
          "$ref": "#/$defs/shape203"
        },
        "readOnly": {
          "$ref": "#/$defs/shape29"
        },
        "required": {
          "$ref": "#/$defs/shape208"
        },
        "then": {
          "$ref": "#/$defs/shape202"
        },
        "title": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape209"
        },
        "uniqueItems": {
          "$ref": "#/$defs/shape29"
        },
        "writeOnly": {
          "$ref": "#/$defs/shape29"
        }
      },
      "additionalProperties": false
    },
    "shape201": {
      "type": "object",
      "properties": {
        "description": {
          "$ref": "#/$defs/shape6"
        },
        "expectedControlRevision": {
          "$ref": "#/$defs/shape28"
        },
        "label": {
          "$ref": "#/$defs/shape6"
        },
        "presentation": {
          "$ref": "#/$defs/shape196"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "schema": {
          "$ref": "#/$defs/shape202"
        },
        "slug": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "schema",
        "view"
      ],
      "additionalProperties": false
    },
    "shape199": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape200"
        },
        "payload": {
          "$ref": "#/$defs/shape201"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "types.activate"
    },
    "shape221": {
      "type": "object",
      "properties": {
        "expectedControlRevision": {
          "$ref": "#/$defs/shape28"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "version": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "expectedControlRevision",
        "requestId",
        "version",
        "view"
      ],
      "additionalProperties": false
    },
    "shape219": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape220"
        },
        "payload": {
          "$ref": "#/$defs/shape221"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "types.deprecate"
    },
    "shape224": {
      "type": "object",
      "properties": {
        "expectedControlRevision": {
          "$ref": "#/$defs/shape28"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "version": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "expectedControlRevision",
        "requestId",
        "version",
        "view"
      ],
      "additionalProperties": false
    },
    "shape222": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape223"
        },
        "payload": {
          "$ref": "#/$defs/shape224"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "content.get"
    },
    "shape227": {
      "type": "object",
      "properties": {
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "revision",
        "view"
      ],
      "additionalProperties": false
    },
    "shape225": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape226"
        },
        "payload": {
          "$ref": "#/$defs/shape227"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "content.revise"
    },
    "shape233": {
      "const": "set"
    },
    "shape232": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape196"
        },
        "mode": {
          "$ref": "#/$defs/shape233"
        },
        "version": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "metadata",
        "mode",
        "version"
      ],
      "additionalProperties": false
    },
    "shape235": {
      "const": "inherit"
    },
    "shape236": {
      "forbidden": true
    },
    "shape234": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape196"
        },
        "mode": {
          "$ref": "#/$defs/shape235"
        },
        "version": {
          "$ref": "#/$defs/shape236"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape238": {
      "const": "clear"
    },
    "shape237": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape236"
        },
        "mode": {
          "$ref": "#/$defs/shape238"
        },
        "version": {
          "$ref": "#/$defs/shape236"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape231": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape232"
        },
        {
          "$ref": "#/$defs/shape234"
        },
        {
          "$ref": "#/$defs/shape237"
        }
      ]
    },
    "shape230": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape231"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "expectedRevision",
        "requestId",
        "typing",
        "view"
      ],
      "additionalProperties": false
    },
    "shape228": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape229"
        },
        "payload": {
          "$ref": "#/$defs/shape230"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "upload"
    },
    "shape241": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape231"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape239": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape240"
        },
        "payload": {
          "$ref": "#/$defs/shape241"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "acquire"
    },
    "shape244": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape231"
        },
        "url": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "url"
      ],
      "additionalProperties": false
    },
    "shape242": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape243"
        },
        "payload": {
          "$ref": "#/$defs/shape244"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "refresh"
    },
    "shape247": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape231"
        },
        "view": {
          "$ref": "#/$defs/shape180"
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
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape246"
        },
        "payload": {
          "$ref": "#/$defs/shape247"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "revise"
    },
    "shape250": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
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
        },
        "typing": {
          "$ref": "#/$defs/shape231"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape248": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape249"
        },
        "payload": {
          "$ref": "#/$defs/shape250"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "replace"
    },
    "shape253": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
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
        },
        "typing": {
          "$ref": "#/$defs/shape231"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape251": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape252"
        },
        "payload": {
          "$ref": "#/$defs/shape253"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "list"
    },
    "shape259": {
      "const": "source"
    },
    "shape260": {
      "const": "report"
    },
    "shape258": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape259"
        },
        {
          "$ref": "#/$defs/shape260"
        }
      ]
    },
    "shape257": {
      "type": "object",
      "properties": {
        "author": {
          "$ref": "#/$defs/shape6"
        },
        "autoTag": {
          "$ref": "#/$defs/shape208"
        },
        "contentType": {
          "$ref": "#/$defs/shape6"
        },
        "contentTypeVersion": {
          "$ref": "#/$defs/shape6"
        },
        "documentAuthor": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape258"
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
          "$ref": "#/$defs/shape208"
        },
        "untyped": {
          "$ref": "#/$defs/shape29"
        },
        "when": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape256": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape257"
        },
        "kind": {
          "$ref": "#/$defs/shape258"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape254": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape255"
        },
        "payload": {
          "$ref": "#/$defs/shape256"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "facets"
    },
    "shape263": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape257"
        },
        "kind": {
          "$ref": "#/$defs/shape258"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape261": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape262"
        },
        "payload": {
          "$ref": "#/$defs/shape263"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "tags"
    },
    "shape266": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "tags": {
          "$ref": "#/$defs/shape208"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "revision",
        "tags"
      ],
      "additionalProperties": false
    },
    "shape264": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape265"
        },
        "payload": {
          "$ref": "#/$defs/shape266"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "tags.groups"
    },
    "shape271": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape208"
      }
    },
    "shape270": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape271"
        }
      ]
    },
    "shape269": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape270"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "groups",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape267": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape268"
        },
        "payload": {
          "$ref": "#/$defs/shape269"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "inventory"
    },
    "shape274": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape272": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape273"
        },
        "payload": {
          "$ref": "#/$defs/shape274"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "acquisitions"
    },
    "shape277": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape275": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape276"
        },
        "payload": {
          "$ref": "#/$defs/shape277"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "status"
    },
    "shape280": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape278": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape279"
        },
        "payload": {
          "$ref": "#/$defs/shape280"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "await"
    },
    "shape286": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape236"
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
    "shape287": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape236"
        }
      },
      "required": [
        "acquisition"
      ],
      "additionalProperties": false
    },
    "shape285": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape286"
        },
        {
          "$ref": "#/$defs/shape287"
        }
      ]
    },
    "shape284": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape285"
      }
    },
    "shape283": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape284"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        },
        "waitMs": {
          "$ref": "#/$defs/shape28"
        }
      },
      "required": [
        "sources"
      ],
      "additionalProperties": false
    },
    "shape281": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape282"
        },
        "payload": {
          "$ref": "#/$defs/shape283"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "read"
    },
    "shape290": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape288": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape289"
        },
        "payload": {
          "$ref": "#/$defs/shape290"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "outline"
    },
    "shape293": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
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
    "shape291": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape292"
        },
        "payload": {
          "$ref": "#/$defs/shape293"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "symbols"
    },
    "shape296": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
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
    "shape294": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape295"
        },
        "payload": {
          "$ref": "#/$defs/shape296"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "search"
    },
    "shape299": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape257"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "query": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape297": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape298"
        },
        "payload": {
          "$ref": "#/$defs/shape299"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "rank"
    },
    "shape302": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape257"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "query": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape300": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape301"
        },
        "payload": {
          "$ref": "#/$defs/shape302"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "ask"
    },
    "shape305": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape28"
        },
        "question": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "question",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape303": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape304"
        },
        "payload": {
          "$ref": "#/$defs/shape305"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "evidence.record"
    },
    "shape308": {
      "type": "object",
      "properties": {
        "end": {
          "$ref": "#/$defs/shape28"
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
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape180"
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
    "shape306": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape307"
        },
        "payload": {
          "$ref": "#/$defs/shape308"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
      "const": "evidence.read"
    },
    "shape311": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "evidence"
      ],
      "additionalProperties": false
    },
    "shape309": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape310"
        },
        "payload": {
          "$ref": "#/$defs/shape311"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape313": {
      "const": "record.report"
    },
    "shape318": {
      "const": "holds"
    },
    "shape319": {
      "const": "weakened"
    },
    "shape320": {
      "const": "refuted"
    },
    "shape321": {
      "const": "not_checked"
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
    "shape316": {
      "type": "object",
      "properties": {
        "claim": {
          "$ref": "#/$defs/shape6"
        },
        "counterEvidence": {
          "$ref": "#/$defs/shape208"
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
          "$ref": "#/$defs/shape208"
        },
        "verdict": {
          "$ref": "#/$defs/shape317"
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
    "shape315": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape316"
      }
    },
    "shape323": {
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
    "shape322": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape323"
      }
    },
    "shape314": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape208"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "feedback": {
          "$ref": "#/$defs/shape6"
        },
        "findings": {
          "$ref": "#/$defs/shape315"
        },
        "inputs": {
          "$ref": "#/$defs/shape208"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "objectives": {
          "$ref": "#/$defs/shape208"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "reviews": {
          "$ref": "#/$defs/shape322"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape208"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape231"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape312": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape313"
        },
        "payload": {
          "$ref": "#/$defs/shape314"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape325": {
      "const": "finalise"
    },
    "shape326": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape324": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape325"
        },
        "payload": {
          "$ref": "#/$defs/shape326"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape328": {
      "const": "link"
    },
    "shape329": {
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
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape327": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape328"
        },
        "payload": {
          "$ref": "#/$defs/shape329"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape331": {
      "const": "unlink"
    },
    "shape332": {
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
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape330": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape331"
        },
        "payload": {
          "$ref": "#/$defs/shape332"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape334": {
      "const": "share"
    },
    "shape335": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape333": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape334"
        },
        "payload": {
          "$ref": "#/$defs/shape335"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape337": {
      "const": "unshare"
    },
    "shape338": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape336": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape337"
        },
        "payload": {
          "$ref": "#/$defs/shape338"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape340": {
      "const": "withdraw"
    },
    "shape341": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape339": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape340"
        },
        "payload": {
          "$ref": "#/$defs/shape341"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape343": {
      "const": "exclude"
    },
    "shape344": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape342": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape343"
        },
        "payload": {
          "$ref": "#/$defs/shape344"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape346": {
      "const": "unexclude"
    },
    "shape347": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape345": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape346"
        },
        "payload": {
          "$ref": "#/$defs/shape347"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape349": {
      "const": "restore"
    },
    "shape350": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape348": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape349"
        },
        "payload": {
          "$ref": "#/$defs/shape350"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape352": {
      "const": "delete"
    },
    "shape353": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape351": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape352"
        },
        "payload": {
          "$ref": "#/$defs/shape353"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape355": {
      "const": "retry"
    },
    "shape359": {
      "const": "extract"
    },
    "shape360": {
      "const": "derive"
    },
    "shape361": {
      "const": "embed"
    },
    "shape362": {
      "const": "summarise"
    },
    "shape363": {
      "const": "summary_embed"
    },
    "shape364": {
      "const": "autoTag"
    },
    "shape365": {
      "const": "tagGroups"
    },
    "shape358": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape359"
        },
        {
          "$ref": "#/$defs/shape360"
        },
        {
          "$ref": "#/$defs/shape361"
        },
        {
          "$ref": "#/$defs/shape362"
        },
        {
          "$ref": "#/$defs/shape363"
        },
        {
          "$ref": "#/$defs/shape364"
        },
        {
          "$ref": "#/$defs/shape365"
        }
      ]
    },
    "shape357": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape358"
      }
    },
    "shape356": {
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
        },
        "stages": {
          "$ref": "#/$defs/shape357"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape354": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape355"
        },
        "payload": {
          "$ref": "#/$defs/shape356"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape367": {
      "const": "rebuild"
    },
    "shape368": {
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
        },
        "stages": {
          "$ref": "#/$defs/shape357"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "requestId",
        "revision",
        "stage"
      ],
      "additionalProperties": false
    },
    "shape366": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape367"
        },
        "payload": {
          "$ref": "#/$defs/shape368"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape370": {
      "const": "allowance"
    },
    "shape371": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape28"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "maxModelCalls",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape369": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape370"
        },
        "payload": {
          "$ref": "#/$defs/shape371"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape373": {
      "const": "events"
    },
    "shape374": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape28"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape372": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape373"
        },
        "payload": {
          "$ref": "#/$defs/shape374"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape376": {
      "const": "migration.list"
    },
    "shape377": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape375": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape376"
        },
        "payload": {
          "$ref": "#/$defs/shape377"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape379": {
      "const": "migration.adopt"
    },
    "shape380": {
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
        "view": {
          "$ref": "#/$defs/shape180"
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
    "shape378": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape379"
        },
        "payload": {
          "$ref": "#/$defs/shape380"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape382": {
      "const": "migration.inspect"
    },
    "shape383": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "payload": {
          "$ref": "#/$defs/shape6"
        },
        "reason": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "payload",
        "reason"
      ],
      "additionalProperties": false
    },
    "shape381": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape382"
        },
        "payload": {
          "$ref": "#/$defs/shape383"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape385": {
      "const": "migration.release"
    },
    "shape386": {
      "type": "object",
      "properties": {
        "inputs": {
          "$ref": "#/$defs/shape208"
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
        },
        "view": {
          "$ref": "#/$defs/shape180"
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
    "shape384": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape385"
        },
        "payload": {
          "$ref": "#/$defs/shape386"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
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
    "shape389": {
      "const": "bootstrap"
    },
    "shape390": {
      "const": "demo"
    },
    "shape391": {
      "const": "disconnect"
    },
    "shape392": {
      "const": "approvals-refresh"
    },
    "shape388": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape246"
        },
        {
          "$ref": "#/$defs/shape389"
        },
        {
          "$ref": "#/$defs/shape390"
        },
        {
          "$ref": "#/$defs/shape391"
        },
        {
          "$ref": "#/$defs/shape392"
        }
      ]
    },
    "shape387": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape388"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape394": {
      "const": "project-access"
    },
    "shape396": {
      "const": "project.access"
    },
    "shape397": {
      "const": "project.member.add"
    },
    "shape398": {
      "const": "project.member.remove"
    },
    "shape399": {
      "const": "project.member.role"
    },
    "shape395": {
      "anyOf": [
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
    "shape401": {
      "const": "VIEWER"
    },
    "shape402": {
      "const": "CONTRIBUTOR"
    },
    "shape403": {
      "const": "MANAGER"
    },
    "shape400": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape401"
        },
        {
          "$ref": "#/$defs/shape402"
        },
        {
          "$ref": "#/$defs/shape403"
        }
      ]
    },
    "shape393": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape394"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape395"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape400"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape405": {
      "const": "server-admin"
    },
    "shape406": {
      "const": "admin.pricing.list"
    },
    "shape407": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape236"
      }
    },
    "shape404": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape406"
        },
        "payload": {
          "$ref": "#/$defs/shape407"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape409": {
      "const": "admin.pricing.set"
    },
    "shape412": {
      "const": "TOKEN"
    },
    "shape413": {
      "const": "INCLUDED"
    },
    "shape414": {
      "const": "ZERO_RATE"
    },
    "shape415": {
      "const": "UNPRICED"
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
        }
      ]
    },
    "shape416": {
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
    "shape419": {
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
    "shape418": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape28"
        },
        "rates": {
          "$ref": "#/$defs/shape419"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape417": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape418"
      }
    },
    "shape410": {
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
          "$ref": "#/$defs/shape411"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "rates": {
          "$ref": "#/$defs/shape416"
        },
        "requestFee": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "tiers": {
          "$ref": "#/$defs/shape417"
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
    "shape408": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape409"
        },
        "payload": {
          "$ref": "#/$defs/shape410"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape421": {
      "const": "admin.accounts"
    },
    "shape420": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape421"
        },
        "payload": {
          "$ref": "#/$defs/shape407"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape423": {
      "const": "admin.account.create"
    },
    "shape424": {
      "type": "object",
      "properties": {
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape29"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape422": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape423"
        },
        "payload": {
          "$ref": "#/$defs/shape424"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape426": {
      "const": "admin.account.update"
    },
    "shape427": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape29"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "serverAdmin": {
          "$ref": "#/$defs/shape29"
        }
      },
      "required": [
        "handle"
      ],
      "additionalProperties": false
    },
    "shape425": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape426"
        },
        "payload": {
          "$ref": "#/$defs/shape427"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape429": {
      "const": "admin.account.reset"
    },
    "shape430": {
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
    "shape428": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape429"
        },
        "payload": {
          "$ref": "#/$defs/shape430"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape432": {
      "const": "admin.sessions"
    },
    "shape433": {
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
    "shape431": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape432"
        },
        "payload": {
          "$ref": "#/$defs/shape433"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape435": {
      "const": "admin.session.revoke"
    },
    "shape436": {
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
    "shape434": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape435"
        },
        "payload": {
          "$ref": "#/$defs/shape436"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape438": {
      "const": "admin.audit"
    },
    "shape439": {
      "type": "object",
      "properties": {
        "before": {
          "$ref": "#/$defs/shape28"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        }
      },
      "additionalProperties": false
    },
    "shape437": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape438"
        },
        "payload": {
          "$ref": "#/$defs/shape439"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape441": {
      "const": "admin.service.accounts"
    },
    "shape440": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape441"
        },
        "payload": {
          "$ref": "#/$defs/shape407"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape443": {
      "const": "admin.service.account.create"
    },
    "shape444": {
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
    "shape442": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape443"
        },
        "payload": {
          "$ref": "#/$defs/shape444"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape446": {
      "const": "admin.service.account.update"
    },
    "shape447": {
      "type": "object",
      "properties": {
        "enabled": {
          "$ref": "#/$defs/shape29"
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
    "shape445": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape446"
        },
        "payload": {
          "$ref": "#/$defs/shape447"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape449": {
      "const": "admin.service.tokens"
    },
    "shape450": {
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
    "shape448": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape449"
        },
        "payload": {
          "$ref": "#/$defs/shape450"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape452": {
      "const": "admin.service.token.create"
    },
    "shape455": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape400"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape454": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape455"
      }
    },
    "shape453": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape28"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "scopes": {
          "$ref": "#/$defs/shape454"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape451": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape452"
        },
        "payload": {
          "$ref": "#/$defs/shape453"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape457": {
      "const": "admin.service.token.rotate"
    },
    "shape458": {
      "type": "object",
      "properties": {
        "expiresInDays": {
          "$ref": "#/$defs/shape28"
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
    "shape456": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape457"
        },
        "payload": {
          "$ref": "#/$defs/shape458"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape460": {
      "const": "admin.service.token.revoke"
    },
    "shape461": {
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
    "shape459": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape405"
        },
        "operation": {
          "$ref": "#/$defs/shape460"
        },
        "payload": {
          "$ref": "#/$defs/shape461"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape463": {
      "const": "server-project-create"
    },
    "shape465": {
      "const": "MANAGED"
    },
    "shape466": {
      "const": "DISJOINT"
    },
    "shape464": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape465"
        },
        {
          "$ref": "#/$defs/shape466"
        }
      ]
    },
    "shape467": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape468": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape462": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape463"
        },
        "applicationRoot": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape464"
        },
        "workspace": {
          "$ref": "#/$defs/shape6"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape467"
        },
        "writePaths": {
          "$ref": "#/$defs/shape468"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape470": {
      "const": "server-setup"
    },
    "shape469": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape470"
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
    "shape472": {
      "const": "connect"
    },
    "shape471": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape472"
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
    "shape474": {
      "const": "connection-select"
    },
    "shape473": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape474"
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
    "shape476": {
      "const": "connection-preferences"
    },
    "shape478": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape479": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape29"
      }
    },
    "shape477": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape478"
        },
        "drafts": {
          "$ref": "#/$defs/shape478"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape479"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape479"
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
    "shape475": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape6"
        },
        "action": {
          "$ref": "#/$defs/shape476"
        },
        "preference": {
          "$ref": "#/$defs/shape477"
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
    "shape481": {
      "const": "connection-rename"
    },
    "shape480": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape481"
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
    "shape483": {
      "const": "connection-remove"
    },
    "shape482": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape483"
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
    "shape485": {
      "const": "personal-section"
    },
    "shape487": {
      "const": "In"
    },
    "shape488": {
      "const": "Out"
    },
    "shape489": {
      "const": "Resources"
    },
    "shape490": {
      "const": "Archive"
    },
    "shape491": {
      "const": "Planning"
    },
    "shape492": {
      "const": "Bots"
    },
    "shape486": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape487"
        },
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
        },
        {
          "$ref": "#/$defs/shape492"
        }
      ]
    },
    "shape484": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape485"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "section": {
          "$ref": "#/$defs/shape486"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape494": {
      "const": "personal-bots"
    },
    "shape493": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape494"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape496": {
      "const": "personal-recreate"
    },
    "shape495": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape496"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape498": {
      "const": "files-choose"
    },
    "shape497": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape498"
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
    "shape500": {
      "const": "files-withdraw"
    },
    "shape499": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape500"
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
    "shape502": {
      "const": "sync-refresh"
    },
    "shape501": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape502"
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
    "shape504": {
      "const": "sync-inspect"
    },
    "shape503": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape504"
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
    "shape506": {
      "const": "sync-change"
    },
    "shape508": {
      "const": "on"
    },
    "shape509": {
      "const": "off"
    },
    "shape510": {
      "const": "now"
    },
    "shape507": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape508"
        },
        {
          "$ref": "#/$defs/shape509"
        },
        {
          "$ref": "#/$defs/shape510"
        }
      ]
    },
    "shape505": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape507"
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
    "shape512": {
      "const": "sync-resolve"
    },
    "shape514": {
      "const": "mine"
    },
    "shape515": {
      "const": "theirs"
    },
    "shape516": {
      "const": "done"
    },
    "shape513": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape514"
        },
        {
          "$ref": "#/$defs/shape515"
        },
        {
          "$ref": "#/$defs/shape516"
        }
      ]
    },
    "shape511": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape512"
        },
        "how": {
          "$ref": "#/$defs/shape513"
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
    "shape519": {
      "const": "project-open"
    },
    "shape520": {
      "const": "project-remove"
    },
    "shape518": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape519"
        },
        {
          "$ref": "#/$defs/shape520"
        }
      ]
    },
    "shape517": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape518"
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
      "const": "scope"
    },
    "shape524": {
      "const": "create"
    },
    "shape522": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape523"
        },
        {
          "$ref": "#/$defs/shape524"
        }
      ]
    },
    "shape521": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape522"
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
    "shape526": {
      "const": "history"
    },
    "shape525": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape526"
        },
        "before": {
          "$ref": "#/$defs/shape28"
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
    "shape528": {
      "const": "select"
    },
    "shape527": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
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
    "shape530": {
      "const": "trajectory"
    },
    "shape529": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape530"
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
    "shape532": {
      "const": "board-swarm-types"
    },
    "shape531": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
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
    "shape534": {
      "const": "board-post-topics"
    },
    "shape533": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape534"
        },
        "more": {
          "$ref": "#/$defs/shape29"
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
    "shape536": {
      "const": "board-create"
    },
    "shape535": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape536"
        },
        "body": {
          "$ref": "#/$defs/shape6"
        },
        "label": {
          "$ref": "#/$defs/shape6"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape28"
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
    "shape538": {
      "const": "board-retry"
    },
    "shape537": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape538"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape28"
        },
        "member": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "reconcile": {
          "$ref": "#/$defs/shape29"
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
    "shape540": {
      "const": "board-post"
    },
    "shape539": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
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
    "shape542": {
      "const": "workspace-chat"
    },
    "shape541": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape542"
        },
        "manage": {
          "$ref": "#/$defs/shape29"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape544": {
      "const": "workspace-layout"
    },
    "shape543": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape544"
        },
        "height": {
          "$ref": "#/$defs/shape28"
        },
        "visible": {
          "$ref": "#/$defs/shape29"
        },
        "width": {
          "$ref": "#/$defs/shape28"
        },
        "x": {
          "$ref": "#/$defs/shape28"
        },
        "y": {
          "$ref": "#/$defs/shape28"
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
    "shape546": {
      "const": "workspace-refresh"
    },
    "shape545": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape546"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape548": {
      "const": "library"
    },
    "shape550": {
      "const": "sources"
    },
    "shape551": {
      "const": "documents"
    },
    "shape552": {
      "const": "memories"
    },
    "shape553": {
      "const": "manual"
    },
    "shape549": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape298"
        },
        {
          "$ref": "#/$defs/shape550"
        },
        {
          "$ref": "#/$defs/shape551"
        },
        {
          "$ref": "#/$defs/shape552"
        },
        {
          "$ref": "#/$defs/shape553"
        }
      ]
    },
    "shape547": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape548"
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
          "$ref": "#/$defs/shape549"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape555": {
      "const": "library-view"
    },
    "shape554": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape555"
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
          "$ref": "#/$defs/shape549"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape558": {
      "const": "library-refresh"
    },
    "shape559": {
      "const": "library-citations"
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
    "shape556": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape557"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape561": {
      "const": "library-documents"
    },
    "shape560": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape561"
        },
        "more": {
          "$ref": "#/$defs/shape29"
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
    "shape564": {
      "const": "library-document"
    },
    "shape565": {
      "const": "library-memory"
    },
    "shape566": {
      "const": "library-chunk"
    },
    "shape567": {
      "const": "library-conversation"
    },
    "shape563": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape564"
        },
        {
          "$ref": "#/$defs/shape565"
        },
        {
          "$ref": "#/$defs/shape566"
        },
        {
          "$ref": "#/$defs/shape567"
        }
      ]
    },
    "shape562": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape563"
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
    "shape569": {
      "const": "library-source-text"
    },
    "shape568": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape569"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        }
      },
      "required": [
        "action",
        "id",
        "offset"
      ],
      "additionalProperties": false
    },
    "shape571": {
      "const": "library-stance"
    },
    "shape570": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape571"
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
    "shape573": {
      "const": "library-search"
    },
    "shape575": {
      "const": "retrieve"
    },
    "shape576": {
      "const": "recall"
    },
    "shape577": {
      "const": "navigate"
    },
    "shape574": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape301"
        },
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape551"
        },
        {
          "$ref": "#/$defs/shape575"
        },
        {
          "$ref": "#/$defs/shape576"
        },
        {
          "$ref": "#/$defs/shape577"
        }
      ]
    },
    "shape579": {
      "const": "lexical"
    },
    "shape580": {
      "const": "semantic"
    },
    "shape581": {
      "const": "hybrid"
    },
    "shape578": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape579"
        },
        {
          "$ref": "#/$defs/shape580"
        },
        {
          "$ref": "#/$defs/shape581"
        }
      ]
    },
    "shape572": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape573"
        },
        "kind": {
          "$ref": "#/$defs/shape574"
        },
        "mode": {
          "$ref": "#/$defs/shape578"
        },
        "more": {
          "$ref": "#/$defs/shape29"
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
    "shape583": {
      "const": "library-maintain"
    },
    "shape585": {
      "const": "invalidate"
    },
    "shape586": {
      "const": "resolve"
    },
    "shape587": {
      "const": "reembed"
    },
    "shape588": {
      "const": "reconsider"
    },
    "shape584": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape585"
        },
        {
          "$ref": "#/$defs/shape586"
        },
        {
          "$ref": "#/$defs/shape587"
        },
        {
          "$ref": "#/$defs/shape588"
        }
      ]
    },
    "shape582": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape29"
        },
        "action": {
          "$ref": "#/$defs/shape583"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape584"
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
    "shape590": {
      "const": "builder-outputs"
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
      "const": "builder-trajectory"
    },
    "shape591": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape592"
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
    "shape594": {
      "const": "builder-prepare"
    },
    "shape593": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape594"
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
    "shape596": {
      "const": "builder-start"
    },
    "shape595": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape596"
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
    "shape598": {
      "const": "activity"
    },
    "shape600": {
      "const": "inbox"
    },
    "shape601": {
      "const": "runs"
    },
    "shape602": {
      "const": "definitions"
    },
    "shape603": {
      "const": "schedules"
    },
    "shape604": {
      "const": "builder"
    },
    "shape599": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape600"
        },
        {
          "$ref": "#/$defs/shape601"
        },
        {
          "$ref": "#/$defs/shape602"
        },
        {
          "$ref": "#/$defs/shape603"
        },
        {
          "$ref": "#/$defs/shape604"
        }
      ]
    },
    "shape597": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape598"
        },
        "view": {
          "$ref": "#/$defs/shape599"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape606": {
      "const": "activity-view"
    },
    "shape605": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape606"
        },
        "view": {
          "$ref": "#/$defs/shape599"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape608": {
      "const": "activity-refresh"
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
    "shape610": {
      "const": "question-refresh"
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
    "shape612": {
      "const": "inbox-read"
    },
    "shape611": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape612"
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
    "shape614": {
      "const": "inbox-more"
    },
    "shape613": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape614"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape616": {
      "const": "run-detail"
    },
    "shape615": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape616"
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
    "shape618": {
      "const": "schedule-refresh"
    },
    "shape617": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape618"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape620": {
      "const": "schedule-preview"
    },
    "shape619": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape620"
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
    "shape622": {
      "const": "schedule-save"
    },
    "shape626": {
      "const": "skill"
    },
    "shape627": {
      "const": "orchestration"
    },
    "shape625": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape89"
        },
        {
          "$ref": "#/$defs/shape626"
        },
        {
          "$ref": "#/$defs/shape627"
        }
      ]
    },
    "shape629": {
      "const": "INHERITED"
    },
    "shape630": {
      "const": "SUMMARISED"
    },
    "shape631": {
      "const": "NEW"
    },
    "shape632": {
      "const": "DIRECT"
    },
    "shape628": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape629"
        },
        {
          "$ref": "#/$defs/shape630"
        },
        {
          "$ref": "#/$defs/shape631"
        },
        {
          "$ref": "#/$defs/shape632"
        }
      ]
    },
    "shape624": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape625"
        },
        "mode": {
          "$ref": "#/$defs/shape628"
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
    "shape634": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape28"
        }
      ]
    },
    "shape633": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape634"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape634"
        },
        "queueCap": {
          "$ref": "#/$defs/shape28"
        }
      },
      "required": [
        "maxModelCalls",
        "maxTurns",
        "queueCap"
      ],
      "additionalProperties": false
    },
    "shape637": {
      "const": "mailbox"
    },
    "shape638": {
      "const": "message"
    },
    "shape636": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape637"
        },
        {
          "$ref": "#/$defs/shape638"
        }
      ]
    },
    "shape635": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape636"
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
    "shape639": {
      "const": 1
    },
    "shape623": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape624"
        },
        "cron": {
          "$ref": "#/$defs/shape6"
        },
        "limits": {
          "$ref": "#/$defs/shape633"
        },
        "paused": {
          "$ref": "#/$defs/shape29"
        },
        "target": {
          "$ref": "#/$defs/shape635"
        },
        "version": {
          "$ref": "#/$defs/shape639"
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
    "shape641": {
      "const": "server"
    },
    "shape642": {
      "const": "workspace"
    },
    "shape640": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape641"
        },
        {
          "$ref": "#/$defs/shape642"
        }
      ]
    },
    "shape621": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape622"
        },
        "definition": {
          "$ref": "#/$defs/shape623"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape640"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape644": {
      "const": "schedule-file-save"
    },
    "shape643": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape644"
        },
        "definition": {
          "$ref": "#/$defs/shape623"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "overwrite": {
          "$ref": "#/$defs/shape29"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape640"
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
    "shape646": {
      "const": "schedule-sync"
    },
    "shape645": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape646"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape640"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape648": {
      "const": "schedule-change"
    },
    "shape650": {
      "const": "schedule"
    },
    "shape651": {
      "const": "trigger"
    },
    "shape649": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape650"
        },
        {
          "$ref": "#/$defs/shape651"
        }
      ]
    },
    "shape647": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape648"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape649"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "paused": {
          "$ref": "#/$defs/shape29"
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
    "shape653": {
      "const": "schedule-fire"
    },
    "shape652": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape653"
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
    "shape655": {
      "const": "run-definitions"
    },
    "shape654": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape655"
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
    "shape657": {
      "const": "run-record"
    },
    "shape656": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape657"
        },
        "before": {
          "$ref": "#/$defs/shape28"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "kinds": {
          "$ref": "#/$defs/shape468"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape659": {
      "const": "run-answer"
    },
    "shape661": {
      "type": "object",
      "properties": {
        "chosen": {
          "$ref": "#/$defs/shape208"
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
    "shape660": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape661"
      }
    },
    "shape658": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape659"
        },
        "answer": {
          "$ref": "#/$defs/shape6"
        },
        "choices": {
          "$ref": "#/$defs/shape660"
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
    "shape663": {
      "const": "run-cancel"
    },
    "shape662": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape663"
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
    "shape665": {
      "const": "run-resume"
    },
    "shape664": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape665"
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
    "shape667": {
      "const": "run-trajectory"
    },
    "shape669": {
      "const": "conductor"
    },
    "shape670": {
      "const": "caller"
    },
    "shape668": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape669"
        },
        {
          "$ref": "#/$defs/shape670"
        }
      ]
    },
    "shape666": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape667"
        },
        "actor": {
          "$ref": "#/$defs/shape668"
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
    "shape672": {
      "const": "run-stage-trajectory"
    },
    "shape671": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape672"
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
    "shape674": {
      "const": "delegate-trajectory"
    },
    "shape673": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape674"
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
    "shape677": {
      "const": "board-inspection"
    },
    "shape678": {
      "const": "board-view"
    },
    "shape676": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape677"
        },
        {
          "$ref": "#/$defs/shape678"
        }
      ]
    },
    "shape680": {
      "const": "board"
    },
    "shape681": {
      "const": "swarm"
    },
    "shape679": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape680"
        },
        {
          "$ref": "#/$defs/shape681"
        }
      ]
    },
    "shape675": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape676"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape679"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape684": {
      "const": "board-refresh"
    },
    "shape685": {
      "const": "board-more"
    },
    "shape683": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape684"
        },
        {
          "$ref": "#/$defs/shape685"
        }
      ]
    },
    "shape682": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape683"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape687": {
      "const": "board-topic"
    },
    "shape686": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape687"
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
    "shape689": {
      "const": "board-trajectory"
    },
    "shape688": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape689"
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
    "shape691": {
      "const": "context"
    },
    "shape690": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape691"
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
    "shape693": {
      "const": "open-link"
    },
    "shape692": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape693"
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
    "shape695": {
      "const": "copy-text"
    },
    "shape694": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape695"
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
    "shape697": {
      "const": "workflow-start"
    },
    "shape696": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape697"
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
    "shape698": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape92"
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
    "shape700": {
      "const": "cancel"
    },
    "shape699": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape700"
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
    "shape702": {
      "const": "answer"
    },
    "shape703": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape158"
        },
        {
          "$ref": "#/$defs/shape160"
        }
      ]
    },
    "shape701": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape702"
        },
        "decision": {
          "$ref": "#/$defs/shape703"
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
    "shape705": {
      "const": "native-mcp"
    },
    "shape704": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape705"
        },
        "command": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "command"
      ],
      "additionalProperties": false
    },
    "shape707": {
      "const": "provider-management"
    },
    "shape708": {
      "const": "provider.connection.list"
    },
    "shape709": {
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
    "shape706": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape708"
        },
        "payload": {
          "$ref": "#/$defs/shape709"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape711": {
      "const": "provider.connection.status"
    },
    "shape712": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "connection",
        "project"
      ],
      "additionalProperties": false
    },
    "shape710": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape711"
        },
        "payload": {
          "$ref": "#/$defs/shape712"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape714": {
      "const": "provider.connection.receipt"
    },
    "shape715": {
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
    "shape713": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape714"
        },
        "payload": {
          "$ref": "#/$defs/shape715"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape717": {
      "const": "provider.connection.save"
    },
    "shape721": {
      "const": "Authorization"
    },
    "shape722": {
      "const": "X-API-Key"
    },
    "shape723": {
      "const": "Api-Key"
    },
    "shape720": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape721"
        },
        {
          "$ref": "#/$defs/shape722"
        },
        {
          "$ref": "#/$defs/shape723"
        }
      ]
    },
    "shape725": {
      "const": "API_KEY"
    },
    "shape726": {
      "const": "OAUTH_PKCE"
    },
    "shape727": {
      "const": "CLIENT_CREDENTIALS"
    },
    "shape724": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape725"
        },
        {
          "$ref": "#/$defs/shape726"
        },
        {
          "$ref": "#/$defs/shape727"
        }
      ]
    },
    "shape729": {
      "type": "object",
      "properties": {
        "authorizationEndpoint": {
          "$ref": "#/$defs/shape6"
        },
        "clientId": {
          "$ref": "#/$defs/shape6"
        },
        "issuer": {
          "$ref": "#/$defs/shape6"
        },
        "issuerMetadataUrl": {
          "$ref": "#/$defs/shape6"
        },
        "redirectUri": {
          "$ref": "#/$defs/shape6"
        },
        "resource": {
          "$ref": "#/$defs/shape6"
        },
        "resourceMetadataUrl": {
          "$ref": "#/$defs/shape6"
        },
        "scopes": {
          "$ref": "#/$defs/shape208"
        },
        "tokenEndpoint": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "authorizationEndpoint",
        "clientId",
        "issuer",
        "issuerMetadataUrl",
        "redirectUri",
        "resource",
        "resourceMetadataUrl",
        "scopes",
        "tokenEndpoint"
      ],
      "additionalProperties": false
    },
    "shape728": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape729"
        }
      ]
    },
    "shape719": {
      "type": "object",
      "properties": {
        "apiKeyHeader": {
          "$ref": "#/$defs/shape720"
        },
        "authentication": {
          "$ref": "#/$defs/shape724"
        },
        "endpoint": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "oauth": {
          "$ref": "#/$defs/shape728"
        }
      },
      "required": [
        "apiKeyHeader",
        "authentication",
        "endpoint",
        "name",
        "oauth"
      ],
      "additionalProperties": false
    },
    "shape718": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape719"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "connection",
        "definition",
        "expectedRevision",
        "project",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape716": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape717"
        },
        "payload": {
          "$ref": "#/$defs/shape718"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape731": {
      "const": "provider.connection.change"
    },
    "shape734": {
      "const": "ENABLE"
    },
    "shape735": {
      "const": "DISABLE"
    },
    "shape736": {
      "const": "DELETE"
    },
    "shape733": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape734"
        },
        {
          "$ref": "#/$defs/shape735"
        },
        {
          "$ref": "#/$defs/shape736"
        }
      ]
    },
    "shape732": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape733"
        },
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "connection",
        "expectedRevision",
        "project",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape730": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape731"
        },
        "payload": {
          "$ref": "#/$defs/shape732"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape738": {
      "const": "provider.credential.status"
    },
    "shape739": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "subject": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "connection",
        "project",
        "subject"
      ],
      "additionalProperties": false
    },
    "shape737": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape738"
        },
        "payload": {
          "$ref": "#/$defs/shape739"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape741": {
      "const": "provider.credential.put"
    },
    "shape742": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "secret": {
          "$ref": "#/$defs/shape6"
        },
        "subject": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "connection",
        "expectedGeneration",
        "project",
        "requestId",
        "secret",
        "subject"
      ],
      "additionalProperties": false
    },
    "shape740": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape741"
        },
        "payload": {
          "$ref": "#/$defs/shape742"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape744": {
      "const": "provider.credential.revoke"
    },
    "shape745": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape7"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "subject": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "connection",
        "expectedGeneration",
        "project",
        "requestId",
        "subject"
      ],
      "additionalProperties": false
    },
    "shape743": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape744"
        },
        "payload": {
          "$ref": "#/$defs/shape745"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape747": {
      "const": "provider.credential.rekey"
    },
    "shape746": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape747"
        },
        "payload": {
          "$ref": "#/$defs/shape745"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape749": {
      "const": "provider.credential.authorize"
    },
    "shape748": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape749"
        },
        "payload": {
          "$ref": "#/$defs/shape745"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape751": {
      "const": "provider.credential.refresh"
    },
    "shape750": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape751"
        },
        "payload": {
          "$ref": "#/$defs/shape745"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape753": {
      "const": "tool.scope.host.list"
    },
    "shape754": {
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
    "shape752": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape753"
        },
        "payload": {
          "$ref": "#/$defs/shape754"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape756": {
      "const": "tool.scope.host.status"
    },
    "shape757": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "binding",
        "project"
      ],
      "additionalProperties": false
    },
    "shape755": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape756"
        },
        "payload": {
          "$ref": "#/$defs/shape757"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape759": {
      "const": "tool.scope.host.receipt"
    },
    "shape760": {
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
    "shape758": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape759"
        },
        "payload": {
          "$ref": "#/$defs/shape760"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape762": {
      "const": "tool.scope.host.save"
    },
    "shape764": {
      "type": "object",
      "properties": {
        "agents": {
          "$ref": "#/$defs/shape208"
        },
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "credentialProject": {
          "$ref": "#/$defs/shape6"
        },
        "executor": {
          "$ref": "#/$defs/shape6"
        },
        "grants": {
          "$ref": "#/$defs/shape208"
        },
        "leaseSeconds": {
          "$ref": "#/$defs/shape28"
        },
        "prefix": {
          "$ref": "#/$defs/shape6"
        },
        "provider": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape6"
        },
        "subject": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "agents",
        "connection",
        "credentialProject",
        "executor",
        "grants",
        "leaseSeconds",
        "prefix",
        "provider",
        "scope",
        "subject"
      ],
      "additionalProperties": false
    },
    "shape763": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape764"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape634"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "binding",
        "definition",
        "expectedGeneration",
        "project",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape761": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape762"
        },
        "payload": {
          "$ref": "#/$defs/shape763"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape766": {
      "const": "tool.scope.host.change"
    },
    "shape767": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape733"
        },
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape28"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "binding",
        "expectedGeneration",
        "project",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape765": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape766"
        },
        "payload": {
          "$ref": "#/$defs/shape767"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape769": {
      "const": "tool.scope.list"
    },
    "shape770": {
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
    "shape768": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        },
        "operation": {
          "$ref": "#/$defs/shape769"
        },
        "payload": {
          "$ref": "#/$defs/shape770"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape772": {
      "const": "provider-management-pending"
    },
    "shape771": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape772"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape774": {
      "const": "provider-management-recover"
    },
    "shape777": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape717"
        },
        {
          "$ref": "#/$defs/shape731"
        }
      ]
    },
    "shape776": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape777"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "connection",
        "expectedRevision",
        "operation",
        "project",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape779": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape741"
        },
        {
          "$ref": "#/$defs/shape744"
        },
        {
          "$ref": "#/$defs/shape747"
        },
        {
          "$ref": "#/$defs/shape749"
        },
        {
          "$ref": "#/$defs/shape751"
        }
      ]
    },
    "shape778": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape779"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "subject": {
          "$ref": "#/$defs/shape7"
        }
      },
      "required": [
        "connection",
        "expectedGeneration",
        "operation",
        "project",
        "requestId",
        "subject"
      ],
      "additionalProperties": false
    },
    "shape781": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape762"
        },
        {
          "$ref": "#/$defs/shape766"
        }
      ]
    },
    "shape780": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape634"
        },
        "operation": {
          "$ref": "#/$defs/shape781"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "binding",
        "expectedGeneration",
        "operation",
        "project",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape775": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape776"
        },
        {
          "$ref": "#/$defs/shape778"
        },
        {
          "$ref": "#/$defs/shape780"
        }
      ]
    },
    "shape773": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape774"
        },
        "intent": {
          "$ref": "#/$defs/shape775"
        }
      },
      "required": [
        "action",
        "intent"
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
          "$ref": "#/$defs/shape25"
        },
        {
          "$ref": "#/$defs/shape32"
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
          "$ref": "#/$defs/shape43"
        },
        {
          "$ref": "#/$defs/shape45"
        },
        {
          "$ref": "#/$defs/shape47"
        },
        {
          "$ref": "#/$defs/shape49"
        },
        {
          "$ref": "#/$defs/shape51"
        },
        {
          "$ref": "#/$defs/shape53"
        },
        {
          "$ref": "#/$defs/shape55"
        },
        {
          "$ref": "#/$defs/shape57"
        },
        {
          "$ref": "#/$defs/shape59"
        },
        {
          "$ref": "#/$defs/shape65"
        },
        {
          "$ref": "#/$defs/shape70"
        },
        {
          "$ref": "#/$defs/shape79"
        },
        {
          "$ref": "#/$defs/shape81"
        },
        {
          "$ref": "#/$defs/shape104"
        },
        {
          "$ref": "#/$defs/shape107"
        },
        {
          "$ref": "#/$defs/shape109"
        },
        {
          "$ref": "#/$defs/shape111"
        },
        {
          "$ref": "#/$defs/shape113"
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
          "$ref": "#/$defs/shape120"
        },
        {
          "$ref": "#/$defs/shape123"
        },
        {
          "$ref": "#/$defs/shape126"
        },
        {
          "$ref": "#/$defs/shape129"
        },
        {
          "$ref": "#/$defs/shape132"
        },
        {
          "$ref": "#/$defs/shape134"
        },
        {
          "$ref": "#/$defs/shape152"
        },
        {
          "$ref": "#/$defs/shape154"
        },
        {
          "$ref": "#/$defs/shape174"
        },
        {
          "$ref": "#/$defs/shape176"
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
          "$ref": "#/$defs/shape199"
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
          "$ref": "#/$defs/shape261"
        },
        {
          "$ref": "#/$defs/shape264"
        },
        {
          "$ref": "#/$defs/shape267"
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
          "$ref": "#/$defs/shape324"
        },
        {
          "$ref": "#/$defs/shape327"
        },
        {
          "$ref": "#/$defs/shape330"
        },
        {
          "$ref": "#/$defs/shape333"
        },
        {
          "$ref": "#/$defs/shape336"
        },
        {
          "$ref": "#/$defs/shape339"
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
          "$ref": "#/$defs/shape354"
        },
        {
          "$ref": "#/$defs/shape366"
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
          "$ref": "#/$defs/shape381"
        },
        {
          "$ref": "#/$defs/shape384"
        },
        {
          "$ref": "#/$defs/shape387"
        },
        {
          "$ref": "#/$defs/shape393"
        },
        {
          "$ref": "#/$defs/shape404"
        },
        {
          "$ref": "#/$defs/shape408"
        },
        {
          "$ref": "#/$defs/shape420"
        },
        {
          "$ref": "#/$defs/shape422"
        },
        {
          "$ref": "#/$defs/shape425"
        },
        {
          "$ref": "#/$defs/shape428"
        },
        {
          "$ref": "#/$defs/shape431"
        },
        {
          "$ref": "#/$defs/shape434"
        },
        {
          "$ref": "#/$defs/shape437"
        },
        {
          "$ref": "#/$defs/shape440"
        },
        {
          "$ref": "#/$defs/shape442"
        },
        {
          "$ref": "#/$defs/shape445"
        },
        {
          "$ref": "#/$defs/shape448"
        },
        {
          "$ref": "#/$defs/shape451"
        },
        {
          "$ref": "#/$defs/shape456"
        },
        {
          "$ref": "#/$defs/shape459"
        },
        {
          "$ref": "#/$defs/shape462"
        },
        {
          "$ref": "#/$defs/shape469"
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
          "$ref": "#/$defs/shape480"
        },
        {
          "$ref": "#/$defs/shape482"
        },
        {
          "$ref": "#/$defs/shape484"
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
          "$ref": "#/$defs/shape499"
        },
        {
          "$ref": "#/$defs/shape501"
        },
        {
          "$ref": "#/$defs/shape503"
        },
        {
          "$ref": "#/$defs/shape505"
        },
        {
          "$ref": "#/$defs/shape511"
        },
        {
          "$ref": "#/$defs/shape517"
        },
        {
          "$ref": "#/$defs/shape521"
        },
        {
          "$ref": "#/$defs/shape525"
        },
        {
          "$ref": "#/$defs/shape527"
        },
        {
          "$ref": "#/$defs/shape529"
        },
        {
          "$ref": "#/$defs/shape531"
        },
        {
          "$ref": "#/$defs/shape533"
        },
        {
          "$ref": "#/$defs/shape535"
        },
        {
          "$ref": "#/$defs/shape537"
        },
        {
          "$ref": "#/$defs/shape539"
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
          "$ref": "#/$defs/shape554"
        },
        {
          "$ref": "#/$defs/shape556"
        },
        {
          "$ref": "#/$defs/shape560"
        },
        {
          "$ref": "#/$defs/shape562"
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
          "$ref": "#/$defs/shape582"
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
          "$ref": "#/$defs/shape605"
        },
        {
          "$ref": "#/$defs/shape607"
        },
        {
          "$ref": "#/$defs/shape609"
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
          "$ref": "#/$defs/shape643"
        },
        {
          "$ref": "#/$defs/shape645"
        },
        {
          "$ref": "#/$defs/shape647"
        },
        {
          "$ref": "#/$defs/shape652"
        },
        {
          "$ref": "#/$defs/shape654"
        },
        {
          "$ref": "#/$defs/shape656"
        },
        {
          "$ref": "#/$defs/shape658"
        },
        {
          "$ref": "#/$defs/shape662"
        },
        {
          "$ref": "#/$defs/shape664"
        },
        {
          "$ref": "#/$defs/shape666"
        },
        {
          "$ref": "#/$defs/shape671"
        },
        {
          "$ref": "#/$defs/shape673"
        },
        {
          "$ref": "#/$defs/shape675"
        },
        {
          "$ref": "#/$defs/shape682"
        },
        {
          "$ref": "#/$defs/shape686"
        },
        {
          "$ref": "#/$defs/shape688"
        },
        {
          "$ref": "#/$defs/shape690"
        },
        {
          "$ref": "#/$defs/shape692"
        },
        {
          "$ref": "#/$defs/shape694"
        },
        {
          "$ref": "#/$defs/shape696"
        },
        {
          "$ref": "#/$defs/shape698"
        },
        {
          "$ref": "#/$defs/shape699"
        },
        {
          "$ref": "#/$defs/shape701"
        },
        {
          "$ref": "#/$defs/shape704"
        },
        {
          "$ref": "#/$defs/shape706"
        },
        {
          "$ref": "#/$defs/shape710"
        },
        {
          "$ref": "#/$defs/shape713"
        },
        {
          "$ref": "#/$defs/shape716"
        },
        {
          "$ref": "#/$defs/shape730"
        },
        {
          "$ref": "#/$defs/shape737"
        },
        {
          "$ref": "#/$defs/shape740"
        },
        {
          "$ref": "#/$defs/shape743"
        },
        {
          "$ref": "#/$defs/shape746"
        },
        {
          "$ref": "#/$defs/shape748"
        },
        {
          "$ref": "#/$defs/shape750"
        },
        {
          "$ref": "#/$defs/shape752"
        },
        {
          "$ref": "#/$defs/shape755"
        },
        {
          "$ref": "#/$defs/shape758"
        },
        {
          "$ref": "#/$defs/shape761"
        },
        {
          "$ref": "#/$defs/shape765"
        },
        {
          "$ref": "#/$defs/shape768"
        },
        {
          "$ref": "#/$defs/shape771"
        },
        {
          "$ref": "#/$defs/shape773"
        }
      ]
    }
  }
}
