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
      "const": "proc"
    },
    "shape55": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape56"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape58": {
      "const": "proc-read"
    },
    "shape61": {
      "const": "mine"
    },
    "shape62": {
      "const": "server"
    },
    "shape60": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape61"
        },
        {
          "$ref": "#/$defs/shape62"
        }
      ]
    },
    "shape59": {
      "type": "object",
      "properties": {
        "active": {
          "$ref": "#/$defs/shape29"
        },
        "after": {
          "$ref": "#/$defs/shape6"
        },
        "causation": {
          "$ref": "#/$defs/shape6"
        },
        "conversation": {
          "$ref": "#/$defs/shape6"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "root": {
          "$ref": "#/$defs/shape6"
        },
        "visibility": {
          "$ref": "#/$defs/shape60"
        }
      },
      "additionalProperties": false
    },
    "shape63": {
      "const": "proc.list"
    },
    "shape57": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape58"
        },
        "payload": {
          "$ref": "#/$defs/shape59"
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
    "shape65": {
      "const": "proc.inspect"
    },
    "shape64": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape58"
        },
        "payload": {
          "$ref": "#/$defs/shape59"
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
    "shape67": {
      "const": "proc.calls"
    },
    "shape66": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape58"
        },
        "payload": {
          "$ref": "#/$defs/shape59"
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
    "shape69": {
      "const": "proc.queues"
    },
    "shape68": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape58"
        },
        "payload": {
          "$ref": "#/$defs/shape59"
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
      "const": "proc.tools"
    },
    "shape70": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape58"
        },
        "payload": {
          "$ref": "#/$defs/shape59"
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
    "shape73": {
      "const": "proc-trajectory"
    },
    "shape72": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape73"
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
    "shape75": {
      "const": "context-snapshot"
    },
    "shape74": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape75"
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
    "shape77": {
      "const": "relay"
    },
    "shape76": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape77"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape79": {
      "const": "relay-read"
    },
    "shape81": {
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
    "shape82": {
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
    "shape80": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape81"
        },
        {
          "$ref": "#/$defs/shape82"
        }
      ]
    },
    "shape83": {
      "const": "relay.topics"
    },
    "shape78": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape79"
        },
        "payload": {
          "$ref": "#/$defs/shape80"
        },
        "type": {
          "$ref": "#/$defs/shape83"
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
    "shape87": {
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
    "shape85": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape86"
        },
        {
          "$ref": "#/$defs/shape87"
        }
      ]
    },
    "shape88": {
      "const": "relay.log"
    },
    "shape84": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape79"
        },
        "payload": {
          "$ref": "#/$defs/shape85"
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
      "const": "relay-operate"
    },
    "shape93": {
      "const": "ACKNOWLEDGE_GAP"
    },
    "shape94": {
      "const": "RECONCILE"
    },
    "shape95": {
      "const": "ABANDON"
    },
    "shape96": {
      "const": "REMOVE_SUBSCRIPTION"
    },
    "shape97": {
      "const": "REMOVE_TOPIC"
    },
    "shape92": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape93"
        },
        {
          "$ref": "#/$defs/shape94"
        },
        {
          "$ref": "#/$defs/shape95"
        },
        {
          "$ref": "#/$defs/shape96"
        },
        {
          "$ref": "#/$defs/shape97"
        }
      ]
    },
    "shape91": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape92"
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
    "shape89": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape90"
        },
        "payload": {
          "$ref": "#/$defs/shape91"
        }
      },
      "required": [
        "action",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape99": {
      "const": "relay-trajectory"
    },
    "shape98": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape99"
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
    "shape101": {
      "const": "usage-open"
    },
    "shape105": {
      "const": "day"
    },
    "shape106": {
      "const": "model"
    },
    "shape107": {
      "const": "pool"
    },
    "shape108": {
      "const": "agent"
    },
    "shape109": {
      "const": "operation"
    },
    "shape110": {
      "const": "project"
    },
    "shape111": {
      "const": "run"
    },
    "shape104": {
      "anyOf": [
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
        }
      ]
    },
    "shape103": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape104"
      }
    },
    "shape113": {
      "const": "direct"
    },
    "shape114": {
      "const": "subtree"
    },
    "shape112": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape113"
        },
        {
          "$ref": "#/$defs/shape114"
        }
      ]
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
          "$ref": "#/$defs/shape103"
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
          "$ref": "#/$defs/shape112"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape116": {
      "const": "usage.conversation"
    },
    "shape117": {
      "const": "usage.project"
    },
    "shape118": {
      "const": "usage.agent"
    },
    "shape119": {
      "const": "usage.run"
    },
    "shape120": {
      "const": "usage.orchestration"
    },
    "shape121": {
      "const": "usage.models"
    },
    "shape122": {
      "const": "usage.pools"
    },
    "shape115": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape122"
        }
      ]
    },
    "shape100": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape101"
        },
        "filter": {
          "$ref": "#/$defs/shape102"
        },
        "type": {
          "$ref": "#/$defs/shape115"
        }
      },
      "required": [
        "action",
        "filter",
        "type"
      ],
      "additionalProperties": false
    },
    "shape124": {
      "const": "usage-read"
    },
    "shape125": {
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
          "$ref": "#/$defs/shape103"
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
          "$ref": "#/$defs/shape112"
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
    "shape123": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape124"
        },
        "payload": {
          "$ref": "#/$defs/shape125"
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
    "shape127": {
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
          "$ref": "#/$defs/shape103"
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
          "$ref": "#/$defs/shape112"
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
    "shape126": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape124"
        },
        "payload": {
          "$ref": "#/$defs/shape127"
        },
        "type": {
          "$ref": "#/$defs/shape117"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape129": {
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
          "$ref": "#/$defs/shape103"
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
          "$ref": "#/$defs/shape112"
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
    "shape128": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape124"
        },
        "payload": {
          "$ref": "#/$defs/shape129"
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
    "shape131": {
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
          "$ref": "#/$defs/shape103"
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
          "$ref": "#/$defs/shape112"
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
    "shape130": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape124"
        },
        "payload": {
          "$ref": "#/$defs/shape131"
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
    "shape133": {
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
          "$ref": "#/$defs/shape103"
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
          "$ref": "#/$defs/shape112"
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
    "shape132": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape124"
        },
        "payload": {
          "$ref": "#/$defs/shape133"
        },
        "type": {
          "$ref": "#/$defs/shape120"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape134": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape124"
        },
        "payload": {
          "$ref": "#/$defs/shape102"
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
    "shape135": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape124"
        },
        "payload": {
          "$ref": "#/$defs/shape102"
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
    "shape137": {
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
          "$ref": "#/$defs/shape103"
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
          "$ref": "#/$defs/shape112"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape138": {
      "const": "usage.calls"
    },
    "shape136": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape124"
        },
        "payload": {
          "$ref": "#/$defs/shape137"
        },
        "type": {
          "$ref": "#/$defs/shape138"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape140": {
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
          "$ref": "#/$defs/shape103"
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
          "$ref": "#/$defs/shape115"
        },
        "route": {
          "$ref": "#/$defs/shape6"
        },
        "run": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape112"
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
    "shape141": {
      "const": "usage.subscribe"
    },
    "shape139": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape124"
        },
        "payload": {
          "$ref": "#/$defs/shape140"
        },
        "type": {
          "$ref": "#/$defs/shape141"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape143": {
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
    "shape144": {
      "const": "usage.unsubscribe"
    },
    "shape142": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape124"
        },
        "payload": {
          "$ref": "#/$defs/shape143"
        },
        "type": {
          "$ref": "#/$defs/shape144"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape146": {
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
    "shape147": {
      "const": "conversation.context.count"
    },
    "shape145": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape124"
        },
        "payload": {
          "$ref": "#/$defs/shape146"
        },
        "type": {
          "$ref": "#/$defs/shape147"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape149": {
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
    "shape150": {
      "const": "conversation.context.snapshot"
    },
    "shape148": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape124"
        },
        "payload": {
          "$ref": "#/$defs/shape149"
        },
        "type": {
          "$ref": "#/$defs/shape150"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape152": {
      "const": "usage-close"
    },
    "shape151": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape152"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape154": {
      "const": "operator-prepare"
    },
    "shape156": {
      "const": "memory-write"
    },
    "shape157": {
      "const": "memory-digest"
    },
    "shape158": {
      "const": "agent-curate"
    },
    "shape159": {
      "const": "conversation-lifecycle"
    },
    "shape160": {
      "const": "conversation-resume"
    },
    "shape161": {
      "const": "job-limits"
    },
    "shape162": {
      "const": "approval-grant"
    },
    "shape163": {
      "const": "approval-revoke"
    },
    "shape164": {
      "const": "board-topup"
    },
    "shape165": {
      "const": "message-deliveries"
    },
    "shape166": {
      "const": "message-open"
    },
    "shape167": {
      "const": "message-default"
    },
    "shape168": {
      "const": "message-stop"
    },
    "shape169": {
      "const": "message-archive"
    },
    "shape170": {
      "const": "caps"
    },
    "shape155": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape156"
        },
        {
          "$ref": "#/$defs/shape157"
        },
        {
          "$ref": "#/$defs/shape158"
        },
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape160"
        },
        {
          "$ref": "#/$defs/shape161"
        },
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
        },
        {
          "$ref": "#/$defs/shape168"
        },
        {
          "$ref": "#/$defs/shape169"
        },
        {
          "$ref": "#/$defs/shape170"
        }
      ]
    },
    "shape153": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape154"
        },
        "kind": {
          "$ref": "#/$defs/shape155"
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
    "shape172": {
      "const": "operator-messages"
    },
    "shape171": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape172"
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
    "shape174": {
      "const": "operator-preview"
    },
    "shape177": {
      "const": "once"
    },
    "shape178": {
      "const": "conversation"
    },
    "shape179": {
      "const": "deny"
    },
    "shape176": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape110"
        },
        {
          "$ref": "#/$defs/shape177"
        },
        {
          "$ref": "#/$defs/shape178"
        },
        {
          "$ref": "#/$defs/shape179"
        }
      ]
    },
    "shape181": {
      "const": "steps"
    },
    "shape182": {
      "const": "budget"
    },
    "shape183": {
      "const": "auto-continue"
    },
    "shape184": {
      "const": "time"
    },
    "shape185": {
      "const": "failed-checks"
    },
    "shape186": {
      "const": "auto-increase"
    },
    "shape180": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape181"
        },
        {
          "$ref": "#/$defs/shape182"
        },
        {
          "$ref": "#/$defs/shape183"
        },
        {
          "$ref": "#/$defs/shape184"
        },
        {
          "$ref": "#/$defs/shape185"
        },
        {
          "$ref": "#/$defs/shape186"
        }
      ]
    },
    "shape188": {
      "const": "active"
    },
    "shape189": {
      "const": "archived"
    },
    "shape187": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape188"
        },
        {
          "$ref": "#/$defs/shape189"
        }
      ]
    },
    "shape191": {
      "const": "true"
    },
    "shape192": {
      "const": "false"
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
    "shape175": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "body": {
          "$ref": "#/$defs/shape6"
        },
        "decision": {
          "$ref": "#/$defs/shape176"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "key": {
          "$ref": "#/$defs/shape180"
        },
        "lifecycle": {
          "$ref": "#/$defs/shape187"
        },
        "makeDefault": {
          "$ref": "#/$defs/shape190"
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
    "shape173": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape174"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape175"
        }
      },
      "required": [
        "action",
        "identity",
        "input"
      ],
      "additionalProperties": false
    },
    "shape194": {
      "const": "operator-apply"
    },
    "shape193": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape194"
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
    "shape196": {
      "const": "information"
    },
    "shape197": {
      "const": "navigation.capabilities"
    },
    "shape199": {
      "const": "content-types-v1"
    },
    "shape198": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape202": {
      "const": "personal"
    },
    "shape201": {
      "type": "object",
      "properties": {
        "includeGlobal": {
          "$ref": "#/$defs/shape29"
        },
        "kind": {
          "$ref": "#/$defs/shape202"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape204": {
      "const": "global_read"
    },
    "shape203": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape204"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape205": {
      "type": "object",
      "properties": {
        "includeGlobal": {
          "$ref": "#/$defs/shape29"
        },
        "kind": {
          "$ref": "#/$defs/shape110"
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
    "shape207": {
      "const": "00000000-0000-0000-0000-000000000004"
    },
    "shape208": {
      "const": "account"
    },
    "shape206": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape207"
        },
        "kind": {
          "$ref": "#/$defs/shape208"
        }
      },
      "required": [
        "account",
        "kind"
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
        },
        {
          "$ref": "#/$defs/shape205"
        },
        {
          "$ref": "#/$defs/shape206"
        }
      ]
    },
    "shape195": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape197"
        },
        "payload": {
          "$ref": "#/$defs/shape198"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "documents.list"
    },
    "shape213": {
      "const": "outgoing"
    },
    "shape214": {
      "const": "backlinks"
    },
    "shape212": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape213"
        },
        {
          "$ref": "#/$defs/shape214"
        }
      ]
    },
    "shape216": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape218": {
      "const": "source"
    },
    "shape219": {
      "const": "report"
    },
    "shape217": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape218"
        },
        {
          "$ref": "#/$defs/shape219"
        }
      ]
    },
    "shape215": {
      "type": "object",
      "properties": {
        "author": {
          "$ref": "#/$defs/shape6"
        },
        "autoTag": {
          "$ref": "#/$defs/shape216"
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
          "$ref": "#/$defs/shape217"
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
          "$ref": "#/$defs/shape216"
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
    "shape211": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape212"
        },
        "filter": {
          "$ref": "#/$defs/shape215"
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
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape209": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape210"
        },
        "payload": {
          "$ref": "#/$defs/shape211"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "documents.facets"
    },
    "shape222": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape212"
        },
        "filter": {
          "$ref": "#/$defs/shape215"
        },
        "relatedTo": {
          "$ref": "#/$defs/shape6"
        },
        "relation": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape220": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape221"
        },
        "payload": {
          "$ref": "#/$defs/shape222"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "documents.get"
    },
    "shape226": {
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
    "shape225": {
      "type": "object",
      "properties": {
        "target": {
          "$ref": "#/$defs/shape226"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "target"
      ],
      "additionalProperties": false
    },
    "shape223": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape224"
        },
        "payload": {
          "$ref": "#/$defs/shape225"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "relationships.list"
    },
    "shape229": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape212"
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
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "direction",
        "resource"
      ],
      "additionalProperties": false
    },
    "shape227": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape228"
        },
        "payload": {
          "$ref": "#/$defs/shape229"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "relationships.create"
    },
    "shape232": {
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
          "$ref": "#/$defs/shape226"
        },
        "view": {
          "$ref": "#/$defs/shape199"
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
    "shape230": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape231"
        },
        "payload": {
          "$ref": "#/$defs/shape232"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "relationships.remove"
    },
    "shape235": {
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
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "expectedVersion",
        "relationship",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape233": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape234"
        },
        "payload": {
          "$ref": "#/$defs/shape235"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "policy.get"
    },
    "shape241": {
      "const": "platform"
    },
    "shape242": {
      "forbidden": true
    },
    "shape240": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape241"
        },
        "project": {
          "$ref": "#/$defs/shape242"
        },
        "resource": {
          "$ref": "#/$defs/shape242"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape243": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape110"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "resource": {
          "$ref": "#/$defs/shape242"
        }
      },
      "required": [
        "kind",
        "project"
      ],
      "additionalProperties": false
    },
    "shape245": {
      "const": "document"
    },
    "shape244": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape245"
        },
        "project": {
          "$ref": "#/$defs/shape242"
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
    "shape239": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape240"
        },
        {
          "$ref": "#/$defs/shape243"
        },
        {
          "$ref": "#/$defs/shape244"
        }
      ]
    },
    "shape238": {
      "type": "object",
      "properties": {
        "target": {
          "$ref": "#/$defs/shape239"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "target"
      ],
      "additionalProperties": false
    },
    "shape236": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape237"
        },
        "payload": {
          "$ref": "#/$defs/shape238"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "policy.set"
    },
    "shape250": {
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
    "shape249": {
      "type": "object",
      "properties": {
        "allowOverrides": {
          "$ref": "#/$defs/shape29"
        },
        "defaults": {
          "$ref": "#/$defs/shape250"
        },
        "mandatory": {
          "$ref": "#/$defs/shape250"
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
    "shape248": {
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
          "$ref": "#/$defs/shape249"
        },
        "target": {
          "$ref": "#/$defs/shape239"
        },
        "view": {
          "$ref": "#/$defs/shape199"
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
    "shape246": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape247"
        },
        "payload": {
          "$ref": "#/$defs/shape248"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "views.list"
    },
    "shape253": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape251": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape252"
        },
        "payload": {
          "$ref": "#/$defs/shape253"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "views.get"
    },
    "shape256": {
      "type": "object",
      "properties": {
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "id"
      ],
      "additionalProperties": false
    },
    "shape254": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape255"
        },
        "payload": {
          "$ref": "#/$defs/shape256"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "views.save"
    },
    "shape264": {
      "const": "scope"
    },
    "shape263": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape264"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "kind",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape265": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape245"
        },
        "target": {
          "$ref": "#/$defs/shape226"
        }
      },
      "required": [
        "kind",
        "target"
      ],
      "additionalProperties": false
    },
    "shape267": {
      "const": "traverse"
    },
    "shape266": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape212"
        },
        "kind": {
          "$ref": "#/$defs/shape267"
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
    "shape269": {
      "const": "filter"
    },
    "shape268": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape215"
        },
        "kind": {
          "$ref": "#/$defs/shape269"
        }
      },
      "required": [
        "filter",
        "kind"
      ],
      "additionalProperties": false
    },
    "shape262": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape263"
        },
        {
          "$ref": "#/$defs/shape265"
        },
        {
          "$ref": "#/$defs/shape266"
        },
        {
          "$ref": "#/$defs/shape268"
        }
      ]
    },
    "shape261": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape262"
      }
    },
    "shape260": {
      "type": "object",
      "properties": {
        "steps": {
          "$ref": "#/$defs/shape261"
        }
      },
      "required": [
        "steps"
      ],
      "additionalProperties": false
    },
    "shape259": {
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
          "$ref": "#/$defs/shape260"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
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
    "shape257": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape258"
        },
        "payload": {
          "$ref": "#/$defs/shape259"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "views.delete"
    },
    "shape272": {
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
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "expectedVersion",
        "id",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape270": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape271"
        },
        "payload": {
          "$ref": "#/$defs/shape272"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "interchange.capabilities"
    },
    "shape275": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape273": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape274"
        },
        "payload": {
          "$ref": "#/$defs/shape275"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "interchange.export"
    },
    "shape278": {
      "type": "object",
      "properties": {
        "revisions": {
          "$ref": "#/$defs/shape216"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "revisions"
      ],
      "additionalProperties": false
    },
    "shape276": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape277"
        },
        "payload": {
          "$ref": "#/$defs/shape278"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "interchange.readable"
    },
    "shape281": {
      "type": "object",
      "properties": {
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape279": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape280"
        },
        "payload": {
          "$ref": "#/$defs/shape281"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "interchange.import"
    },
    "shape294": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape293"
      }
    },
    "shape293": {
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
          "$ref": "#/$defs/shape292"
        },
        {
          "$ref": "#/$defs/shape294"
        }
      ]
    },
    "shape292": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape293"
      }
    },
    "shape295": {
      "const": "plowshare-document-metadata/1"
    },
    "shape297": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape296"
      }
    },
    "shape298": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape30"
        },
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape296"
        }
      ]
    },
    "shape299": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape296"
      }
    },
    "shape300": {
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
    "shape301": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape300"
      }
    },
    "shape303": {
      "const": "string"
    },
    "shape304": {
      "const": "number"
    },
    "shape305": {
      "const": "boolean"
    },
    "shape306": {
      "const": "object"
    },
    "shape307": {
      "const": "array"
    },
    "shape308": {
      "const": "integer"
    },
    "shape309": {
      "const": "null"
    },
    "shape311": {
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
        },
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
    "shape310": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape311"
      }
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
        },
        {
          "$ref": "#/$defs/shape307"
        },
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
    "shape296": {
      "type": "object",
      "properties": {
        "$anchor": {
          "$ref": "#/$defs/shape6"
        },
        "$defs": {
          "$ref": "#/$defs/shape297"
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
          "$ref": "#/$defs/shape298"
        },
        "allOf": {
          "$ref": "#/$defs/shape299"
        },
        "anyOf": {
          "$ref": "#/$defs/shape299"
        },
        "const": {
          "$ref": "#/$defs/shape300"
        },
        "contains": {
          "$ref": "#/$defs/shape296"
        },
        "default": {
          "$ref": "#/$defs/shape300"
        },
        "deprecated": {
          "$ref": "#/$defs/shape29"
        },
        "description": {
          "$ref": "#/$defs/shape6"
        },
        "else": {
          "$ref": "#/$defs/shape296"
        },
        "enum": {
          "$ref": "#/$defs/shape301"
        },
        "examples": {
          "$ref": "#/$defs/shape301"
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
          "$ref": "#/$defs/shape296"
        },
        "items": {
          "$ref": "#/$defs/shape296"
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
          "$ref": "#/$defs/shape296"
        },
        "oneOf": {
          "$ref": "#/$defs/shape299"
        },
        "pattern": {
          "$ref": "#/$defs/shape6"
        },
        "patternProperties": {
          "$ref": "#/$defs/shape297"
        },
        "prefixItems": {
          "$ref": "#/$defs/shape299"
        },
        "properties": {
          "$ref": "#/$defs/shape297"
        },
        "readOnly": {
          "$ref": "#/$defs/shape29"
        },
        "required": {
          "$ref": "#/$defs/shape216"
        },
        "then": {
          "$ref": "#/$defs/shape296"
        },
        "title": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape302"
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
    "shape291": {
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
          "$ref": "#/$defs/shape292"
        },
        "profile": {
          "$ref": "#/$defs/shape295"
        },
        "schema": {
          "$ref": "#/$defs/shape296"
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
    "shape290": {
      "type": "object",
      "properties": {
        "contract": {
          "$ref": "#/$defs/shape291"
        },
        "metadata": {
          "$ref": "#/$defs/shape292"
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
    "shape314": {
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
    "shape313": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape314"
      }
    },
    "shape315": {
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
    "shape312": {
      "type": "object",
      "properties": {
        "createdAt": {
          "$ref": "#/$defs/shape6"
        },
        "dependencies": {
          "$ref": "#/$defs/shape216"
        },
        "evidence": {
          "$ref": "#/$defs/shape313"
        },
        "importedFrom": {
          "$ref": "#/$defs/shape315"
        },
        "kind": {
          "$ref": "#/$defs/shape217"
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
    "shape289": {
      "type": "object",
      "properties": {
        "contentHash": {
          "$ref": "#/$defs/shape6"
        },
        "contentType": {
          "$ref": "#/$defs/shape290"
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
          "$ref": "#/$defs/shape312"
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
          "$ref": "#/$defs/shape216"
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
    "shape288": {
      "type": "object",
      "properties": {
        "bytesBase64": {
          "$ref": "#/$defs/shape6"
        },
        "descriptor": {
          "$ref": "#/$defs/shape289"
        }
      },
      "required": [
        "bytesBase64",
        "descriptor"
      ],
      "additionalProperties": false
    },
    "shape287": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape288"
      }
    },
    "shape316": {
      "const": "plowshare-information-bundle/1"
    },
    "shape317": {
      "const": "included-endpoints-only"
    },
    "shape319": {
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
    "shape318": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape319"
      }
    },
    "shape286": {
      "type": "object",
      "properties": {
        "documents": {
          "$ref": "#/$defs/shape287"
        },
        "format": {
          "$ref": "#/$defs/shape316"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "relationshipCoverage": {
          "$ref": "#/$defs/shape317"
        },
        "relationships": {
          "$ref": "#/$defs/shape318"
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
    "shape323": {
      "const": "create"
    },
    "shape322": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape242"
        },
        "mode": {
          "$ref": "#/$defs/shape323"
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
    "shape325": {
      "const": "revise"
    },
    "shape324": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "mode": {
          "$ref": "#/$defs/shape325"
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
    "shape321": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape322"
        },
        {
          "$ref": "#/$defs/shape324"
        }
      ]
    },
    "shape320": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape321"
      }
    },
    "shape327": {
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
    "shape326": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape327"
      }
    },
    "shape329": {
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
    "shape328": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape329"
      }
    },
    "shape285": {
      "type": "object",
      "properties": {
        "bundle": {
          "$ref": "#/$defs/shape286"
        },
        "destinations": {
          "$ref": "#/$defs/shape320"
        },
        "restoreRelationships": {
          "$ref": "#/$defs/shape29"
        },
        "reuse": {
          "$ref": "#/$defs/shape326"
        },
        "types": {
          "$ref": "#/$defs/shape328"
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
    "shape284": {
      "type": "object",
      "properties": {
        "plan": {
          "$ref": "#/$defs/shape285"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "plan",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape282": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape283"
        },
        "payload": {
          "$ref": "#/$defs/shape284"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "interchange.origin"
    },
    "shape332": {
      "type": "object",
      "properties": {
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape330": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape331"
        },
        "payload": {
          "$ref": "#/$defs/shape332"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "types.capabilities"
    },
    "shape335": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape333": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape334"
        },
        "payload": {
          "$ref": "#/$defs/shape335"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "types.list"
    },
    "shape338": {
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
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "view"
      ],
      "additionalProperties": false
    },
    "shape336": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape337"
        },
        "payload": {
          "$ref": "#/$defs/shape338"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "types.get"
    },
    "shape341": {
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
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "version",
        "view"
      ],
      "additionalProperties": false
    },
    "shape339": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape340"
        },
        "payload": {
          "$ref": "#/$defs/shape341"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "types.validate"
    },
    "shape344": {
      "type": "object",
      "properties": {
        "attachedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "metadata": {
          "$ref": "#/$defs/shape292"
        },
        "publishedOnly": {
          "$ref": "#/$defs/shape29"
        },
        "version": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "metadata",
        "version",
        "view"
      ],
      "additionalProperties": false
    },
    "shape342": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape343"
        },
        "payload": {
          "$ref": "#/$defs/shape344"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "types.version.create"
    },
    "shape347": {
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
          "$ref": "#/$defs/shape292"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "schema": {
          "$ref": "#/$defs/shape296"
        },
        "slug": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "schema",
        "view"
      ],
      "additionalProperties": false
    },
    "shape345": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape346"
        },
        "payload": {
          "$ref": "#/$defs/shape347"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "types.activate"
    },
    "shape350": {
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
          "$ref": "#/$defs/shape199"
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
    "shape348": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape349"
        },
        "payload": {
          "$ref": "#/$defs/shape350"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "types.deprecate"
    },
    "shape353": {
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
          "$ref": "#/$defs/shape199"
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
    "shape351": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape352"
        },
        "payload": {
          "$ref": "#/$defs/shape353"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "content.get"
    },
    "shape356": {
      "type": "object",
      "properties": {
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "revision",
        "view"
      ],
      "additionalProperties": false
    },
    "shape354": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape355"
        },
        "payload": {
          "$ref": "#/$defs/shape356"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "content.revise"
    },
    "shape362": {
      "const": "set"
    },
    "shape361": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape292"
        },
        "mode": {
          "$ref": "#/$defs/shape362"
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
    "shape364": {
      "const": "inherit"
    },
    "shape363": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape292"
        },
        "mode": {
          "$ref": "#/$defs/shape364"
        },
        "version": {
          "$ref": "#/$defs/shape242"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape366": {
      "const": "clear"
    },
    "shape365": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape242"
        },
        "mode": {
          "$ref": "#/$defs/shape366"
        },
        "version": {
          "$ref": "#/$defs/shape242"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape360": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape361"
        },
        {
          "$ref": "#/$defs/shape363"
        },
        {
          "$ref": "#/$defs/shape365"
        }
      ]
    },
    "shape359": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape360"
        },
        "view": {
          "$ref": "#/$defs/shape199"
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
    "shape357": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape358"
        },
        "payload": {
          "$ref": "#/$defs/shape359"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "upload"
    },
    "shape369": {
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
          "$ref": "#/$defs/shape360"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape367": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape368"
        },
        "payload": {
          "$ref": "#/$defs/shape369"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "acquire"
    },
    "shape372": {
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
          "$ref": "#/$defs/shape360"
        },
        "url": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "url"
      ],
      "additionalProperties": false
    },
    "shape370": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape371"
        },
        "payload": {
          "$ref": "#/$defs/shape372"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "refresh"
    },
    "shape375": {
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
          "$ref": "#/$defs/shape360"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape373": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape374"
        },
        "payload": {
          "$ref": "#/$defs/shape375"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
          "$ref": "#/$defs/shape360"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape376": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape325"
        },
        "payload": {
          "$ref": "#/$defs/shape377"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "replace"
    },
    "shape380": {
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
          "$ref": "#/$defs/shape360"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape378": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape379"
        },
        "payload": {
          "$ref": "#/$defs/shape380"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "list"
    },
    "shape383": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape215"
        },
        "kind": {
          "$ref": "#/$defs/shape217"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape381": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape382"
        },
        "payload": {
          "$ref": "#/$defs/shape383"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "facets"
    },
    "shape386": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape215"
        },
        "kind": {
          "$ref": "#/$defs/shape217"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape384": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape385"
        },
        "payload": {
          "$ref": "#/$defs/shape386"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape388": {
      "const": "tags"
    },
    "shape389": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "tags": {
          "$ref": "#/$defs/shape216"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "revision",
        "tags"
      ],
      "additionalProperties": false
    },
    "shape387": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape388"
        },
        "payload": {
          "$ref": "#/$defs/shape389"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape391": {
      "const": "tags.groups"
    },
    "shape394": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape216"
      }
    },
    "shape393": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape394"
        }
      ]
    },
    "shape392": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape393"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "groups",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape390": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape391"
        },
        "payload": {
          "$ref": "#/$defs/shape392"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "inventory"
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
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape395": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape396"
        },
        "payload": {
          "$ref": "#/$defs/shape397"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "acquisitions"
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
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape398": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape399"
        },
        "payload": {
          "$ref": "#/$defs/shape400"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "status"
    },
    "shape403": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape401": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape402"
        },
        "payload": {
          "$ref": "#/$defs/shape403"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "await"
    },
    "shape409": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape242"
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
    "shape410": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape242"
        }
      },
      "required": [
        "acquisition"
      ],
      "additionalProperties": false
    },
    "shape408": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape409"
        },
        {
          "$ref": "#/$defs/shape410"
        }
      ]
    },
    "shape407": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape408"
      }
    },
    "shape406": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape407"
        },
        "view": {
          "$ref": "#/$defs/shape199"
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
    "shape404": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape405"
        },
        "payload": {
          "$ref": "#/$defs/shape406"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape412": {
      "const": "read"
    },
    "shape413": {
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
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape411": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape412"
        },
        "payload": {
          "$ref": "#/$defs/shape413"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape415": {
      "const": "outline"
    },
    "shape416": {
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
    "shape414": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape415"
        },
        "payload": {
          "$ref": "#/$defs/shape416"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape418": {
      "const": "symbols"
    },
    "shape419": {
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
    "shape417": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape418"
        },
        "payload": {
          "$ref": "#/$defs/shape419"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape421": {
      "const": "search"
    },
    "shape422": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape215"
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
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape420": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape421"
        },
        "payload": {
          "$ref": "#/$defs/shape422"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape424": {
      "const": "rank"
    },
    "shape425": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape215"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "query": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape423": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape424"
        },
        "payload": {
          "$ref": "#/$defs/shape425"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape427": {
      "const": "ask"
    },
    "shape428": {
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
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "question",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape426": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape427"
        },
        "payload": {
          "$ref": "#/$defs/shape428"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape430": {
      "const": "evidence.record"
    },
    "shape431": {
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
          "$ref": "#/$defs/shape199"
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
    "shape429": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape430"
        },
        "payload": {
          "$ref": "#/$defs/shape431"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape433": {
      "const": "evidence.read"
    },
    "shape434": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "evidence"
      ],
      "additionalProperties": false
    },
    "shape432": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape433"
        },
        "payload": {
          "$ref": "#/$defs/shape434"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape436": {
      "const": "record.report"
    },
    "shape441": {
      "const": "holds"
    },
    "shape442": {
      "const": "weakened"
    },
    "shape443": {
      "const": "refuted"
    },
    "shape444": {
      "const": "not_checked"
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
        },
        {
          "$ref": "#/$defs/shape444"
        }
      ]
    },
    "shape439": {
      "type": "object",
      "properties": {
        "claim": {
          "$ref": "#/$defs/shape6"
        },
        "counterEvidence": {
          "$ref": "#/$defs/shape216"
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
          "$ref": "#/$defs/shape216"
        },
        "verdict": {
          "$ref": "#/$defs/shape440"
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
    "shape438": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape439"
      }
    },
    "shape446": {
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
    "shape445": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape446"
      }
    },
    "shape437": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape216"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "feedback": {
          "$ref": "#/$defs/shape6"
        },
        "findings": {
          "$ref": "#/$defs/shape438"
        },
        "inputs": {
          "$ref": "#/$defs/shape216"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "objectives": {
          "$ref": "#/$defs/shape216"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "reviews": {
          "$ref": "#/$defs/shape445"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape216"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape360"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape435": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape436"
        },
        "payload": {
          "$ref": "#/$defs/shape437"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape448": {
      "const": "finalise"
    },
    "shape449": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape447": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape448"
        },
        "payload": {
          "$ref": "#/$defs/shape449"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape451": {
      "const": "link"
    },
    "shape452": {
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
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape450": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape451"
        },
        "payload": {
          "$ref": "#/$defs/shape452"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape454": {
      "const": "unlink"
    },
    "shape455": {
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
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape453": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape454"
        },
        "payload": {
          "$ref": "#/$defs/shape455"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape457": {
      "const": "share"
    },
    "shape458": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape456": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape457"
        },
        "payload": {
          "$ref": "#/$defs/shape458"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape460": {
      "const": "unshare"
    },
    "shape461": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape459": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape460"
        },
        "payload": {
          "$ref": "#/$defs/shape461"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape463": {
      "const": "withdraw"
    },
    "shape464": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape462": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape463"
        },
        "payload": {
          "$ref": "#/$defs/shape464"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape466": {
      "const": "exclude"
    },
    "shape467": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape465": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape466"
        },
        "payload": {
          "$ref": "#/$defs/shape467"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape469": {
      "const": "unexclude"
    },
    "shape470": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape468": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape469"
        },
        "payload": {
          "$ref": "#/$defs/shape470"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape472": {
      "const": "restore"
    },
    "shape473": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape471": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape472"
        },
        "payload": {
          "$ref": "#/$defs/shape473"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape475": {
      "const": "delete"
    },
    "shape476": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape474": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape475"
        },
        "payload": {
          "$ref": "#/$defs/shape476"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape478": {
      "const": "retry"
    },
    "shape482": {
      "const": "extract"
    },
    "shape483": {
      "const": "derive"
    },
    "shape484": {
      "const": "embed"
    },
    "shape485": {
      "const": "summarise"
    },
    "shape486": {
      "const": "summary_embed"
    },
    "shape487": {
      "const": "autoTag"
    },
    "shape488": {
      "const": "tagGroups"
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
        },
        {
          "$ref": "#/$defs/shape486"
        },
        {
          "$ref": "#/$defs/shape487"
        },
        {
          "$ref": "#/$defs/shape488"
        }
      ]
    },
    "shape480": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape481"
      }
    },
    "shape479": {
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
          "$ref": "#/$defs/shape480"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape477": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape478"
        },
        "payload": {
          "$ref": "#/$defs/shape479"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "rebuild"
    },
    "shape491": {
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
          "$ref": "#/$defs/shape480"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "requestId",
        "revision",
        "stage"
      ],
      "additionalProperties": false
    },
    "shape489": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape490"
        },
        "payload": {
          "$ref": "#/$defs/shape491"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
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
      "const": "allowance"
    },
    "shape494": {
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
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "maxModelCalls",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape492": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape493"
        },
        "payload": {
          "$ref": "#/$defs/shape494"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape496": {
      "const": "events"
    },
    "shape497": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape28"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape495": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape496"
        },
        "payload": {
          "$ref": "#/$defs/shape497"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape499": {
      "const": "migration.list"
    },
    "shape500": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape498": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape499"
        },
        "payload": {
          "$ref": "#/$defs/shape500"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape502": {
      "const": "migration.adopt"
    },
    "shape503": {
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
          "$ref": "#/$defs/shape199"
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
    "shape501": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape502"
        },
        "payload": {
          "$ref": "#/$defs/shape503"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape505": {
      "const": "migration.inspect"
    },
    "shape506": {
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
          "$ref": "#/$defs/shape199"
        }
      },
      "required": [
        "payload",
        "reason"
      ],
      "additionalProperties": false
    },
    "shape504": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape505"
        },
        "payload": {
          "$ref": "#/$defs/shape506"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape508": {
      "const": "migration.release"
    },
    "shape509": {
      "type": "object",
      "properties": {
        "inputs": {
          "$ref": "#/$defs/shape216"
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
          "$ref": "#/$defs/shape199"
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
    "shape507": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape508"
        },
        "payload": {
          "$ref": "#/$defs/shape509"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape512": {
      "const": "bootstrap"
    },
    "shape513": {
      "const": "demo"
    },
    "shape514": {
      "const": "disconnect"
    },
    "shape515": {
      "const": "approvals-refresh"
    },
    "shape511": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape374"
        },
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
    "shape517": {
      "const": "project-access"
    },
    "shape519": {
      "const": "project.access"
    },
    "shape520": {
      "const": "project.member.add"
    },
    "shape521": {
      "const": "project.member.remove"
    },
    "shape522": {
      "const": "project.member.role"
    },
    "shape518": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape519"
        },
        {
          "$ref": "#/$defs/shape520"
        },
        {
          "$ref": "#/$defs/shape521"
        },
        {
          "$ref": "#/$defs/shape522"
        }
      ]
    },
    "shape524": {
      "const": "VIEWER"
    },
    "shape525": {
      "const": "CONTRIBUTOR"
    },
    "shape526": {
      "const": "MANAGER"
    },
    "shape523": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape524"
        },
        {
          "$ref": "#/$defs/shape525"
        },
        {
          "$ref": "#/$defs/shape526"
        }
      ]
    },
    "shape516": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape517"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape518"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape523"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape528": {
      "const": "server-admin"
    },
    "shape529": {
      "const": "admin.pricing.list"
    },
    "shape530": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape242"
      }
    },
    "shape527": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape529"
        },
        "payload": {
          "$ref": "#/$defs/shape530"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape532": {
      "const": "admin.pricing.set"
    },
    "shape535": {
      "const": "TOKEN"
    },
    "shape536": {
      "const": "INCLUDED"
    },
    "shape537": {
      "const": "ZERO_RATE"
    },
    "shape538": {
      "const": "UNPRICED"
    },
    "shape534": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape535"
        },
        {
          "$ref": "#/$defs/shape536"
        },
        {
          "$ref": "#/$defs/shape537"
        },
        {
          "$ref": "#/$defs/shape538"
        }
      ]
    },
    "shape539": {
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
    "shape542": {
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
    "shape541": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape28"
        },
        "rates": {
          "$ref": "#/$defs/shape542"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape540": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape541"
      }
    },
    "shape533": {
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
          "$ref": "#/$defs/shape534"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "rates": {
          "$ref": "#/$defs/shape539"
        },
        "requestFee": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "tiers": {
          "$ref": "#/$defs/shape540"
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
    "shape531": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape532"
        },
        "payload": {
          "$ref": "#/$defs/shape533"
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
      "const": "admin.accounts"
    },
    "shape543": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape544"
        },
        "payload": {
          "$ref": "#/$defs/shape530"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape546": {
      "const": "admin.account.create"
    },
    "shape547": {
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
    "shape545": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape546"
        },
        "payload": {
          "$ref": "#/$defs/shape547"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape549": {
      "const": "admin.account.update"
    },
    "shape550": {
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
    "shape548": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape549"
        },
        "payload": {
          "$ref": "#/$defs/shape550"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape552": {
      "const": "admin.account.reset"
    },
    "shape553": {
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
    "shape551": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape552"
        },
        "payload": {
          "$ref": "#/$defs/shape553"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape555": {
      "const": "admin.sessions"
    },
    "shape556": {
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
    "shape554": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape555"
        },
        "payload": {
          "$ref": "#/$defs/shape556"
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
      "const": "admin.session.revoke"
    },
    "shape559": {
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
    "shape557": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
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
      "const": "admin.audit"
    },
    "shape562": {
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
    "shape560": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
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
      "const": "admin.service.accounts"
    },
    "shape563": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape564"
        },
        "payload": {
          "$ref": "#/$defs/shape530"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape566": {
      "const": "admin.service.account.create"
    },
    "shape567": {
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
    "shape565": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape566"
        },
        "payload": {
          "$ref": "#/$defs/shape567"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape569": {
      "const": "admin.service.account.update"
    },
    "shape570": {
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
    "shape568": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape569"
        },
        "payload": {
          "$ref": "#/$defs/shape570"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape572": {
      "const": "admin.service.tokens"
    },
    "shape573": {
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
    "shape571": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape572"
        },
        "payload": {
          "$ref": "#/$defs/shape573"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape575": {
      "const": "admin.service.token.create"
    },
    "shape578": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape523"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape577": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape578"
      }
    },
    "shape576": {
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
          "$ref": "#/$defs/shape577"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape574": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape575"
        },
        "payload": {
          "$ref": "#/$defs/shape576"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape580": {
      "const": "admin.service.token.rotate"
    },
    "shape581": {
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
    "shape579": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape580"
        },
        "payload": {
          "$ref": "#/$defs/shape581"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape583": {
      "const": "admin.service.token.revoke"
    },
    "shape584": {
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
    "shape582": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape528"
        },
        "operation": {
          "$ref": "#/$defs/shape583"
        },
        "payload": {
          "$ref": "#/$defs/shape584"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape586": {
      "const": "server-project-create"
    },
    "shape588": {
      "const": "MANAGED"
    },
    "shape589": {
      "const": "DISJOINT"
    },
    "shape587": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape588"
        },
        {
          "$ref": "#/$defs/shape589"
        }
      ]
    },
    "shape590": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape591": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape585": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape586"
        },
        "applicationRoot": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape587"
        },
        "workspace": {
          "$ref": "#/$defs/shape6"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape590"
        },
        "writePaths": {
          "$ref": "#/$defs/shape591"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape593": {
      "const": "server-setup"
    },
    "shape592": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape593"
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
    "shape595": {
      "const": "connect"
    },
    "shape594": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape595"
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
    "shape597": {
      "const": "connection-select"
    },
    "shape596": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape597"
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
    "shape599": {
      "const": "connection-preferences"
    },
    "shape601": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape602": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape29"
      }
    },
    "shape600": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape601"
        },
        "drafts": {
          "$ref": "#/$defs/shape601"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape602"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape602"
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
    "shape598": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape6"
        },
        "action": {
          "$ref": "#/$defs/shape599"
        },
        "preference": {
          "$ref": "#/$defs/shape600"
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
    "shape604": {
      "const": "connection-rename"
    },
    "shape603": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape604"
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
    "shape606": {
      "const": "connection-remove"
    },
    "shape605": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape606"
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
    "shape608": {
      "const": "personal-section"
    },
    "shape610": {
      "const": "In"
    },
    "shape611": {
      "const": "Out"
    },
    "shape612": {
      "const": "Resources"
    },
    "shape613": {
      "const": "Archive"
    },
    "shape614": {
      "const": "Planning"
    },
    "shape615": {
      "const": "Bots"
    },
    "shape609": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape614"
        },
        {
          "$ref": "#/$defs/shape615"
        }
      ]
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
        "section": {
          "$ref": "#/$defs/shape609"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape617": {
      "const": "personal-bots"
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
    "shape619": {
      "const": "personal-recreate"
    },
    "shape618": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape619"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape621": {
      "const": "files-choose"
    },
    "shape620": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape621"
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
    "shape623": {
      "const": "files-withdraw"
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
      "const": "sync-refresh"
    },
    "shape624": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape625"
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
    "shape627": {
      "const": "sync-inspect"
    },
    "shape626": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape627"
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
    "shape629": {
      "const": "sync-change"
    },
    "shape631": {
      "const": "on"
    },
    "shape632": {
      "const": "off"
    },
    "shape633": {
      "const": "now"
    },
    "shape630": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape631"
        },
        {
          "$ref": "#/$defs/shape632"
        },
        {
          "$ref": "#/$defs/shape633"
        }
      ]
    },
    "shape628": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape629"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape630"
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
    "shape635": {
      "const": "sync-resolve"
    },
    "shape637": {
      "const": "theirs"
    },
    "shape638": {
      "const": "done"
    },
    "shape636": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape61"
        },
        {
          "$ref": "#/$defs/shape637"
        },
        {
          "$ref": "#/$defs/shape638"
        }
      ]
    },
    "shape634": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape635"
        },
        "how": {
          "$ref": "#/$defs/shape636"
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
    "shape641": {
      "const": "project-open"
    },
    "shape642": {
      "const": "project-remove"
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
    "shape639": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape640"
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
    "shape644": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape264"
        },
        {
          "$ref": "#/$defs/shape323"
        }
      ]
    },
    "shape643": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape644"
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
    "shape646": {
      "const": "history"
    },
    "shape645": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape646"
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
    "shape648": {
      "const": "select"
    },
    "shape647": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape648"
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
    "shape650": {
      "const": "trajectory"
    },
    "shape649": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape650"
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
    "shape652": {
      "const": "board-swarm-types"
    },
    "shape651": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape652"
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
    "shape654": {
      "const": "board-post-topics"
    },
    "shape653": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape654"
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
    "shape656": {
      "const": "board-create"
    },
    "shape655": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape656"
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
    "shape657": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape164"
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
    "shape659": {
      "const": "board-retry"
    },
    "shape658": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape659"
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
    "shape661": {
      "const": "board-post"
    },
    "shape660": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape661"
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
    "shape663": {
      "const": "workspace-chat"
    },
    "shape662": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape663"
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
    "shape665": {
      "const": "workspace-layout"
    },
    "shape664": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape665"
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
    "shape667": {
      "const": "workspace-refresh"
    },
    "shape666": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape667"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape669": {
      "const": "library"
    },
    "shape671": {
      "const": "sources"
    },
    "shape672": {
      "const": "documents"
    },
    "shape673": {
      "const": "memories"
    },
    "shape674": {
      "const": "manual"
    },
    "shape670": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape421"
        },
        {
          "$ref": "#/$defs/shape671"
        },
        {
          "$ref": "#/$defs/shape672"
        },
        {
          "$ref": "#/$defs/shape673"
        },
        {
          "$ref": "#/$defs/shape674"
        }
      ]
    },
    "shape668": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape669"
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
          "$ref": "#/$defs/shape670"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape676": {
      "const": "library-view"
    },
    "shape675": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape676"
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
          "$ref": "#/$defs/shape670"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape679": {
      "const": "library-refresh"
    },
    "shape680": {
      "const": "library-citations"
    },
    "shape678": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape679"
        },
        {
          "$ref": "#/$defs/shape680"
        }
      ]
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
    "shape682": {
      "const": "library-documents"
    },
    "shape681": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape682"
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
    "shape685": {
      "const": "library-document"
    },
    "shape686": {
      "const": "library-memory"
    },
    "shape687": {
      "const": "library-chunk"
    },
    "shape688": {
      "const": "library-conversation"
    },
    "shape684": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape685"
        },
        {
          "$ref": "#/$defs/shape686"
        },
        {
          "$ref": "#/$defs/shape687"
        },
        {
          "$ref": "#/$defs/shape688"
        }
      ]
    },
    "shape683": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape684"
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
    "shape690": {
      "const": "library-source-text"
    },
    "shape689": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape690"
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
    "shape692": {
      "const": "library-stance"
    },
    "shape691": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape692"
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
    "shape694": {
      "const": "library-search"
    },
    "shape696": {
      "const": "retrieve"
    },
    "shape697": {
      "const": "recall"
    },
    "shape698": {
      "const": "navigate"
    },
    "shape695": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape424"
        },
        {
          "$ref": "#/$defs/shape178"
        },
        {
          "$ref": "#/$defs/shape672"
        },
        {
          "$ref": "#/$defs/shape696"
        },
        {
          "$ref": "#/$defs/shape697"
        },
        {
          "$ref": "#/$defs/shape698"
        }
      ]
    },
    "shape700": {
      "const": "lexical"
    },
    "shape701": {
      "const": "semantic"
    },
    "shape702": {
      "const": "hybrid"
    },
    "shape699": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape700"
        },
        {
          "$ref": "#/$defs/shape701"
        },
        {
          "$ref": "#/$defs/shape702"
        }
      ]
    },
    "shape693": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape694"
        },
        "kind": {
          "$ref": "#/$defs/shape695"
        },
        "mode": {
          "$ref": "#/$defs/shape699"
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
    "shape704": {
      "const": "library-maintain"
    },
    "shape706": {
      "const": "invalidate"
    },
    "shape707": {
      "const": "resolve"
    },
    "shape708": {
      "const": "reembed"
    },
    "shape709": {
      "const": "reconsider"
    },
    "shape705": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape706"
        },
        {
          "$ref": "#/$defs/shape707"
        },
        {
          "$ref": "#/$defs/shape708"
        },
        {
          "$ref": "#/$defs/shape709"
        }
      ]
    },
    "shape703": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape29"
        },
        "action": {
          "$ref": "#/$defs/shape704"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape705"
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
    "shape711": {
      "const": "builder-outputs"
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
      "const": "builder-trajectory"
    },
    "shape712": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape713"
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
    "shape715": {
      "const": "builder-prepare"
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
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape717": {
      "const": "builder-start"
    },
    "shape716": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape717"
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
    "shape719": {
      "const": "activity"
    },
    "shape721": {
      "const": "inbox"
    },
    "shape722": {
      "const": "runs"
    },
    "shape723": {
      "const": "definitions"
    },
    "shape724": {
      "const": "schedules"
    },
    "shape725": {
      "const": "builder"
    },
    "shape720": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape721"
        },
        {
          "$ref": "#/$defs/shape722"
        },
        {
          "$ref": "#/$defs/shape723"
        },
        {
          "$ref": "#/$defs/shape724"
        },
        {
          "$ref": "#/$defs/shape725"
        }
      ]
    },
    "shape718": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape719"
        },
        "view": {
          "$ref": "#/$defs/shape720"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape727": {
      "const": "activity-view"
    },
    "shape726": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape727"
        },
        "view": {
          "$ref": "#/$defs/shape720"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape729": {
      "const": "activity-refresh"
    },
    "shape728": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape729"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape731": {
      "const": "question-refresh"
    },
    "shape730": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape731"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape733": {
      "const": "inbox-read"
    },
    "shape732": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape733"
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
    "shape735": {
      "const": "inbox-more"
    },
    "shape734": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape735"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape737": {
      "const": "run-detail"
    },
    "shape736": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape737"
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
    "shape739": {
      "const": "schedule-refresh"
    },
    "shape741": {
      "const": "next"
    },
    "shape742": {
      "const": "previous"
    },
    "shape743": {
      "const": "first"
    },
    "shape740": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape741"
        },
        {
          "$ref": "#/$defs/shape742"
        },
        {
          "$ref": "#/$defs/shape743"
        }
      ]
    },
    "shape745": {
      "const": "files"
    },
    "shape744": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape724"
        },
        {
          "$ref": "#/$defs/shape745"
        }
      ]
    },
    "shape738": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape739"
        },
        "direction": {
          "$ref": "#/$defs/shape740"
        },
        "section": {
          "$ref": "#/$defs/shape744"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape747": {
      "const": "schedule-preview"
    },
    "shape746": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape747"
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
    "shape749": {
      "const": "schedule-save"
    },
    "shape753": {
      "const": "skill"
    },
    "shape754": {
      "const": "orchestration"
    },
    "shape752": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape108"
        },
        {
          "$ref": "#/$defs/shape753"
        },
        {
          "$ref": "#/$defs/shape754"
        }
      ]
    },
    "shape756": {
      "const": "INHERITED"
    },
    "shape757": {
      "const": "SUMMARISED"
    },
    "shape758": {
      "const": "NEW"
    },
    "shape759": {
      "const": "DIRECT"
    },
    "shape755": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape756"
        },
        {
          "$ref": "#/$defs/shape757"
        },
        {
          "$ref": "#/$defs/shape758"
        },
        {
          "$ref": "#/$defs/shape759"
        }
      ]
    },
    "shape751": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape752"
        },
        "mode": {
          "$ref": "#/$defs/shape755"
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
    "shape761": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape28"
        }
      ]
    },
    "shape760": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape761"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape761"
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
    "shape764": {
      "const": "mailbox"
    },
    "shape765": {
      "const": "message"
    },
    "shape763": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape178"
        },
        {
          "$ref": "#/$defs/shape764"
        },
        {
          "$ref": "#/$defs/shape765"
        }
      ]
    },
    "shape762": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape763"
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
    "shape766": {
      "const": 1
    },
    "shape750": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape751"
        },
        "cron": {
          "$ref": "#/$defs/shape6"
        },
        "limits": {
          "$ref": "#/$defs/shape760"
        },
        "paused": {
          "$ref": "#/$defs/shape29"
        },
        "target": {
          "$ref": "#/$defs/shape762"
        },
        "version": {
          "$ref": "#/$defs/shape766"
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
    "shape768": {
      "const": "workspace"
    },
    "shape767": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape62"
        },
        {
          "$ref": "#/$defs/shape768"
        }
      ]
    },
    "shape748": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape749"
        },
        "definition": {
          "$ref": "#/$defs/shape750"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape767"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape770": {
      "const": "schedule-file-save"
    },
    "shape769": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape770"
        },
        "definition": {
          "$ref": "#/$defs/shape750"
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
          "$ref": "#/$defs/shape767"
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
    "shape772": {
      "const": "schedule-sync"
    },
    "shape771": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape772"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape767"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape774": {
      "const": "schedule-change"
    },
    "shape776": {
      "const": "schedule"
    },
    "shape777": {
      "const": "trigger"
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
    "shape773": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape774"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape775"
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
    "shape779": {
      "const": "schedule-fire"
    },
    "shape778": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape779"
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
    "shape781": {
      "const": "run-definitions"
    },
    "shape780": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape781"
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
    "shape783": {
      "const": "run-record"
    },
    "shape782": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape783"
        },
        "before": {
          "$ref": "#/$defs/shape28"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "kinds": {
          "$ref": "#/$defs/shape591"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape785": {
      "const": "run-answer"
    },
    "shape787": {
      "type": "object",
      "properties": {
        "chosen": {
          "$ref": "#/$defs/shape216"
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
    "shape786": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape787"
      }
    },
    "shape784": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape785"
        },
        "answer": {
          "$ref": "#/$defs/shape6"
        },
        "choices": {
          "$ref": "#/$defs/shape786"
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
    "shape789": {
      "const": "run-cancel"
    },
    "shape788": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape789"
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
    "shape791": {
      "const": "run-resume"
    },
    "shape790": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape791"
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
    "shape793": {
      "const": "run-trajectory"
    },
    "shape795": {
      "const": "conductor"
    },
    "shape796": {
      "const": "caller"
    },
    "shape794": {
      "anyOf": [
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
        "actor": {
          "$ref": "#/$defs/shape794"
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
    "shape798": {
      "const": "run-stage-trajectory"
    },
    "shape797": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape798"
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
    "shape800": {
      "const": "delegate-trajectory"
    },
    "shape799": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape800"
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
    "shape803": {
      "const": "board-inspection"
    },
    "shape804": {
      "const": "board-view"
    },
    "shape802": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape803"
        },
        {
          "$ref": "#/$defs/shape804"
        }
      ]
    },
    "shape806": {
      "const": "board"
    },
    "shape807": {
      "const": "swarm"
    },
    "shape805": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape806"
        },
        {
          "$ref": "#/$defs/shape807"
        }
      ]
    },
    "shape801": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape802"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape805"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape810": {
      "const": "board-refresh"
    },
    "shape811": {
      "const": "board-more"
    },
    "shape809": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape810"
        },
        {
          "$ref": "#/$defs/shape811"
        }
      ]
    },
    "shape808": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape809"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape813": {
      "const": "board-topic"
    },
    "shape812": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape813"
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
    "shape815": {
      "const": "board-trajectory"
    },
    "shape814": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape815"
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
    "shape817": {
      "const": "context"
    },
    "shape816": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape817"
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
    "shape819": {
      "const": "open-link"
    },
    "shape818": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape819"
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
    "shape821": {
      "const": "copy-text"
    },
    "shape820": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape821"
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
    "shape823": {
      "const": "workflow-start"
    },
    "shape822": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape823"
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
    "shape824": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape111"
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
    "shape826": {
      "const": "cancel"
    },
    "shape825": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape826"
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
    "shape828": {
      "const": "answer"
    },
    "shape829": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape177"
        },
        {
          "$ref": "#/$defs/shape179"
        }
      ]
    },
    "shape827": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape828"
        },
        "decision": {
          "$ref": "#/$defs/shape829"
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
    "shape831": {
      "const": "native-mcp"
    },
    "shape830": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape831"
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
    "shape833": {
      "const": "provider-management"
    },
    "shape834": {
      "const": "provider.connection.list"
    },
    "shape835": {
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
    "shape832": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
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
    "shape837": {
      "const": "provider.connection.status"
    },
    "shape838": {
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
    "shape836": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
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
      "const": "provider.connection.receipt"
    },
    "shape841": {
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
    "shape839": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
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
      "const": "provider.connection.save"
    },
    "shape847": {
      "const": "Authorization"
    },
    "shape848": {
      "const": "X-API-Key"
    },
    "shape849": {
      "const": "Api-Key"
    },
    "shape846": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape847"
        },
        {
          "$ref": "#/$defs/shape848"
        },
        {
          "$ref": "#/$defs/shape849"
        }
      ]
    },
    "shape851": {
      "const": "API_KEY"
    },
    "shape852": {
      "const": "OAUTH_PKCE"
    },
    "shape853": {
      "const": "CLIENT_CREDENTIALS"
    },
    "shape850": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape851"
        },
        {
          "$ref": "#/$defs/shape852"
        },
        {
          "$ref": "#/$defs/shape853"
        }
      ]
    },
    "shape855": {
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
          "$ref": "#/$defs/shape216"
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
    "shape854": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape855"
        }
      ]
    },
    "shape845": {
      "type": "object",
      "properties": {
        "apiKeyHeader": {
          "$ref": "#/$defs/shape846"
        },
        "authentication": {
          "$ref": "#/$defs/shape850"
        },
        "endpoint": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "oauth": {
          "$ref": "#/$defs/shape854"
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
    "shape844": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape845"
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
    "shape842": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
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
    "shape857": {
      "const": "provider.connection.change"
    },
    "shape860": {
      "const": "ENABLE"
    },
    "shape861": {
      "const": "DISABLE"
    },
    "shape862": {
      "const": "DELETE"
    },
    "shape859": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape860"
        },
        {
          "$ref": "#/$defs/shape861"
        },
        {
          "$ref": "#/$defs/shape862"
        }
      ]
    },
    "shape858": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape859"
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
    "shape856": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
        },
        "operation": {
          "$ref": "#/$defs/shape857"
        },
        "payload": {
          "$ref": "#/$defs/shape858"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape864": {
      "const": "provider.credential.status"
    },
    "shape865": {
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
    "shape863": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
        },
        "operation": {
          "$ref": "#/$defs/shape864"
        },
        "payload": {
          "$ref": "#/$defs/shape865"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape867": {
      "const": "provider.credential.put"
    },
    "shape868": {
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
    "shape866": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
        },
        "operation": {
          "$ref": "#/$defs/shape867"
        },
        "payload": {
          "$ref": "#/$defs/shape868"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape870": {
      "const": "provider.credential.revoke"
    },
    "shape871": {
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
    "shape869": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
        },
        "operation": {
          "$ref": "#/$defs/shape870"
        },
        "payload": {
          "$ref": "#/$defs/shape871"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape873": {
      "const": "provider.credential.rekey"
    },
    "shape872": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
        },
        "operation": {
          "$ref": "#/$defs/shape873"
        },
        "payload": {
          "$ref": "#/$defs/shape871"
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
      "const": "provider.credential.authorize"
    },
    "shape874": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
        },
        "operation": {
          "$ref": "#/$defs/shape875"
        },
        "payload": {
          "$ref": "#/$defs/shape871"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape877": {
      "const": "provider.credential.refresh"
    },
    "shape876": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
        },
        "operation": {
          "$ref": "#/$defs/shape877"
        },
        "payload": {
          "$ref": "#/$defs/shape871"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape879": {
      "const": "tool.scope.host.list"
    },
    "shape880": {
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
    "shape878": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
        },
        "operation": {
          "$ref": "#/$defs/shape879"
        },
        "payload": {
          "$ref": "#/$defs/shape880"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape882": {
      "const": "tool.scope.host.status"
    },
    "shape883": {
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
    "shape881": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
        },
        "operation": {
          "$ref": "#/$defs/shape882"
        },
        "payload": {
          "$ref": "#/$defs/shape883"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape885": {
      "const": "tool.scope.host.receipt"
    },
    "shape886": {
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
    "shape884": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
        },
        "operation": {
          "$ref": "#/$defs/shape885"
        },
        "payload": {
          "$ref": "#/$defs/shape886"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape888": {
      "const": "tool.scope.host.save"
    },
    "shape890": {
      "type": "object",
      "properties": {
        "agents": {
          "$ref": "#/$defs/shape216"
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
          "$ref": "#/$defs/shape216"
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
    "shape889": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape890"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape761"
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
    "shape887": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
        },
        "operation": {
          "$ref": "#/$defs/shape888"
        },
        "payload": {
          "$ref": "#/$defs/shape889"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape892": {
      "const": "tool.scope.host.change"
    },
    "shape893": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape859"
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
    "shape891": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
        },
        "operation": {
          "$ref": "#/$defs/shape892"
        },
        "payload": {
          "$ref": "#/$defs/shape893"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape895": {
      "const": "tool.scope.list"
    },
    "shape896": {
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
    "shape894": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
        },
        "operation": {
          "$ref": "#/$defs/shape895"
        },
        "payload": {
          "$ref": "#/$defs/shape896"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape898": {
      "const": "provider-management-pending"
    },
    "shape897": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape898"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape900": {
      "const": "provider-management-recover"
    },
    "shape903": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape843"
        },
        {
          "$ref": "#/$defs/shape857"
        }
      ]
    },
    "shape902": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape903"
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
    "shape905": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape867"
        },
        {
          "$ref": "#/$defs/shape870"
        },
        {
          "$ref": "#/$defs/shape873"
        },
        {
          "$ref": "#/$defs/shape875"
        },
        {
          "$ref": "#/$defs/shape877"
        }
      ]
    },
    "shape904": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape905"
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
    "shape907": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape888"
        },
        {
          "$ref": "#/$defs/shape892"
        }
      ]
    },
    "shape906": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape761"
        },
        "operation": {
          "$ref": "#/$defs/shape907"
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
    "shape901": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape902"
        },
        {
          "$ref": "#/$defs/shape904"
        },
        {
          "$ref": "#/$defs/shape906"
        }
      ]
    },
    "shape899": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape900"
        },
        "intent": {
          "$ref": "#/$defs/shape901"
        }
      },
      "required": [
        "action",
        "intent"
      ],
      "additionalProperties": false
    },
    "shape909": {
      "const": "information-import-recovery-save"
    },
    "shape911": {
      "const": "plowshare-information-import-recovery/1"
    },
    "shape910": {
      "type": "object",
      "properties": {
        "context": {
          "$ref": "#/$defs/shape6"
        },
        "format": {
          "$ref": "#/$defs/shape911"
        },
        "plan": {
          "$ref": "#/$defs/shape285"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape200"
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
    "shape908": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape909"
        },
        "recovery": {
          "$ref": "#/$defs/shape910"
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
          "$ref": "#/$defs/shape64"
        },
        {
          "$ref": "#/$defs/shape66"
        },
        {
          "$ref": "#/$defs/shape68"
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
          "$ref": "#/$defs/shape84"
        },
        {
          "$ref": "#/$defs/shape89"
        },
        {
          "$ref": "#/$defs/shape98"
        },
        {
          "$ref": "#/$defs/shape100"
        },
        {
          "$ref": "#/$defs/shape123"
        },
        {
          "$ref": "#/$defs/shape126"
        },
        {
          "$ref": "#/$defs/shape128"
        },
        {
          "$ref": "#/$defs/shape130"
        },
        {
          "$ref": "#/$defs/shape132"
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
          "$ref": "#/$defs/shape139"
        },
        {
          "$ref": "#/$defs/shape142"
        },
        {
          "$ref": "#/$defs/shape145"
        },
        {
          "$ref": "#/$defs/shape148"
        },
        {
          "$ref": "#/$defs/shape151"
        },
        {
          "$ref": "#/$defs/shape153"
        },
        {
          "$ref": "#/$defs/shape171"
        },
        {
          "$ref": "#/$defs/shape173"
        },
        {
          "$ref": "#/$defs/shape193"
        },
        {
          "$ref": "#/$defs/shape195"
        },
        {
          "$ref": "#/$defs/shape209"
        },
        {
          "$ref": "#/$defs/shape220"
        },
        {
          "$ref": "#/$defs/shape223"
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
          "$ref": "#/$defs/shape246"
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
          "$ref": "#/$defs/shape357"
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
          "$ref": "#/$defs/shape390"
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
          "$ref": "#/$defs/shape411"
        },
        {
          "$ref": "#/$defs/shape414"
        },
        {
          "$ref": "#/$defs/shape417"
        },
        {
          "$ref": "#/$defs/shape420"
        },
        {
          "$ref": "#/$defs/shape423"
        },
        {
          "$ref": "#/$defs/shape426"
        },
        {
          "$ref": "#/$defs/shape429"
        },
        {
          "$ref": "#/$defs/shape432"
        },
        {
          "$ref": "#/$defs/shape435"
        },
        {
          "$ref": "#/$defs/shape447"
        },
        {
          "$ref": "#/$defs/shape450"
        },
        {
          "$ref": "#/$defs/shape453"
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
          "$ref": "#/$defs/shape465"
        },
        {
          "$ref": "#/$defs/shape468"
        },
        {
          "$ref": "#/$defs/shape471"
        },
        {
          "$ref": "#/$defs/shape474"
        },
        {
          "$ref": "#/$defs/shape477"
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
          "$ref": "#/$defs/shape504"
        },
        {
          "$ref": "#/$defs/shape507"
        },
        {
          "$ref": "#/$defs/shape510"
        },
        {
          "$ref": "#/$defs/shape516"
        },
        {
          "$ref": "#/$defs/shape527"
        },
        {
          "$ref": "#/$defs/shape531"
        },
        {
          "$ref": "#/$defs/shape543"
        },
        {
          "$ref": "#/$defs/shape545"
        },
        {
          "$ref": "#/$defs/shape548"
        },
        {
          "$ref": "#/$defs/shape551"
        },
        {
          "$ref": "#/$defs/shape554"
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
          "$ref": "#/$defs/shape565"
        },
        {
          "$ref": "#/$defs/shape568"
        },
        {
          "$ref": "#/$defs/shape571"
        },
        {
          "$ref": "#/$defs/shape574"
        },
        {
          "$ref": "#/$defs/shape579"
        },
        {
          "$ref": "#/$defs/shape582"
        },
        {
          "$ref": "#/$defs/shape585"
        },
        {
          "$ref": "#/$defs/shape592"
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
          "$ref": "#/$defs/shape603"
        },
        {
          "$ref": "#/$defs/shape605"
        },
        {
          "$ref": "#/$defs/shape607"
        },
        {
          "$ref": "#/$defs/shape616"
        },
        {
          "$ref": "#/$defs/shape618"
        },
        {
          "$ref": "#/$defs/shape620"
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
          "$ref": "#/$defs/shape634"
        },
        {
          "$ref": "#/$defs/shape639"
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
          "$ref": "#/$defs/shape658"
        },
        {
          "$ref": "#/$defs/shape660"
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
          "$ref": "#/$defs/shape668"
        },
        {
          "$ref": "#/$defs/shape675"
        },
        {
          "$ref": "#/$defs/shape677"
        },
        {
          "$ref": "#/$defs/shape681"
        },
        {
          "$ref": "#/$defs/shape683"
        },
        {
          "$ref": "#/$defs/shape689"
        },
        {
          "$ref": "#/$defs/shape691"
        },
        {
          "$ref": "#/$defs/shape693"
        },
        {
          "$ref": "#/$defs/shape703"
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
          "$ref": "#/$defs/shape726"
        },
        {
          "$ref": "#/$defs/shape728"
        },
        {
          "$ref": "#/$defs/shape730"
        },
        {
          "$ref": "#/$defs/shape732"
        },
        {
          "$ref": "#/$defs/shape734"
        },
        {
          "$ref": "#/$defs/shape736"
        },
        {
          "$ref": "#/$defs/shape738"
        },
        {
          "$ref": "#/$defs/shape746"
        },
        {
          "$ref": "#/$defs/shape748"
        },
        {
          "$ref": "#/$defs/shape769"
        },
        {
          "$ref": "#/$defs/shape771"
        },
        {
          "$ref": "#/$defs/shape773"
        },
        {
          "$ref": "#/$defs/shape778"
        },
        {
          "$ref": "#/$defs/shape780"
        },
        {
          "$ref": "#/$defs/shape782"
        },
        {
          "$ref": "#/$defs/shape784"
        },
        {
          "$ref": "#/$defs/shape788"
        },
        {
          "$ref": "#/$defs/shape790"
        },
        {
          "$ref": "#/$defs/shape792"
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
          "$ref": "#/$defs/shape808"
        },
        {
          "$ref": "#/$defs/shape812"
        },
        {
          "$ref": "#/$defs/shape814"
        },
        {
          "$ref": "#/$defs/shape816"
        },
        {
          "$ref": "#/$defs/shape818"
        },
        {
          "$ref": "#/$defs/shape820"
        },
        {
          "$ref": "#/$defs/shape822"
        },
        {
          "$ref": "#/$defs/shape824"
        },
        {
          "$ref": "#/$defs/shape825"
        },
        {
          "$ref": "#/$defs/shape827"
        },
        {
          "$ref": "#/$defs/shape830"
        },
        {
          "$ref": "#/$defs/shape832"
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
          "$ref": "#/$defs/shape856"
        },
        {
          "$ref": "#/$defs/shape863"
        },
        {
          "$ref": "#/$defs/shape866"
        },
        {
          "$ref": "#/$defs/shape869"
        },
        {
          "$ref": "#/$defs/shape872"
        },
        {
          "$ref": "#/$defs/shape874"
        },
        {
          "$ref": "#/$defs/shape876"
        },
        {
          "$ref": "#/$defs/shape878"
        },
        {
          "$ref": "#/$defs/shape881"
        },
        {
          "$ref": "#/$defs/shape884"
        },
        {
          "$ref": "#/$defs/shape887"
        },
        {
          "$ref": "#/$defs/shape891"
        },
        {
          "$ref": "#/$defs/shape894"
        },
        {
          "$ref": "#/$defs/shape897"
        },
        {
          "$ref": "#/$defs/shape899"
        },
        {
          "$ref": "#/$defs/shape908"
        }
      ]
    }
  }
}
