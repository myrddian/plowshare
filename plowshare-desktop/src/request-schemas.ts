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
      "const": "types.capabilities"
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
      "const": "types.list"
    },
    "shape256": {
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
      "const": "types.get"
    },
    "shape259": {
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
      "const": "types.validate"
    },
    "shape265": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape264"
      }
    },
    "shape264": {
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
          "$ref": "#/$defs/shape263"
        },
        {
          "$ref": "#/$defs/shape265"
        }
      ]
    },
    "shape263": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape264"
      }
    },
    "shape262": {
      "type": "object",
      "properties": {
        "attachedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "metadata": {
          "$ref": "#/$defs/shape263"
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
    "shape267": {
      "const": "types.version.create"
    },
    "shape270": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape269"
      }
    },
    "shape271": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape30"
        },
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape269"
        }
      ]
    },
    "shape272": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape269"
      }
    },
    "shape273": {
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
    "shape274": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape273"
      }
    },
    "shape276": {
      "const": "string"
    },
    "shape277": {
      "const": "number"
    },
    "shape278": {
      "const": "boolean"
    },
    "shape279": {
      "const": "object"
    },
    "shape280": {
      "const": "array"
    },
    "shape281": {
      "const": "integer"
    },
    "shape282": {
      "const": "null"
    },
    "shape284": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape276"
        },
        {
          "$ref": "#/$defs/shape277"
        },
        {
          "$ref": "#/$defs/shape278"
        },
        {
          "$ref": "#/$defs/shape279"
        },
        {
          "$ref": "#/$defs/shape280"
        },
        {
          "$ref": "#/$defs/shape281"
        },
        {
          "$ref": "#/$defs/shape282"
        }
      ]
    },
    "shape283": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape284"
      }
    },
    "shape275": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape276"
        },
        {
          "$ref": "#/$defs/shape277"
        },
        {
          "$ref": "#/$defs/shape278"
        },
        {
          "$ref": "#/$defs/shape279"
        },
        {
          "$ref": "#/$defs/shape280"
        },
        {
          "$ref": "#/$defs/shape281"
        },
        {
          "$ref": "#/$defs/shape282"
        },
        {
          "$ref": "#/$defs/shape283"
        }
      ]
    },
    "shape269": {
      "type": "object",
      "properties": {
        "$anchor": {
          "$ref": "#/$defs/shape6"
        },
        "$defs": {
          "$ref": "#/$defs/shape270"
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
          "$ref": "#/$defs/shape271"
        },
        "allOf": {
          "$ref": "#/$defs/shape272"
        },
        "anyOf": {
          "$ref": "#/$defs/shape272"
        },
        "const": {
          "$ref": "#/$defs/shape273"
        },
        "contains": {
          "$ref": "#/$defs/shape269"
        },
        "default": {
          "$ref": "#/$defs/shape273"
        },
        "deprecated": {
          "$ref": "#/$defs/shape29"
        },
        "description": {
          "$ref": "#/$defs/shape6"
        },
        "else": {
          "$ref": "#/$defs/shape269"
        },
        "enum": {
          "$ref": "#/$defs/shape274"
        },
        "examples": {
          "$ref": "#/$defs/shape274"
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
          "$ref": "#/$defs/shape269"
        },
        "items": {
          "$ref": "#/$defs/shape269"
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
          "$ref": "#/$defs/shape269"
        },
        "oneOf": {
          "$ref": "#/$defs/shape272"
        },
        "pattern": {
          "$ref": "#/$defs/shape6"
        },
        "patternProperties": {
          "$ref": "#/$defs/shape270"
        },
        "prefixItems": {
          "$ref": "#/$defs/shape272"
        },
        "properties": {
          "$ref": "#/$defs/shape270"
        },
        "readOnly": {
          "$ref": "#/$defs/shape29"
        },
        "required": {
          "$ref": "#/$defs/shape194"
        },
        "then": {
          "$ref": "#/$defs/shape269"
        },
        "title": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape275"
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
    "shape268": {
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
          "$ref": "#/$defs/shape263"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "schema": {
          "$ref": "#/$defs/shape269"
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
    "shape266": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape267"
        },
        "payload": {
          "$ref": "#/$defs/shape268"
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
    "shape286": {
      "const": "types.activate"
    },
    "shape287": {
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
      "const": "types.deprecate"
    },
    "shape290": {
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
      "const": "content.get"
    },
    "shape293": {
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
      "const": "content.revise"
    },
    "shape299": {
      "const": "set"
    },
    "shape298": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape263"
        },
        "mode": {
          "$ref": "#/$defs/shape299"
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
    "shape301": {
      "const": "inherit"
    },
    "shape300": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape263"
        },
        "mode": {
          "$ref": "#/$defs/shape301"
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
    "shape303": {
      "const": "clear"
    },
    "shape302": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape220"
        },
        "mode": {
          "$ref": "#/$defs/shape303"
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
    "shape297": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape298"
        },
        {
          "$ref": "#/$defs/shape300"
        },
        {
          "$ref": "#/$defs/shape302"
        }
      ]
    },
    "shape296": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape297"
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
    "shape305": {
      "const": "upload"
    },
    "shape306": {
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
          "$ref": "#/$defs/shape297"
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
    "shape304": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape305"
        },
        "payload": {
          "$ref": "#/$defs/shape306"
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
    "shape308": {
      "const": "acquire"
    },
    "shape309": {
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
          "$ref": "#/$defs/shape297"
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
    "shape307": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape308"
        },
        "payload": {
          "$ref": "#/$defs/shape309"
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
    "shape311": {
      "const": "refresh"
    },
    "shape312": {
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
          "$ref": "#/$defs/shape297"
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
    "shape310": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape311"
        },
        "payload": {
          "$ref": "#/$defs/shape312"
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
    "shape314": {
      "const": "revise"
    },
    "shape315": {
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
          "$ref": "#/$defs/shape297"
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
    "shape313": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape314"
        },
        "payload": {
          "$ref": "#/$defs/shape315"
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
    "shape317": {
      "const": "replace"
    },
    "shape318": {
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
          "$ref": "#/$defs/shape297"
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
    "shape316": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape317"
        },
        "payload": {
          "$ref": "#/$defs/shape318"
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
    "shape320": {
      "const": "list"
    },
    "shape321": {
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
    "shape319": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape320"
        },
        "payload": {
          "$ref": "#/$defs/shape321"
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
    "shape323": {
      "const": "facets"
    },
    "shape324": {
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
    "shape322": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape323"
        },
        "payload": {
          "$ref": "#/$defs/shape324"
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
    "shape326": {
      "const": "tags"
    },
    "shape327": {
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
    "shape325": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape326"
        },
        "payload": {
          "$ref": "#/$defs/shape327"
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
    "shape329": {
      "const": "tags.groups"
    },
    "shape332": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape194"
      }
    },
    "shape331": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape332"
        }
      ]
    },
    "shape330": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape331"
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
    "shape328": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape329"
        },
        "payload": {
          "$ref": "#/$defs/shape330"
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
      "const": "inventory"
    },
    "shape335": {
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
      "const": "acquisitions"
    },
    "shape338": {
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
      "const": "status"
    },
    "shape341": {
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
      "const": "await"
    },
    "shape347": {
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
    "shape348": {
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
    "shape346": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape347"
        },
        {
          "$ref": "#/$defs/shape348"
        }
      ]
    },
    "shape345": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape346"
      }
    },
    "shape344": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape345"
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
    "shape350": {
      "const": "read"
    },
    "shape351": {
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
    "shape349": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape350"
        },
        "payload": {
          "$ref": "#/$defs/shape351"
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
    "shape353": {
      "const": "outline"
    },
    "shape354": {
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
    "shape352": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape353"
        },
        "payload": {
          "$ref": "#/$defs/shape354"
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
    "shape356": {
      "const": "symbols"
    },
    "shape357": {
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
    "shape355": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape356"
        },
        "payload": {
          "$ref": "#/$defs/shape357"
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
    "shape359": {
      "const": "search"
    },
    "shape360": {
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
    "shape358": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape359"
        },
        "payload": {
          "$ref": "#/$defs/shape360"
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
    "shape362": {
      "const": "rank"
    },
    "shape363": {
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
    "shape361": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape362"
        },
        "payload": {
          "$ref": "#/$defs/shape363"
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
    "shape365": {
      "const": "ask"
    },
    "shape366": {
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
    "shape364": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape365"
        },
        "payload": {
          "$ref": "#/$defs/shape366"
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
    "shape368": {
      "const": "evidence.record"
    },
    "shape369": {
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
    "shape367": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape368"
        },
        "payload": {
          "$ref": "#/$defs/shape369"
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
    "shape371": {
      "const": "evidence.read"
    },
    "shape372": {
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
    "shape370": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape371"
        },
        "payload": {
          "$ref": "#/$defs/shape372"
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
      "const": "record.report"
    },
    "shape379": {
      "const": "holds"
    },
    "shape380": {
      "const": "weakened"
    },
    "shape381": {
      "const": "refuted"
    },
    "shape382": {
      "const": "not_checked"
    },
    "shape378": {
      "anyOf": [
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
        }
      ]
    },
    "shape377": {
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
          "$ref": "#/$defs/shape378"
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
    "shape376": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape377"
      }
    },
    "shape384": {
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
    "shape383": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape384"
      }
    },
    "shape375": {
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
          "$ref": "#/$defs/shape376"
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
          "$ref": "#/$defs/shape383"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape194"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape297"
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
    "shape386": {
      "const": "finalise"
    },
    "shape387": {
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
    "shape389": {
      "const": "link"
    },
    "shape390": {
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
    "shape388": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape389"
        },
        "payload": {
          "$ref": "#/$defs/shape390"
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
    "shape392": {
      "const": "unlink"
    },
    "shape393": {
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
    "shape391": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape392"
        },
        "payload": {
          "$ref": "#/$defs/shape393"
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
    "shape395": {
      "const": "share"
    },
    "shape396": {
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
    "shape394": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape395"
        },
        "payload": {
          "$ref": "#/$defs/shape396"
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
    "shape398": {
      "const": "unshare"
    },
    "shape399": {
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
    "shape397": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape398"
        },
        "payload": {
          "$ref": "#/$defs/shape399"
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
    "shape401": {
      "const": "withdraw"
    },
    "shape402": {
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
    "shape400": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape401"
        },
        "payload": {
          "$ref": "#/$defs/shape402"
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
    "shape404": {
      "const": "exclude"
    },
    "shape405": {
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
    "shape403": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape404"
        },
        "payload": {
          "$ref": "#/$defs/shape405"
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
    "shape407": {
      "const": "unexclude"
    },
    "shape408": {
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
    "shape406": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape407"
        },
        "payload": {
          "$ref": "#/$defs/shape408"
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
    "shape410": {
      "const": "restore"
    },
    "shape411": {
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
    "shape409": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape410"
        },
        "payload": {
          "$ref": "#/$defs/shape411"
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
    "shape413": {
      "const": "delete"
    },
    "shape414": {
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
    "shape412": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape413"
        },
        "payload": {
          "$ref": "#/$defs/shape414"
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
    "shape416": {
      "const": "retry"
    },
    "shape420": {
      "const": "extract"
    },
    "shape421": {
      "const": "derive"
    },
    "shape422": {
      "const": "embed"
    },
    "shape423": {
      "const": "summarise"
    },
    "shape424": {
      "const": "summary_embed"
    },
    "shape425": {
      "const": "autoTag"
    },
    "shape426": {
      "const": "tagGroups"
    },
    "shape419": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape420"
        },
        {
          "$ref": "#/$defs/shape421"
        },
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
        },
        {
          "$ref": "#/$defs/shape426"
        }
      ]
    },
    "shape418": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape419"
      }
    },
    "shape417": {
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
          "$ref": "#/$defs/shape418"
        },
        "view": {
          "$ref": "#/$defs/shape180"
        }
      },
      "additionalProperties": false
    },
    "shape415": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape416"
        },
        "payload": {
          "$ref": "#/$defs/shape417"
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
    "shape428": {
      "const": "rebuild"
    },
    "shape429": {
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
          "$ref": "#/$defs/shape418"
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
    "shape427": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape428"
        },
        "payload": {
          "$ref": "#/$defs/shape429"
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
    "shape431": {
      "const": "allowance"
    },
    "shape432": {
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
    "shape430": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape431"
        },
        "payload": {
          "$ref": "#/$defs/shape432"
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
    "shape434": {
      "const": "events"
    },
    "shape435": {
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
    "shape433": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape434"
        },
        "payload": {
          "$ref": "#/$defs/shape435"
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
    "shape437": {
      "const": "migration.list"
    },
    "shape438": {
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
    "shape436": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape437"
        },
        "payload": {
          "$ref": "#/$defs/shape438"
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
    "shape440": {
      "const": "migration.adopt"
    },
    "shape441": {
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
    "shape439": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape440"
        },
        "payload": {
          "$ref": "#/$defs/shape441"
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
    "shape443": {
      "const": "migration.inspect"
    },
    "shape444": {
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
    "shape442": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape443"
        },
        "payload": {
          "$ref": "#/$defs/shape444"
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
    "shape446": {
      "const": "migration.release"
    },
    "shape447": {
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
    "shape445": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape177"
        },
        "operation": {
          "$ref": "#/$defs/shape446"
        },
        "payload": {
          "$ref": "#/$defs/shape447"
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
      "const": "bootstrap"
    },
    "shape451": {
      "const": "demo"
    },
    "shape452": {
      "const": "disconnect"
    },
    "shape453": {
      "const": "approvals-refresh"
    },
    "shape449": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape311"
        },
        {
          "$ref": "#/$defs/shape450"
        },
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
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape455": {
      "const": "project-access"
    },
    "shape457": {
      "const": "project.access"
    },
    "shape458": {
      "const": "project.member.add"
    },
    "shape459": {
      "const": "project.member.remove"
    },
    "shape460": {
      "const": "project.member.role"
    },
    "shape456": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape457"
        },
        {
          "$ref": "#/$defs/shape458"
        },
        {
          "$ref": "#/$defs/shape459"
        },
        {
          "$ref": "#/$defs/shape460"
        }
      ]
    },
    "shape462": {
      "const": "VIEWER"
    },
    "shape463": {
      "const": "CONTRIBUTOR"
    },
    "shape464": {
      "const": "MANAGER"
    },
    "shape461": {
      "anyOf": [
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
    "shape454": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape455"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape456"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape461"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape466": {
      "const": "server-admin"
    },
    "shape467": {
      "const": "admin.pricing.list"
    },
    "shape468": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape220"
      }
    },
    "shape465": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "operation": {
          "$ref": "#/$defs/shape467"
        },
        "payload": {
          "$ref": "#/$defs/shape468"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape470": {
      "const": "admin.pricing.set"
    },
    "shape473": {
      "const": "TOKEN"
    },
    "shape474": {
      "const": "INCLUDED"
    },
    "shape475": {
      "const": "ZERO_RATE"
    },
    "shape476": {
      "const": "UNPRICED"
    },
    "shape472": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape473"
        },
        {
          "$ref": "#/$defs/shape474"
        },
        {
          "$ref": "#/$defs/shape475"
        },
        {
          "$ref": "#/$defs/shape476"
        }
      ]
    },
    "shape477": {
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
    "shape480": {
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
    "shape479": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape28"
        },
        "rates": {
          "$ref": "#/$defs/shape480"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape478": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape479"
      }
    },
    "shape471": {
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
          "$ref": "#/$defs/shape472"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "rates": {
          "$ref": "#/$defs/shape477"
        },
        "requestFee": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "tiers": {
          "$ref": "#/$defs/shape478"
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
    "shape469": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "operation": {
          "$ref": "#/$defs/shape470"
        },
        "payload": {
          "$ref": "#/$defs/shape471"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape482": {
      "const": "admin.accounts"
    },
    "shape481": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "operation": {
          "$ref": "#/$defs/shape482"
        },
        "payload": {
          "$ref": "#/$defs/shape468"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape484": {
      "const": "admin.account.create"
    },
    "shape485": {
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
    "shape483": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "operation": {
          "$ref": "#/$defs/shape484"
        },
        "payload": {
          "$ref": "#/$defs/shape485"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape487": {
      "const": "admin.account.update"
    },
    "shape488": {
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
    "shape486": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "operation": {
          "$ref": "#/$defs/shape487"
        },
        "payload": {
          "$ref": "#/$defs/shape488"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape490": {
      "const": "admin.account.reset"
    },
    "shape491": {
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
    "shape489": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "operation": {
          "$ref": "#/$defs/shape490"
        },
        "payload": {
          "$ref": "#/$defs/shape491"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape493": {
      "const": "admin.sessions"
    },
    "shape494": {
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
    "shape492": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "operation": {
          "$ref": "#/$defs/shape493"
        },
        "payload": {
          "$ref": "#/$defs/shape494"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape496": {
      "const": "admin.session.revoke"
    },
    "shape497": {
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
    "shape495": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "operation": {
          "$ref": "#/$defs/shape496"
        },
        "payload": {
          "$ref": "#/$defs/shape497"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape499": {
      "const": "admin.audit"
    },
    "shape500": {
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
    "shape498": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "operation": {
          "$ref": "#/$defs/shape499"
        },
        "payload": {
          "$ref": "#/$defs/shape500"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape502": {
      "const": "admin.service.accounts"
    },
    "shape501": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "operation": {
          "$ref": "#/$defs/shape502"
        },
        "payload": {
          "$ref": "#/$defs/shape468"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape504": {
      "const": "admin.service.account.create"
    },
    "shape505": {
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
    "shape503": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "operation": {
          "$ref": "#/$defs/shape504"
        },
        "payload": {
          "$ref": "#/$defs/shape505"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape507": {
      "const": "admin.service.account.update"
    },
    "shape508": {
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
    "shape506": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
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
      "const": "admin.service.tokens"
    },
    "shape511": {
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
    "shape509": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
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
      "const": "admin.service.token.create"
    },
    "shape516": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape461"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape515": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape516"
      }
    },
    "shape514": {
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
          "$ref": "#/$defs/shape515"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape512": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
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
    "shape518": {
      "const": "admin.service.token.rotate"
    },
    "shape519": {
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
    "shape517": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "operation": {
          "$ref": "#/$defs/shape518"
        },
        "payload": {
          "$ref": "#/$defs/shape519"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape521": {
      "const": "admin.service.token.revoke"
    },
    "shape522": {
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
    "shape520": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape466"
        },
        "operation": {
          "$ref": "#/$defs/shape521"
        },
        "payload": {
          "$ref": "#/$defs/shape522"
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
      "const": "server-project-create"
    },
    "shape526": {
      "const": "MANAGED"
    },
    "shape527": {
      "const": "DISJOINT"
    },
    "shape525": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape526"
        },
        {
          "$ref": "#/$defs/shape527"
        }
      ]
    },
    "shape528": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape529": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape523": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape524"
        },
        "applicationRoot": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape525"
        },
        "workspace": {
          "$ref": "#/$defs/shape6"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape528"
        },
        "writePaths": {
          "$ref": "#/$defs/shape529"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape531": {
      "const": "server-setup"
    },
    "shape530": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape531"
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
    "shape533": {
      "const": "connect"
    },
    "shape532": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape533"
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
    "shape535": {
      "const": "connection-select"
    },
    "shape534": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape535"
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
    "shape537": {
      "const": "connection-preferences"
    },
    "shape539": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape540": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape29"
      }
    },
    "shape538": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape539"
        },
        "drafts": {
          "$ref": "#/$defs/shape539"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape540"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape540"
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
    "shape536": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape6"
        },
        "action": {
          "$ref": "#/$defs/shape537"
        },
        "preference": {
          "$ref": "#/$defs/shape538"
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
    "shape542": {
      "const": "connection-rename"
    },
    "shape541": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape542"
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
    "shape544": {
      "const": "connection-remove"
    },
    "shape543": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape544"
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
    "shape546": {
      "const": "personal-section"
    },
    "shape548": {
      "const": "In"
    },
    "shape549": {
      "const": "Out"
    },
    "shape550": {
      "const": "Resources"
    },
    "shape551": {
      "const": "Archive"
    },
    "shape552": {
      "const": "Planning"
    },
    "shape553": {
      "const": "Bots"
    },
    "shape547": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape548"
        },
        {
          "$ref": "#/$defs/shape549"
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
    "shape545": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape546"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "section": {
          "$ref": "#/$defs/shape547"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape555": {
      "const": "personal-bots"
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
      "const": "personal-recreate"
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
    "shape559": {
      "const": "files-choose"
    },
    "shape558": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape559"
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
    "shape561": {
      "const": "files-withdraw"
    },
    "shape560": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape561"
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
    "shape563": {
      "const": "sync-refresh"
    },
    "shape562": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape563"
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
    "shape565": {
      "const": "sync-inspect"
    },
    "shape564": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape565"
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
    "shape567": {
      "const": "sync-change"
    },
    "shape569": {
      "const": "on"
    },
    "shape570": {
      "const": "off"
    },
    "shape571": {
      "const": "now"
    },
    "shape568": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape569"
        },
        {
          "$ref": "#/$defs/shape570"
        },
        {
          "$ref": "#/$defs/shape571"
        }
      ]
    },
    "shape566": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape567"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape568"
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
    "shape573": {
      "const": "sync-resolve"
    },
    "shape575": {
      "const": "mine"
    },
    "shape576": {
      "const": "theirs"
    },
    "shape577": {
      "const": "done"
    },
    "shape574": {
      "anyOf": [
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
    "shape572": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape573"
        },
        "how": {
          "$ref": "#/$defs/shape574"
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
    "shape580": {
      "const": "project-open"
    },
    "shape581": {
      "const": "project-remove"
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
    "shape578": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape579"
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
    "shape584": {
      "const": "create"
    },
    "shape583": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape242"
        },
        {
          "$ref": "#/$defs/shape584"
        }
      ]
    },
    "shape582": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape583"
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
    "shape586": {
      "const": "history"
    },
    "shape585": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape586"
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
    "shape588": {
      "const": "select"
    },
    "shape587": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape588"
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
    "shape590": {
      "const": "trajectory"
    },
    "shape589": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape590"
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
    "shape592": {
      "const": "board-swarm-types"
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
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape594": {
      "const": "board-post-topics"
    },
    "shape593": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape594"
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
    "shape596": {
      "const": "board-create"
    },
    "shape595": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape596"
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
    "shape598": {
      "const": "board-retry"
    },
    "shape597": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape598"
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
    "shape600": {
      "const": "board-post"
    },
    "shape599": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape600"
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
    "shape602": {
      "const": "workspace-chat"
    },
    "shape601": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape602"
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
    "shape604": {
      "const": "workspace-layout"
    },
    "shape603": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape604"
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
    "shape606": {
      "const": "workspace-refresh"
    },
    "shape605": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape606"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape608": {
      "const": "library"
    },
    "shape610": {
      "const": "sources"
    },
    "shape611": {
      "const": "documents"
    },
    "shape612": {
      "const": "memories"
    },
    "shape613": {
      "const": "manual"
    },
    "shape609": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape359"
        },
        {
          "$ref": "#/$defs/shape610"
        },
        {
          "$ref": "#/$defs/shape611"
        },
        {
          "$ref": "#/$defs/shape612"
        },
        {
          "$ref": "#/$defs/shape613"
        }
      ]
    },
    "shape607": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape608"
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
          "$ref": "#/$defs/shape609"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape615": {
      "const": "library-view"
    },
    "shape614": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape615"
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
          "$ref": "#/$defs/shape609"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape618": {
      "const": "library-refresh"
    },
    "shape619": {
      "const": "library-citations"
    },
    "shape617": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape618"
        },
        {
          "$ref": "#/$defs/shape619"
        }
      ]
    },
    "shape616": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape617"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape621": {
      "const": "library-documents"
    },
    "shape620": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape621"
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
    "shape624": {
      "const": "library-document"
    },
    "shape625": {
      "const": "library-memory"
    },
    "shape626": {
      "const": "library-chunk"
    },
    "shape627": {
      "const": "library-conversation"
    },
    "shape623": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape624"
        },
        {
          "$ref": "#/$defs/shape625"
        },
        {
          "$ref": "#/$defs/shape626"
        },
        {
          "$ref": "#/$defs/shape627"
        }
      ]
    },
    "shape622": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape623"
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
    "shape629": {
      "const": "library-source-text"
    },
    "shape628": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape629"
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
    "shape631": {
      "const": "library-stance"
    },
    "shape630": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape631"
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
    "shape633": {
      "const": "library-search"
    },
    "shape635": {
      "const": "retrieve"
    },
    "shape636": {
      "const": "recall"
    },
    "shape637": {
      "const": "navigate"
    },
    "shape634": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape362"
        },
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape611"
        },
        {
          "$ref": "#/$defs/shape635"
        },
        {
          "$ref": "#/$defs/shape636"
        },
        {
          "$ref": "#/$defs/shape637"
        }
      ]
    },
    "shape639": {
      "const": "lexical"
    },
    "shape640": {
      "const": "semantic"
    },
    "shape641": {
      "const": "hybrid"
    },
    "shape638": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape639"
        },
        {
          "$ref": "#/$defs/shape640"
        },
        {
          "$ref": "#/$defs/shape641"
        }
      ]
    },
    "shape632": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape633"
        },
        "kind": {
          "$ref": "#/$defs/shape634"
        },
        "mode": {
          "$ref": "#/$defs/shape638"
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
    "shape643": {
      "const": "library-maintain"
    },
    "shape645": {
      "const": "invalidate"
    },
    "shape646": {
      "const": "resolve"
    },
    "shape647": {
      "const": "reembed"
    },
    "shape648": {
      "const": "reconsider"
    },
    "shape644": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape645"
        },
        {
          "$ref": "#/$defs/shape646"
        },
        {
          "$ref": "#/$defs/shape647"
        },
        {
          "$ref": "#/$defs/shape648"
        }
      ]
    },
    "shape642": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape29"
        },
        "action": {
          "$ref": "#/$defs/shape643"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape644"
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
    "shape650": {
      "const": "builder-outputs"
    },
    "shape649": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape650"
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
    "shape652": {
      "const": "builder-trajectory"
    },
    "shape651": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape652"
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
    "shape654": {
      "const": "builder-prepare"
    },
    "shape653": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape654"
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
    "shape656": {
      "const": "builder-start"
    },
    "shape655": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape656"
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
    "shape658": {
      "const": "activity"
    },
    "shape660": {
      "const": "inbox"
    },
    "shape661": {
      "const": "runs"
    },
    "shape662": {
      "const": "definitions"
    },
    "shape663": {
      "const": "schedules"
    },
    "shape664": {
      "const": "builder"
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
        },
        {
          "$ref": "#/$defs/shape663"
        },
        {
          "$ref": "#/$defs/shape664"
        }
      ]
    },
    "shape657": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape658"
        },
        "view": {
          "$ref": "#/$defs/shape659"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape666": {
      "const": "activity-view"
    },
    "shape665": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape666"
        },
        "view": {
          "$ref": "#/$defs/shape659"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape668": {
      "const": "activity-refresh"
    },
    "shape667": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape668"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape670": {
      "const": "question-refresh"
    },
    "shape669": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape670"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape672": {
      "const": "inbox-read"
    },
    "shape671": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape672"
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
    "shape674": {
      "const": "inbox-more"
    },
    "shape673": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape674"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape676": {
      "const": "run-detail"
    },
    "shape675": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape676"
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
    "shape678": {
      "const": "schedule-refresh"
    },
    "shape677": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape678"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape680": {
      "const": "schedule-preview"
    },
    "shape679": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape680"
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
    "shape682": {
      "const": "schedule-save"
    },
    "shape686": {
      "const": "skill"
    },
    "shape687": {
      "const": "orchestration"
    },
    "shape685": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape89"
        },
        {
          "$ref": "#/$defs/shape686"
        },
        {
          "$ref": "#/$defs/shape687"
        }
      ]
    },
    "shape689": {
      "const": "INHERITED"
    },
    "shape690": {
      "const": "SUMMARISED"
    },
    "shape691": {
      "const": "NEW"
    },
    "shape692": {
      "const": "DIRECT"
    },
    "shape688": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape689"
        },
        {
          "$ref": "#/$defs/shape690"
        },
        {
          "$ref": "#/$defs/shape691"
        },
        {
          "$ref": "#/$defs/shape692"
        }
      ]
    },
    "shape684": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape685"
        },
        "mode": {
          "$ref": "#/$defs/shape688"
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
    "shape694": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape28"
        }
      ]
    },
    "shape693": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape694"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape694"
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
    "shape697": {
      "const": "mailbox"
    },
    "shape698": {
      "const": "message"
    },
    "shape696": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape697"
        },
        {
          "$ref": "#/$defs/shape698"
        }
      ]
    },
    "shape695": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape696"
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
    "shape699": {
      "const": 1
    },
    "shape683": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape684"
        },
        "cron": {
          "$ref": "#/$defs/shape6"
        },
        "limits": {
          "$ref": "#/$defs/shape693"
        },
        "paused": {
          "$ref": "#/$defs/shape29"
        },
        "target": {
          "$ref": "#/$defs/shape695"
        },
        "version": {
          "$ref": "#/$defs/shape699"
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
    "shape701": {
      "const": "server"
    },
    "shape702": {
      "const": "workspace"
    },
    "shape700": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape701"
        },
        {
          "$ref": "#/$defs/shape702"
        }
      ]
    },
    "shape681": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape682"
        },
        "definition": {
          "$ref": "#/$defs/shape683"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape700"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape704": {
      "const": "schedule-file-save"
    },
    "shape703": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape704"
        },
        "definition": {
          "$ref": "#/$defs/shape683"
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
          "$ref": "#/$defs/shape700"
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
    "shape706": {
      "const": "schedule-sync"
    },
    "shape705": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape706"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape700"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape708": {
      "const": "schedule-change"
    },
    "shape710": {
      "const": "schedule"
    },
    "shape711": {
      "const": "trigger"
    },
    "shape709": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape710"
        },
        {
          "$ref": "#/$defs/shape711"
        }
      ]
    },
    "shape707": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape708"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape709"
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
    "shape713": {
      "const": "schedule-fire"
    },
    "shape712": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape713"
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
    "shape715": {
      "const": "run-definitions"
    },
    "shape714": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape715"
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
    "shape717": {
      "const": "run-record"
    },
    "shape716": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape717"
        },
        "before": {
          "$ref": "#/$defs/shape28"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "kinds": {
          "$ref": "#/$defs/shape529"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape719": {
      "const": "run-answer"
    },
    "shape721": {
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
    "shape720": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape721"
      }
    },
    "shape718": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape719"
        },
        "answer": {
          "$ref": "#/$defs/shape6"
        },
        "choices": {
          "$ref": "#/$defs/shape720"
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
    "shape723": {
      "const": "run-cancel"
    },
    "shape722": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape723"
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
    "shape725": {
      "const": "run-resume"
    },
    "shape724": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape725"
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
    "shape727": {
      "const": "run-trajectory"
    },
    "shape729": {
      "const": "conductor"
    },
    "shape730": {
      "const": "caller"
    },
    "shape728": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape729"
        },
        {
          "$ref": "#/$defs/shape730"
        }
      ]
    },
    "shape726": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape727"
        },
        "actor": {
          "$ref": "#/$defs/shape728"
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
    "shape732": {
      "const": "run-stage-trajectory"
    },
    "shape731": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape732"
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
    "shape734": {
      "const": "delegate-trajectory"
    },
    "shape733": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape734"
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
    "shape737": {
      "const": "board-inspection"
    },
    "shape738": {
      "const": "board-view"
    },
    "shape736": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape737"
        },
        {
          "$ref": "#/$defs/shape738"
        }
      ]
    },
    "shape740": {
      "const": "board"
    },
    "shape741": {
      "const": "swarm"
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
    "shape735": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape736"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape739"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape744": {
      "const": "board-refresh"
    },
    "shape745": {
      "const": "board-more"
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
    "shape742": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape743"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape747": {
      "const": "board-topic"
    },
    "shape746": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape747"
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
    "shape749": {
      "const": "board-trajectory"
    },
    "shape748": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape749"
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
    "shape751": {
      "const": "context"
    },
    "shape750": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape751"
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
    "shape753": {
      "const": "open-link"
    },
    "shape752": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape753"
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
    "shape755": {
      "const": "copy-text"
    },
    "shape754": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape755"
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
    "shape757": {
      "const": "workflow-start"
    },
    "shape756": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape757"
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
    "shape758": {
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
    "shape760": {
      "const": "cancel"
    },
    "shape759": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape760"
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
    "shape762": {
      "const": "answer"
    },
    "shape763": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape158"
        },
        {
          "$ref": "#/$defs/shape160"
        }
      ]
    },
    "shape761": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape762"
        },
        "decision": {
          "$ref": "#/$defs/shape763"
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
    "shape765": {
      "const": "native-mcp"
    },
    "shape764": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape765"
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
    "shape767": {
      "const": "provider-management"
    },
    "shape768": {
      "const": "provider.connection.list"
    },
    "shape769": {
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
    "shape766": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape768"
        },
        "payload": {
          "$ref": "#/$defs/shape769"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape771": {
      "const": "provider.connection.status"
    },
    "shape772": {
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
    "shape770": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape771"
        },
        "payload": {
          "$ref": "#/$defs/shape772"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape774": {
      "const": "provider.connection.receipt"
    },
    "shape775": {
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
    "shape773": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape774"
        },
        "payload": {
          "$ref": "#/$defs/shape775"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape777": {
      "const": "provider.connection.save"
    },
    "shape781": {
      "const": "Authorization"
    },
    "shape782": {
      "const": "X-API-Key"
    },
    "shape783": {
      "const": "Api-Key"
    },
    "shape780": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape781"
        },
        {
          "$ref": "#/$defs/shape782"
        },
        {
          "$ref": "#/$defs/shape783"
        }
      ]
    },
    "shape785": {
      "const": "API_KEY"
    },
    "shape786": {
      "const": "OAUTH_PKCE"
    },
    "shape787": {
      "const": "CLIENT_CREDENTIALS"
    },
    "shape784": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape785"
        },
        {
          "$ref": "#/$defs/shape786"
        },
        {
          "$ref": "#/$defs/shape787"
        }
      ]
    },
    "shape789": {
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
    "shape788": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape789"
        }
      ]
    },
    "shape779": {
      "type": "object",
      "properties": {
        "apiKeyHeader": {
          "$ref": "#/$defs/shape780"
        },
        "authentication": {
          "$ref": "#/$defs/shape784"
        },
        "endpoint": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "oauth": {
          "$ref": "#/$defs/shape788"
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
    "shape778": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape779"
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
    "shape776": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape777"
        },
        "payload": {
          "$ref": "#/$defs/shape778"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape791": {
      "const": "provider.connection.change"
    },
    "shape794": {
      "const": "ENABLE"
    },
    "shape795": {
      "const": "DISABLE"
    },
    "shape796": {
      "const": "DELETE"
    },
    "shape793": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape794"
        },
        {
          "$ref": "#/$defs/shape795"
        },
        {
          "$ref": "#/$defs/shape796"
        }
      ]
    },
    "shape792": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape793"
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
    "shape790": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape791"
        },
        "payload": {
          "$ref": "#/$defs/shape792"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape798": {
      "const": "provider.credential.status"
    },
    "shape799": {
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
    "shape797": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape798"
        },
        "payload": {
          "$ref": "#/$defs/shape799"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape801": {
      "const": "provider.credential.put"
    },
    "shape802": {
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
    "shape800": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape801"
        },
        "payload": {
          "$ref": "#/$defs/shape802"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape804": {
      "const": "provider.credential.revoke"
    },
    "shape805": {
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
    "shape803": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape804"
        },
        "payload": {
          "$ref": "#/$defs/shape805"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape807": {
      "const": "provider.credential.rekey"
    },
    "shape806": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape807"
        },
        "payload": {
          "$ref": "#/$defs/shape805"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape809": {
      "const": "provider.credential.authorize"
    },
    "shape808": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape809"
        },
        "payload": {
          "$ref": "#/$defs/shape805"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape811": {
      "const": "provider.credential.refresh"
    },
    "shape810": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape811"
        },
        "payload": {
          "$ref": "#/$defs/shape805"
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
      "const": "tool.scope.host.list"
    },
    "shape814": {
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
    "shape812": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
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
      "const": "tool.scope.host.status"
    },
    "shape817": {
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
    "shape815": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
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
    "shape819": {
      "const": "tool.scope.host.receipt"
    },
    "shape820": {
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
    "shape818": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape819"
        },
        "payload": {
          "$ref": "#/$defs/shape820"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape822": {
      "const": "tool.scope.host.save"
    },
    "shape824": {
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
    "shape823": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape824"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape694"
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
    "shape821": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape822"
        },
        "payload": {
          "$ref": "#/$defs/shape823"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape826": {
      "const": "tool.scope.host.change"
    },
    "shape827": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape793"
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
    "shape825": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape826"
        },
        "payload": {
          "$ref": "#/$defs/shape827"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape829": {
      "const": "tool.scope.list"
    },
    "shape830": {
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
    "shape828": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "operation": {
          "$ref": "#/$defs/shape829"
        },
        "payload": {
          "$ref": "#/$defs/shape830"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape832": {
      "const": "provider-management-pending"
    },
    "shape831": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape832"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape834": {
      "const": "provider-management-recover"
    },
    "shape837": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape777"
        },
        {
          "$ref": "#/$defs/shape791"
        }
      ]
    },
    "shape836": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape837"
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
    "shape839": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape801"
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
          "$ref": "#/$defs/shape811"
        }
      ]
    },
    "shape838": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape839"
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
    "shape841": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape822"
        },
        {
          "$ref": "#/$defs/shape826"
        }
      ]
    },
    "shape840": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape694"
        },
        "operation": {
          "$ref": "#/$defs/shape841"
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
    "shape835": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape836"
        },
        {
          "$ref": "#/$defs/shape838"
        },
        {
          "$ref": "#/$defs/shape840"
        }
      ]
    },
    "shape833": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
        },
        "intent": {
          "$ref": "#/$defs/shape835"
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
          "$ref": "#/$defs/shape266"
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
          "$ref": "#/$defs/shape304"
        },
        {
          "$ref": "#/$defs/shape307"
        },
        {
          "$ref": "#/$defs/shape310"
        },
        {
          "$ref": "#/$defs/shape313"
        },
        {
          "$ref": "#/$defs/shape316"
        },
        {
          "$ref": "#/$defs/shape319"
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
          "$ref": "#/$defs/shape370"
        },
        {
          "$ref": "#/$defs/shape373"
        },
        {
          "$ref": "#/$defs/shape385"
        },
        {
          "$ref": "#/$defs/shape388"
        },
        {
          "$ref": "#/$defs/shape391"
        },
        {
          "$ref": "#/$defs/shape394"
        },
        {
          "$ref": "#/$defs/shape397"
        },
        {
          "$ref": "#/$defs/shape400"
        },
        {
          "$ref": "#/$defs/shape403"
        },
        {
          "$ref": "#/$defs/shape406"
        },
        {
          "$ref": "#/$defs/shape409"
        },
        {
          "$ref": "#/$defs/shape412"
        },
        {
          "$ref": "#/$defs/shape415"
        },
        {
          "$ref": "#/$defs/shape427"
        },
        {
          "$ref": "#/$defs/shape430"
        },
        {
          "$ref": "#/$defs/shape433"
        },
        {
          "$ref": "#/$defs/shape436"
        },
        {
          "$ref": "#/$defs/shape439"
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
          "$ref": "#/$defs/shape454"
        },
        {
          "$ref": "#/$defs/shape465"
        },
        {
          "$ref": "#/$defs/shape469"
        },
        {
          "$ref": "#/$defs/shape481"
        },
        {
          "$ref": "#/$defs/shape483"
        },
        {
          "$ref": "#/$defs/shape486"
        },
        {
          "$ref": "#/$defs/shape489"
        },
        {
          "$ref": "#/$defs/shape492"
        },
        {
          "$ref": "#/$defs/shape495"
        },
        {
          "$ref": "#/$defs/shape498"
        },
        {
          "$ref": "#/$defs/shape501"
        },
        {
          "$ref": "#/$defs/shape503"
        },
        {
          "$ref": "#/$defs/shape506"
        },
        {
          "$ref": "#/$defs/shape509"
        },
        {
          "$ref": "#/$defs/shape512"
        },
        {
          "$ref": "#/$defs/shape517"
        },
        {
          "$ref": "#/$defs/shape520"
        },
        {
          "$ref": "#/$defs/shape523"
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
          "$ref": "#/$defs/shape541"
        },
        {
          "$ref": "#/$defs/shape543"
        },
        {
          "$ref": "#/$defs/shape545"
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
          "$ref": "#/$defs/shape560"
        },
        {
          "$ref": "#/$defs/shape562"
        },
        {
          "$ref": "#/$defs/shape564"
        },
        {
          "$ref": "#/$defs/shape566"
        },
        {
          "$ref": "#/$defs/shape572"
        },
        {
          "$ref": "#/$defs/shape578"
        },
        {
          "$ref": "#/$defs/shape582"
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
          "$ref": "#/$defs/shape614"
        },
        {
          "$ref": "#/$defs/shape616"
        },
        {
          "$ref": "#/$defs/shape620"
        },
        {
          "$ref": "#/$defs/shape622"
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
          "$ref": "#/$defs/shape642"
        },
        {
          "$ref": "#/$defs/shape649"
        },
        {
          "$ref": "#/$defs/shape651"
        },
        {
          "$ref": "#/$defs/shape653"
        },
        {
          "$ref": "#/$defs/shape655"
        },
        {
          "$ref": "#/$defs/shape657"
        },
        {
          "$ref": "#/$defs/shape665"
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
          "$ref": "#/$defs/shape673"
        },
        {
          "$ref": "#/$defs/shape675"
        },
        {
          "$ref": "#/$defs/shape677"
        },
        {
          "$ref": "#/$defs/shape679"
        },
        {
          "$ref": "#/$defs/shape681"
        },
        {
          "$ref": "#/$defs/shape703"
        },
        {
          "$ref": "#/$defs/shape705"
        },
        {
          "$ref": "#/$defs/shape707"
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
          "$ref": "#/$defs/shape722"
        },
        {
          "$ref": "#/$defs/shape724"
        },
        {
          "$ref": "#/$defs/shape726"
        },
        {
          "$ref": "#/$defs/shape731"
        },
        {
          "$ref": "#/$defs/shape733"
        },
        {
          "$ref": "#/$defs/shape735"
        },
        {
          "$ref": "#/$defs/shape742"
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
          "$ref": "#/$defs/shape754"
        },
        {
          "$ref": "#/$defs/shape756"
        },
        {
          "$ref": "#/$defs/shape758"
        },
        {
          "$ref": "#/$defs/shape759"
        },
        {
          "$ref": "#/$defs/shape761"
        },
        {
          "$ref": "#/$defs/shape764"
        },
        {
          "$ref": "#/$defs/shape766"
        },
        {
          "$ref": "#/$defs/shape770"
        },
        {
          "$ref": "#/$defs/shape773"
        },
        {
          "$ref": "#/$defs/shape776"
        },
        {
          "$ref": "#/$defs/shape790"
        },
        {
          "$ref": "#/$defs/shape797"
        },
        {
          "$ref": "#/$defs/shape800"
        },
        {
          "$ref": "#/$defs/shape803"
        },
        {
          "$ref": "#/$defs/shape806"
        },
        {
          "$ref": "#/$defs/shape808"
        },
        {
          "$ref": "#/$defs/shape810"
        },
        {
          "$ref": "#/$defs/shape812"
        },
        {
          "$ref": "#/$defs/shape815"
        },
        {
          "$ref": "#/$defs/shape818"
        },
        {
          "$ref": "#/$defs/shape821"
        },
        {
          "$ref": "#/$defs/shape825"
        },
        {
          "$ref": "#/$defs/shape828"
        },
        {
          "$ref": "#/$defs/shape831"
        },
        {
          "$ref": "#/$defs/shape833"
        }
      ]
    }
  }
}
