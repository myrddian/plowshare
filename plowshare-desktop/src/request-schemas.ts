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
      "const": "upload"
    },
    "shape179": {
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
    "shape183": {
      "const": "personal"
    },
    "shape184": {
      "const": "shared"
    },
    "shape182": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape183"
        },
        {
          "$ref": "#/$defs/shape184"
        }
      ]
    },
    "shape181": {
      "type": "object",
      "properties": {
        "includeShared": {
          "$ref": "#/$defs/shape29"
        },
        "kind": {
          "$ref": "#/$defs/shape182"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape185": {
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
    "shape180": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape181"
        },
        {
          "$ref": "#/$defs/shape185"
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
          "$ref": "#/$defs/shape180"
        }
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
      "const": "acquire"
    },
    "shape188": {
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
    "shape186": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape187"
        },
        "payload": {
          "$ref": "#/$defs/shape188"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "refresh"
    },
    "shape191": {
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
    "shape189": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape190"
        },
        "payload": {
          "$ref": "#/$defs/shape191"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "revise"
    },
    "shape194": {
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
    "shape192": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape193"
        },
        "payload": {
          "$ref": "#/$defs/shape194"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "replace"
    },
    "shape197": {
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
    "shape195": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape196"
        },
        "payload": {
          "$ref": "#/$defs/shape197"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "list"
    },
    "shape202": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape204": {
      "const": "source"
    },
    "shape205": {
      "const": "report"
    },
    "shape203": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape204"
        },
        {
          "$ref": "#/$defs/shape205"
        }
      ]
    },
    "shape201": {
      "type": "object",
      "properties": {
        "author": {
          "$ref": "#/$defs/shape6"
        },
        "autoTag": {
          "$ref": "#/$defs/shape202"
        },
        "documentAuthor": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape203"
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
          "$ref": "#/$defs/shape202"
        },
        "when": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape200": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape201"
        },
        "kind": {
          "$ref": "#/$defs/shape203"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
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
          "$ref": "#/$defs/shape180"
        }
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
      "const": "facets"
    },
    "shape208": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape201"
        },
        "kind": {
          "$ref": "#/$defs/shape203"
        }
      },
      "additionalProperties": false
    },
    "shape206": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape207"
        },
        "payload": {
          "$ref": "#/$defs/shape208"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "tags"
    },
    "shape211": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "tags": {
          "$ref": "#/$defs/shape202"
        }
      },
      "required": [
        "requestId",
        "revision",
        "tags"
      ],
      "additionalProperties": false
    },
    "shape209": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape210"
        },
        "payload": {
          "$ref": "#/$defs/shape211"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "tags.groups"
    },
    "shape216": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape202"
      }
    },
    "shape215": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape216"
        }
      ]
    },
    "shape214": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape215"
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
    "shape212": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape213"
        },
        "payload": {
          "$ref": "#/$defs/shape214"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape218": {
      "const": "inventory"
    },
    "shape219": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        }
      },
      "additionalProperties": false
    },
    "shape217": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape218"
        },
        "payload": {
          "$ref": "#/$defs/shape219"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape221": {
      "const": "acquisitions"
    },
    "shape222": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        }
      },
      "additionalProperties": false
    },
    "shape220": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape221"
        },
        "payload": {
          "$ref": "#/$defs/shape222"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape224": {
      "const": "status"
    },
    "shape225": {
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
    "shape223": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape224"
        },
        "payload": {
          "$ref": "#/$defs/shape225"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape227": {
      "const": "await"
    },
    "shape232": {
      "forbidden": true
    },
    "shape231": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape232"
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
    "shape233": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape232"
        }
      },
      "required": [
        "acquisition"
      ],
      "additionalProperties": false
    },
    "shape230": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape231"
        },
        {
          "$ref": "#/$defs/shape233"
        }
      ]
    },
    "shape229": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape230"
      }
    },
    "shape228": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape229"
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
    "shape226": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape227"
        },
        "payload": {
          "$ref": "#/$defs/shape228"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "read"
    },
    "shape236": {
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
    "shape234": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape235"
        },
        "payload": {
          "$ref": "#/$defs/shape236"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "outline"
    },
    "shape239": {
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
    "shape237": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape238"
        },
        "payload": {
          "$ref": "#/$defs/shape239"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "symbols"
    },
    "shape242": {
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
    "shape240": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape241"
        },
        "payload": {
          "$ref": "#/$defs/shape242"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "search"
    },
    "shape245": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape201"
        },
        "limit": {
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
    "shape243": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape244"
        },
        "payload": {
          "$ref": "#/$defs/shape245"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "rank"
    },
    "shape248": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape201"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
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
    "shape246": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape247"
        },
        "payload": {
          "$ref": "#/$defs/shape248"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "ask"
    },
    "shape251": {
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
        }
      },
      "required": [
        "question",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape249": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape250"
        },
        "payload": {
          "$ref": "#/$defs/shape251"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "evidence.record"
    },
    "shape254": {
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
    "shape252": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape253"
        },
        "payload": {
          "$ref": "#/$defs/shape254"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "evidence.read"
    },
    "shape257": {
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
    "shape255": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape256"
        },
        "payload": {
          "$ref": "#/$defs/shape257"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "record.report"
    },
    "shape264": {
      "const": "holds"
    },
    "shape265": {
      "const": "weakened"
    },
    "shape266": {
      "const": "refuted"
    },
    "shape267": {
      "const": "not_checked"
    },
    "shape263": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape264"
        },
        {
          "$ref": "#/$defs/shape265"
        },
        {
          "$ref": "#/$defs/shape266"
        },
        {
          "$ref": "#/$defs/shape267"
        }
      ]
    },
    "shape262": {
      "type": "object",
      "properties": {
        "claim": {
          "$ref": "#/$defs/shape6"
        },
        "counterEvidence": {
          "$ref": "#/$defs/shape202"
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
          "$ref": "#/$defs/shape202"
        },
        "verdict": {
          "$ref": "#/$defs/shape263"
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
    "shape261": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape262"
      }
    },
    "shape269": {
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
    "shape268": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape269"
      }
    },
    "shape260": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape202"
        },
        "feedback": {
          "$ref": "#/$defs/shape6"
        },
        "findings": {
          "$ref": "#/$defs/shape261"
        },
        "inputs": {
          "$ref": "#/$defs/shape202"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "objectives": {
          "$ref": "#/$defs/shape202"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "reviews": {
          "$ref": "#/$defs/shape268"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape202"
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
    "shape258": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape259"
        },
        "payload": {
          "$ref": "#/$defs/shape260"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "finalise"
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
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape271"
        },
        "payload": {
          "$ref": "#/$defs/shape272"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "link"
    },
    "shape275": {
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
    "shape273": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape274"
        },
        "payload": {
          "$ref": "#/$defs/shape275"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "unlink"
    },
    "shape278": {
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
    "shape276": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape277"
        },
        "payload": {
          "$ref": "#/$defs/shape278"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "share"
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
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape280"
        },
        "payload": {
          "$ref": "#/$defs/shape281"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "unshare"
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
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape283"
        },
        "payload": {
          "$ref": "#/$defs/shape284"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "withdraw"
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
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape286"
        },
        "payload": {
          "$ref": "#/$defs/shape287"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
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
      "const": "exclude"
    },
    "shape290": {
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
          "$ref": "#/$defs/shape180"
        }
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
      "const": "unexclude"
    },
    "shape293": {
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
          "$ref": "#/$defs/shape180"
        }
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
      "const": "restore"
    },
    "shape296": {
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
          "$ref": "#/$defs/shape180"
        }
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
      "const": "delete"
    },
    "shape299": {
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
          "$ref": "#/$defs/shape180"
        }
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
      "const": "retry"
    },
    "shape302": {
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
          "$ref": "#/$defs/shape180"
        }
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
      "const": "rebuild"
    },
    "shape305": {
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
          "$ref": "#/$defs/shape180"
        }
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
      "const": "allowance"
    },
    "shape308": {
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
        }
      },
      "required": [
        "maxModelCalls",
        "requestId",
        "revision"
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
          "$ref": "#/$defs/shape180"
        }
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
      "const": "events"
    },
    "shape311": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape28"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        }
      },
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
          "$ref": "#/$defs/shape180"
        }
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
      "const": "migration.list"
    },
    "shape314": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        }
      },
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
          "$ref": "#/$defs/shape180"
        }
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
      "const": "migration.adopt"
    },
    "shape317": {
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
    "shape315": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape316"
        },
        "payload": {
          "$ref": "#/$defs/shape317"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape319": {
      "const": "migration.inspect"
    },
    "shape320": {
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
        }
      },
      "required": [
        "payload",
        "reason"
      ],
      "additionalProperties": false
    },
    "shape318": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape319"
        },
        "payload": {
          "$ref": "#/$defs/shape320"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape322": {
      "const": "migration.release"
    },
    "shape323": {
      "type": "object",
      "properties": {
        "inputs": {
          "$ref": "#/$defs/shape202"
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
    "shape321": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape322"
        },
        "payload": {
          "$ref": "#/$defs/shape323"
        },
        "scope": {
          "$ref": "#/$defs/shape180"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape326": {
      "const": "bootstrap"
    },
    "shape327": {
      "const": "demo"
    },
    "shape328": {
      "const": "disconnect"
    },
    "shape329": {
      "const": "approvals-refresh"
    },
    "shape325": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape190"
        },
        {
          "$ref": "#/$defs/shape326"
        },
        {
          "$ref": "#/$defs/shape327"
        },
        {
          "$ref": "#/$defs/shape328"
        },
        {
          "$ref": "#/$defs/shape329"
        }
      ]
    },
    "shape324": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape325"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape331": {
      "const": "project-access"
    },
    "shape333": {
      "const": "project.access"
    },
    "shape334": {
      "const": "project.member.add"
    },
    "shape335": {
      "const": "project.member.remove"
    },
    "shape336": {
      "const": "project.member.role"
    },
    "shape332": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape333"
        },
        {
          "$ref": "#/$defs/shape334"
        },
        {
          "$ref": "#/$defs/shape335"
        },
        {
          "$ref": "#/$defs/shape336"
        }
      ]
    },
    "shape338": {
      "const": "VIEWER"
    },
    "shape339": {
      "const": "CONTRIBUTOR"
    },
    "shape340": {
      "const": "MANAGER"
    },
    "shape337": {
      "anyOf": [
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
    "shape330": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape331"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape332"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape337"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape342": {
      "const": "server-admin"
    },
    "shape343": {
      "const": "admin.pricing.list"
    },
    "shape344": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape232"
      }
    },
    "shape341": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
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
      "const": "admin.pricing.set"
    },
    "shape349": {
      "const": "TOKEN"
    },
    "shape350": {
      "const": "INCLUDED"
    },
    "shape351": {
      "const": "ZERO_RATE"
    },
    "shape352": {
      "const": "UNPRICED"
    },
    "shape348": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape349"
        },
        {
          "$ref": "#/$defs/shape350"
        },
        {
          "$ref": "#/$defs/shape351"
        },
        {
          "$ref": "#/$defs/shape352"
        }
      ]
    },
    "shape353": {
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
    "shape356": {
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
    "shape355": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape28"
        },
        "rates": {
          "$ref": "#/$defs/shape356"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape354": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape355"
      }
    },
    "shape347": {
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
          "$ref": "#/$defs/shape348"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "rates": {
          "$ref": "#/$defs/shape353"
        },
        "requestFee": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "tiers": {
          "$ref": "#/$defs/shape354"
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
    "shape345": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
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
    "shape358": {
      "const": "admin.accounts"
    },
    "shape357": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
        },
        "operation": {
          "$ref": "#/$defs/shape358"
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
    "shape360": {
      "const": "admin.account.create"
    },
    "shape361": {
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
    "shape359": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
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
      "const": "admin.account.update"
    },
    "shape364": {
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
    "shape362": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
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
      "const": "admin.account.reset"
    },
    "shape367": {
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
    "shape365": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
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
      "const": "admin.sessions"
    },
    "shape370": {
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
    "shape368": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
        },
        "operation": {
          "$ref": "#/$defs/shape369"
        },
        "payload": {
          "$ref": "#/$defs/shape370"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape372": {
      "const": "admin.session.revoke"
    },
    "shape373": {
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
    "shape371": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
        },
        "operation": {
          "$ref": "#/$defs/shape372"
        },
        "payload": {
          "$ref": "#/$defs/shape373"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape375": {
      "const": "admin.audit"
    },
    "shape376": {
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
    "shape374": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
        },
        "operation": {
          "$ref": "#/$defs/shape375"
        },
        "payload": {
          "$ref": "#/$defs/shape376"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape378": {
      "const": "admin.service.accounts"
    },
    "shape377": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
        },
        "operation": {
          "$ref": "#/$defs/shape378"
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
    "shape380": {
      "const": "admin.service.account.create"
    },
    "shape381": {
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
    "shape379": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
        },
        "operation": {
          "$ref": "#/$defs/shape380"
        },
        "payload": {
          "$ref": "#/$defs/shape381"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape383": {
      "const": "admin.service.account.update"
    },
    "shape384": {
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
    "shape382": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
        },
        "operation": {
          "$ref": "#/$defs/shape383"
        },
        "payload": {
          "$ref": "#/$defs/shape384"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape386": {
      "const": "admin.service.tokens"
    },
    "shape387": {
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
    "shape385": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
        },
        "operation": {
          "$ref": "#/$defs/shape386"
        },
        "payload": {
          "$ref": "#/$defs/shape387"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape389": {
      "const": "admin.service.token.create"
    },
    "shape392": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape337"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape391": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape392"
      }
    },
    "shape390": {
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
          "$ref": "#/$defs/shape391"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape388": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
        },
        "operation": {
          "$ref": "#/$defs/shape389"
        },
        "payload": {
          "$ref": "#/$defs/shape390"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape394": {
      "const": "admin.service.token.rotate"
    },
    "shape395": {
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
    "shape393": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
        },
        "operation": {
          "$ref": "#/$defs/shape394"
        },
        "payload": {
          "$ref": "#/$defs/shape395"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape397": {
      "const": "admin.service.token.revoke"
    },
    "shape398": {
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
    "shape396": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape342"
        },
        "operation": {
          "$ref": "#/$defs/shape397"
        },
        "payload": {
          "$ref": "#/$defs/shape398"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape400": {
      "const": "server-project-create"
    },
    "shape402": {
      "const": "MANAGED"
    },
    "shape403": {
      "const": "DISJOINT"
    },
    "shape401": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape402"
        },
        {
          "$ref": "#/$defs/shape403"
        }
      ]
    },
    "shape404": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape405": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape399": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape400"
        },
        "applicationRoot": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape401"
        },
        "workspace": {
          "$ref": "#/$defs/shape6"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape404"
        },
        "writePaths": {
          "$ref": "#/$defs/shape405"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape407": {
      "const": "server-setup"
    },
    "shape406": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape407"
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
    "shape409": {
      "const": "connect"
    },
    "shape408": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape409"
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
    "shape411": {
      "const": "connection-select"
    },
    "shape410": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape411"
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
    "shape413": {
      "const": "connection-preferences"
    },
    "shape415": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape416": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape29"
      }
    },
    "shape414": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape415"
        },
        "drafts": {
          "$ref": "#/$defs/shape415"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape416"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape416"
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
    "shape412": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape6"
        },
        "action": {
          "$ref": "#/$defs/shape413"
        },
        "preference": {
          "$ref": "#/$defs/shape414"
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
    "shape418": {
      "const": "connection-rename"
    },
    "shape417": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape418"
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
    "shape420": {
      "const": "connection-remove"
    },
    "shape419": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape420"
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
    "shape422": {
      "const": "personal-section"
    },
    "shape424": {
      "const": "In"
    },
    "shape425": {
      "const": "Out"
    },
    "shape426": {
      "const": "Resources"
    },
    "shape427": {
      "const": "Archive"
    },
    "shape428": {
      "const": "Planning"
    },
    "shape429": {
      "const": "Bots"
    },
    "shape423": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape424"
        },
        {
          "$ref": "#/$defs/shape425"
        },
        {
          "$ref": "#/$defs/shape426"
        },
        {
          "$ref": "#/$defs/shape427"
        },
        {
          "$ref": "#/$defs/shape428"
        },
        {
          "$ref": "#/$defs/shape429"
        }
      ]
    },
    "shape421": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape422"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "section": {
          "$ref": "#/$defs/shape423"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape431": {
      "const": "personal-bots"
    },
    "shape430": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape431"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape433": {
      "const": "personal-recreate"
    },
    "shape432": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape433"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape435": {
      "const": "files-choose"
    },
    "shape434": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape435"
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
    "shape437": {
      "const": "files-withdraw"
    },
    "shape436": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape437"
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
    "shape439": {
      "const": "sync-refresh"
    },
    "shape438": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape439"
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
    "shape441": {
      "const": "sync-inspect"
    },
    "shape440": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape441"
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
    "shape443": {
      "const": "sync-change"
    },
    "shape445": {
      "const": "on"
    },
    "shape446": {
      "const": "off"
    },
    "shape447": {
      "const": "now"
    },
    "shape444": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape445"
        },
        {
          "$ref": "#/$defs/shape446"
        },
        {
          "$ref": "#/$defs/shape447"
        }
      ]
    },
    "shape442": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape443"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape444"
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
    "shape449": {
      "const": "sync-resolve"
    },
    "shape451": {
      "const": "mine"
    },
    "shape452": {
      "const": "theirs"
    },
    "shape453": {
      "const": "done"
    },
    "shape450": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape451"
        },
        {
          "$ref": "#/$defs/shape452"
        },
        {
          "$ref": "#/$defs/shape453"
        }
      ]
    },
    "shape448": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape449"
        },
        "how": {
          "$ref": "#/$defs/shape450"
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
    "shape456": {
      "const": "project-open"
    },
    "shape457": {
      "const": "project-remove"
    },
    "shape455": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape456"
        },
        {
          "$ref": "#/$defs/shape457"
        }
      ]
    },
    "shape454": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape455"
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
    "shape460": {
      "const": "scope"
    },
    "shape461": {
      "const": "create"
    },
    "shape459": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape460"
        },
        {
          "$ref": "#/$defs/shape461"
        }
      ]
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
        "action"
      ],
      "additionalProperties": false
    },
    "shape463": {
      "const": "history"
    },
    "shape462": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape463"
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
    "shape465": {
      "const": "select"
    },
    "shape464": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape465"
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
    "shape467": {
      "const": "trajectory"
    },
    "shape466": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape467"
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
    "shape469": {
      "const": "board-swarm-types"
    },
    "shape468": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape469"
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
    "shape471": {
      "const": "board-post-topics"
    },
    "shape470": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape471"
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
    "shape473": {
      "const": "board-create"
    },
    "shape472": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape473"
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
    "shape475": {
      "const": "board-retry"
    },
    "shape474": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape475"
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
    "shape477": {
      "const": "board-post"
    },
    "shape476": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape477"
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
    "shape479": {
      "const": "workspace-chat"
    },
    "shape478": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape479"
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
    "shape481": {
      "const": "workspace-layout"
    },
    "shape480": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape481"
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
    "shape483": {
      "const": "workspace-refresh"
    },
    "shape482": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape483"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape485": {
      "const": "library"
    },
    "shape487": {
      "const": "sources"
    },
    "shape488": {
      "const": "documents"
    },
    "shape489": {
      "const": "memories"
    },
    "shape490": {
      "const": "manual"
    },
    "shape486": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape244"
        },
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
        }
      ]
    },
    "shape484": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape485"
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
          "$ref": "#/$defs/shape486"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape492": {
      "const": "library-view"
    },
    "shape491": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape492"
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
          "$ref": "#/$defs/shape486"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape495": {
      "const": "library-refresh"
    },
    "shape496": {
      "const": "library-citations"
    },
    "shape494": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape495"
        },
        {
          "$ref": "#/$defs/shape496"
        }
      ]
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
    "shape498": {
      "const": "library-documents"
    },
    "shape497": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape498"
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
    "shape501": {
      "const": "library-document"
    },
    "shape502": {
      "const": "library-memory"
    },
    "shape503": {
      "const": "library-chunk"
    },
    "shape504": {
      "const": "library-conversation"
    },
    "shape500": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape501"
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
    "shape499": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape500"
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
    "shape506": {
      "const": "library-source-text"
    },
    "shape505": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape506"
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
    "shape508": {
      "const": "library-stance"
    },
    "shape507": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape508"
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
    "shape510": {
      "const": "library-search"
    },
    "shape512": {
      "const": "retrieve"
    },
    "shape513": {
      "const": "recall"
    },
    "shape514": {
      "const": "navigate"
    },
    "shape511": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape247"
        },
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape488"
        },
        {
          "$ref": "#/$defs/shape512"
        },
        {
          "$ref": "#/$defs/shape513"
        },
        {
          "$ref": "#/$defs/shape514"
        }
      ]
    },
    "shape516": {
      "const": "lexical"
    },
    "shape517": {
      "const": "semantic"
    },
    "shape518": {
      "const": "hybrid"
    },
    "shape515": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape516"
        },
        {
          "$ref": "#/$defs/shape517"
        },
        {
          "$ref": "#/$defs/shape518"
        }
      ]
    },
    "shape509": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape510"
        },
        "kind": {
          "$ref": "#/$defs/shape511"
        },
        "mode": {
          "$ref": "#/$defs/shape515"
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
    "shape520": {
      "const": "library-maintain"
    },
    "shape522": {
      "const": "invalidate"
    },
    "shape523": {
      "const": "resolve"
    },
    "shape524": {
      "const": "reembed"
    },
    "shape525": {
      "const": "reconsider"
    },
    "shape521": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape522"
        },
        {
          "$ref": "#/$defs/shape523"
        },
        {
          "$ref": "#/$defs/shape524"
        },
        {
          "$ref": "#/$defs/shape525"
        }
      ]
    },
    "shape519": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape29"
        },
        "action": {
          "$ref": "#/$defs/shape520"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape521"
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
    "shape527": {
      "const": "builder-outputs"
    },
    "shape526": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape527"
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
    "shape529": {
      "const": "builder-trajectory"
    },
    "shape528": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
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
    "shape531": {
      "const": "builder-prepare"
    },
    "shape530": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape531"
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
    "shape533": {
      "const": "builder-start"
    },
    "shape532": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape533"
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
    "shape535": {
      "const": "activity"
    },
    "shape537": {
      "const": "inbox"
    },
    "shape538": {
      "const": "runs"
    },
    "shape539": {
      "const": "definitions"
    },
    "shape540": {
      "const": "schedules"
    },
    "shape541": {
      "const": "builder"
    },
    "shape536": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape537"
        },
        {
          "$ref": "#/$defs/shape538"
        },
        {
          "$ref": "#/$defs/shape539"
        },
        {
          "$ref": "#/$defs/shape540"
        },
        {
          "$ref": "#/$defs/shape541"
        }
      ]
    },
    "shape534": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape535"
        },
        "view": {
          "$ref": "#/$defs/shape536"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape543": {
      "const": "activity-view"
    },
    "shape542": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
        },
        "view": {
          "$ref": "#/$defs/shape536"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape545": {
      "const": "activity-refresh"
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
      "const": "question-refresh"
    },
    "shape546": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape547"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape549": {
      "const": "inbox-read"
    },
    "shape548": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape549"
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
    "shape551": {
      "const": "inbox-more"
    },
    "shape550": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape551"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape553": {
      "const": "run-detail"
    },
    "shape552": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape553"
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
    "shape555": {
      "const": "schedule-refresh"
    },
    "shape554": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape555"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape557": {
      "const": "schedule-preview"
    },
    "shape556": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape557"
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
    "shape559": {
      "const": "schedule-save"
    },
    "shape563": {
      "const": "skill"
    },
    "shape564": {
      "const": "orchestration"
    },
    "shape562": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape89"
        },
        {
          "$ref": "#/$defs/shape563"
        },
        {
          "$ref": "#/$defs/shape564"
        }
      ]
    },
    "shape566": {
      "const": "INHERITED"
    },
    "shape567": {
      "const": "SUMMARISED"
    },
    "shape568": {
      "const": "NEW"
    },
    "shape569": {
      "const": "DIRECT"
    },
    "shape565": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape566"
        },
        {
          "$ref": "#/$defs/shape567"
        },
        {
          "$ref": "#/$defs/shape568"
        },
        {
          "$ref": "#/$defs/shape569"
        }
      ]
    },
    "shape561": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape562"
        },
        "mode": {
          "$ref": "#/$defs/shape565"
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
    "shape571": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape28"
        }
      ]
    },
    "shape570": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape571"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape571"
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
    "shape574": {
      "const": "mailbox"
    },
    "shape575": {
      "const": "message"
    },
    "shape573": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape574"
        },
        {
          "$ref": "#/$defs/shape575"
        }
      ]
    },
    "shape572": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape573"
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
    "shape576": {
      "const": 1
    },
    "shape560": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape561"
        },
        "cron": {
          "$ref": "#/$defs/shape6"
        },
        "limits": {
          "$ref": "#/$defs/shape570"
        },
        "paused": {
          "$ref": "#/$defs/shape29"
        },
        "target": {
          "$ref": "#/$defs/shape572"
        },
        "version": {
          "$ref": "#/$defs/shape576"
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
    "shape578": {
      "const": "server"
    },
    "shape579": {
      "const": "workspace"
    },
    "shape577": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape578"
        },
        {
          "$ref": "#/$defs/shape579"
        }
      ]
    },
    "shape558": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape559"
        },
        "definition": {
          "$ref": "#/$defs/shape560"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape577"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape581": {
      "const": "schedule-file-save"
    },
    "shape580": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape581"
        },
        "definition": {
          "$ref": "#/$defs/shape560"
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
          "$ref": "#/$defs/shape577"
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
    "shape583": {
      "const": "schedule-sync"
    },
    "shape582": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape583"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape577"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape585": {
      "const": "schedule-change"
    },
    "shape587": {
      "const": "schedule"
    },
    "shape588": {
      "const": "trigger"
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
    "shape584": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape585"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape586"
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
    "shape590": {
      "const": "schedule-fire"
    },
    "shape589": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape590"
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
    "shape592": {
      "const": "run-definitions"
    },
    "shape591": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape592"
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
    "shape594": {
      "const": "run-record"
    },
    "shape593": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape594"
        },
        "before": {
          "$ref": "#/$defs/shape28"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "kinds": {
          "$ref": "#/$defs/shape405"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape596": {
      "const": "run-answer"
    },
    "shape598": {
      "type": "object",
      "properties": {
        "chosen": {
          "$ref": "#/$defs/shape202"
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
    "shape597": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape598"
      }
    },
    "shape595": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape596"
        },
        "answer": {
          "$ref": "#/$defs/shape6"
        },
        "choices": {
          "$ref": "#/$defs/shape597"
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
    "shape600": {
      "const": "run-cancel"
    },
    "shape599": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape600"
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
    "shape602": {
      "const": "run-resume"
    },
    "shape601": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape602"
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
    "shape604": {
      "const": "run-trajectory"
    },
    "shape606": {
      "const": "conductor"
    },
    "shape607": {
      "const": "caller"
    },
    "shape605": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape606"
        },
        {
          "$ref": "#/$defs/shape607"
        }
      ]
    },
    "shape603": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape604"
        },
        "actor": {
          "$ref": "#/$defs/shape605"
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
    "shape609": {
      "const": "run-stage-trajectory"
    },
    "shape608": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape609"
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
    "shape611": {
      "const": "delegate-trajectory"
    },
    "shape610": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape611"
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
    "shape614": {
      "const": "board-inspection"
    },
    "shape615": {
      "const": "board-view"
    },
    "shape613": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape614"
        },
        {
          "$ref": "#/$defs/shape615"
        }
      ]
    },
    "shape617": {
      "const": "board"
    },
    "shape618": {
      "const": "swarm"
    },
    "shape616": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape617"
        },
        {
          "$ref": "#/$defs/shape618"
        }
      ]
    },
    "shape612": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape613"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape616"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape621": {
      "const": "board-refresh"
    },
    "shape622": {
      "const": "board-more"
    },
    "shape620": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape621"
        },
        {
          "$ref": "#/$defs/shape622"
        }
      ]
    },
    "shape619": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape620"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape624": {
      "const": "board-topic"
    },
    "shape623": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape624"
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
    "shape626": {
      "const": "board-trajectory"
    },
    "shape625": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape626"
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
    "shape628": {
      "const": "context"
    },
    "shape627": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape628"
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
    "shape630": {
      "const": "open-link"
    },
    "shape629": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape630"
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
    "shape632": {
      "const": "copy-text"
    },
    "shape631": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape632"
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
    "shape634": {
      "const": "workflow-start"
    },
    "shape633": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape634"
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
    "shape635": {
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
    "shape637": {
      "const": "cancel"
    },
    "shape636": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape637"
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
    "shape639": {
      "const": "answer"
    },
    "shape640": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape158"
        },
        {
          "$ref": "#/$defs/shape160"
        }
      ]
    },
    "shape638": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape639"
        },
        "decision": {
          "$ref": "#/$defs/shape640"
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
    "shape642": {
      "const": "provider-management"
    },
    "shape643": {
      "const": "provider.connection.list"
    },
    "shape644": {
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
    "shape641": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape643"
        },
        "payload": {
          "$ref": "#/$defs/shape644"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape646": {
      "const": "provider.connection.status"
    },
    "shape647": {
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
    "shape645": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape646"
        },
        "payload": {
          "$ref": "#/$defs/shape647"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape649": {
      "const": "provider.connection.receipt"
    },
    "shape650": {
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
    "shape648": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape649"
        },
        "payload": {
          "$ref": "#/$defs/shape650"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape652": {
      "const": "provider.connection.save"
    },
    "shape656": {
      "const": "Authorization"
    },
    "shape657": {
      "const": "X-API-Key"
    },
    "shape658": {
      "const": "Api-Key"
    },
    "shape655": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape656"
        },
        {
          "$ref": "#/$defs/shape657"
        },
        {
          "$ref": "#/$defs/shape658"
        }
      ]
    },
    "shape660": {
      "const": "API_KEY"
    },
    "shape661": {
      "const": "OAUTH_PKCE"
    },
    "shape662": {
      "const": "CLIENT_CREDENTIALS"
    },
    "shape659": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape660"
        },
        {
          "$ref": "#/$defs/shape661"
        },
        {
          "$ref": "#/$defs/shape662"
        }
      ]
    },
    "shape664": {
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
          "$ref": "#/$defs/shape202"
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
    "shape663": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape664"
        }
      ]
    },
    "shape654": {
      "type": "object",
      "properties": {
        "apiKeyHeader": {
          "$ref": "#/$defs/shape655"
        },
        "authentication": {
          "$ref": "#/$defs/shape659"
        },
        "endpoint": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "oauth": {
          "$ref": "#/$defs/shape663"
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
    "shape653": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape654"
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
    "shape651": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape652"
        },
        "payload": {
          "$ref": "#/$defs/shape653"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape666": {
      "const": "provider.connection.change"
    },
    "shape669": {
      "const": "ENABLE"
    },
    "shape670": {
      "const": "DISABLE"
    },
    "shape671": {
      "const": "DELETE"
    },
    "shape668": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape669"
        },
        {
          "$ref": "#/$defs/shape670"
        },
        {
          "$ref": "#/$defs/shape671"
        }
      ]
    },
    "shape667": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape668"
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
    "shape665": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape666"
        },
        "payload": {
          "$ref": "#/$defs/shape667"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape673": {
      "const": "provider.credential.status"
    },
    "shape674": {
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
    "shape672": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape673"
        },
        "payload": {
          "$ref": "#/$defs/shape674"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape676": {
      "const": "provider.credential.put"
    },
    "shape677": {
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
    "shape675": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape676"
        },
        "payload": {
          "$ref": "#/$defs/shape677"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape679": {
      "const": "provider.credential.revoke"
    },
    "shape680": {
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
    "shape678": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape679"
        },
        "payload": {
          "$ref": "#/$defs/shape680"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape682": {
      "const": "provider.credential.rekey"
    },
    "shape681": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape682"
        },
        "payload": {
          "$ref": "#/$defs/shape680"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape684": {
      "const": "provider.credential.authorize"
    },
    "shape683": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape684"
        },
        "payload": {
          "$ref": "#/$defs/shape680"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape686": {
      "const": "provider.credential.refresh"
    },
    "shape685": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape686"
        },
        "payload": {
          "$ref": "#/$defs/shape680"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape688": {
      "const": "tool.scope.host.list"
    },
    "shape689": {
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
    "shape687": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape688"
        },
        "payload": {
          "$ref": "#/$defs/shape689"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape691": {
      "const": "tool.scope.host.status"
    },
    "shape692": {
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
    "shape690": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape691"
        },
        "payload": {
          "$ref": "#/$defs/shape692"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape694": {
      "const": "tool.scope.host.receipt"
    },
    "shape695": {
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
    "shape693": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape694"
        },
        "payload": {
          "$ref": "#/$defs/shape695"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape697": {
      "const": "tool.scope.host.save"
    },
    "shape699": {
      "type": "object",
      "properties": {
        "agents": {
          "$ref": "#/$defs/shape202"
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
          "$ref": "#/$defs/shape202"
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
    "shape698": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape699"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape571"
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
    "shape696": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape697"
        },
        "payload": {
          "$ref": "#/$defs/shape698"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape701": {
      "const": "tool.scope.host.change"
    },
    "shape702": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape668"
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
    "shape700": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape701"
        },
        "payload": {
          "$ref": "#/$defs/shape702"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape704": {
      "const": "tool.scope.list"
    },
    "shape705": {
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
    "shape703": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
        },
        "operation": {
          "$ref": "#/$defs/shape704"
        },
        "payload": {
          "$ref": "#/$defs/shape705"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape707": {
      "const": "provider-management-pending"
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
      "const": "provider-management-recover"
    },
    "shape712": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape652"
        },
        {
          "$ref": "#/$defs/shape666"
        }
      ]
    },
    "shape711": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape712"
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
    "shape714": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape676"
        },
        {
          "$ref": "#/$defs/shape679"
        },
        {
          "$ref": "#/$defs/shape682"
        },
        {
          "$ref": "#/$defs/shape684"
        },
        {
          "$ref": "#/$defs/shape686"
        }
      ]
    },
    "shape713": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape714"
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
    "shape716": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape697"
        },
        {
          "$ref": "#/$defs/shape701"
        }
      ]
    },
    "shape715": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape571"
        },
        "operation": {
          "$ref": "#/$defs/shape716"
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
    "shape710": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape711"
        },
        {
          "$ref": "#/$defs/shape713"
        },
        {
          "$ref": "#/$defs/shape715"
        }
      ]
    },
    "shape708": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape709"
        },
        "intent": {
          "$ref": "#/$defs/shape710"
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
          "$ref": "#/$defs/shape198"
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
          "$ref": "#/$defs/shape217"
        },
        {
          "$ref": "#/$defs/shape220"
        },
        {
          "$ref": "#/$defs/shape223"
        },
        {
          "$ref": "#/$defs/shape226"
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
          "$ref": "#/$defs/shape315"
        },
        {
          "$ref": "#/$defs/shape318"
        },
        {
          "$ref": "#/$defs/shape321"
        },
        {
          "$ref": "#/$defs/shape324"
        },
        {
          "$ref": "#/$defs/shape330"
        },
        {
          "$ref": "#/$defs/shape341"
        },
        {
          "$ref": "#/$defs/shape345"
        },
        {
          "$ref": "#/$defs/shape357"
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
          "$ref": "#/$defs/shape371"
        },
        {
          "$ref": "#/$defs/shape374"
        },
        {
          "$ref": "#/$defs/shape377"
        },
        {
          "$ref": "#/$defs/shape379"
        },
        {
          "$ref": "#/$defs/shape382"
        },
        {
          "$ref": "#/$defs/shape385"
        },
        {
          "$ref": "#/$defs/shape388"
        },
        {
          "$ref": "#/$defs/shape393"
        },
        {
          "$ref": "#/$defs/shape396"
        },
        {
          "$ref": "#/$defs/shape399"
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
          "$ref": "#/$defs/shape417"
        },
        {
          "$ref": "#/$defs/shape419"
        },
        {
          "$ref": "#/$defs/shape421"
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
          "$ref": "#/$defs/shape448"
        },
        {
          "$ref": "#/$defs/shape454"
        },
        {
          "$ref": "#/$defs/shape458"
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
          "$ref": "#/$defs/shape476"
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
          "$ref": "#/$defs/shape491"
        },
        {
          "$ref": "#/$defs/shape493"
        },
        {
          "$ref": "#/$defs/shape497"
        },
        {
          "$ref": "#/$defs/shape499"
        },
        {
          "$ref": "#/$defs/shape505"
        },
        {
          "$ref": "#/$defs/shape507"
        },
        {
          "$ref": "#/$defs/shape509"
        },
        {
          "$ref": "#/$defs/shape519"
        },
        {
          "$ref": "#/$defs/shape526"
        },
        {
          "$ref": "#/$defs/shape528"
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
          "$ref": "#/$defs/shape550"
        },
        {
          "$ref": "#/$defs/shape552"
        },
        {
          "$ref": "#/$defs/shape554"
        },
        {
          "$ref": "#/$defs/shape556"
        },
        {
          "$ref": "#/$defs/shape558"
        },
        {
          "$ref": "#/$defs/shape580"
        },
        {
          "$ref": "#/$defs/shape582"
        },
        {
          "$ref": "#/$defs/shape584"
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
          "$ref": "#/$defs/shape599"
        },
        {
          "$ref": "#/$defs/shape601"
        },
        {
          "$ref": "#/$defs/shape603"
        },
        {
          "$ref": "#/$defs/shape608"
        },
        {
          "$ref": "#/$defs/shape610"
        },
        {
          "$ref": "#/$defs/shape612"
        },
        {
          "$ref": "#/$defs/shape619"
        },
        {
          "$ref": "#/$defs/shape623"
        },
        {
          "$ref": "#/$defs/shape625"
        },
        {
          "$ref": "#/$defs/shape627"
        },
        {
          "$ref": "#/$defs/shape629"
        },
        {
          "$ref": "#/$defs/shape631"
        },
        {
          "$ref": "#/$defs/shape633"
        },
        {
          "$ref": "#/$defs/shape635"
        },
        {
          "$ref": "#/$defs/shape636"
        },
        {
          "$ref": "#/$defs/shape638"
        },
        {
          "$ref": "#/$defs/shape641"
        },
        {
          "$ref": "#/$defs/shape645"
        },
        {
          "$ref": "#/$defs/shape648"
        },
        {
          "$ref": "#/$defs/shape651"
        },
        {
          "$ref": "#/$defs/shape665"
        },
        {
          "$ref": "#/$defs/shape672"
        },
        {
          "$ref": "#/$defs/shape675"
        },
        {
          "$ref": "#/$defs/shape678"
        },
        {
          "$ref": "#/$defs/shape681"
        },
        {
          "$ref": "#/$defs/shape683"
        },
        {
          "$ref": "#/$defs/shape685"
        },
        {
          "$ref": "#/$defs/shape687"
        },
        {
          "$ref": "#/$defs/shape690"
        },
        {
          "$ref": "#/$defs/shape693"
        },
        {
          "$ref": "#/$defs/shape696"
        },
        {
          "$ref": "#/$defs/shape700"
        },
        {
          "$ref": "#/$defs/shape703"
        },
        {
          "$ref": "#/$defs/shape706"
        },
        {
          "$ref": "#/$defs/shape708"
        }
      ]
    }
  }
}
