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
    "shape183": {
      "const": "personal"
    },
    "shape182": {
      "type": "object",
      "properties": {
        "includeGlobal": {
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
    "shape185": {
      "const": "global_read"
    },
    "shape184": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape185"
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
        "includeGlobal": {
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
    "shape188": {
      "const": "00000000-0000-0000-0000-000000000004"
    },
    "shape189": {
      "const": "account"
    },
    "shape187": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape188"
        },
        "kind": {
          "$ref": "#/$defs/shape189"
        }
      },
      "required": [
        "account",
        "kind"
      ],
      "additionalProperties": false
    },
    "shape181": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape182"
        },
        {
          "$ref": "#/$defs/shape184"
        },
        {
          "$ref": "#/$defs/shape186"
        },
        {
          "$ref": "#/$defs/shape187"
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
    "shape191": {
      "const": "documents.list"
    },
    "shape194": {
      "const": "outgoing"
    },
    "shape195": {
      "const": "backlinks"
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
    "shape197": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape199": {
      "const": "source"
    },
    "shape200": {
      "const": "report"
    },
    "shape198": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape199"
        },
        {
          "$ref": "#/$defs/shape200"
        }
      ]
    },
    "shape196": {
      "type": "object",
      "properties": {
        "author": {
          "$ref": "#/$defs/shape6"
        },
        "autoTag": {
          "$ref": "#/$defs/shape197"
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
          "$ref": "#/$defs/shape198"
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
          "$ref": "#/$defs/shape197"
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
    "shape192": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape193"
        },
        "filter": {
          "$ref": "#/$defs/shape196"
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
    "shape202": {
      "const": "documents.facets"
    },
    "shape203": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape193"
        },
        "filter": {
          "$ref": "#/$defs/shape196"
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
    "shape205": {
      "const": "documents.get"
    },
    "shape207": {
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
    "shape206": {
      "type": "object",
      "properties": {
        "target": {
          "$ref": "#/$defs/shape207"
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
    "shape204": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape205"
        },
        "payload": {
          "$ref": "#/$defs/shape206"
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
      "const": "relationships.list"
    },
    "shape210": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape193"
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
      "const": "relationships.create"
    },
    "shape213": {
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
          "$ref": "#/$defs/shape207"
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
      "const": "relationships.remove"
    },
    "shape216": {
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
    "shape218": {
      "const": "policy.get"
    },
    "shape222": {
      "const": "platform"
    },
    "shape223": {
      "forbidden": true
    },
    "shape221": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape222"
        },
        "project": {
          "$ref": "#/$defs/shape223"
        },
        "resource": {
          "$ref": "#/$defs/shape223"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape224": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape91"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "resource": {
          "$ref": "#/$defs/shape223"
        }
      },
      "required": [
        "kind",
        "project"
      ],
      "additionalProperties": false
    },
    "shape226": {
      "const": "document"
    },
    "shape225": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape226"
        },
        "project": {
          "$ref": "#/$defs/shape223"
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
    "shape220": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape221"
        },
        {
          "$ref": "#/$defs/shape224"
        },
        {
          "$ref": "#/$defs/shape225"
        }
      ]
    },
    "shape219": {
      "type": "object",
      "properties": {
        "target": {
          "$ref": "#/$defs/shape220"
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
    "shape228": {
      "const": "policy.set"
    },
    "shape231": {
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
    "shape230": {
      "type": "object",
      "properties": {
        "allowOverrides": {
          "$ref": "#/$defs/shape29"
        },
        "defaults": {
          "$ref": "#/$defs/shape231"
        },
        "mandatory": {
          "$ref": "#/$defs/shape231"
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
    "shape229": {
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
          "$ref": "#/$defs/shape230"
        },
        "target": {
          "$ref": "#/$defs/shape220"
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
    "shape227": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape228"
        },
        "payload": {
          "$ref": "#/$defs/shape229"
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
      "const": "views.list"
    },
    "shape234": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
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
      "const": "views.get"
    },
    "shape237": {
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
    "shape239": {
      "const": "views.save"
    },
    "shape245": {
      "const": "scope"
    },
    "shape244": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape245"
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
    "shape246": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape226"
        },
        "target": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "kind",
        "target"
      ],
      "additionalProperties": false
    },
    "shape248": {
      "const": "traverse"
    },
    "shape247": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape193"
        },
        "kind": {
          "$ref": "#/$defs/shape248"
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
    "shape250": {
      "const": "filter"
    },
    "shape249": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape196"
        },
        "kind": {
          "$ref": "#/$defs/shape250"
        }
      },
      "required": [
        "filter",
        "kind"
      ],
      "additionalProperties": false
    },
    "shape243": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape244"
        },
        {
          "$ref": "#/$defs/shape246"
        },
        {
          "$ref": "#/$defs/shape247"
        },
        {
          "$ref": "#/$defs/shape249"
        }
      ]
    },
    "shape242": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape243"
      }
    },
    "shape241": {
      "type": "object",
      "properties": {
        "steps": {
          "$ref": "#/$defs/shape242"
        }
      },
      "required": [
        "steps"
      ],
      "additionalProperties": false
    },
    "shape240": {
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
          "$ref": "#/$defs/shape241"
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
    "shape238": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape239"
        },
        "payload": {
          "$ref": "#/$defs/shape240"
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
      "const": "views.delete"
    },
    "shape253": {
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
      "const": "interchange.capabilities"
    },
    "shape256": {
      "type": "object",
      "properties": {
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
    "shape258": {
      "const": "interchange.export"
    },
    "shape259": {
      "type": "object",
      "properties": {
        "revisions": {
          "$ref": "#/$defs/shape197"
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
      "const": "interchange.readable"
    },
    "shape262": {
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
    "shape264": {
      "const": "interchange.import"
    },
    "shape275": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape274"
      }
    },
    "shape274": {
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
          "$ref": "#/$defs/shape273"
        },
        {
          "$ref": "#/$defs/shape275"
        }
      ]
    },
    "shape273": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape274"
      }
    },
    "shape276": {
      "const": "plowshare-document-metadata/1"
    },
    "shape278": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape277"
      }
    },
    "shape279": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape30"
        },
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape277"
        }
      ]
    },
    "shape280": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape277"
      }
    },
    "shape281": {
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
    "shape282": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape281"
      }
    },
    "shape284": {
      "const": "string"
    },
    "shape285": {
      "const": "number"
    },
    "shape286": {
      "const": "boolean"
    },
    "shape287": {
      "const": "object"
    },
    "shape288": {
      "const": "array"
    },
    "shape289": {
      "const": "integer"
    },
    "shape290": {
      "const": "null"
    },
    "shape292": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape289"
        },
        {
          "$ref": "#/$defs/shape290"
        }
      ]
    },
    "shape291": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape292"
      }
    },
    "shape283": {
      "anyOf": [
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
    "shape277": {
      "type": "object",
      "properties": {
        "$anchor": {
          "$ref": "#/$defs/shape6"
        },
        "$defs": {
          "$ref": "#/$defs/shape278"
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
          "$ref": "#/$defs/shape279"
        },
        "allOf": {
          "$ref": "#/$defs/shape280"
        },
        "anyOf": {
          "$ref": "#/$defs/shape280"
        },
        "const": {
          "$ref": "#/$defs/shape281"
        },
        "contains": {
          "$ref": "#/$defs/shape277"
        },
        "default": {
          "$ref": "#/$defs/shape281"
        },
        "deprecated": {
          "$ref": "#/$defs/shape29"
        },
        "description": {
          "$ref": "#/$defs/shape6"
        },
        "else": {
          "$ref": "#/$defs/shape277"
        },
        "enum": {
          "$ref": "#/$defs/shape282"
        },
        "examples": {
          "$ref": "#/$defs/shape282"
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
          "$ref": "#/$defs/shape277"
        },
        "items": {
          "$ref": "#/$defs/shape277"
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
          "$ref": "#/$defs/shape277"
        },
        "oneOf": {
          "$ref": "#/$defs/shape280"
        },
        "pattern": {
          "$ref": "#/$defs/shape6"
        },
        "patternProperties": {
          "$ref": "#/$defs/shape278"
        },
        "prefixItems": {
          "$ref": "#/$defs/shape280"
        },
        "properties": {
          "$ref": "#/$defs/shape278"
        },
        "readOnly": {
          "$ref": "#/$defs/shape29"
        },
        "required": {
          "$ref": "#/$defs/shape197"
        },
        "then": {
          "$ref": "#/$defs/shape277"
        },
        "title": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape283"
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
    "shape272": {
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
          "$ref": "#/$defs/shape273"
        },
        "profile": {
          "$ref": "#/$defs/shape276"
        },
        "schema": {
          "$ref": "#/$defs/shape277"
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
    "shape271": {
      "type": "object",
      "properties": {
        "contract": {
          "$ref": "#/$defs/shape272"
        },
        "metadata": {
          "$ref": "#/$defs/shape273"
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
    "shape295": {
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
    "shape294": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape295"
      }
    },
    "shape296": {
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
    "shape293": {
      "type": "object",
      "properties": {
        "createdAt": {
          "$ref": "#/$defs/shape6"
        },
        "dependencies": {
          "$ref": "#/$defs/shape197"
        },
        "evidence": {
          "$ref": "#/$defs/shape294"
        },
        "importedFrom": {
          "$ref": "#/$defs/shape296"
        },
        "kind": {
          "$ref": "#/$defs/shape198"
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
    "shape270": {
      "type": "object",
      "properties": {
        "contentHash": {
          "$ref": "#/$defs/shape6"
        },
        "contentType": {
          "$ref": "#/$defs/shape271"
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
          "$ref": "#/$defs/shape293"
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
          "$ref": "#/$defs/shape197"
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
    "shape269": {
      "type": "object",
      "properties": {
        "bytesBase64": {
          "$ref": "#/$defs/shape6"
        },
        "descriptor": {
          "$ref": "#/$defs/shape270"
        }
      },
      "required": [
        "bytesBase64",
        "descriptor"
      ],
      "additionalProperties": false
    },
    "shape268": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape269"
      }
    },
    "shape297": {
      "const": "plowshare-information-bundle/1"
    },
    "shape298": {
      "const": "included-endpoints-only"
    },
    "shape300": {
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
    "shape299": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape300"
      }
    },
    "shape267": {
      "type": "object",
      "properties": {
        "documents": {
          "$ref": "#/$defs/shape268"
        },
        "format": {
          "$ref": "#/$defs/shape297"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "relationshipCoverage": {
          "$ref": "#/$defs/shape298"
        },
        "relationships": {
          "$ref": "#/$defs/shape299"
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
    "shape304": {
      "const": "create"
    },
    "shape303": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape223"
        },
        "mode": {
          "$ref": "#/$defs/shape304"
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
    "shape306": {
      "const": "revise"
    },
    "shape305": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "mode": {
          "$ref": "#/$defs/shape306"
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
    "shape302": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape303"
        },
        {
          "$ref": "#/$defs/shape305"
        }
      ]
    },
    "shape301": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape302"
      }
    },
    "shape308": {
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
    "shape307": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape308"
      }
    },
    "shape310": {
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
    "shape309": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape310"
      }
    },
    "shape266": {
      "type": "object",
      "properties": {
        "bundle": {
          "$ref": "#/$defs/shape267"
        },
        "destinations": {
          "$ref": "#/$defs/shape301"
        },
        "restoreRelationships": {
          "$ref": "#/$defs/shape29"
        },
        "reuse": {
          "$ref": "#/$defs/shape307"
        },
        "types": {
          "$ref": "#/$defs/shape309"
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
    "shape265": {
      "type": "object",
      "properties": {
        "plan": {
          "$ref": "#/$defs/shape266"
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
    "shape263": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape264"
        },
        "payload": {
          "$ref": "#/$defs/shape265"
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
      "const": "interchange.origin"
    },
    "shape313": {
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
      "const": "types.capabilities"
    },
    "shape316": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
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
      "const": "types.list"
    },
    "shape319": {
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
      "const": "types.get"
    },
    "shape322": {
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
      "const": "types.validate"
    },
    "shape325": {
      "type": "object",
      "properties": {
        "attachedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "metadata": {
          "$ref": "#/$defs/shape273"
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
      "const": "types.version.create"
    },
    "shape328": {
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
          "$ref": "#/$defs/shape273"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "schema": {
          "$ref": "#/$defs/shape277"
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
      "const": "types.activate"
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
      "const": "types.deprecate"
    },
    "shape334": {
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
      "const": "content.get"
    },
    "shape337": {
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
    "shape339": {
      "const": "content.revise"
    },
    "shape343": {
      "const": "set"
    },
    "shape342": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape273"
        },
        "mode": {
          "$ref": "#/$defs/shape343"
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
    "shape345": {
      "const": "inherit"
    },
    "shape344": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape273"
        },
        "mode": {
          "$ref": "#/$defs/shape345"
        },
        "version": {
          "$ref": "#/$defs/shape223"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape347": {
      "const": "clear"
    },
    "shape346": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape223"
        },
        "mode": {
          "$ref": "#/$defs/shape347"
        },
        "version": {
          "$ref": "#/$defs/shape223"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape341": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape342"
        },
        {
          "$ref": "#/$defs/shape344"
        },
        {
          "$ref": "#/$defs/shape346"
        }
      ]
    },
    "shape340": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape341"
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
    "shape338": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape339"
        },
        "payload": {
          "$ref": "#/$defs/shape340"
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
      "const": "upload"
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
        "text": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape341"
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
      "const": "acquire"
    },
    "shape353": {
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
          "$ref": "#/$defs/shape341"
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
      "const": "refresh"
    },
    "shape356": {
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
          "$ref": "#/$defs/shape341"
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
          "$ref": "#/$defs/shape341"
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
    "shape357": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape306"
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
      "const": "replace"
    },
    "shape361": {
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
          "$ref": "#/$defs/shape341"
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
      "const": "list"
    },
    "shape364": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape196"
        },
        "kind": {
          "$ref": "#/$defs/shape198"
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
      "const": "facets"
    },
    "shape367": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape196"
        },
        "kind": {
          "$ref": "#/$defs/shape198"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
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
      "const": "tags"
    },
    "shape370": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "tags": {
          "$ref": "#/$defs/shape197"
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
    "shape372": {
      "const": "tags.groups"
    },
    "shape375": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape197"
      }
    },
    "shape374": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape375"
        }
      ]
    },
    "shape373": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape374"
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
    "shape371": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape372"
        },
        "payload": {
          "$ref": "#/$defs/shape373"
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
      "const": "inventory"
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
      "const": "acquisitions"
    },
    "shape381": {
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
      "const": "status"
    },
    "shape384": {
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
    "shape386": {
      "const": "await"
    },
    "shape390": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape223"
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
    "shape391": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape223"
        }
      },
      "required": [
        "acquisition"
      ],
      "additionalProperties": false
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
    "shape388": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape389"
      }
    },
    "shape387": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape388"
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
    "shape385": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape386"
        },
        "payload": {
          "$ref": "#/$defs/shape387"
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
      "const": "read"
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
      "const": "outline"
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
        "revision": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "revision"
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
      "const": "symbols"
    },
    "shape400": {
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
      "const": "search"
    },
    "shape403": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape196"
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
      "const": "rank"
    },
    "shape406": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape196"
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
      "const": "ask"
    },
    "shape409": {
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
      "const": "evidence.record"
    },
    "shape412": {
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
      "const": "evidence.read"
    },
    "shape415": {
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
    "shape417": {
      "const": "record.report"
    },
    "shape422": {
      "const": "holds"
    },
    "shape423": {
      "const": "weakened"
    },
    "shape424": {
      "const": "refuted"
    },
    "shape425": {
      "const": "not_checked"
    },
    "shape421": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape422"
        },
        {
          "$ref": "#/$defs/shape423"
        },
        {
          "$ref": "#/$defs/shape424"
        },
        {
          "$ref": "#/$defs/shape425"
        }
      ]
    },
    "shape420": {
      "type": "object",
      "properties": {
        "claim": {
          "$ref": "#/$defs/shape6"
        },
        "counterEvidence": {
          "$ref": "#/$defs/shape197"
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
          "$ref": "#/$defs/shape197"
        },
        "verdict": {
          "$ref": "#/$defs/shape421"
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
    "shape419": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape420"
      }
    },
    "shape427": {
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
    "shape426": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape427"
      }
    },
    "shape418": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape197"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "feedback": {
          "$ref": "#/$defs/shape6"
        },
        "findings": {
          "$ref": "#/$defs/shape419"
        },
        "inputs": {
          "$ref": "#/$defs/shape197"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "objectives": {
          "$ref": "#/$defs/shape197"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "reviews": {
          "$ref": "#/$defs/shape426"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape197"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape341"
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
    "shape416": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape417"
        },
        "payload": {
          "$ref": "#/$defs/shape418"
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
      "const": "finalise"
    },
    "shape430": {
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
      "const": "link"
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
      "const": "unlink"
    },
    "shape436": {
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
      "const": "share"
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
      "const": "unshare"
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
      "const": "withdraw"
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
      "const": "exclude"
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
      "const": "unexclude"
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
      "const": "restore"
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
      "const": "delete"
    },
    "shape457": {
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
    "shape459": {
      "const": "retry"
    },
    "shape463": {
      "const": "extract"
    },
    "shape464": {
      "const": "derive"
    },
    "shape465": {
      "const": "embed"
    },
    "shape466": {
      "const": "summarise"
    },
    "shape467": {
      "const": "summary_embed"
    },
    "shape468": {
      "const": "autoTag"
    },
    "shape469": {
      "const": "tagGroups"
    },
    "shape462": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape467"
        },
        {
          "$ref": "#/$defs/shape468"
        },
        {
          "$ref": "#/$defs/shape469"
        }
      ]
    },
    "shape461": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape462"
      }
    },
    "shape460": {
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
          "$ref": "#/$defs/shape461"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape458": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape459"
        },
        "payload": {
          "$ref": "#/$defs/shape460"
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
      "const": "rebuild"
    },
    "shape472": {
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
          "$ref": "#/$defs/shape461"
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
      "const": "allowance"
    },
    "shape475": {
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
      "const": "events"
    },
    "shape478": {
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
      "const": "migration.list"
    },
    "shape481": {
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
      "const": "migration.adopt"
    },
    "shape484": {
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
      "const": "migration.inspect"
    },
    "shape487": {
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
    "shape489": {
      "const": "migration.release"
    },
    "shape490": {
      "type": "object",
      "properties": {
        "inputs": {
          "$ref": "#/$defs/shape197"
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
    "shape488": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape489"
        },
        "payload": {
          "$ref": "#/$defs/shape490"
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
    "shape493": {
      "const": "bootstrap"
    },
    "shape494": {
      "const": "demo"
    },
    "shape495": {
      "const": "disconnect"
    },
    "shape496": {
      "const": "approvals-refresh"
    },
    "shape492": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape355"
        },
        {
          "$ref": "#/$defs/shape493"
        },
        {
          "$ref": "#/$defs/shape494"
        },
        {
          "$ref": "#/$defs/shape495"
        },
        {
          "$ref": "#/$defs/shape496"
        }
      ]
    },
    "shape491": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape492"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape498": {
      "const": "project-access"
    },
    "shape500": {
      "const": "project.access"
    },
    "shape501": {
      "const": "project.member.add"
    },
    "shape502": {
      "const": "project.member.remove"
    },
    "shape503": {
      "const": "project.member.role"
    },
    "shape499": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape500"
        },
        {
          "$ref": "#/$defs/shape501"
        },
        {
          "$ref": "#/$defs/shape502"
        },
        {
          "$ref": "#/$defs/shape503"
        }
      ]
    },
    "shape505": {
      "const": "VIEWER"
    },
    "shape506": {
      "const": "CONTRIBUTOR"
    },
    "shape507": {
      "const": "MANAGER"
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
        }
      ]
    },
    "shape497": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape498"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape499"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape504"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape509": {
      "const": "server-admin"
    },
    "shape510": {
      "const": "admin.pricing.list"
    },
    "shape511": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape223"
      }
    },
    "shape508": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
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
    "shape513": {
      "const": "admin.pricing.set"
    },
    "shape516": {
      "const": "TOKEN"
    },
    "shape517": {
      "const": "INCLUDED"
    },
    "shape518": {
      "const": "ZERO_RATE"
    },
    "shape519": {
      "const": "UNPRICED"
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
        },
        {
          "$ref": "#/$defs/shape519"
        }
      ]
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
    "shape523": {
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
    "shape522": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape28"
        },
        "rates": {
          "$ref": "#/$defs/shape523"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape521": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape522"
      }
    },
    "shape514": {
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
          "$ref": "#/$defs/shape515"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "rates": {
          "$ref": "#/$defs/shape520"
        },
        "requestFee": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "tiers": {
          "$ref": "#/$defs/shape521"
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
    "shape512": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
        },
        "operation": {
          "$ref": "#/$defs/shape513"
        },
        "payload": {
          "$ref": "#/$defs/shape514"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape525": {
      "const": "admin.accounts"
    },
    "shape524": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
        },
        "operation": {
          "$ref": "#/$defs/shape525"
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
    "shape527": {
      "const": "admin.account.create"
    },
    "shape528": {
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
    "shape526": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
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
      "const": "admin.account.update"
    },
    "shape531": {
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
    "shape529": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
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
      "const": "admin.account.reset"
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
          "$ref": "#/$defs/shape509"
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
      "const": "admin.sessions"
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
          "$ref": "#/$defs/shape509"
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
      "const": "admin.session.revoke"
    },
    "shape540": {
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
    "shape538": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
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
      "const": "admin.audit"
    },
    "shape543": {
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
    "shape541": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
        },
        "operation": {
          "$ref": "#/$defs/shape542"
        },
        "payload": {
          "$ref": "#/$defs/shape543"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape545": {
      "const": "admin.service.accounts"
    },
    "shape544": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
        },
        "operation": {
          "$ref": "#/$defs/shape545"
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
    "shape547": {
      "const": "admin.service.account.create"
    },
    "shape548": {
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
    "shape546": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
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
      "const": "admin.service.account.update"
    },
    "shape551": {
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
    "shape549": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
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
      "const": "admin.service.tokens"
    },
    "shape554": {
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
    "shape552": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
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
    "shape556": {
      "const": "admin.service.token.create"
    },
    "shape559": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape504"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape558": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape559"
      }
    },
    "shape557": {
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
          "$ref": "#/$defs/shape558"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape555": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
        },
        "operation": {
          "$ref": "#/$defs/shape556"
        },
        "payload": {
          "$ref": "#/$defs/shape557"
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
      "const": "admin.service.token.rotate"
    },
    "shape562": {
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
    "shape560": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
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
      "const": "admin.service.token.revoke"
    },
    "shape565": {
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
    "shape563": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape509"
        },
        "operation": {
          "$ref": "#/$defs/shape564"
        },
        "payload": {
          "$ref": "#/$defs/shape565"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape567": {
      "const": "server-project-create"
    },
    "shape569": {
      "const": "MANAGED"
    },
    "shape570": {
      "const": "DISJOINT"
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
    "shape571": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape572": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape566": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape567"
        },
        "applicationRoot": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape568"
        },
        "workspace": {
          "$ref": "#/$defs/shape6"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape571"
        },
        "writePaths": {
          "$ref": "#/$defs/shape572"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape574": {
      "const": "server-setup"
    },
    "shape573": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape574"
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
    "shape576": {
      "const": "connect"
    },
    "shape575": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape576"
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
    "shape578": {
      "const": "connection-select"
    },
    "shape577": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape578"
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
    "shape580": {
      "const": "connection-preferences"
    },
    "shape582": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape583": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape29"
      }
    },
    "shape581": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape582"
        },
        "drafts": {
          "$ref": "#/$defs/shape582"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape583"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape583"
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
    "shape579": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape6"
        },
        "action": {
          "$ref": "#/$defs/shape580"
        },
        "preference": {
          "$ref": "#/$defs/shape581"
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
    "shape585": {
      "const": "connection-rename"
    },
    "shape584": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape585"
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
    "shape587": {
      "const": "connection-remove"
    },
    "shape586": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape587"
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
    "shape589": {
      "const": "personal-section"
    },
    "shape591": {
      "const": "In"
    },
    "shape592": {
      "const": "Out"
    },
    "shape593": {
      "const": "Resources"
    },
    "shape594": {
      "const": "Archive"
    },
    "shape595": {
      "const": "Planning"
    },
    "shape596": {
      "const": "Bots"
    },
    "shape590": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape591"
        },
        {
          "$ref": "#/$defs/shape592"
        },
        {
          "$ref": "#/$defs/shape593"
        },
        {
          "$ref": "#/$defs/shape594"
        },
        {
          "$ref": "#/$defs/shape595"
        },
        {
          "$ref": "#/$defs/shape596"
        }
      ]
    },
    "shape588": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape589"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "section": {
          "$ref": "#/$defs/shape590"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape598": {
      "const": "personal-bots"
    },
    "shape597": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape598"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape600": {
      "const": "personal-recreate"
    },
    "shape599": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape600"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape602": {
      "const": "files-choose"
    },
    "shape601": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape602"
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
    "shape604": {
      "const": "files-withdraw"
    },
    "shape603": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape604"
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
    "shape606": {
      "const": "sync-refresh"
    },
    "shape605": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape606"
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
    "shape608": {
      "const": "sync-inspect"
    },
    "shape607": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape608"
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
    "shape610": {
      "const": "sync-change"
    },
    "shape612": {
      "const": "on"
    },
    "shape613": {
      "const": "off"
    },
    "shape614": {
      "const": "now"
    },
    "shape611": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape612"
        },
        {
          "$ref": "#/$defs/shape613"
        },
        {
          "$ref": "#/$defs/shape614"
        }
      ]
    },
    "shape609": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape610"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape611"
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
    "shape616": {
      "const": "sync-resolve"
    },
    "shape618": {
      "const": "mine"
    },
    "shape619": {
      "const": "theirs"
    },
    "shape620": {
      "const": "done"
    },
    "shape617": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape618"
        },
        {
          "$ref": "#/$defs/shape619"
        },
        {
          "$ref": "#/$defs/shape620"
        }
      ]
    },
    "shape615": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape616"
        },
        "how": {
          "$ref": "#/$defs/shape617"
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
    "shape623": {
      "const": "project-open"
    },
    "shape624": {
      "const": "project-remove"
    },
    "shape622": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape623"
        },
        {
          "$ref": "#/$defs/shape624"
        }
      ]
    },
    "shape621": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape622"
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
    "shape626": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape245"
        },
        {
          "$ref": "#/$defs/shape304"
        }
      ]
    },
    "shape625": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape626"
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
    "shape628": {
      "const": "history"
    },
    "shape627": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape628"
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
    "shape630": {
      "const": "select"
    },
    "shape629": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape630"
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
    "shape632": {
      "const": "trajectory"
    },
    "shape631": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape632"
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
    "shape634": {
      "const": "board-swarm-types"
    },
    "shape633": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape634"
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
    "shape636": {
      "const": "board-post-topics"
    },
    "shape635": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape636"
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
    "shape638": {
      "const": "board-create"
    },
    "shape637": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape638"
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
    "shape639": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape145"
        },
        "maxModelCalls": {
          "$ref": "#/$defs/shape28"
        },
        "topic": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "maxModelCalls",
        "topic"
      ],
      "additionalProperties": false
    },
    "shape641": {
      "const": "board-retry"
    },
    "shape640": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape641"
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
    "shape643": {
      "const": "board-post"
    },
    "shape642": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape643"
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
    "shape645": {
      "const": "workspace-chat"
    },
    "shape644": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape645"
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
    "shape647": {
      "const": "workspace-layout"
    },
    "shape646": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape647"
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
    "shape649": {
      "const": "workspace-refresh"
    },
    "shape648": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape649"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape651": {
      "const": "library"
    },
    "shape653": {
      "const": "sources"
    },
    "shape654": {
      "const": "documents"
    },
    "shape655": {
      "const": "memories"
    },
    "shape656": {
      "const": "manual"
    },
    "shape652": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape402"
        },
        {
          "$ref": "#/$defs/shape653"
        },
        {
          "$ref": "#/$defs/shape654"
        },
        {
          "$ref": "#/$defs/shape655"
        },
        {
          "$ref": "#/$defs/shape656"
        }
      ]
    },
    "shape650": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape651"
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
          "$ref": "#/$defs/shape652"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape658": {
      "const": "library-view"
    },
    "shape657": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape658"
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
          "$ref": "#/$defs/shape652"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape661": {
      "const": "library-refresh"
    },
    "shape662": {
      "const": "library-citations"
    },
    "shape660": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape661"
        },
        {
          "$ref": "#/$defs/shape662"
        }
      ]
    },
    "shape659": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape660"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape664": {
      "const": "library-documents"
    },
    "shape663": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape664"
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
    "shape667": {
      "const": "library-document"
    },
    "shape668": {
      "const": "library-memory"
    },
    "shape669": {
      "const": "library-chunk"
    },
    "shape670": {
      "const": "library-conversation"
    },
    "shape666": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape667"
        },
        {
          "$ref": "#/$defs/shape668"
        },
        {
          "$ref": "#/$defs/shape669"
        },
        {
          "$ref": "#/$defs/shape670"
        }
      ]
    },
    "shape665": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape666"
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
    "shape672": {
      "const": "library-source-text"
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
    "shape674": {
      "const": "library-stance"
    },
    "shape673": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape674"
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
    "shape676": {
      "const": "library-search"
    },
    "shape678": {
      "const": "retrieve"
    },
    "shape679": {
      "const": "recall"
    },
    "shape680": {
      "const": "navigate"
    },
    "shape677": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape405"
        },
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape654"
        },
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
    "shape682": {
      "const": "lexical"
    },
    "shape683": {
      "const": "semantic"
    },
    "shape684": {
      "const": "hybrid"
    },
    "shape681": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape682"
        },
        {
          "$ref": "#/$defs/shape683"
        },
        {
          "$ref": "#/$defs/shape684"
        }
      ]
    },
    "shape675": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape676"
        },
        "kind": {
          "$ref": "#/$defs/shape677"
        },
        "mode": {
          "$ref": "#/$defs/shape681"
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
    "shape686": {
      "const": "library-maintain"
    },
    "shape688": {
      "const": "invalidate"
    },
    "shape689": {
      "const": "resolve"
    },
    "shape690": {
      "const": "reembed"
    },
    "shape691": {
      "const": "reconsider"
    },
    "shape687": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape688"
        },
        {
          "$ref": "#/$defs/shape689"
        },
        {
          "$ref": "#/$defs/shape690"
        },
        {
          "$ref": "#/$defs/shape691"
        }
      ]
    },
    "shape685": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape29"
        },
        "action": {
          "$ref": "#/$defs/shape686"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape687"
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
    "shape693": {
      "const": "builder-outputs"
    },
    "shape692": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape693"
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
    "shape695": {
      "const": "builder-trajectory"
    },
    "shape694": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape695"
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
    "shape697": {
      "const": "builder-prepare"
    },
    "shape696": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape697"
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
    "shape699": {
      "const": "builder-start"
    },
    "shape698": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape699"
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
    "shape701": {
      "const": "activity"
    },
    "shape703": {
      "const": "inbox"
    },
    "shape704": {
      "const": "runs"
    },
    "shape705": {
      "const": "definitions"
    },
    "shape706": {
      "const": "schedules"
    },
    "shape707": {
      "const": "builder"
    },
    "shape702": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape703"
        },
        {
          "$ref": "#/$defs/shape704"
        },
        {
          "$ref": "#/$defs/shape705"
        },
        {
          "$ref": "#/$defs/shape706"
        },
        {
          "$ref": "#/$defs/shape707"
        }
      ]
    },
    "shape700": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape701"
        },
        "view": {
          "$ref": "#/$defs/shape702"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape709": {
      "const": "activity-view"
    },
    "shape708": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape709"
        },
        "view": {
          "$ref": "#/$defs/shape702"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape711": {
      "const": "activity-refresh"
    },
    "shape710": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape711"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape713": {
      "const": "question-refresh"
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
      "const": "inbox-read"
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
      "const": "inbox-more"
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
      "const": "run-detail"
    },
    "shape718": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape719"
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
    "shape721": {
      "const": "schedule-refresh"
    },
    "shape720": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape721"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape723": {
      "const": "schedule-preview"
    },
    "shape722": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape723"
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
    "shape725": {
      "const": "schedule-save"
    },
    "shape729": {
      "const": "skill"
    },
    "shape730": {
      "const": "orchestration"
    },
    "shape728": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape89"
        },
        {
          "$ref": "#/$defs/shape729"
        },
        {
          "$ref": "#/$defs/shape730"
        }
      ]
    },
    "shape732": {
      "const": "INHERITED"
    },
    "shape733": {
      "const": "SUMMARISED"
    },
    "shape734": {
      "const": "NEW"
    },
    "shape735": {
      "const": "DIRECT"
    },
    "shape731": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape732"
        },
        {
          "$ref": "#/$defs/shape733"
        },
        {
          "$ref": "#/$defs/shape734"
        },
        {
          "$ref": "#/$defs/shape735"
        }
      ]
    },
    "shape727": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape728"
        },
        "mode": {
          "$ref": "#/$defs/shape731"
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
    "shape737": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape28"
        }
      ]
    },
    "shape736": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape737"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape737"
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
    "shape740": {
      "const": "mailbox"
    },
    "shape741": {
      "const": "message"
    },
    "shape739": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape740"
        },
        {
          "$ref": "#/$defs/shape741"
        }
      ]
    },
    "shape738": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape739"
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
    "shape742": {
      "const": 1
    },
    "shape726": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape727"
        },
        "cron": {
          "$ref": "#/$defs/shape6"
        },
        "limits": {
          "$ref": "#/$defs/shape736"
        },
        "paused": {
          "$ref": "#/$defs/shape29"
        },
        "target": {
          "$ref": "#/$defs/shape738"
        },
        "version": {
          "$ref": "#/$defs/shape742"
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
    "shape744": {
      "const": "server"
    },
    "shape745": {
      "const": "workspace"
    },
    "shape743": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape744"
        },
        {
          "$ref": "#/$defs/shape745"
        }
      ]
    },
    "shape724": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape725"
        },
        "definition": {
          "$ref": "#/$defs/shape726"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape743"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape747": {
      "const": "schedule-file-save"
    },
    "shape746": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape747"
        },
        "definition": {
          "$ref": "#/$defs/shape726"
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
          "$ref": "#/$defs/shape743"
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
    "shape749": {
      "const": "schedule-sync"
    },
    "shape748": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape749"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape743"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape751": {
      "const": "schedule-change"
    },
    "shape753": {
      "const": "schedule"
    },
    "shape754": {
      "const": "trigger"
    },
    "shape752": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape753"
        },
        {
          "$ref": "#/$defs/shape754"
        }
      ]
    },
    "shape750": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape751"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape752"
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
    "shape756": {
      "const": "schedule-fire"
    },
    "shape755": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape756"
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
    "shape758": {
      "const": "run-definitions"
    },
    "shape757": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape758"
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
    "shape760": {
      "const": "run-record"
    },
    "shape759": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape760"
        },
        "before": {
          "$ref": "#/$defs/shape28"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "kinds": {
          "$ref": "#/$defs/shape572"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape762": {
      "const": "run-answer"
    },
    "shape764": {
      "type": "object",
      "properties": {
        "chosen": {
          "$ref": "#/$defs/shape197"
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
    "shape763": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape764"
      }
    },
    "shape761": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape762"
        },
        "answer": {
          "$ref": "#/$defs/shape6"
        },
        "choices": {
          "$ref": "#/$defs/shape763"
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
    "shape766": {
      "const": "run-cancel"
    },
    "shape765": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape766"
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
    "shape768": {
      "const": "run-resume"
    },
    "shape767": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape768"
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
    "shape770": {
      "const": "run-trajectory"
    },
    "shape772": {
      "const": "conductor"
    },
    "shape773": {
      "const": "caller"
    },
    "shape771": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape772"
        },
        {
          "$ref": "#/$defs/shape773"
        }
      ]
    },
    "shape769": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape770"
        },
        "actor": {
          "$ref": "#/$defs/shape771"
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
    "shape775": {
      "const": "run-stage-trajectory"
    },
    "shape774": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape775"
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
    "shape777": {
      "const": "delegate-trajectory"
    },
    "shape776": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape777"
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
    "shape780": {
      "const": "board-inspection"
    },
    "shape781": {
      "const": "board-view"
    },
    "shape779": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape780"
        },
        {
          "$ref": "#/$defs/shape781"
        }
      ]
    },
    "shape783": {
      "const": "board"
    },
    "shape784": {
      "const": "swarm"
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
    "shape778": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape779"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape782"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape787": {
      "const": "board-refresh"
    },
    "shape788": {
      "const": "board-more"
    },
    "shape786": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape787"
        },
        {
          "$ref": "#/$defs/shape788"
        }
      ]
    },
    "shape785": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape786"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape790": {
      "const": "board-topic"
    },
    "shape789": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape790"
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
    "shape792": {
      "const": "board-trajectory"
    },
    "shape791": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape792"
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
    "shape794": {
      "const": "context"
    },
    "shape793": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape794"
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
    "shape796": {
      "const": "open-link"
    },
    "shape795": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape796"
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
    "shape798": {
      "const": "copy-text"
    },
    "shape797": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape798"
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
    "shape800": {
      "const": "workflow-start"
    },
    "shape799": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape800"
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
    "shape801": {
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
    "shape803": {
      "const": "cancel"
    },
    "shape802": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape803"
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
    "shape805": {
      "const": "answer"
    },
    "shape806": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape158"
        },
        {
          "$ref": "#/$defs/shape160"
        }
      ]
    },
    "shape804": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape805"
        },
        "decision": {
          "$ref": "#/$defs/shape806"
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
    "shape808": {
      "const": "native-mcp"
    },
    "shape807": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape808"
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
    "shape810": {
      "const": "provider-management"
    },
    "shape811": {
      "const": "provider.connection.list"
    },
    "shape812": {
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
    "shape809": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape811"
        },
        "payload": {
          "$ref": "#/$defs/shape812"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape814": {
      "const": "provider.connection.status"
    },
    "shape815": {
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
    "shape813": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape814"
        },
        "payload": {
          "$ref": "#/$defs/shape815"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape817": {
      "const": "provider.connection.receipt"
    },
    "shape818": {
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
    "shape816": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape817"
        },
        "payload": {
          "$ref": "#/$defs/shape818"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape820": {
      "const": "provider.connection.save"
    },
    "shape824": {
      "const": "Authorization"
    },
    "shape825": {
      "const": "X-API-Key"
    },
    "shape826": {
      "const": "Api-Key"
    },
    "shape823": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
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
      "const": "API_KEY"
    },
    "shape829": {
      "const": "OAUTH_PKCE"
    },
    "shape830": {
      "const": "CLIENT_CREDENTIALS"
    },
    "shape827": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape828"
        },
        {
          "$ref": "#/$defs/shape829"
        },
        {
          "$ref": "#/$defs/shape830"
        }
      ]
    },
    "shape832": {
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
          "$ref": "#/$defs/shape197"
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
    "shape831": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape832"
        }
      ]
    },
    "shape822": {
      "type": "object",
      "properties": {
        "apiKeyHeader": {
          "$ref": "#/$defs/shape823"
        },
        "authentication": {
          "$ref": "#/$defs/shape827"
        },
        "endpoint": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "oauth": {
          "$ref": "#/$defs/shape831"
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
    "shape821": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape822"
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
    "shape819": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape820"
        },
        "payload": {
          "$ref": "#/$defs/shape821"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape834": {
      "const": "provider.connection.change"
    },
    "shape837": {
      "const": "ENABLE"
    },
    "shape838": {
      "const": "DISABLE"
    },
    "shape839": {
      "const": "DELETE"
    },
    "shape836": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape837"
        },
        {
          "$ref": "#/$defs/shape838"
        },
        {
          "$ref": "#/$defs/shape839"
        }
      ]
    },
    "shape835": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape836"
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
    "shape833": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape834"
        },
        "payload": {
          "$ref": "#/$defs/shape835"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape841": {
      "const": "provider.credential.status"
    },
    "shape842": {
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
    "shape840": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape841"
        },
        "payload": {
          "$ref": "#/$defs/shape842"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape844": {
      "const": "provider.credential.put"
    },
    "shape845": {
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
    "shape843": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape844"
        },
        "payload": {
          "$ref": "#/$defs/shape845"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape847": {
      "const": "provider.credential.revoke"
    },
    "shape848": {
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
    "shape846": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape847"
        },
        "payload": {
          "$ref": "#/$defs/shape848"
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
      "const": "provider.credential.rekey"
    },
    "shape849": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape850"
        },
        "payload": {
          "$ref": "#/$defs/shape848"
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
      "const": "provider.credential.authorize"
    },
    "shape851": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape852"
        },
        "payload": {
          "$ref": "#/$defs/shape848"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape854": {
      "const": "provider.credential.refresh"
    },
    "shape853": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape854"
        },
        "payload": {
          "$ref": "#/$defs/shape848"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape856": {
      "const": "tool.scope.host.list"
    },
    "shape857": {
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
    "shape855": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape856"
        },
        "payload": {
          "$ref": "#/$defs/shape857"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape859": {
      "const": "tool.scope.host.status"
    },
    "shape860": {
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
    "shape858": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape859"
        },
        "payload": {
          "$ref": "#/$defs/shape860"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape862": {
      "const": "tool.scope.host.receipt"
    },
    "shape863": {
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
    "shape861": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape862"
        },
        "payload": {
          "$ref": "#/$defs/shape863"
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
      "const": "tool.scope.host.save"
    },
    "shape867": {
      "type": "object",
      "properties": {
        "agents": {
          "$ref": "#/$defs/shape197"
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
          "$ref": "#/$defs/shape197"
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
    "shape866": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape867"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape737"
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
    "shape864": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
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
    "shape869": {
      "const": "tool.scope.host.change"
    },
    "shape870": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape836"
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
    "shape868": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape869"
        },
        "payload": {
          "$ref": "#/$defs/shape870"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape872": {
      "const": "tool.scope.list"
    },
    "shape873": {
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
    "shape871": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        },
        "operation": {
          "$ref": "#/$defs/shape872"
        },
        "payload": {
          "$ref": "#/$defs/shape873"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape875": {
      "const": "provider-management-pending"
    },
    "shape874": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape875"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape877": {
      "const": "provider-management-recover"
    },
    "shape880": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape820"
        },
        {
          "$ref": "#/$defs/shape834"
        }
      ]
    },
    "shape879": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape7"
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
        "connection",
        "expectedRevision",
        "operation",
        "project",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape882": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape844"
        },
        {
          "$ref": "#/$defs/shape847"
        },
        {
          "$ref": "#/$defs/shape850"
        },
        {
          "$ref": "#/$defs/shape852"
        },
        {
          "$ref": "#/$defs/shape854"
        }
      ]
    },
    "shape881": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape882"
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
    "shape884": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape865"
        },
        {
          "$ref": "#/$defs/shape869"
        }
      ]
    },
    "shape883": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape737"
        },
        "operation": {
          "$ref": "#/$defs/shape884"
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
    "shape878": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape879"
        },
        {
          "$ref": "#/$defs/shape881"
        },
        {
          "$ref": "#/$defs/shape883"
        }
      ]
    },
    "shape876": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape877"
        },
        "intent": {
          "$ref": "#/$defs/shape878"
        }
      },
      "required": [
        "action",
        "intent"
      ],
      "additionalProperties": false
    },
    "shape886": {
      "const": "information-import-recovery-save"
    },
    "shape888": {
      "const": "plowshare-information-import-recovery/1"
    },
    "shape887": {
      "type": "object",
      "properties": {
        "context": {
          "$ref": "#/$defs/shape6"
        },
        "format": {
          "$ref": "#/$defs/shape888"
        },
        "plan": {
          "$ref": "#/$defs/shape266"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape181"
        }
      },
      "required": [
        "context",
        "format",
        "plan",
        "requestId",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape885": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape886"
        },
        "recovery": {
          "$ref": "#/$defs/shape887"
        }
      },
      "required": [
        "action",
        "recovery"
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
          "$ref": "#/$defs/shape190"
        },
        {
          "$ref": "#/$defs/shape201"
        },
        {
          "$ref": "#/$defs/shape204"
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
          "$ref": "#/$defs/shape217"
        },
        {
          "$ref": "#/$defs/shape227"
        },
        {
          "$ref": "#/$defs/shape232"
        },
        {
          "$ref": "#/$defs/shape235"
        },
        {
          "$ref": "#/$defs/shape238"
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
          "$ref": "#/$defs/shape338"
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
          "$ref": "#/$defs/shape376"
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
          "$ref": "#/$defs/shape416"
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
          "$ref": "#/$defs/shape458"
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
          "$ref": "#/$defs/shape491"
        },
        {
          "$ref": "#/$defs/shape497"
        },
        {
          "$ref": "#/$defs/shape508"
        },
        {
          "$ref": "#/$defs/shape512"
        },
        {
          "$ref": "#/$defs/shape524"
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
          "$ref": "#/$defs/shape544"
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
          "$ref": "#/$defs/shape555"
        },
        {
          "$ref": "#/$defs/shape560"
        },
        {
          "$ref": "#/$defs/shape563"
        },
        {
          "$ref": "#/$defs/shape566"
        },
        {
          "$ref": "#/$defs/shape573"
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
          "$ref": "#/$defs/shape584"
        },
        {
          "$ref": "#/$defs/shape586"
        },
        {
          "$ref": "#/$defs/shape588"
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
          "$ref": "#/$defs/shape605"
        },
        {
          "$ref": "#/$defs/shape607"
        },
        {
          "$ref": "#/$defs/shape609"
        },
        {
          "$ref": "#/$defs/shape615"
        },
        {
          "$ref": "#/$defs/shape621"
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
          "$ref": "#/$defs/shape637"
        },
        {
          "$ref": "#/$defs/shape639"
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
          "$ref": "#/$defs/shape648"
        },
        {
          "$ref": "#/$defs/shape650"
        },
        {
          "$ref": "#/$defs/shape657"
        },
        {
          "$ref": "#/$defs/shape659"
        },
        {
          "$ref": "#/$defs/shape663"
        },
        {
          "$ref": "#/$defs/shape665"
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
          "$ref": "#/$defs/shape685"
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
          "$ref": "#/$defs/shape700"
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
          "$ref": "#/$defs/shape722"
        },
        {
          "$ref": "#/$defs/shape724"
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
          "$ref": "#/$defs/shape755"
        },
        {
          "$ref": "#/$defs/shape757"
        },
        {
          "$ref": "#/$defs/shape759"
        },
        {
          "$ref": "#/$defs/shape761"
        },
        {
          "$ref": "#/$defs/shape765"
        },
        {
          "$ref": "#/$defs/shape767"
        },
        {
          "$ref": "#/$defs/shape769"
        },
        {
          "$ref": "#/$defs/shape774"
        },
        {
          "$ref": "#/$defs/shape776"
        },
        {
          "$ref": "#/$defs/shape778"
        },
        {
          "$ref": "#/$defs/shape785"
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
          "$ref": "#/$defs/shape799"
        },
        {
          "$ref": "#/$defs/shape801"
        },
        {
          "$ref": "#/$defs/shape802"
        },
        {
          "$ref": "#/$defs/shape804"
        },
        {
          "$ref": "#/$defs/shape807"
        },
        {
          "$ref": "#/$defs/shape809"
        },
        {
          "$ref": "#/$defs/shape813"
        },
        {
          "$ref": "#/$defs/shape816"
        },
        {
          "$ref": "#/$defs/shape819"
        },
        {
          "$ref": "#/$defs/shape833"
        },
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
          "$ref": "#/$defs/shape849"
        },
        {
          "$ref": "#/$defs/shape851"
        },
        {
          "$ref": "#/$defs/shape853"
        },
        {
          "$ref": "#/$defs/shape855"
        },
        {
          "$ref": "#/$defs/shape858"
        },
        {
          "$ref": "#/$defs/shape861"
        },
        {
          "$ref": "#/$defs/shape864"
        },
        {
          "$ref": "#/$defs/shape868"
        },
        {
          "$ref": "#/$defs/shape871"
        },
        {
          "$ref": "#/$defs/shape874"
        },
        {
          "$ref": "#/$defs/shape876"
        },
        {
          "$ref": "#/$defs/shape885"
        }
      ]
    }
  }
}
