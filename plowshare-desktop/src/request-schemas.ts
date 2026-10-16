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
      "const": "navigation.capabilities"
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
      "const": "documents.list"
    },
    "shape191": {
      "const": "outgoing"
    },
    "shape192": {
      "const": "backlinks"
    },
    "shape190": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape191"
        },
        {
          "$ref": "#/$defs/shape192"
        }
      ]
    },
    "shape194": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape196": {
      "const": "source"
    },
    "shape197": {
      "const": "report"
    },
    "shape195": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape196"
        },
        {
          "$ref": "#/$defs/shape197"
        }
      ]
    },
    "shape193": {
      "type": "object",
      "properties": {
        "author": {
          "$ref": "#/$defs/shape6"
        },
        "autoTag": {
          "$ref": "#/$defs/shape194"
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
          "$ref": "#/$defs/shape195"
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
          "$ref": "#/$defs/shape194"
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
    "shape189": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape190"
        },
        "filter": {
          "$ref": "#/$defs/shape193"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "relatedTo": {
          "$ref": "#/$defs/shape6"
        },
        "relation": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
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
    "shape199": {
      "const": "documents.facets"
    },
    "shape200": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape190"
        },
        "filter": {
          "$ref": "#/$defs/shape193"
        },
        "relatedTo": {
          "$ref": "#/$defs/shape6"
        },
        "relation": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape198": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape199"
        },
        "payload": {
          "$ref": "#/$defs/shape200"
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
    "shape202": {
      "const": "documents.get"
    },
    "shape204": {
      "type": "object",
      "properties": {
        "resource": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "resource"
      ],
      "additionalProperties": false
    },
    "shape203": {
      "type": "object",
      "properties": {
        "target": {
          "$ref": "#/$defs/shape204"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "target"
      ],
      "additionalProperties": false
    },
    "shape201": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape202"
        },
        "payload": {
          "$ref": "#/$defs/shape203"
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
    "shape206": {
      "const": "relationships.list"
    },
    "shape207": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape190"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "otherResource": {
          "$ref": "#/$defs/shape6"
        },
        "relation": {
          "$ref": "#/$defs/shape6"
        },
        "resource": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "direction",
        "resource"
      ],
      "additionalProperties": false
    },
    "shape205": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape206"
        },
        "payload": {
          "$ref": "#/$defs/shape207"
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
    "shape209": {
      "const": "relationships.create"
    },
    "shape210": {
      "type": "object",
      "properties": {
        "expectedSourceRevision": {
          "$ref": "#/$defs/shape6"
        },
        "relation": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "target": {
          "$ref": "#/$defs/shape204"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "expectedSourceRevision",
        "relation",
        "requestId",
        "source",
        "target"
      ],
      "additionalProperties": false
    },
    "shape208": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape209"
        },
        "payload": {
          "$ref": "#/$defs/shape210"
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
    "shape212": {
      "const": "relationships.remove"
    },
    "shape213": {
      "type": "object",
      "properties": {
        "expectedVersion": {
          "$ref": "#/$defs/shape28"
        },
        "relationship": {
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
        "expectedVersion",
        "relationship",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape211": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape212"
        },
        "payload": {
          "$ref": "#/$defs/shape213"
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
    "shape215": {
      "const": "policy.get"
    },
    "shape219": {
      "const": "platform"
    },
    "shape220": {
      "forbidden": true
    },
    "shape218": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape219"
        },
        "project": {
          "$ref": "#/$defs/shape220"
        },
        "resource": {
          "$ref": "#/$defs/shape220"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape221": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape91"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "resource": {
          "$ref": "#/$defs/shape220"
        }
      },
      "required": [
        "kind",
        "project"
      ],
      "additionalProperties": false
    },
    "shape223": {
      "const": "document"
    },
    "shape222": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape223"
        },
        "project": {
          "$ref": "#/$defs/shape220"
        },
        "resource": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "kind",
        "resource"
      ],
      "additionalProperties": false
    },
    "shape217": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape218"
        },
        {
          "$ref": "#/$defs/shape221"
        },
        {
          "$ref": "#/$defs/shape222"
        }
      ]
    },
    "shape216": {
      "type": "object",
      "properties": {
        "target": {
          "$ref": "#/$defs/shape217"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "target"
      ],
      "additionalProperties": false
    },
    "shape214": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape215"
        },
        "payload": {
          "$ref": "#/$defs/shape216"
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
    "shape225": {
      "const": "policy.set"
    },
    "shape228": {
      "type": "object",
      "properties": {
        "ownerOnly": {
          "$ref": "#/$defs/shape29"
        },
        "propagate": {
          "$ref": "#/$defs/shape29"
        },
        "referenceHidden": {
          "$ref": "#/$defs/shape29"
        }
      },
      "required": [
        "ownerOnly",
        "propagate",
        "referenceHidden"
      ],
      "additionalProperties": false
    },
    "shape227": {
      "type": "object",
      "properties": {
        "allowOverrides": {
          "$ref": "#/$defs/shape29"
        },
        "defaults": {
          "$ref": "#/$defs/shape228"
        },
        "mandatory": {
          "$ref": "#/$defs/shape228"
        },
        "referenceLabel": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "allowOverrides",
        "defaults",
        "mandatory",
        "referenceLabel"
      ],
      "additionalProperties": false
    },
    "shape226": {
      "type": "object",
      "properties": {
        "expectedVersion": {
          "$ref": "#/$defs/shape28"
        },
        "reason": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "settings": {
          "$ref": "#/$defs/shape227"
        },
        "target": {
          "$ref": "#/$defs/shape217"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "expectedVersion",
        "reason",
        "requestId",
        "settings",
        "target"
      ],
      "additionalProperties": false
    },
    "shape224": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape225"
        },
        "payload": {
          "$ref": "#/$defs/shape226"
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
    "shape230": {
      "const": "views.list"
    },
    "shape231": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape229": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape230"
        },
        "payload": {
          "$ref": "#/$defs/shape231"
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
    "shape233": {
      "const": "views.get"
    },
    "shape234": {
      "type": "object",
      "properties": {
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "id"
      ],
      "additionalProperties": false
    },
    "shape232": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape233"
        },
        "payload": {
          "$ref": "#/$defs/shape234"
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
    "shape236": {
      "const": "views.save"
    },
    "shape242": {
      "const": "scope"
    },
    "shape241": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape242"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
        }
      },
      "required": [
        "kind",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape243": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape223"
        },
        "target": {
          "$ref": "#/$defs/shape204"
        }
      },
      "required": [
        "kind",
        "target"
      ],
      "additionalProperties": false
    },
    "shape245": {
      "const": "traverse"
    },
    "shape244": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape190"
        },
        "kind": {
          "$ref": "#/$defs/shape245"
        },
        "relation": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "direction",
        "kind",
        "relation"
      ],
      "additionalProperties": false
    },
    "shape247": {
      "const": "filter"
    },
    "shape246": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape193"
        },
        "kind": {
          "$ref": "#/$defs/shape247"
        }
      },
      "required": [
        "filter",
        "kind"
      ],
      "additionalProperties": false
    },
    "shape240": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape241"
        },
        {
          "$ref": "#/$defs/shape243"
        },
        {
          "$ref": "#/$defs/shape244"
        },
        {
          "$ref": "#/$defs/shape246"
        }
      ]
    },
    "shape239": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape240"
      }
    },
    "shape238": {
      "type": "object",
      "properties": {
        "steps": {
          "$ref": "#/$defs/shape239"
        }
      },
      "required": [
        "steps"
      ],
      "additionalProperties": false
    },
    "shape237": {
      "type": "object",
      "properties": {
        "expectedVersion": {
          "$ref": "#/$defs/shape28"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "label": {
          "$ref": "#/$defs/shape6"
        },
        "path": {
          "$ref": "#/$defs/shape238"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "expectedVersion",
        "id",
        "label",
        "path",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape235": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape236"
        },
        "payload": {
          "$ref": "#/$defs/shape237"
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
      "const": "views.delete"
    },
    "shape250": {
      "type": "object",
      "properties": {
        "expectedVersion": {
          "$ref": "#/$defs/shape28"
        },
        "id": {
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
        "expectedVersion",
        "id",
        "requestId"
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
      "const": "interchange.capabilities"
    },
    "shape253": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
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
      "const": "interchange.export"
    },
    "shape256": {
      "type": "object",
      "properties": {
        "revisions": {
          "$ref": "#/$defs/shape194"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "revisions"
      ],
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
    "shape258": {
      "const": "interchange.readable"
    },
    "shape259": {
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
        "revision"
      ],
      "additionalProperties": false
    },
    "shape257": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape258"
        },
        "payload": {
          "$ref": "#/$defs/shape259"
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
    "shape261": {
      "const": "interchange.import"
    },
    "shape272": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape271"
      }
    },
    "shape271": {
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
          "$ref": "#/$defs/shape270"
        },
        {
          "$ref": "#/$defs/shape272"
        }
      ]
    },
    "shape270": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape271"
      }
    },
    "shape273": {
      "const": "plowshare-document-metadata/1"
    },
    "shape275": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape274"
      }
    },
    "shape276": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape30"
        },
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape274"
        }
      ]
    },
    "shape277": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape274"
      }
    },
    "shape278": {
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
    "shape279": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape278"
      }
    },
    "shape281": {
      "const": "string"
    },
    "shape282": {
      "const": "number"
    },
    "shape283": {
      "const": "boolean"
    },
    "shape284": {
      "const": "object"
    },
    "shape285": {
      "const": "array"
    },
    "shape286": {
      "const": "integer"
    },
    "shape287": {
      "const": "null"
    },
    "shape289": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape285"
        },
        {
          "$ref": "#/$defs/shape286"
        },
        {
          "$ref": "#/$defs/shape287"
        }
      ]
    },
    "shape288": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape289"
      }
    },
    "shape280": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape285"
        },
        {
          "$ref": "#/$defs/shape286"
        },
        {
          "$ref": "#/$defs/shape287"
        },
        {
          "$ref": "#/$defs/shape288"
        }
      ]
    },
    "shape274": {
      "type": "object",
      "properties": {
        "$anchor": {
          "$ref": "#/$defs/shape6"
        },
        "$defs": {
          "$ref": "#/$defs/shape275"
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
          "$ref": "#/$defs/shape276"
        },
        "allOf": {
          "$ref": "#/$defs/shape277"
        },
        "anyOf": {
          "$ref": "#/$defs/shape277"
        },
        "const": {
          "$ref": "#/$defs/shape278"
        },
        "contains": {
          "$ref": "#/$defs/shape274"
        },
        "default": {
          "$ref": "#/$defs/shape278"
        },
        "deprecated": {
          "$ref": "#/$defs/shape29"
        },
        "description": {
          "$ref": "#/$defs/shape6"
        },
        "else": {
          "$ref": "#/$defs/shape274"
        },
        "enum": {
          "$ref": "#/$defs/shape279"
        },
        "examples": {
          "$ref": "#/$defs/shape279"
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
          "$ref": "#/$defs/shape274"
        },
        "items": {
          "$ref": "#/$defs/shape274"
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
          "$ref": "#/$defs/shape274"
        },
        "oneOf": {
          "$ref": "#/$defs/shape277"
        },
        "pattern": {
          "$ref": "#/$defs/shape6"
        },
        "patternProperties": {
          "$ref": "#/$defs/shape275"
        },
        "prefixItems": {
          "$ref": "#/$defs/shape277"
        },
        "properties": {
          "$ref": "#/$defs/shape275"
        },
        "readOnly": {
          "$ref": "#/$defs/shape29"
        },
        "required": {
          "$ref": "#/$defs/shape194"
        },
        "then": {
          "$ref": "#/$defs/shape274"
        },
        "title": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape280"
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
    "shape269": {
      "type": "object",
      "properties": {
        "definitionHash": {
          "$ref": "#/$defs/shape6"
        },
        "description": {
          "$ref": "#/$defs/shape6"
        },
        "label": {
          "$ref": "#/$defs/shape6"
        },
        "ordinal": {
          "$ref": "#/$defs/shape28"
        },
        "presentation": {
          "$ref": "#/$defs/shape270"
        },
        "profile": {
          "$ref": "#/$defs/shape273"
        },
        "schema": {
          "$ref": "#/$defs/shape274"
        },
        "slug": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape6"
        },
        "version": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "definitionHash",
        "description",
        "label",
        "ordinal",
        "presentation",
        "profile",
        "schema",
        "slug",
        "type",
        "version"
      ],
      "additionalProperties": false
    },
    "shape268": {
      "type": "object",
      "properties": {
        "contract": {
          "$ref": "#/$defs/shape269"
        },
        "metadata": {
          "$ref": "#/$defs/shape270"
        },
        "metadataHash": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "contract",
        "metadata",
        "metadataHash"
      ],
      "additionalProperties": false
    },
    "shape292": {
      "type": "object",
      "properties": {
        "end": {
          "$ref": "#/$defs/shape28"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "locator": {
          "$ref": "#/$defs/shape6"
        },
        "quote": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "start": {
          "$ref": "#/$defs/shape28"
        }
      },
      "required": [
        "end",
        "id",
        "quote",
        "revision",
        "start"
      ],
      "additionalProperties": false
    },
    "shape291": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape292"
      }
    },
    "shape293": {
      "type": "object",
      "properties": {
        "bundle": {
          "$ref": "#/$defs/shape6"
        },
        "manifestHash": {
          "$ref": "#/$defs/shape6"
        },
        "owner": {
          "$ref": "#/$defs/shape6"
        },
        "resource": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "sourceHash": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "bundle",
        "manifestHash",
        "owner",
        "resource",
        "revision",
        "sourceHash"
      ],
      "additionalProperties": false
    },
    "shape290": {
      "type": "object",
      "properties": {
        "createdAt": {
          "$ref": "#/$defs/shape6"
        },
        "dependencies": {
          "$ref": "#/$defs/shape194"
        },
        "evidence": {
          "$ref": "#/$defs/shape291"
        },
        "importedFrom": {
          "$ref": "#/$defs/shape293"
        },
        "kind": {
          "$ref": "#/$defs/shape195"
        },
        "owner": {
          "$ref": "#/$defs/shape6"
        },
        "producer": {
          "$ref": "#/$defs/shape6"
        },
        "producerDefinitionHash": {
          "$ref": "#/$defs/shape6"
        },
        "sourceHash": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "createdAt",
        "dependencies",
        "evidence",
        "kind",
        "owner",
        "sourceHash"
      ],
      "additionalProperties": false
    },
    "shape267": {
      "type": "object",
      "properties": {
        "contentHash": {
          "$ref": "#/$defs/shape6"
        },
        "contentType": {
          "$ref": "#/$defs/shape268"
        },
        "mediaType": {
          "$ref": "#/$defs/shape6"
        },
        "ordinal": {
          "$ref": "#/$defs/shape28"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "provenance": {
          "$ref": "#/$defs/shape290"
        },
        "resource": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "sourceName": {
          "$ref": "#/$defs/shape6"
        },
        "tags": {
          "$ref": "#/$defs/shape194"
        },
        "title": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "contentHash",
        "mediaType",
        "ordinal",
        "path",
        "provenance",
        "resource",
        "revision",
        "sourceName",
        "tags",
        "title"
      ],
      "additionalProperties": false
    },
    "shape266": {
      "type": "object",
      "properties": {
        "bytesBase64": {
          "$ref": "#/$defs/shape6"
        },
        "descriptor": {
          "$ref": "#/$defs/shape267"
        }
      },
      "required": [
        "bytesBase64",
        "descriptor"
      ],
      "additionalProperties": false
    },
    "shape265": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape266"
      }
    },
    "shape294": {
      "const": "plowshare-information-bundle/1"
    },
    "shape295": {
      "const": "included-endpoints-only"
    },
    "shape297": {
      "type": "object",
      "properties": {
        "pinnedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "relation": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "target": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "relation",
        "source",
        "target"
      ],
      "additionalProperties": false
    },
    "shape296": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape297"
      }
    },
    "shape264": {
      "type": "object",
      "properties": {
        "documents": {
          "$ref": "#/$defs/shape265"
        },
        "format": {
          "$ref": "#/$defs/shape294"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "relationshipCoverage": {
          "$ref": "#/$defs/shape295"
        },
        "relationships": {
          "$ref": "#/$defs/shape296"
        }
      },
      "required": [
        "documents",
        "format",
        "id",
        "relationshipCoverage",
        "relationships"
      ],
      "additionalProperties": false
    },
    "shape301": {
      "const": "create"
    },
    "shape300": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape220"
        },
        "mode": {
          "$ref": "#/$defs/shape301"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "resource": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "mode",
        "name",
        "resource"
      ],
      "additionalProperties": false
    },
    "shape303": {
      "const": "revise"
    },
    "shape302": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "mode": {
          "$ref": "#/$defs/shape303"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "resource": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "expectedRevision",
        "mode",
        "name",
        "resource"
      ],
      "additionalProperties": false
    },
    "shape299": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape300"
        },
        {
          "$ref": "#/$defs/shape302"
        }
      ]
    },
    "shape298": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape299"
      }
    },
    "shape305": {
      "type": "object",
      "properties": {
        "foreignRevision": {
          "$ref": "#/$defs/shape6"
        },
        "localRevision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "foreignRevision",
        "localRevision"
      ],
      "additionalProperties": false
    },
    "shape304": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape305"
      }
    },
    "shape307": {
      "type": "object",
      "properties": {
        "foreignVersion": {
          "$ref": "#/$defs/shape6"
        },
        "localVersion": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "foreignVersion",
        "localVersion"
      ],
      "additionalProperties": false
    },
    "shape306": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape307"
      }
    },
    "shape263": {
      "type": "object",
      "properties": {
        "bundle": {
          "$ref": "#/$defs/shape264"
        },
        "destinations": {
          "$ref": "#/$defs/shape298"
        },
        "restoreRelationships": {
          "$ref": "#/$defs/shape29"
        },
        "reuse": {
          "$ref": "#/$defs/shape304"
        },
        "types": {
          "$ref": "#/$defs/shape306"
        }
      },
      "required": [
        "bundle",
        "destinations",
        "restoreRelationships",
        "reuse",
        "types"
      ],
      "additionalProperties": false
    },
    "shape262": {
      "type": "object",
      "properties": {
        "plan": {
          "$ref": "#/$defs/shape263"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "plan",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape260": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape261"
        },
        "payload": {
          "$ref": "#/$defs/shape262"
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
    "shape309": {
      "const": "interchange.origin"
    },
    "shape310": {
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
        "revision"
      ],
      "additionalProperties": false
    },
    "shape308": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape309"
        },
        "payload": {
          "$ref": "#/$defs/shape310"
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
    "shape312": {
      "const": "types.capabilities"
    },
    "shape313": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape311": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape312"
        },
        "payload": {
          "$ref": "#/$defs/shape313"
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
    "shape315": {
      "const": "types.list"
    },
    "shape316": {
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
    "shape314": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape315"
        },
        "payload": {
          "$ref": "#/$defs/shape316"
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
    "shape318": {
      "const": "types.get"
    },
    "shape319": {
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
    "shape317": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape318"
        },
        "payload": {
          "$ref": "#/$defs/shape319"
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
    "shape321": {
      "const": "types.validate"
    },
    "shape322": {
      "type": "object",
      "properties": {
        "attachedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "metadata": {
          "$ref": "#/$defs/shape270"
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
    "shape320": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape321"
        },
        "payload": {
          "$ref": "#/$defs/shape322"
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
    "shape324": {
      "const": "types.version.create"
    },
    "shape325": {
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
          "$ref": "#/$defs/shape270"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "schema": {
          "$ref": "#/$defs/shape274"
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
    "shape323": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape324"
        },
        "payload": {
          "$ref": "#/$defs/shape325"
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
    "shape327": {
      "const": "types.activate"
    },
    "shape328": {
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
    "shape326": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape327"
        },
        "payload": {
          "$ref": "#/$defs/shape328"
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
    "shape330": {
      "const": "types.deprecate"
    },
    "shape331": {
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
    "shape329": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape330"
        },
        "payload": {
          "$ref": "#/$defs/shape331"
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
    "shape333": {
      "const": "content.get"
    },
    "shape334": {
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
    "shape332": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape333"
        },
        "payload": {
          "$ref": "#/$defs/shape334"
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
    "shape336": {
      "const": "content.revise"
    },
    "shape340": {
      "const": "set"
    },
    "shape339": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape270"
        },
        "mode": {
          "$ref": "#/$defs/shape340"
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
    "shape342": {
      "const": "inherit"
    },
    "shape341": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape270"
        },
        "mode": {
          "$ref": "#/$defs/shape342"
        },
        "version": {
          "$ref": "#/$defs/shape220"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape344": {
      "const": "clear"
    },
    "shape343": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape220"
        },
        "mode": {
          "$ref": "#/$defs/shape344"
        },
        "version": {
          "$ref": "#/$defs/shape220"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape338": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape339"
        },
        {
          "$ref": "#/$defs/shape341"
        },
        {
          "$ref": "#/$defs/shape343"
        }
      ]
    },
    "shape337": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape338"
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
    "shape335": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape336"
        },
        "payload": {
          "$ref": "#/$defs/shape337"
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
      "const": "upload"
    },
    "shape347": {
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
          "$ref": "#/$defs/shape338"
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
      "const": "acquire"
    },
    "shape350": {
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
          "$ref": "#/$defs/shape338"
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
      "const": "refresh"
    },
    "shape353": {
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
          "$ref": "#/$defs/shape338"
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
          "$ref": "#/$defs/shape338"
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
    "shape354": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape303"
        },
        "payload": {
          "$ref": "#/$defs/shape355"
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
    "shape357": {
      "const": "replace"
    },
    "shape358": {
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
          "$ref": "#/$defs/shape338"
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
    "shape356": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape357"
        },
        "payload": {
          "$ref": "#/$defs/shape358"
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
    "shape360": {
      "const": "list"
    },
    "shape361": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape193"
        },
        "kind": {
          "$ref": "#/$defs/shape195"
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
    "shape359": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape360"
        },
        "payload": {
          "$ref": "#/$defs/shape361"
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
    "shape363": {
      "const": "facets"
    },
    "shape364": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape193"
        },
        "kind": {
          "$ref": "#/$defs/shape195"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape362": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape363"
        },
        "payload": {
          "$ref": "#/$defs/shape364"
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
    "shape366": {
      "const": "tags"
    },
    "shape367": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "tags": {
          "$ref": "#/$defs/shape194"
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
    "shape365": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape366"
        },
        "payload": {
          "$ref": "#/$defs/shape367"
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
    "shape369": {
      "const": "tags.groups"
    },
    "shape372": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape194"
      }
    },
    "shape371": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape372"
        }
      ]
    },
    "shape370": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape371"
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
    "shape368": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape369"
        },
        "payload": {
          "$ref": "#/$defs/shape370"
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
    "shape374": {
      "const": "inventory"
    },
    "shape375": {
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
    "shape373": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape374"
        },
        "payload": {
          "$ref": "#/$defs/shape375"
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
    "shape377": {
      "const": "acquisitions"
    },
    "shape378": {
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
    "shape376": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape377"
        },
        "payload": {
          "$ref": "#/$defs/shape378"
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
    "shape380": {
      "const": "status"
    },
    "shape381": {
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
    "shape379": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape380"
        },
        "payload": {
          "$ref": "#/$defs/shape381"
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
    "shape383": {
      "const": "await"
    },
    "shape387": {
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
    "shape388": {
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
    "shape386": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape387"
        },
        {
          "$ref": "#/$defs/shape388"
        }
      ]
    },
    "shape385": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape386"
      }
    },
    "shape384": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape385"
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
    "shape382": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape383"
        },
        "payload": {
          "$ref": "#/$defs/shape384"
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
    "shape390": {
      "const": "read"
    },
    "shape391": {
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
    "shape389": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape390"
        },
        "payload": {
          "$ref": "#/$defs/shape391"
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
    "shape393": {
      "const": "outline"
    },
    "shape394": {
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
    "shape392": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape393"
        },
        "payload": {
          "$ref": "#/$defs/shape394"
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
    "shape396": {
      "const": "symbols"
    },
    "shape397": {
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
    "shape395": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape396"
        },
        "payload": {
          "$ref": "#/$defs/shape397"
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
    "shape399": {
      "const": "search"
    },
    "shape400": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape193"
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
    "shape398": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape399"
        },
        "payload": {
          "$ref": "#/$defs/shape400"
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
    "shape402": {
      "const": "rank"
    },
    "shape403": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape193"
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
    "shape401": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape402"
        },
        "payload": {
          "$ref": "#/$defs/shape403"
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
    "shape405": {
      "const": "ask"
    },
    "shape406": {
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
    "shape404": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape405"
        },
        "payload": {
          "$ref": "#/$defs/shape406"
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
    "shape408": {
      "const": "evidence.record"
    },
    "shape409": {
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
    "shape407": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape408"
        },
        "payload": {
          "$ref": "#/$defs/shape409"
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
    "shape411": {
      "const": "evidence.read"
    },
    "shape412": {
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
    "shape410": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape411"
        },
        "payload": {
          "$ref": "#/$defs/shape412"
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
    "shape414": {
      "const": "record.report"
    },
    "shape419": {
      "const": "holds"
    },
    "shape420": {
      "const": "weakened"
    },
    "shape421": {
      "const": "refuted"
    },
    "shape422": {
      "const": "not_checked"
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
        },
        {
          "$ref": "#/$defs/shape422"
        }
      ]
    },
    "shape417": {
      "type": "object",
      "properties": {
        "claim": {
          "$ref": "#/$defs/shape6"
        },
        "counterEvidence": {
          "$ref": "#/$defs/shape194"
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
          "$ref": "#/$defs/shape194"
        },
        "verdict": {
          "$ref": "#/$defs/shape418"
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
    "shape416": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape417"
      }
    },
    "shape424": {
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
    "shape423": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape424"
      }
    },
    "shape415": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape194"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "feedback": {
          "$ref": "#/$defs/shape6"
        },
        "findings": {
          "$ref": "#/$defs/shape416"
        },
        "inputs": {
          "$ref": "#/$defs/shape194"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "objectives": {
          "$ref": "#/$defs/shape194"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "reviews": {
          "$ref": "#/$defs/shape423"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape194"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape338"
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
    "shape413": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape414"
        },
        "payload": {
          "$ref": "#/$defs/shape415"
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
    "shape426": {
      "const": "finalise"
    },
    "shape427": {
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
    "shape425": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape426"
        },
        "payload": {
          "$ref": "#/$defs/shape427"
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
    "shape429": {
      "const": "link"
    },
    "shape430": {
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
    "shape428": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape429"
        },
        "payload": {
          "$ref": "#/$defs/shape430"
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
    "shape432": {
      "const": "unlink"
    },
    "shape433": {
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
    "shape431": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape432"
        },
        "payload": {
          "$ref": "#/$defs/shape433"
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
    "shape435": {
      "const": "share"
    },
    "shape436": {
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
    "shape434": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape435"
        },
        "payload": {
          "$ref": "#/$defs/shape436"
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
    "shape438": {
      "const": "unshare"
    },
    "shape439": {
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
    "shape437": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape438"
        },
        "payload": {
          "$ref": "#/$defs/shape439"
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
    "shape441": {
      "const": "withdraw"
    },
    "shape442": {
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
    "shape440": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape441"
        },
        "payload": {
          "$ref": "#/$defs/shape442"
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
    "shape444": {
      "const": "exclude"
    },
    "shape445": {
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
    "shape443": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape444"
        },
        "payload": {
          "$ref": "#/$defs/shape445"
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
    "shape447": {
      "const": "unexclude"
    },
    "shape448": {
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
    "shape446": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape447"
        },
        "payload": {
          "$ref": "#/$defs/shape448"
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
    "shape450": {
      "const": "restore"
    },
    "shape451": {
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
    "shape449": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape450"
        },
        "payload": {
          "$ref": "#/$defs/shape451"
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
    "shape453": {
      "const": "delete"
    },
    "shape454": {
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
    "shape452": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape453"
        },
        "payload": {
          "$ref": "#/$defs/shape454"
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
    "shape456": {
      "const": "retry"
    },
    "shape460": {
      "const": "extract"
    },
    "shape461": {
      "const": "derive"
    },
    "shape462": {
      "const": "embed"
    },
    "shape463": {
      "const": "summarise"
    },
    "shape464": {
      "const": "summary_embed"
    },
    "shape465": {
      "const": "autoTag"
    },
    "shape466": {
      "const": "tagGroups"
    },
    "shape459": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape460"
        },
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
        },
        {
          "$ref": "#/$defs/shape465"
        },
        {
          "$ref": "#/$defs/shape466"
        }
      ]
    },
    "shape458": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape459"
      }
    },
    "shape457": {
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
          "$ref": "#/$defs/shape458"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape455": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape456"
        },
        "payload": {
          "$ref": "#/$defs/shape457"
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
    "shape468": {
      "const": "rebuild"
    },
    "shape469": {
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
          "$ref": "#/$defs/shape458"
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
    "shape467": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape468"
        },
        "payload": {
          "$ref": "#/$defs/shape469"
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
    "shape471": {
      "const": "allowance"
    },
    "shape472": {
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
    "shape470": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape471"
        },
        "payload": {
          "$ref": "#/$defs/shape472"
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
    "shape474": {
      "const": "events"
    },
    "shape475": {
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
    "shape473": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape474"
        },
        "payload": {
          "$ref": "#/$defs/shape475"
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
    "shape477": {
      "const": "migration.list"
    },
    "shape478": {
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
    "shape476": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape477"
        },
        "payload": {
          "$ref": "#/$defs/shape478"
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
    "shape480": {
      "const": "migration.adopt"
    },
    "shape481": {
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
    "shape479": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape480"
        },
        "payload": {
          "$ref": "#/$defs/shape481"
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
    "shape483": {
      "const": "migration.inspect"
    },
    "shape484": {
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
    "shape482": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape483"
        },
        "payload": {
          "$ref": "#/$defs/shape484"
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
    "shape486": {
      "const": "migration.release"
    },
    "shape487": {
      "type": "object",
      "properties": {
        "inputs": {
          "$ref": "#/$defs/shape194"
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
    "shape485": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape486"
        },
        "payload": {
          "$ref": "#/$defs/shape487"
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
    "shape490": {
      "const": "bootstrap"
    },
    "shape491": {
      "const": "demo"
    },
    "shape492": {
      "const": "disconnect"
    },
    "shape493": {
      "const": "approvals-refresh"
    },
    "shape489": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape352"
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
    "shape488": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape489"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape495": {
      "const": "project-access"
    },
    "shape497": {
      "const": "project.access"
    },
    "shape498": {
      "const": "project.member.add"
    },
    "shape499": {
      "const": "project.member.remove"
    },
    "shape500": {
      "const": "project.member.role"
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
        }
      ]
    },
    "shape502": {
      "const": "VIEWER"
    },
    "shape503": {
      "const": "CONTRIBUTOR"
    },
    "shape504": {
      "const": "MANAGER"
    },
    "shape501": {
      "anyOf": [
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
    "shape494": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape495"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape496"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape501"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape506": {
      "const": "server-admin"
    },
    "shape507": {
      "const": "admin.pricing.list"
    },
    "shape508": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape220"
      }
    },
    "shape505": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape507"
        },
        "payload": {
          "$ref": "#/$defs/shape508"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape510": {
      "const": "admin.pricing.set"
    },
    "shape513": {
      "const": "TOKEN"
    },
    "shape514": {
      "const": "INCLUDED"
    },
    "shape515": {
      "const": "ZERO_RATE"
    },
    "shape516": {
      "const": "UNPRICED"
    },
    "shape512": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape513"
        },
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
    "shape517": {
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
    "shape520": {
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
    "shape519": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape28"
        },
        "rates": {
          "$ref": "#/$defs/shape520"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape518": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape519"
      }
    },
    "shape511": {
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
          "$ref": "#/$defs/shape512"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "rates": {
          "$ref": "#/$defs/shape517"
        },
        "requestFee": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "tiers": {
          "$ref": "#/$defs/shape518"
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
    "shape509": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape510"
        },
        "payload": {
          "$ref": "#/$defs/shape511"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape522": {
      "const": "admin.accounts"
    },
    "shape521": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape522"
        },
        "payload": {
          "$ref": "#/$defs/shape508"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape524": {
      "const": "admin.account.create"
    },
    "shape525": {
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
    "shape523": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape524"
        },
        "payload": {
          "$ref": "#/$defs/shape525"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape527": {
      "const": "admin.account.update"
    },
    "shape528": {
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
    "shape526": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape527"
        },
        "payload": {
          "$ref": "#/$defs/shape528"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape530": {
      "const": "admin.account.reset"
    },
    "shape531": {
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
    "shape529": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape530"
        },
        "payload": {
          "$ref": "#/$defs/shape531"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape533": {
      "const": "admin.sessions"
    },
    "shape534": {
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
    "shape532": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape533"
        },
        "payload": {
          "$ref": "#/$defs/shape534"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape536": {
      "const": "admin.session.revoke"
    },
    "shape537": {
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
    "shape535": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape536"
        },
        "payload": {
          "$ref": "#/$defs/shape537"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape539": {
      "const": "admin.audit"
    },
    "shape540": {
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
    "shape538": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape539"
        },
        "payload": {
          "$ref": "#/$defs/shape540"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape542": {
      "const": "admin.service.accounts"
    },
    "shape541": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape542"
        },
        "payload": {
          "$ref": "#/$defs/shape508"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape544": {
      "const": "admin.service.account.create"
    },
    "shape545": {
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
    "shape543": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape544"
        },
        "payload": {
          "$ref": "#/$defs/shape545"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape547": {
      "const": "admin.service.account.update"
    },
    "shape548": {
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
    "shape546": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape547"
        },
        "payload": {
          "$ref": "#/$defs/shape548"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape550": {
      "const": "admin.service.tokens"
    },
    "shape551": {
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
    "shape549": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape550"
        },
        "payload": {
          "$ref": "#/$defs/shape551"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape553": {
      "const": "admin.service.token.create"
    },
    "shape556": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape501"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape555": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape556"
      }
    },
    "shape554": {
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
          "$ref": "#/$defs/shape555"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape552": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape553"
        },
        "payload": {
          "$ref": "#/$defs/shape554"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape558": {
      "const": "admin.service.token.rotate"
    },
    "shape559": {
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
    "shape557": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape558"
        },
        "payload": {
          "$ref": "#/$defs/shape559"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape561": {
      "const": "admin.service.token.revoke"
    },
    "shape562": {
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
    "shape560": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
        },
        "operation": {
          "$ref": "#/$defs/shape561"
        },
        "payload": {
          "$ref": "#/$defs/shape562"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape564": {
      "const": "server-project-create"
    },
    "shape566": {
      "const": "MANAGED"
    },
    "shape567": {
      "const": "DISJOINT"
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
    "shape568": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape569": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape563": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape564"
        },
        "applicationRoot": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape565"
        },
        "workspace": {
          "$ref": "#/$defs/shape6"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape568"
        },
        "writePaths": {
          "$ref": "#/$defs/shape569"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape571": {
      "const": "server-setup"
    },
    "shape570": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape571"
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
    "shape573": {
      "const": "connect"
    },
    "shape572": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape573"
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
    "shape575": {
      "const": "connection-select"
    },
    "shape574": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape575"
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
    "shape577": {
      "const": "connection-preferences"
    },
    "shape579": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape580": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape29"
      }
    },
    "shape578": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape579"
        },
        "drafts": {
          "$ref": "#/$defs/shape579"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape580"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape580"
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
    "shape576": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape6"
        },
        "action": {
          "$ref": "#/$defs/shape577"
        },
        "preference": {
          "$ref": "#/$defs/shape578"
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
    "shape582": {
      "const": "connection-rename"
    },
    "shape581": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape582"
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
    "shape584": {
      "const": "connection-remove"
    },
    "shape583": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape584"
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
    "shape586": {
      "const": "personal-section"
    },
    "shape588": {
      "const": "In"
    },
    "shape589": {
      "const": "Out"
    },
    "shape590": {
      "const": "Resources"
    },
    "shape591": {
      "const": "Archive"
    },
    "shape592": {
      "const": "Planning"
    },
    "shape593": {
      "const": "Bots"
    },
    "shape587": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape588"
        },
        {
          "$ref": "#/$defs/shape589"
        },
        {
          "$ref": "#/$defs/shape590"
        },
        {
          "$ref": "#/$defs/shape591"
        },
        {
          "$ref": "#/$defs/shape592"
        },
        {
          "$ref": "#/$defs/shape593"
        }
      ]
    },
    "shape585": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape586"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "section": {
          "$ref": "#/$defs/shape587"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape595": {
      "const": "personal-bots"
    },
    "shape594": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape595"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape597": {
      "const": "personal-recreate"
    },
    "shape596": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape597"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape599": {
      "const": "files-choose"
    },
    "shape598": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape599"
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
    "shape601": {
      "const": "files-withdraw"
    },
    "shape600": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape601"
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
    "shape603": {
      "const": "sync-refresh"
    },
    "shape602": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape603"
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
    "shape605": {
      "const": "sync-inspect"
    },
    "shape604": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape605"
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
    "shape607": {
      "const": "sync-change"
    },
    "shape609": {
      "const": "on"
    },
    "shape610": {
      "const": "off"
    },
    "shape611": {
      "const": "now"
    },
    "shape608": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape609"
        },
        {
          "$ref": "#/$defs/shape610"
        },
        {
          "$ref": "#/$defs/shape611"
        }
      ]
    },
    "shape606": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape607"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape608"
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
    "shape613": {
      "const": "sync-resolve"
    },
    "shape615": {
      "const": "mine"
    },
    "shape616": {
      "const": "theirs"
    },
    "shape617": {
      "const": "done"
    },
    "shape614": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape615"
        },
        {
          "$ref": "#/$defs/shape616"
        },
        {
          "$ref": "#/$defs/shape617"
        }
      ]
    },
    "shape612": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape613"
        },
        "how": {
          "$ref": "#/$defs/shape614"
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
    "shape620": {
      "const": "project-open"
    },
    "shape621": {
      "const": "project-remove"
    },
    "shape619": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape620"
        },
        {
          "$ref": "#/$defs/shape621"
        }
      ]
    },
    "shape618": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape619"
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
    "shape623": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape242"
        },
        {
          "$ref": "#/$defs/shape301"
        }
      ]
    },
    "shape622": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape623"
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
    "shape625": {
      "const": "history"
    },
    "shape624": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape625"
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
    "shape627": {
      "const": "select"
    },
    "shape626": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape627"
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
    "shape629": {
      "const": "trajectory"
    },
    "shape628": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape629"
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
    "shape631": {
      "const": "board-swarm-types"
    },
    "shape630": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape631"
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
    "shape633": {
      "const": "board-post-topics"
    },
    "shape632": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape633"
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
    "shape635": {
      "const": "board-create"
    },
    "shape634": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape635"
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
    "shape637": {
      "const": "board-retry"
    },
    "shape636": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape637"
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
    "shape639": {
      "const": "board-post"
    },
    "shape638": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape639"
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
    "shape641": {
      "const": "workspace-chat"
    },
    "shape640": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape641"
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
    "shape643": {
      "const": "workspace-layout"
    },
    "shape642": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape643"
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
    "shape645": {
      "const": "workspace-refresh"
    },
    "shape644": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape645"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape647": {
      "const": "library"
    },
    "shape649": {
      "const": "sources"
    },
    "shape650": {
      "const": "documents"
    },
    "shape651": {
      "const": "memories"
    },
    "shape652": {
      "const": "manual"
    },
    "shape648": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape399"
        },
        {
          "$ref": "#/$defs/shape649"
        },
        {
          "$ref": "#/$defs/shape650"
        },
        {
          "$ref": "#/$defs/shape651"
        },
        {
          "$ref": "#/$defs/shape652"
        }
      ]
    },
    "shape646": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape647"
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
          "$ref": "#/$defs/shape648"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape654": {
      "const": "library-view"
    },
    "shape653": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape654"
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
          "$ref": "#/$defs/shape648"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape657": {
      "const": "library-refresh"
    },
    "shape658": {
      "const": "library-citations"
    },
    "shape656": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape657"
        },
        {
          "$ref": "#/$defs/shape658"
        }
      ]
    },
    "shape655": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape656"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape660": {
      "const": "library-documents"
    },
    "shape659": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape660"
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
    "shape663": {
      "const": "library-document"
    },
    "shape664": {
      "const": "library-memory"
    },
    "shape665": {
      "const": "library-chunk"
    },
    "shape666": {
      "const": "library-conversation"
    },
    "shape662": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape663"
        },
        {
          "$ref": "#/$defs/shape664"
        },
        {
          "$ref": "#/$defs/shape665"
        },
        {
          "$ref": "#/$defs/shape666"
        }
      ]
    },
    "shape661": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape662"
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
    "shape668": {
      "const": "library-source-text"
    },
    "shape667": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape668"
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
    "shape670": {
      "const": "library-stance"
    },
    "shape669": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape670"
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
    "shape672": {
      "const": "library-search"
    },
    "shape674": {
      "const": "retrieve"
    },
    "shape675": {
      "const": "recall"
    },
    "shape676": {
      "const": "navigate"
    },
    "shape673": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape402"
        },
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape650"
        },
        {
          "$ref": "#/$defs/shape674"
        },
        {
          "$ref": "#/$defs/shape675"
        },
        {
          "$ref": "#/$defs/shape676"
        }
      ]
    },
    "shape678": {
      "const": "lexical"
    },
    "shape679": {
      "const": "semantic"
    },
    "shape680": {
      "const": "hybrid"
    },
    "shape677": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape678"
        },
        {
          "$ref": "#/$defs/shape679"
        },
        {
          "$ref": "#/$defs/shape680"
        }
      ]
    },
    "shape671": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape672"
        },
        "kind": {
          "$ref": "#/$defs/shape673"
        },
        "mode": {
          "$ref": "#/$defs/shape677"
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
    "shape682": {
      "const": "library-maintain"
    },
    "shape684": {
      "const": "invalidate"
    },
    "shape685": {
      "const": "resolve"
    },
    "shape686": {
      "const": "reembed"
    },
    "shape687": {
      "const": "reconsider"
    },
    "shape683": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape684"
        },
        {
          "$ref": "#/$defs/shape685"
        },
        {
          "$ref": "#/$defs/shape686"
        },
        {
          "$ref": "#/$defs/shape687"
        }
      ]
    },
    "shape681": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape29"
        },
        "action": {
          "$ref": "#/$defs/shape682"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape683"
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
    "shape689": {
      "const": "builder-outputs"
    },
    "shape688": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape689"
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
    "shape691": {
      "const": "builder-trajectory"
    },
    "shape690": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape691"
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
    "shape693": {
      "const": "builder-prepare"
    },
    "shape692": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape693"
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
    "shape695": {
      "const": "builder-start"
    },
    "shape694": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape695"
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
    "shape697": {
      "const": "activity"
    },
    "shape699": {
      "const": "inbox"
    },
    "shape700": {
      "const": "runs"
    },
    "shape701": {
      "const": "definitions"
    },
    "shape702": {
      "const": "schedules"
    },
    "shape703": {
      "const": "builder"
    },
    "shape698": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape699"
        },
        {
          "$ref": "#/$defs/shape700"
        },
        {
          "$ref": "#/$defs/shape701"
        },
        {
          "$ref": "#/$defs/shape702"
        },
        {
          "$ref": "#/$defs/shape703"
        }
      ]
    },
    "shape696": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape697"
        },
        "view": {
          "$ref": "#/$defs/shape698"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape705": {
      "const": "activity-view"
    },
    "shape704": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape705"
        },
        "view": {
          "$ref": "#/$defs/shape698"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape707": {
      "const": "activity-refresh"
    },
    "shape706": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape709": {
      "const": "question-refresh"
    },
    "shape708": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape709"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape711": {
      "const": "inbox-read"
    },
    "shape710": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape711"
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
    "shape713": {
      "const": "inbox-more"
    },
    "shape712": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape713"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape715": {
      "const": "run-detail"
    },
    "shape714": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape715"
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
    "shape717": {
      "const": "schedule-refresh"
    },
    "shape716": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape717"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape719": {
      "const": "schedule-preview"
    },
    "shape718": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape719"
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
    "shape721": {
      "const": "schedule-save"
    },
    "shape725": {
      "const": "skill"
    },
    "shape726": {
      "const": "orchestration"
    },
    "shape724": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape89"
        },
        {
          "$ref": "#/$defs/shape725"
        },
        {
          "$ref": "#/$defs/shape726"
        }
      ]
    },
    "shape728": {
      "const": "INHERITED"
    },
    "shape729": {
      "const": "SUMMARISED"
    },
    "shape730": {
      "const": "NEW"
    },
    "shape731": {
      "const": "DIRECT"
    },
    "shape727": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape728"
        },
        {
          "$ref": "#/$defs/shape729"
        },
        {
          "$ref": "#/$defs/shape730"
        },
        {
          "$ref": "#/$defs/shape731"
        }
      ]
    },
    "shape723": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape724"
        },
        "mode": {
          "$ref": "#/$defs/shape727"
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
    "shape733": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape28"
        }
      ]
    },
    "shape732": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape733"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape733"
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
    "shape736": {
      "const": "mailbox"
    },
    "shape737": {
      "const": "message"
    },
    "shape735": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape736"
        },
        {
          "$ref": "#/$defs/shape737"
        }
      ]
    },
    "shape734": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape735"
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
    "shape738": {
      "const": 1
    },
    "shape722": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape723"
        },
        "cron": {
          "$ref": "#/$defs/shape6"
        },
        "limits": {
          "$ref": "#/$defs/shape732"
        },
        "paused": {
          "$ref": "#/$defs/shape29"
        },
        "target": {
          "$ref": "#/$defs/shape734"
        },
        "version": {
          "$ref": "#/$defs/shape738"
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
    "shape740": {
      "const": "server"
    },
    "shape741": {
      "const": "workspace"
    },
    "shape739": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape740"
        },
        {
          "$ref": "#/$defs/shape741"
        }
      ]
    },
    "shape720": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape721"
        },
        "definition": {
          "$ref": "#/$defs/shape722"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape739"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape743": {
      "const": "schedule-file-save"
    },
    "shape742": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape743"
        },
        "definition": {
          "$ref": "#/$defs/shape722"
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
          "$ref": "#/$defs/shape739"
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
    "shape745": {
      "const": "schedule-sync"
    },
    "shape744": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape745"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape739"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape747": {
      "const": "schedule-change"
    },
    "shape749": {
      "const": "schedule"
    },
    "shape750": {
      "const": "trigger"
    },
    "shape748": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape749"
        },
        {
          "$ref": "#/$defs/shape750"
        }
      ]
    },
    "shape746": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape747"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape748"
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
    "shape752": {
      "const": "schedule-fire"
    },
    "shape751": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape752"
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
    "shape754": {
      "const": "run-definitions"
    },
    "shape753": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape754"
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
    "shape756": {
      "const": "run-record"
    },
    "shape755": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape756"
        },
        "before": {
          "$ref": "#/$defs/shape28"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "kinds": {
          "$ref": "#/$defs/shape569"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape758": {
      "const": "run-answer"
    },
    "shape760": {
      "type": "object",
      "properties": {
        "chosen": {
          "$ref": "#/$defs/shape194"
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
    "shape759": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape760"
      }
    },
    "shape757": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape758"
        },
        "answer": {
          "$ref": "#/$defs/shape6"
        },
        "choices": {
          "$ref": "#/$defs/shape759"
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
    "shape762": {
      "const": "run-cancel"
    },
    "shape761": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape762"
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
    "shape764": {
      "const": "run-resume"
    },
    "shape763": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape764"
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
    "shape766": {
      "const": "run-trajectory"
    },
    "shape768": {
      "const": "conductor"
    },
    "shape769": {
      "const": "caller"
    },
    "shape767": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape768"
        },
        {
          "$ref": "#/$defs/shape769"
        }
      ]
    },
    "shape765": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape766"
        },
        "actor": {
          "$ref": "#/$defs/shape767"
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
    "shape771": {
      "const": "run-stage-trajectory"
    },
    "shape770": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape771"
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
    "shape773": {
      "const": "delegate-trajectory"
    },
    "shape772": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape773"
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
    "shape776": {
      "const": "board-inspection"
    },
    "shape777": {
      "const": "board-view"
    },
    "shape775": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape776"
        },
        {
          "$ref": "#/$defs/shape777"
        }
      ]
    },
    "shape779": {
      "const": "board"
    },
    "shape780": {
      "const": "swarm"
    },
    "shape778": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape779"
        },
        {
          "$ref": "#/$defs/shape780"
        }
      ]
    },
    "shape774": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape775"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape778"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape783": {
      "const": "board-refresh"
    },
    "shape784": {
      "const": "board-more"
    },
    "shape782": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape783"
        },
        {
          "$ref": "#/$defs/shape784"
        }
      ]
    },
    "shape781": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape782"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape786": {
      "const": "board-topic"
    },
    "shape785": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape786"
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
    "shape788": {
      "const": "board-trajectory"
    },
    "shape787": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape788"
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
    "shape790": {
      "const": "context"
    },
    "shape789": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape790"
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
    "shape792": {
      "const": "open-link"
    },
    "shape791": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape792"
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
    "shape794": {
      "const": "copy-text"
    },
    "shape793": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape794"
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
    "shape796": {
      "const": "workflow-start"
    },
    "shape795": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape796"
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
    "shape797": {
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
    "shape799": {
      "const": "cancel"
    },
    "shape798": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape799"
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
    "shape801": {
      "const": "answer"
    },
    "shape802": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape158"
        },
        {
          "$ref": "#/$defs/shape160"
        }
      ]
    },
    "shape800": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape801"
        },
        "decision": {
          "$ref": "#/$defs/shape802"
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
    "shape804": {
      "const": "native-mcp"
    },
    "shape803": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape804"
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
    "shape806": {
      "const": "provider-management"
    },
    "shape807": {
      "const": "provider.connection.list"
    },
    "shape808": {
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
    "shape805": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape807"
        },
        "payload": {
          "$ref": "#/$defs/shape808"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape810": {
      "const": "provider.connection.status"
    },
    "shape811": {
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
    "shape809": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape810"
        },
        "payload": {
          "$ref": "#/$defs/shape811"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape813": {
      "const": "provider.connection.receipt"
    },
    "shape814": {
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
    "shape812": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape813"
        },
        "payload": {
          "$ref": "#/$defs/shape814"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape816": {
      "const": "provider.connection.save"
    },
    "shape820": {
      "const": "Authorization"
    },
    "shape821": {
      "const": "X-API-Key"
    },
    "shape822": {
      "const": "Api-Key"
    },
    "shape819": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape820"
        },
        {
          "$ref": "#/$defs/shape821"
        },
        {
          "$ref": "#/$defs/shape822"
        }
      ]
    },
    "shape824": {
      "const": "API_KEY"
    },
    "shape825": {
      "const": "OAUTH_PKCE"
    },
    "shape826": {
      "const": "CLIENT_CREDENTIALS"
    },
    "shape823": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape824"
        },
        {
          "$ref": "#/$defs/shape825"
        },
        {
          "$ref": "#/$defs/shape826"
        }
      ]
    },
    "shape828": {
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
          "$ref": "#/$defs/shape194"
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
    "shape827": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape828"
        }
      ]
    },
    "shape818": {
      "type": "object",
      "properties": {
        "apiKeyHeader": {
          "$ref": "#/$defs/shape819"
        },
        "authentication": {
          "$ref": "#/$defs/shape823"
        },
        "endpoint": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "oauth": {
          "$ref": "#/$defs/shape827"
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
    "shape817": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape818"
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
    "shape815": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape816"
        },
        "payload": {
          "$ref": "#/$defs/shape817"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape830": {
      "const": "provider.connection.change"
    },
    "shape833": {
      "const": "ENABLE"
    },
    "shape834": {
      "const": "DISABLE"
    },
    "shape835": {
      "const": "DELETE"
    },
    "shape832": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape833"
        },
        {
          "$ref": "#/$defs/shape834"
        },
        {
          "$ref": "#/$defs/shape835"
        }
      ]
    },
    "shape831": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape832"
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
    "shape829": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape830"
        },
        "payload": {
          "$ref": "#/$defs/shape831"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape837": {
      "const": "provider.credential.status"
    },
    "shape838": {
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
    "shape836": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape837"
        },
        "payload": {
          "$ref": "#/$defs/shape838"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape840": {
      "const": "provider.credential.put"
    },
    "shape841": {
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
    "shape839": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape840"
        },
        "payload": {
          "$ref": "#/$defs/shape841"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape843": {
      "const": "provider.credential.revoke"
    },
    "shape844": {
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
    "shape842": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape843"
        },
        "payload": {
          "$ref": "#/$defs/shape844"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape846": {
      "const": "provider.credential.rekey"
    },
    "shape845": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape846"
        },
        "payload": {
          "$ref": "#/$defs/shape844"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape848": {
      "const": "provider.credential.authorize"
    },
    "shape847": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape848"
        },
        "payload": {
          "$ref": "#/$defs/shape844"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape850": {
      "const": "provider.credential.refresh"
    },
    "shape849": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape850"
        },
        "payload": {
          "$ref": "#/$defs/shape844"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape852": {
      "const": "tool.scope.host.list"
    },
    "shape853": {
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
    "shape851": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape852"
        },
        "payload": {
          "$ref": "#/$defs/shape853"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape855": {
      "const": "tool.scope.host.status"
    },
    "shape856": {
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
    "shape854": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape855"
        },
        "payload": {
          "$ref": "#/$defs/shape856"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape858": {
      "const": "tool.scope.host.receipt"
    },
    "shape859": {
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
    "shape857": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape858"
        },
        "payload": {
          "$ref": "#/$defs/shape859"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape861": {
      "const": "tool.scope.host.save"
    },
    "shape863": {
      "type": "object",
      "properties": {
        "agents": {
          "$ref": "#/$defs/shape194"
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
          "$ref": "#/$defs/shape194"
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
    "shape862": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape863"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape733"
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
    "shape860": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape861"
        },
        "payload": {
          "$ref": "#/$defs/shape862"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape865": {
      "const": "tool.scope.host.change"
    },
    "shape866": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape832"
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
    "shape864": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape865"
        },
        "payload": {
          "$ref": "#/$defs/shape866"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape868": {
      "const": "tool.scope.list"
    },
    "shape869": {
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
    "shape867": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "operation": {
          "$ref": "#/$defs/shape868"
        },
        "payload": {
          "$ref": "#/$defs/shape869"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape871": {
      "const": "provider-management-pending"
    },
    "shape870": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape871"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape873": {
      "const": "provider-management-recover"
    },
    "shape876": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape816"
        },
        {
          "$ref": "#/$defs/shape830"
        }
      ]
    },
    "shape875": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape876"
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
    "shape878": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape840"
        },
        {
          "$ref": "#/$defs/shape843"
        },
        {
          "$ref": "#/$defs/shape846"
        },
        {
          "$ref": "#/$defs/shape848"
        },
        {
          "$ref": "#/$defs/shape850"
        }
      ]
    },
    "shape877": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape878"
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
    "shape880": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape861"
        },
        {
          "$ref": "#/$defs/shape865"
        }
      ]
    },
    "shape879": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape733"
        },
        "operation": {
          "$ref": "#/$defs/shape880"
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
    "shape874": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape875"
        },
        {
          "$ref": "#/$defs/shape877"
        },
        {
          "$ref": "#/$defs/shape879"
        }
      ]
    },
    "shape872": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape873"
        },
        "intent": {
          "$ref": "#/$defs/shape874"
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
          "$ref": "#/$defs/shape198"
        },
        {
          "$ref": "#/$defs/shape201"
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
          "$ref": "#/$defs/shape224"
        },
        {
          "$ref": "#/$defs/shape229"
        },
        {
          "$ref": "#/$defs/shape232"
        },
        {
          "$ref": "#/$defs/shape235"
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
          "$ref": "#/$defs/shape308"
        },
        {
          "$ref": "#/$defs/shape311"
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
          "$ref": "#/$defs/shape335"
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
          "$ref": "#/$defs/shape368"
        },
        {
          "$ref": "#/$defs/shape373"
        },
        {
          "$ref": "#/$defs/shape376"
        },
        {
          "$ref": "#/$defs/shape379"
        },
        {
          "$ref": "#/$defs/shape382"
        },
        {
          "$ref": "#/$defs/shape389"
        },
        {
          "$ref": "#/$defs/shape392"
        },
        {
          "$ref": "#/$defs/shape395"
        },
        {
          "$ref": "#/$defs/shape398"
        },
        {
          "$ref": "#/$defs/shape401"
        },
        {
          "$ref": "#/$defs/shape404"
        },
        {
          "$ref": "#/$defs/shape407"
        },
        {
          "$ref": "#/$defs/shape410"
        },
        {
          "$ref": "#/$defs/shape413"
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
          "$ref": "#/$defs/shape443"
        },
        {
          "$ref": "#/$defs/shape446"
        },
        {
          "$ref": "#/$defs/shape449"
        },
        {
          "$ref": "#/$defs/shape452"
        },
        {
          "$ref": "#/$defs/shape455"
        },
        {
          "$ref": "#/$defs/shape467"
        },
        {
          "$ref": "#/$defs/shape470"
        },
        {
          "$ref": "#/$defs/shape473"
        },
        {
          "$ref": "#/$defs/shape476"
        },
        {
          "$ref": "#/$defs/shape479"
        },
        {
          "$ref": "#/$defs/shape482"
        },
        {
          "$ref": "#/$defs/shape485"
        },
        {
          "$ref": "#/$defs/shape488"
        },
        {
          "$ref": "#/$defs/shape494"
        },
        {
          "$ref": "#/$defs/shape505"
        },
        {
          "$ref": "#/$defs/shape509"
        },
        {
          "$ref": "#/$defs/shape521"
        },
        {
          "$ref": "#/$defs/shape523"
        },
        {
          "$ref": "#/$defs/shape526"
        },
        {
          "$ref": "#/$defs/shape529"
        },
        {
          "$ref": "#/$defs/shape532"
        },
        {
          "$ref": "#/$defs/shape535"
        },
        {
          "$ref": "#/$defs/shape538"
        },
        {
          "$ref": "#/$defs/shape541"
        },
        {
          "$ref": "#/$defs/shape543"
        },
        {
          "$ref": "#/$defs/shape546"
        },
        {
          "$ref": "#/$defs/shape549"
        },
        {
          "$ref": "#/$defs/shape552"
        },
        {
          "$ref": "#/$defs/shape557"
        },
        {
          "$ref": "#/$defs/shape560"
        },
        {
          "$ref": "#/$defs/shape563"
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
          "$ref": "#/$defs/shape576"
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
          "$ref": "#/$defs/shape594"
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
          "$ref": "#/$defs/shape602"
        },
        {
          "$ref": "#/$defs/shape604"
        },
        {
          "$ref": "#/$defs/shape606"
        },
        {
          "$ref": "#/$defs/shape612"
        },
        {
          "$ref": "#/$defs/shape618"
        },
        {
          "$ref": "#/$defs/shape622"
        },
        {
          "$ref": "#/$defs/shape624"
        },
        {
          "$ref": "#/$defs/shape626"
        },
        {
          "$ref": "#/$defs/shape628"
        },
        {
          "$ref": "#/$defs/shape630"
        },
        {
          "$ref": "#/$defs/shape632"
        },
        {
          "$ref": "#/$defs/shape634"
        },
        {
          "$ref": "#/$defs/shape636"
        },
        {
          "$ref": "#/$defs/shape638"
        },
        {
          "$ref": "#/$defs/shape640"
        },
        {
          "$ref": "#/$defs/shape642"
        },
        {
          "$ref": "#/$defs/shape644"
        },
        {
          "$ref": "#/$defs/shape646"
        },
        {
          "$ref": "#/$defs/shape653"
        },
        {
          "$ref": "#/$defs/shape655"
        },
        {
          "$ref": "#/$defs/shape659"
        },
        {
          "$ref": "#/$defs/shape661"
        },
        {
          "$ref": "#/$defs/shape667"
        },
        {
          "$ref": "#/$defs/shape669"
        },
        {
          "$ref": "#/$defs/shape671"
        },
        {
          "$ref": "#/$defs/shape681"
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
          "$ref": "#/$defs/shape704"
        },
        {
          "$ref": "#/$defs/shape706"
        },
        {
          "$ref": "#/$defs/shape708"
        },
        {
          "$ref": "#/$defs/shape710"
        },
        {
          "$ref": "#/$defs/shape712"
        },
        {
          "$ref": "#/$defs/shape714"
        },
        {
          "$ref": "#/$defs/shape716"
        },
        {
          "$ref": "#/$defs/shape718"
        },
        {
          "$ref": "#/$defs/shape720"
        },
        {
          "$ref": "#/$defs/shape742"
        },
        {
          "$ref": "#/$defs/shape744"
        },
        {
          "$ref": "#/$defs/shape746"
        },
        {
          "$ref": "#/$defs/shape751"
        },
        {
          "$ref": "#/$defs/shape753"
        },
        {
          "$ref": "#/$defs/shape755"
        },
        {
          "$ref": "#/$defs/shape757"
        },
        {
          "$ref": "#/$defs/shape761"
        },
        {
          "$ref": "#/$defs/shape763"
        },
        {
          "$ref": "#/$defs/shape765"
        },
        {
          "$ref": "#/$defs/shape770"
        },
        {
          "$ref": "#/$defs/shape772"
        },
        {
          "$ref": "#/$defs/shape774"
        },
        {
          "$ref": "#/$defs/shape781"
        },
        {
          "$ref": "#/$defs/shape785"
        },
        {
          "$ref": "#/$defs/shape787"
        },
        {
          "$ref": "#/$defs/shape789"
        },
        {
          "$ref": "#/$defs/shape791"
        },
        {
          "$ref": "#/$defs/shape793"
        },
        {
          "$ref": "#/$defs/shape795"
        },
        {
          "$ref": "#/$defs/shape797"
        },
        {
          "$ref": "#/$defs/shape798"
        },
        {
          "$ref": "#/$defs/shape800"
        },
        {
          "$ref": "#/$defs/shape803"
        },
        {
          "$ref": "#/$defs/shape805"
        },
        {
          "$ref": "#/$defs/shape809"
        },
        {
          "$ref": "#/$defs/shape812"
        },
        {
          "$ref": "#/$defs/shape815"
        },
        {
          "$ref": "#/$defs/shape829"
        },
        {
          "$ref": "#/$defs/shape836"
        },
        {
          "$ref": "#/$defs/shape839"
        },
        {
          "$ref": "#/$defs/shape842"
        },
        {
          "$ref": "#/$defs/shape845"
        },
        {
          "$ref": "#/$defs/shape847"
        },
        {
          "$ref": "#/$defs/shape849"
        },
        {
          "$ref": "#/$defs/shape851"
        },
        {
          "$ref": "#/$defs/shape854"
        },
        {
          "$ref": "#/$defs/shape857"
        },
        {
          "$ref": "#/$defs/shape860"
        },
        {
          "$ref": "#/$defs/shape864"
        },
        {
          "$ref": "#/$defs/shape867"
        },
        {
          "$ref": "#/$defs/shape870"
        },
        {
          "$ref": "#/$defs/shape872"
        }
      ]
    }
  }
}
