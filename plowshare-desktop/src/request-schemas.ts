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
    "shape220": {
      "const": "metadata-v1"
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
        "projection": {
          "$ref": "#/$defs/shape220"
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
    "shape222": {
      "const": "documents.facets"
    },
    "shape223": {
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
    "shape221": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape222"
        },
        "payload": {
          "$ref": "#/$defs/shape223"
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
    "shape225": {
      "const": "documents.get"
    },
    "shape227": {
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
    "shape226": {
      "type": "object",
      "properties": {
        "target": {
          "$ref": "#/$defs/shape227"
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
    "shape224": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape225"
        },
        "payload": {
          "$ref": "#/$defs/shape226"
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
    "shape229": {
      "const": "relationships.list"
    },
    "shape230": {
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
    "shape228": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape229"
        },
        "payload": {
          "$ref": "#/$defs/shape230"
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
    "shape232": {
      "const": "relationships.create"
    },
    "shape233": {
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
          "$ref": "#/$defs/shape227"
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
    "shape231": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape232"
        },
        "payload": {
          "$ref": "#/$defs/shape233"
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
    "shape235": {
      "const": "relationships.remove"
    },
    "shape236": {
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
    "shape234": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape235"
        },
        "payload": {
          "$ref": "#/$defs/shape236"
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
    "shape238": {
      "const": "policy.get"
    },
    "shape242": {
      "const": "platform"
    },
    "shape243": {
      "forbidden": true
    },
    "shape241": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape242"
        },
        "project": {
          "$ref": "#/$defs/shape243"
        },
        "resource": {
          "$ref": "#/$defs/shape243"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape244": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape110"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "resource": {
          "$ref": "#/$defs/shape243"
        }
      },
      "required": [
        "kind",
        "project"
      ],
      "additionalProperties": false
    },
    "shape246": {
      "const": "document"
    },
    "shape245": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape246"
        },
        "project": {
          "$ref": "#/$defs/shape243"
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
    "shape240": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape241"
        },
        {
          "$ref": "#/$defs/shape244"
        },
        {
          "$ref": "#/$defs/shape245"
        }
      ]
    },
    "shape239": {
      "type": "object",
      "properties": {
        "target": {
          "$ref": "#/$defs/shape240"
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
    "shape237": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape238"
        },
        "payload": {
          "$ref": "#/$defs/shape239"
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
    "shape248": {
      "const": "policy.set"
    },
    "shape251": {
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
    "shape250": {
      "type": "object",
      "properties": {
        "allowOverrides": {
          "$ref": "#/$defs/shape29"
        },
        "defaults": {
          "$ref": "#/$defs/shape251"
        },
        "mandatory": {
          "$ref": "#/$defs/shape251"
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
    "shape249": {
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
          "$ref": "#/$defs/shape250"
        },
        "target": {
          "$ref": "#/$defs/shape240"
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
    "shape247": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape248"
        },
        "payload": {
          "$ref": "#/$defs/shape249"
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
    "shape253": {
      "const": "views.list"
    },
    "shape254": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape252": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape253"
        },
        "payload": {
          "$ref": "#/$defs/shape254"
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
    "shape256": {
      "const": "views.get"
    },
    "shape257": {
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
    "shape255": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape256"
        },
        "payload": {
          "$ref": "#/$defs/shape257"
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
    "shape259": {
      "const": "views.save"
    },
    "shape265": {
      "const": "scope"
    },
    "shape264": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape265"
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
    "shape266": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape246"
        },
        "target": {
          "$ref": "#/$defs/shape227"
        }
      },
      "required": [
        "kind",
        "target"
      ],
      "additionalProperties": false
    },
    "shape268": {
      "const": "traverse"
    },
    "shape267": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape212"
        },
        "kind": {
          "$ref": "#/$defs/shape268"
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
    "shape270": {
      "const": "filter"
    },
    "shape269": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape215"
        },
        "kind": {
          "$ref": "#/$defs/shape270"
        }
      },
      "required": [
        "filter",
        "kind"
      ],
      "additionalProperties": false
    },
    "shape263": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape264"
        },
        {
          "$ref": "#/$defs/shape266"
        },
        {
          "$ref": "#/$defs/shape267"
        },
        {
          "$ref": "#/$defs/shape269"
        }
      ]
    },
    "shape262": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape263"
      }
    },
    "shape261": {
      "type": "object",
      "properties": {
        "steps": {
          "$ref": "#/$defs/shape262"
        }
      },
      "required": [
        "steps"
      ],
      "additionalProperties": false
    },
    "shape260": {
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
          "$ref": "#/$defs/shape261"
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
    "shape258": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape259"
        },
        "payload": {
          "$ref": "#/$defs/shape260"
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
    "shape272": {
      "const": "views.delete"
    },
    "shape273": {
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
    "shape271": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape272"
        },
        "payload": {
          "$ref": "#/$defs/shape273"
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
    "shape275": {
      "const": "interchange.capabilities"
    },
    "shape276": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape274": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape275"
        },
        "payload": {
          "$ref": "#/$defs/shape276"
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
    "shape278": {
      "const": "interchange.export"
    },
    "shape279": {
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
    "shape277": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape278"
        },
        "payload": {
          "$ref": "#/$defs/shape279"
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
    "shape281": {
      "const": "interchange.readable"
    },
    "shape282": {
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
    "shape280": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape281"
        },
        "payload": {
          "$ref": "#/$defs/shape282"
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
    "shape284": {
      "const": "interchange.import"
    },
    "shape295": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape294"
      }
    },
    "shape294": {
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
          "$ref": "#/$defs/shape293"
        },
        {
          "$ref": "#/$defs/shape295"
        }
      ]
    },
    "shape293": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape294"
      }
    },
    "shape296": {
      "const": "plowshare-document-metadata/1"
    },
    "shape298": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape297"
      }
    },
    "shape299": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape30"
        },
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape297"
        }
      ]
    },
    "shape300": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape297"
      }
    },
    "shape301": {
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
    "shape302": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape301"
      }
    },
    "shape304": {
      "const": "string"
    },
    "shape305": {
      "const": "number"
    },
    "shape306": {
      "const": "boolean"
    },
    "shape307": {
      "const": "object"
    },
    "shape308": {
      "const": "array"
    },
    "shape309": {
      "const": "integer"
    },
    "shape310": {
      "const": "null"
    },
    "shape312": {
      "anyOf": [
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
    "shape311": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape312"
      }
    },
    "shape303": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape311"
        }
      ]
    },
    "shape297": {
      "type": "object",
      "properties": {
        "$anchor": {
          "$ref": "#/$defs/shape6"
        },
        "$defs": {
          "$ref": "#/$defs/shape298"
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
          "$ref": "#/$defs/shape299"
        },
        "allOf": {
          "$ref": "#/$defs/shape300"
        },
        "anyOf": {
          "$ref": "#/$defs/shape300"
        },
        "const": {
          "$ref": "#/$defs/shape301"
        },
        "contains": {
          "$ref": "#/$defs/shape297"
        },
        "default": {
          "$ref": "#/$defs/shape301"
        },
        "deprecated": {
          "$ref": "#/$defs/shape29"
        },
        "description": {
          "$ref": "#/$defs/shape6"
        },
        "else": {
          "$ref": "#/$defs/shape297"
        },
        "enum": {
          "$ref": "#/$defs/shape302"
        },
        "examples": {
          "$ref": "#/$defs/shape302"
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
          "$ref": "#/$defs/shape297"
        },
        "items": {
          "$ref": "#/$defs/shape297"
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
          "$ref": "#/$defs/shape297"
        },
        "oneOf": {
          "$ref": "#/$defs/shape300"
        },
        "pattern": {
          "$ref": "#/$defs/shape6"
        },
        "patternProperties": {
          "$ref": "#/$defs/shape298"
        },
        "prefixItems": {
          "$ref": "#/$defs/shape300"
        },
        "properties": {
          "$ref": "#/$defs/shape298"
        },
        "readOnly": {
          "$ref": "#/$defs/shape29"
        },
        "required": {
          "$ref": "#/$defs/shape216"
        },
        "then": {
          "$ref": "#/$defs/shape297"
        },
        "title": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape303"
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
    "shape292": {
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
          "$ref": "#/$defs/shape293"
        },
        "profile": {
          "$ref": "#/$defs/shape296"
        },
        "schema": {
          "$ref": "#/$defs/shape297"
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
    "shape291": {
      "type": "object",
      "properties": {
        "contract": {
          "$ref": "#/$defs/shape292"
        },
        "metadata": {
          "$ref": "#/$defs/shape293"
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
    "shape315": {
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
    "shape314": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape315"
      }
    },
    "shape316": {
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
    "shape313": {
      "type": "object",
      "properties": {
        "createdAt": {
          "$ref": "#/$defs/shape6"
        },
        "dependencies": {
          "$ref": "#/$defs/shape216"
        },
        "evidence": {
          "$ref": "#/$defs/shape314"
        },
        "importedFrom": {
          "$ref": "#/$defs/shape316"
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
    "shape290": {
      "type": "object",
      "properties": {
        "contentHash": {
          "$ref": "#/$defs/shape6"
        },
        "contentType": {
          "$ref": "#/$defs/shape291"
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
          "$ref": "#/$defs/shape313"
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
    "shape289": {
      "type": "object",
      "properties": {
        "bytesBase64": {
          "$ref": "#/$defs/shape6"
        },
        "descriptor": {
          "$ref": "#/$defs/shape290"
        }
      },
      "required": [
        "bytesBase64",
        "descriptor"
      ],
      "additionalProperties": false
    },
    "shape288": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape289"
      }
    },
    "shape317": {
      "const": "plowshare-information-bundle/1"
    },
    "shape318": {
      "const": "included-endpoints-only"
    },
    "shape320": {
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
    "shape319": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape320"
      }
    },
    "shape287": {
      "type": "object",
      "properties": {
        "documents": {
          "$ref": "#/$defs/shape288"
        },
        "format": {
          "$ref": "#/$defs/shape317"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "relationshipCoverage": {
          "$ref": "#/$defs/shape318"
        },
        "relationships": {
          "$ref": "#/$defs/shape319"
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
    "shape324": {
      "const": "create"
    },
    "shape323": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape243"
        },
        "mode": {
          "$ref": "#/$defs/shape324"
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
    "shape326": {
      "const": "revise"
    },
    "shape325": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "mode": {
          "$ref": "#/$defs/shape326"
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
    "shape322": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape323"
        },
        {
          "$ref": "#/$defs/shape325"
        }
      ]
    },
    "shape321": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape322"
      }
    },
    "shape328": {
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
    "shape327": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape328"
      }
    },
    "shape330": {
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
    "shape329": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape330"
      }
    },
    "shape286": {
      "type": "object",
      "properties": {
        "bundle": {
          "$ref": "#/$defs/shape287"
        },
        "destinations": {
          "$ref": "#/$defs/shape321"
        },
        "restoreRelationships": {
          "$ref": "#/$defs/shape29"
        },
        "reuse": {
          "$ref": "#/$defs/shape327"
        },
        "types": {
          "$ref": "#/$defs/shape329"
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
    "shape285": {
      "type": "object",
      "properties": {
        "plan": {
          "$ref": "#/$defs/shape286"
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
    "shape283": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape284"
        },
        "payload": {
          "$ref": "#/$defs/shape285"
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
    "shape332": {
      "const": "interchange.origin"
    },
    "shape333": {
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
    "shape331": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape332"
        },
        "payload": {
          "$ref": "#/$defs/shape333"
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
    "shape335": {
      "const": "types.capabilities"
    },
    "shape336": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape334": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape335"
        },
        "payload": {
          "$ref": "#/$defs/shape336"
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
    "shape338": {
      "const": "types.list"
    },
    "shape339": {
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
    "shape337": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape338"
        },
        "payload": {
          "$ref": "#/$defs/shape339"
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
    "shape341": {
      "const": "types.get"
    },
    "shape342": {
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
    "shape340": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape341"
        },
        "payload": {
          "$ref": "#/$defs/shape342"
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
    "shape344": {
      "const": "types.validate"
    },
    "shape345": {
      "type": "object",
      "properties": {
        "attachedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "metadata": {
          "$ref": "#/$defs/shape293"
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
    "shape343": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape344"
        },
        "payload": {
          "$ref": "#/$defs/shape345"
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
    "shape347": {
      "const": "types.version.create"
    },
    "shape348": {
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
          "$ref": "#/$defs/shape293"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "schema": {
          "$ref": "#/$defs/shape297"
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
    "shape346": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape347"
        },
        "payload": {
          "$ref": "#/$defs/shape348"
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
    "shape350": {
      "const": "types.activate"
    },
    "shape351": {
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
    "shape349": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape350"
        },
        "payload": {
          "$ref": "#/$defs/shape351"
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
    "shape353": {
      "const": "types.deprecate"
    },
    "shape354": {
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
    "shape352": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape353"
        },
        "payload": {
          "$ref": "#/$defs/shape354"
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
    "shape356": {
      "const": "content.get"
    },
    "shape357": {
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
    "shape355": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape356"
        },
        "payload": {
          "$ref": "#/$defs/shape357"
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
    "shape359": {
      "const": "content.revise"
    },
    "shape363": {
      "const": "set"
    },
    "shape362": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape293"
        },
        "mode": {
          "$ref": "#/$defs/shape363"
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
    "shape365": {
      "const": "inherit"
    },
    "shape364": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape293"
        },
        "mode": {
          "$ref": "#/$defs/shape365"
        },
        "version": {
          "$ref": "#/$defs/shape243"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape367": {
      "const": "clear"
    },
    "shape366": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape243"
        },
        "mode": {
          "$ref": "#/$defs/shape367"
        },
        "version": {
          "$ref": "#/$defs/shape243"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape361": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape362"
        },
        {
          "$ref": "#/$defs/shape364"
        },
        {
          "$ref": "#/$defs/shape366"
        }
      ]
    },
    "shape360": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape361"
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
    "shape358": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape359"
        },
        "payload": {
          "$ref": "#/$defs/shape360"
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
    "shape369": {
      "const": "upload"
    },
    "shape370": {
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
          "$ref": "#/$defs/shape361"
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
    "shape368": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape369"
        },
        "payload": {
          "$ref": "#/$defs/shape370"
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
    "shape372": {
      "const": "acquire"
    },
    "shape373": {
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
          "$ref": "#/$defs/shape361"
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
    "shape371": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape372"
        },
        "payload": {
          "$ref": "#/$defs/shape373"
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
    "shape375": {
      "const": "refresh"
    },
    "shape376": {
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
          "$ref": "#/$defs/shape361"
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
    "shape374": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape375"
        },
        "payload": {
          "$ref": "#/$defs/shape376"
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
    "shape378": {
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
          "$ref": "#/$defs/shape361"
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
    "shape377": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape326"
        },
        "payload": {
          "$ref": "#/$defs/shape378"
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
    "shape380": {
      "const": "replace"
    },
    "shape381": {
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
          "$ref": "#/$defs/shape361"
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
    "shape379": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape380"
        },
        "payload": {
          "$ref": "#/$defs/shape381"
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
    "shape383": {
      "const": "list"
    },
    "shape384": {
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
    "shape382": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape383"
        },
        "payload": {
          "$ref": "#/$defs/shape384"
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
    "shape386": {
      "const": "facets"
    },
    "shape387": {
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
    "shape385": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape386"
        },
        "payload": {
          "$ref": "#/$defs/shape387"
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
    "shape389": {
      "const": "tags"
    },
    "shape390": {
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
    "shape388": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape389"
        },
        "payload": {
          "$ref": "#/$defs/shape390"
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
    "shape392": {
      "const": "tags.replace"
    },
    "shape393": {
      "type": "object",
      "properties": {
        "expectedTagsVersion": {
          "$ref": "#/$defs/shape28"
        },
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
        "expectedTagsVersion",
        "requestId",
        "revision",
        "tags"
      ],
      "additionalProperties": false
    },
    "shape391": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape392"
        },
        "payload": {
          "$ref": "#/$defs/shape393"
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
    "shape395": {
      "const": "tags.groups"
    },
    "shape398": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape216"
      }
    },
    "shape397": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape398"
        }
      ]
    },
    "shape396": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape397"
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
    "shape394": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape395"
        },
        "payload": {
          "$ref": "#/$defs/shape396"
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
    "shape400": {
      "const": "inventory"
    },
    "shape401": {
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
    "shape399": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape400"
        },
        "payload": {
          "$ref": "#/$defs/shape401"
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
    "shape403": {
      "const": "acquisitions"
    },
    "shape404": {
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
    "shape402": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape403"
        },
        "payload": {
          "$ref": "#/$defs/shape404"
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
    "shape406": {
      "const": "status"
    },
    "shape407": {
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
    "shape405": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape406"
        },
        "payload": {
          "$ref": "#/$defs/shape407"
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
    "shape409": {
      "const": "await"
    },
    "shape413": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape243"
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
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape243"
        }
      },
      "required": [
        "acquisition"
      ],
      "additionalProperties": false
    },
    "shape412": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape413"
        },
        {
          "$ref": "#/$defs/shape414"
        }
      ]
    },
    "shape411": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape412"
      }
    },
    "shape410": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape411"
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
    "shape408": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape409"
        },
        "payload": {
          "$ref": "#/$defs/shape410"
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
    "shape416": {
      "const": "read"
    },
    "shape417": {
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
    "shape415": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape416"
        },
        "payload": {
          "$ref": "#/$defs/shape417"
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
    "shape419": {
      "const": "outline"
    },
    "shape420": {
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
    "shape418": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape419"
        },
        "payload": {
          "$ref": "#/$defs/shape420"
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
    "shape422": {
      "const": "symbols"
    },
    "shape423": {
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
    "shape421": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape422"
        },
        "payload": {
          "$ref": "#/$defs/shape423"
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
    "shape425": {
      "const": "search"
    },
    "shape426": {
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
    "shape424": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape425"
        },
        "payload": {
          "$ref": "#/$defs/shape426"
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
    "shape428": {
      "const": "rank"
    },
    "shape429": {
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
    "shape427": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape428"
        },
        "payload": {
          "$ref": "#/$defs/shape429"
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
    "shape431": {
      "const": "ask"
    },
    "shape432": {
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
    "shape430": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape431"
        },
        "payload": {
          "$ref": "#/$defs/shape432"
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
    "shape434": {
      "const": "evidence.record"
    },
    "shape435": {
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
    "shape433": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape434"
        },
        "payload": {
          "$ref": "#/$defs/shape435"
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
    "shape437": {
      "const": "evidence.read"
    },
    "shape438": {
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
    "shape436": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape437"
        },
        "payload": {
          "$ref": "#/$defs/shape438"
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
    "shape440": {
      "const": "record.report"
    },
    "shape445": {
      "const": "holds"
    },
    "shape446": {
      "const": "weakened"
    },
    "shape447": {
      "const": "refuted"
    },
    "shape448": {
      "const": "not_checked"
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
        },
        {
          "$ref": "#/$defs/shape448"
        }
      ]
    },
    "shape443": {
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
          "$ref": "#/$defs/shape444"
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
    "shape442": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape443"
      }
    },
    "shape450": {
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
    "shape449": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape450"
      }
    },
    "shape441": {
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
          "$ref": "#/$defs/shape442"
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
          "$ref": "#/$defs/shape449"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape216"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape361"
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
    "shape439": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape440"
        },
        "payload": {
          "$ref": "#/$defs/shape441"
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
    "shape452": {
      "const": "finalise"
    },
    "shape453": {
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
    "shape451": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape452"
        },
        "payload": {
          "$ref": "#/$defs/shape453"
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
    "shape455": {
      "const": "link"
    },
    "shape456": {
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
    "shape454": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape455"
        },
        "payload": {
          "$ref": "#/$defs/shape456"
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
    "shape458": {
      "const": "unlink"
    },
    "shape459": {
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
    "shape457": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape458"
        },
        "payload": {
          "$ref": "#/$defs/shape459"
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
    "shape461": {
      "const": "share"
    },
    "shape462": {
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
    "shape460": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape461"
        },
        "payload": {
          "$ref": "#/$defs/shape462"
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
    "shape464": {
      "const": "unshare"
    },
    "shape465": {
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
    "shape463": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape464"
        },
        "payload": {
          "$ref": "#/$defs/shape465"
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
    "shape467": {
      "const": "withdraw"
    },
    "shape468": {
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
    "shape466": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape467"
        },
        "payload": {
          "$ref": "#/$defs/shape468"
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
    "shape470": {
      "const": "exclude"
    },
    "shape471": {
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
    "shape469": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape470"
        },
        "payload": {
          "$ref": "#/$defs/shape471"
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
    "shape473": {
      "const": "unexclude"
    },
    "shape474": {
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
    "shape472": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape473"
        },
        "payload": {
          "$ref": "#/$defs/shape474"
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
    "shape476": {
      "const": "restore"
    },
    "shape477": {
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
    "shape475": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape476"
        },
        "payload": {
          "$ref": "#/$defs/shape477"
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
    "shape479": {
      "const": "delete"
    },
    "shape480": {
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
    "shape478": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape479"
        },
        "payload": {
          "$ref": "#/$defs/shape480"
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
    "shape482": {
      "const": "retry"
    },
    "shape486": {
      "const": "extract"
    },
    "shape487": {
      "const": "derive"
    },
    "shape488": {
      "const": "embed"
    },
    "shape489": {
      "const": "summarise"
    },
    "shape490": {
      "const": "summary_embed"
    },
    "shape491": {
      "const": "autoTag"
    },
    "shape492": {
      "const": "tagGroups"
    },
    "shape485": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape486"
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
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape485"
      }
    },
    "shape483": {
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
          "$ref": "#/$defs/shape484"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
      "additionalProperties": false
    },
    "shape481": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape482"
        },
        "payload": {
          "$ref": "#/$defs/shape483"
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
    "shape494": {
      "const": "rebuild"
    },
    "shape495": {
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
          "$ref": "#/$defs/shape484"
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
    "shape493": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape494"
        },
        "payload": {
          "$ref": "#/$defs/shape495"
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
    "shape497": {
      "const": "allowance"
    },
    "shape498": {
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
    "shape496": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape497"
        },
        "payload": {
          "$ref": "#/$defs/shape498"
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
    "shape500": {
      "const": "events"
    },
    "shape501": {
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
    "shape499": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape500"
        },
        "payload": {
          "$ref": "#/$defs/shape501"
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
    "shape503": {
      "const": "migration.list"
    },
    "shape504": {
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
    "shape502": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape503"
        },
        "payload": {
          "$ref": "#/$defs/shape504"
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
    "shape506": {
      "const": "migration.adopt"
    },
    "shape507": {
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
    "shape505": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape506"
        },
        "payload": {
          "$ref": "#/$defs/shape507"
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
    "shape509": {
      "const": "migration.inspect"
    },
    "shape510": {
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
    "shape508": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape509"
        },
        "payload": {
          "$ref": "#/$defs/shape510"
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
      "const": "migration.release"
    },
    "shape513": {
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
    "shape511": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape512"
        },
        "payload": {
          "$ref": "#/$defs/shape513"
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
    "shape516": {
      "const": "bootstrap"
    },
    "shape517": {
      "const": "demo"
    },
    "shape518": {
      "const": "disconnect"
    },
    "shape519": {
      "const": "approvals-refresh"
    },
    "shape515": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape375"
        },
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
    "shape521": {
      "const": "project-access"
    },
    "shape523": {
      "const": "project.access"
    },
    "shape524": {
      "const": "project.member.add"
    },
    "shape525": {
      "const": "project.member.remove"
    },
    "shape526": {
      "const": "project.member.role"
    },
    "shape522": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape523"
        },
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
    "shape528": {
      "const": "VIEWER"
    },
    "shape529": {
      "const": "CONTRIBUTOR"
    },
    "shape530": {
      "const": "MANAGER"
    },
    "shape527": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape528"
        },
        {
          "$ref": "#/$defs/shape529"
        },
        {
          "$ref": "#/$defs/shape530"
        }
      ]
    },
    "shape520": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape521"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape522"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape527"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape532": {
      "const": "server-admin"
    },
    "shape533": {
      "const": "admin.pricing.list"
    },
    "shape534": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape243"
      }
    },
    "shape531": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
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
      "const": "admin.pricing.set"
    },
    "shape539": {
      "const": "TOKEN"
    },
    "shape540": {
      "const": "INCLUDED"
    },
    "shape541": {
      "const": "ZERO_RATE"
    },
    "shape542": {
      "const": "UNPRICED"
    },
    "shape538": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape539"
        },
        {
          "$ref": "#/$defs/shape540"
        },
        {
          "$ref": "#/$defs/shape541"
        },
        {
          "$ref": "#/$defs/shape542"
        }
      ]
    },
    "shape543": {
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
    "shape546": {
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
    "shape545": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape28"
        },
        "rates": {
          "$ref": "#/$defs/shape546"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape544": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape545"
      }
    },
    "shape537": {
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
          "$ref": "#/$defs/shape538"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "rates": {
          "$ref": "#/$defs/shape543"
        },
        "requestFee": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "tiers": {
          "$ref": "#/$defs/shape544"
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
    "shape535": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
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
    "shape548": {
      "const": "admin.accounts"
    },
    "shape547": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
        },
        "operation": {
          "$ref": "#/$defs/shape548"
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
    "shape550": {
      "const": "admin.account.create"
    },
    "shape551": {
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
    "shape549": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
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
      "const": "admin.account.update"
    },
    "shape554": {
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
    "shape552": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
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
      "const": "admin.account.reset"
    },
    "shape557": {
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
    "shape555": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
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
    "shape559": {
      "const": "admin.sessions"
    },
    "shape560": {
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
    "shape558": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
        },
        "operation": {
          "$ref": "#/$defs/shape559"
        },
        "payload": {
          "$ref": "#/$defs/shape560"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape562": {
      "const": "admin.session.revoke"
    },
    "shape563": {
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
    "shape561": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
        },
        "operation": {
          "$ref": "#/$defs/shape562"
        },
        "payload": {
          "$ref": "#/$defs/shape563"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape565": {
      "const": "admin.audit"
    },
    "shape566": {
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
    "shape564": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
        },
        "operation": {
          "$ref": "#/$defs/shape565"
        },
        "payload": {
          "$ref": "#/$defs/shape566"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape568": {
      "const": "admin.service.accounts"
    },
    "shape567": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
        },
        "operation": {
          "$ref": "#/$defs/shape568"
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
    "shape570": {
      "const": "admin.service.account.create"
    },
    "shape571": {
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
    "shape569": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
        },
        "operation": {
          "$ref": "#/$defs/shape570"
        },
        "payload": {
          "$ref": "#/$defs/shape571"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape573": {
      "const": "admin.service.account.update"
    },
    "shape574": {
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
    "shape572": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
        },
        "operation": {
          "$ref": "#/$defs/shape573"
        },
        "payload": {
          "$ref": "#/$defs/shape574"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape576": {
      "const": "admin.service.tokens"
    },
    "shape577": {
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
    "shape575": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
        },
        "operation": {
          "$ref": "#/$defs/shape576"
        },
        "payload": {
          "$ref": "#/$defs/shape577"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape579": {
      "const": "admin.service.token.create"
    },
    "shape582": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape527"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape581": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape582"
      }
    },
    "shape580": {
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
          "$ref": "#/$defs/shape581"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape578": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
        },
        "operation": {
          "$ref": "#/$defs/shape579"
        },
        "payload": {
          "$ref": "#/$defs/shape580"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape584": {
      "const": "admin.service.token.rotate"
    },
    "shape585": {
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
    "shape583": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
        },
        "operation": {
          "$ref": "#/$defs/shape584"
        },
        "payload": {
          "$ref": "#/$defs/shape585"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape587": {
      "const": "admin.service.token.revoke"
    },
    "shape588": {
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
    "shape586": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
        },
        "operation": {
          "$ref": "#/$defs/shape587"
        },
        "payload": {
          "$ref": "#/$defs/shape588"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape590": {
      "const": "server-project-create"
    },
    "shape592": {
      "const": "MANAGED"
    },
    "shape593": {
      "const": "DISJOINT"
    },
    "shape591": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape592"
        },
        {
          "$ref": "#/$defs/shape593"
        }
      ]
    },
    "shape594": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape595": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape589": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape590"
        },
        "applicationRoot": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape591"
        },
        "workspace": {
          "$ref": "#/$defs/shape6"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape594"
        },
        "writePaths": {
          "$ref": "#/$defs/shape595"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape597": {
      "const": "server-setup"
    },
    "shape596": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape597"
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
    "shape599": {
      "const": "connect"
    },
    "shape598": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape599"
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
    "shape601": {
      "const": "connection-select"
    },
    "shape600": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape601"
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
    "shape603": {
      "const": "connection-preferences"
    },
    "shape605": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape606": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape29"
      }
    },
    "shape604": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape605"
        },
        "drafts": {
          "$ref": "#/$defs/shape605"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape606"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape606"
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
    "shape602": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape6"
        },
        "action": {
          "$ref": "#/$defs/shape603"
        },
        "preference": {
          "$ref": "#/$defs/shape604"
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
    "shape608": {
      "const": "connection-rename"
    },
    "shape607": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape608"
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
    "shape610": {
      "const": "connection-remove"
    },
    "shape609": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape610"
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
    "shape612": {
      "const": "personal-section"
    },
    "shape614": {
      "const": "In"
    },
    "shape615": {
      "const": "Out"
    },
    "shape616": {
      "const": "Resources"
    },
    "shape617": {
      "const": "Archive"
    },
    "shape618": {
      "const": "Planning"
    },
    "shape619": {
      "const": "Bots"
    },
    "shape613": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape614"
        },
        {
          "$ref": "#/$defs/shape615"
        },
        {
          "$ref": "#/$defs/shape616"
        },
        {
          "$ref": "#/$defs/shape617"
        },
        {
          "$ref": "#/$defs/shape618"
        },
        {
          "$ref": "#/$defs/shape619"
        }
      ]
    },
    "shape611": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape612"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "section": {
          "$ref": "#/$defs/shape613"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape621": {
      "const": "personal-bots"
    },
    "shape620": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape621"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape623": {
      "const": "personal-recreate"
    },
    "shape622": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape623"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape625": {
      "const": "files-choose"
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
        "action"
      ],
      "additionalProperties": false
    },
    "shape627": {
      "const": "files-withdraw"
    },
    "shape626": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape627"
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
    "shape629": {
      "const": "sync-refresh"
    },
    "shape628": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape629"
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
    "shape631": {
      "const": "sync-inspect"
    },
    "shape630": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape631"
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
    "shape633": {
      "const": "sync-change"
    },
    "shape635": {
      "const": "on"
    },
    "shape636": {
      "const": "off"
    },
    "shape637": {
      "const": "now"
    },
    "shape634": {
      "anyOf": [
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
    "shape632": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape633"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape634"
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
    "shape639": {
      "const": "sync-resolve"
    },
    "shape641": {
      "const": "theirs"
    },
    "shape642": {
      "const": "done"
    },
    "shape640": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape61"
        },
        {
          "$ref": "#/$defs/shape641"
        },
        {
          "$ref": "#/$defs/shape642"
        }
      ]
    },
    "shape638": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape639"
        },
        "how": {
          "$ref": "#/$defs/shape640"
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
    "shape645": {
      "const": "project-open"
    },
    "shape646": {
      "const": "project-remove"
    },
    "shape644": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape645"
        },
        {
          "$ref": "#/$defs/shape646"
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
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape648": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape265"
        },
        {
          "$ref": "#/$defs/shape324"
        }
      ]
    },
    "shape647": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape648"
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
    "shape650": {
      "const": "history"
    },
    "shape649": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape650"
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
    "shape652": {
      "const": "select"
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
        "action"
      ],
      "additionalProperties": false
    },
    "shape654": {
      "const": "trajectory"
    },
    "shape653": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape654"
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
    "shape656": {
      "const": "board-swarm-types"
    },
    "shape655": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape656"
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
    "shape658": {
      "const": "board-post-topics"
    },
    "shape657": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape658"
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
    "shape660": {
      "const": "board-create"
    },
    "shape659": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape660"
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
    "shape661": {
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
    "shape663": {
      "const": "board-retry"
    },
    "shape662": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape663"
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
    "shape665": {
      "const": "board-post"
    },
    "shape664": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape665"
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
    "shape667": {
      "const": "workspace-chat"
    },
    "shape666": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape667"
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
    "shape669": {
      "const": "workspace-layout"
    },
    "shape668": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape669"
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
    "shape671": {
      "const": "workspace-refresh"
    },
    "shape670": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape671"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape673": {
      "const": "library"
    },
    "shape675": {
      "const": "sources"
    },
    "shape676": {
      "const": "documents"
    },
    "shape677": {
      "const": "memories"
    },
    "shape678": {
      "const": "manual"
    },
    "shape674": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape425"
        },
        {
          "$ref": "#/$defs/shape675"
        },
        {
          "$ref": "#/$defs/shape676"
        },
        {
          "$ref": "#/$defs/shape677"
        },
        {
          "$ref": "#/$defs/shape678"
        }
      ]
    },
    "shape672": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape673"
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
          "$ref": "#/$defs/shape674"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape680": {
      "const": "library-view"
    },
    "shape679": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape680"
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
          "$ref": "#/$defs/shape674"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape683": {
      "const": "library-refresh"
    },
    "shape684": {
      "const": "library-citations"
    },
    "shape682": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape683"
        },
        {
          "$ref": "#/$defs/shape684"
        }
      ]
    },
    "shape681": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape682"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape686": {
      "const": "library-documents"
    },
    "shape685": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape686"
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
    "shape689": {
      "const": "library-document"
    },
    "shape690": {
      "const": "library-memory"
    },
    "shape691": {
      "const": "library-chunk"
    },
    "shape692": {
      "const": "library-conversation"
    },
    "shape688": {
      "anyOf": [
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
    "shape687": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape688"
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
    "shape694": {
      "const": "library-source-text"
    },
    "shape693": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape694"
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
    "shape696": {
      "const": "library-stance"
    },
    "shape695": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape696"
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
    "shape698": {
      "const": "library-search"
    },
    "shape700": {
      "const": "retrieve"
    },
    "shape701": {
      "const": "recall"
    },
    "shape702": {
      "const": "navigate"
    },
    "shape699": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape428"
        },
        {
          "$ref": "#/$defs/shape178"
        },
        {
          "$ref": "#/$defs/shape676"
        },
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
    "shape704": {
      "const": "lexical"
    },
    "shape705": {
      "const": "semantic"
    },
    "shape706": {
      "const": "hybrid"
    },
    "shape703": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape704"
        },
        {
          "$ref": "#/$defs/shape705"
        },
        {
          "$ref": "#/$defs/shape706"
        }
      ]
    },
    "shape697": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape698"
        },
        "kind": {
          "$ref": "#/$defs/shape699"
        },
        "mode": {
          "$ref": "#/$defs/shape703"
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
    "shape708": {
      "const": "library-maintain"
    },
    "shape710": {
      "const": "invalidate"
    },
    "shape711": {
      "const": "resolve"
    },
    "shape712": {
      "const": "reembed"
    },
    "shape713": {
      "const": "reconsider"
    },
    "shape709": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape710"
        },
        {
          "$ref": "#/$defs/shape711"
        },
        {
          "$ref": "#/$defs/shape712"
        },
        {
          "$ref": "#/$defs/shape713"
        }
      ]
    },
    "shape707": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape29"
        },
        "action": {
          "$ref": "#/$defs/shape708"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape709"
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
    "shape715": {
      "const": "builder-outputs"
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
      "const": "builder-trajectory"
    },
    "shape716": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape717"
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
    "shape719": {
      "const": "builder-prepare"
    },
    "shape718": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape719"
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
    "shape721": {
      "const": "builder-start"
    },
    "shape720": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape721"
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
    "shape723": {
      "const": "activity"
    },
    "shape725": {
      "const": "inbox"
    },
    "shape726": {
      "const": "runs"
    },
    "shape727": {
      "const": "definitions"
    },
    "shape728": {
      "const": "schedules"
    },
    "shape729": {
      "const": "builder"
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
        },
        {
          "$ref": "#/$defs/shape728"
        },
        {
          "$ref": "#/$defs/shape729"
        }
      ]
    },
    "shape722": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape723"
        },
        "view": {
          "$ref": "#/$defs/shape724"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape731": {
      "const": "activity-view"
    },
    "shape730": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape731"
        },
        "view": {
          "$ref": "#/$defs/shape724"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape733": {
      "const": "activity-refresh"
    },
    "shape732": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape733"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape735": {
      "const": "question-refresh"
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
      "const": "inbox-read"
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
      "const": "inbox-more"
    },
    "shape738": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape739"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape741": {
      "const": "run-detail"
    },
    "shape740": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape741"
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
    "shape743": {
      "const": "schedule-refresh"
    },
    "shape745": {
      "const": "next"
    },
    "shape746": {
      "const": "previous"
    },
    "shape747": {
      "const": "first"
    },
    "shape744": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape745"
        },
        {
          "$ref": "#/$defs/shape746"
        },
        {
          "$ref": "#/$defs/shape747"
        }
      ]
    },
    "shape749": {
      "const": "files"
    },
    "shape748": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape728"
        },
        {
          "$ref": "#/$defs/shape749"
        }
      ]
    },
    "shape742": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape743"
        },
        "direction": {
          "$ref": "#/$defs/shape744"
        },
        "section": {
          "$ref": "#/$defs/shape748"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape751": {
      "const": "schedule-preview"
    },
    "shape750": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape751"
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
    "shape753": {
      "const": "schedule-save"
    },
    "shape757": {
      "const": "skill"
    },
    "shape758": {
      "const": "orchestration"
    },
    "shape756": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape108"
        },
        {
          "$ref": "#/$defs/shape757"
        },
        {
          "$ref": "#/$defs/shape758"
        }
      ]
    },
    "shape760": {
      "const": "INHERITED"
    },
    "shape761": {
      "const": "SUMMARISED"
    },
    "shape762": {
      "const": "NEW"
    },
    "shape763": {
      "const": "DIRECT"
    },
    "shape759": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape760"
        },
        {
          "$ref": "#/$defs/shape761"
        },
        {
          "$ref": "#/$defs/shape762"
        },
        {
          "$ref": "#/$defs/shape763"
        }
      ]
    },
    "shape755": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape756"
        },
        "mode": {
          "$ref": "#/$defs/shape759"
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
    "shape765": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape28"
        }
      ]
    },
    "shape764": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape765"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape765"
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
    "shape768": {
      "const": "mailbox"
    },
    "shape769": {
      "const": "message"
    },
    "shape767": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape178"
        },
        {
          "$ref": "#/$defs/shape768"
        },
        {
          "$ref": "#/$defs/shape769"
        }
      ]
    },
    "shape766": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape767"
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
    "shape770": {
      "const": 1
    },
    "shape754": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape755"
        },
        "cron": {
          "$ref": "#/$defs/shape6"
        },
        "limits": {
          "$ref": "#/$defs/shape764"
        },
        "paused": {
          "$ref": "#/$defs/shape29"
        },
        "target": {
          "$ref": "#/$defs/shape766"
        },
        "version": {
          "$ref": "#/$defs/shape770"
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
    "shape772": {
      "const": "workspace"
    },
    "shape771": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape62"
        },
        {
          "$ref": "#/$defs/shape772"
        }
      ]
    },
    "shape752": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape753"
        },
        "definition": {
          "$ref": "#/$defs/shape754"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape771"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape774": {
      "const": "schedule-file-save"
    },
    "shape773": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape774"
        },
        "definition": {
          "$ref": "#/$defs/shape754"
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
          "$ref": "#/$defs/shape771"
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
    "shape776": {
      "const": "schedule-sync"
    },
    "shape775": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape776"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape771"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape778": {
      "const": "schedule-change"
    },
    "shape780": {
      "const": "schedule"
    },
    "shape781": {
      "const": "trigger"
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
    "shape777": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape778"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape779"
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
    "shape783": {
      "const": "schedule-fire"
    },
    "shape782": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape783"
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
    "shape785": {
      "const": "run-definitions"
    },
    "shape784": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape785"
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
    "shape787": {
      "const": "run-record"
    },
    "shape786": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape787"
        },
        "before": {
          "$ref": "#/$defs/shape28"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "kinds": {
          "$ref": "#/$defs/shape595"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape789": {
      "const": "run-answer"
    },
    "shape791": {
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
    "shape790": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape791"
      }
    },
    "shape788": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape789"
        },
        "answer": {
          "$ref": "#/$defs/shape6"
        },
        "choices": {
          "$ref": "#/$defs/shape790"
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
    "shape793": {
      "const": "run-cancel"
    },
    "shape792": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape793"
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
    "shape795": {
      "const": "run-resume"
    },
    "shape794": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape795"
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
    "shape797": {
      "const": "run-trajectory"
    },
    "shape799": {
      "const": "conductor"
    },
    "shape800": {
      "const": "caller"
    },
    "shape798": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape799"
        },
        {
          "$ref": "#/$defs/shape800"
        }
      ]
    },
    "shape796": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape797"
        },
        "actor": {
          "$ref": "#/$defs/shape798"
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
    "shape802": {
      "const": "run-stage-trajectory"
    },
    "shape801": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape802"
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
    "shape804": {
      "const": "delegate-trajectory"
    },
    "shape803": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape804"
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
    "shape807": {
      "const": "board-inspection"
    },
    "shape808": {
      "const": "board-view"
    },
    "shape806": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape807"
        },
        {
          "$ref": "#/$defs/shape808"
        }
      ]
    },
    "shape810": {
      "const": "board"
    },
    "shape811": {
      "const": "swarm"
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
    "shape805": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape809"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape814": {
      "const": "board-refresh"
    },
    "shape815": {
      "const": "board-more"
    },
    "shape813": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape814"
        },
        {
          "$ref": "#/$defs/shape815"
        }
      ]
    },
    "shape812": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape813"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape817": {
      "const": "board-topic"
    },
    "shape816": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape817"
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
    "shape819": {
      "const": "board-trajectory"
    },
    "shape818": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape819"
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
    "shape821": {
      "const": "context"
    },
    "shape820": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape821"
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
    "shape823": {
      "const": "open-link"
    },
    "shape822": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape823"
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
    "shape825": {
      "const": "copy-text"
    },
    "shape824": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape825"
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
    "shape827": {
      "const": "workflow-start"
    },
    "shape826": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape827"
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
    "shape828": {
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
    "shape830": {
      "const": "cancel"
    },
    "shape829": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape830"
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
    "shape832": {
      "const": "answer"
    },
    "shape833": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape177"
        },
        {
          "$ref": "#/$defs/shape179"
        }
      ]
    },
    "shape831": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape832"
        },
        "decision": {
          "$ref": "#/$defs/shape833"
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
    "shape835": {
      "const": "native-mcp"
    },
    "shape834": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape835"
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
    "shape837": {
      "const": "provider-management"
    },
    "shape838": {
      "const": "provider.connection.list"
    },
    "shape839": {
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
    "shape836": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
        },
        "operation": {
          "$ref": "#/$defs/shape838"
        },
        "payload": {
          "$ref": "#/$defs/shape839"
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
      "const": "provider.connection.status"
    },
    "shape842": {
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
    "shape840": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
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
      "const": "provider.connection.receipt"
    },
    "shape845": {
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
    "shape843": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
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
      "const": "provider.connection.save"
    },
    "shape851": {
      "const": "Authorization"
    },
    "shape852": {
      "const": "X-API-Key"
    },
    "shape853": {
      "const": "Api-Key"
    },
    "shape850": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
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
      "const": "API_KEY"
    },
    "shape856": {
      "const": "OAUTH_PKCE"
    },
    "shape857": {
      "const": "CLIENT_CREDENTIALS"
    },
    "shape854": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape855"
        },
        {
          "$ref": "#/$defs/shape856"
        },
        {
          "$ref": "#/$defs/shape857"
        }
      ]
    },
    "shape859": {
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
    "shape858": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape859"
        }
      ]
    },
    "shape849": {
      "type": "object",
      "properties": {
        "apiKeyHeader": {
          "$ref": "#/$defs/shape850"
        },
        "authentication": {
          "$ref": "#/$defs/shape854"
        },
        "endpoint": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "oauth": {
          "$ref": "#/$defs/shape858"
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
    "shape848": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape849"
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
    "shape846": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
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
    "shape861": {
      "const": "provider.connection.change"
    },
    "shape864": {
      "const": "ENABLE"
    },
    "shape865": {
      "const": "DISABLE"
    },
    "shape866": {
      "const": "DELETE"
    },
    "shape863": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape864"
        },
        {
          "$ref": "#/$defs/shape865"
        },
        {
          "$ref": "#/$defs/shape866"
        }
      ]
    },
    "shape862": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape863"
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
    "shape860": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
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
    "shape868": {
      "const": "provider.credential.status"
    },
    "shape869": {
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
    "shape867": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
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
      "const": "provider.credential.put"
    },
    "shape872": {
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
    "shape870": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
        },
        "operation": {
          "$ref": "#/$defs/shape871"
        },
        "payload": {
          "$ref": "#/$defs/shape872"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape874": {
      "const": "provider.credential.revoke"
    },
    "shape875": {
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
    "shape873": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
        },
        "operation": {
          "$ref": "#/$defs/shape874"
        },
        "payload": {
          "$ref": "#/$defs/shape875"
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
      "const": "provider.credential.rekey"
    },
    "shape876": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
        },
        "operation": {
          "$ref": "#/$defs/shape877"
        },
        "payload": {
          "$ref": "#/$defs/shape875"
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
      "const": "provider.credential.authorize"
    },
    "shape878": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
        },
        "operation": {
          "$ref": "#/$defs/shape879"
        },
        "payload": {
          "$ref": "#/$defs/shape875"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape881": {
      "const": "provider.credential.refresh"
    },
    "shape880": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
        },
        "operation": {
          "$ref": "#/$defs/shape881"
        },
        "payload": {
          "$ref": "#/$defs/shape875"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape883": {
      "const": "tool.scope.host.list"
    },
    "shape884": {
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
    "shape882": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
        },
        "operation": {
          "$ref": "#/$defs/shape883"
        },
        "payload": {
          "$ref": "#/$defs/shape884"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape886": {
      "const": "tool.scope.host.status"
    },
    "shape887": {
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
    "shape885": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
        },
        "operation": {
          "$ref": "#/$defs/shape886"
        },
        "payload": {
          "$ref": "#/$defs/shape887"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape889": {
      "const": "tool.scope.host.receipt"
    },
    "shape890": {
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
    "shape888": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
        },
        "operation": {
          "$ref": "#/$defs/shape889"
        },
        "payload": {
          "$ref": "#/$defs/shape890"
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
      "const": "tool.scope.host.save"
    },
    "shape894": {
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
    "shape893": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape894"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape765"
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
    "shape891": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
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
    "shape896": {
      "const": "tool.scope.host.change"
    },
    "shape897": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape863"
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
    "shape895": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
        },
        "operation": {
          "$ref": "#/$defs/shape896"
        },
        "payload": {
          "$ref": "#/$defs/shape897"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape899": {
      "const": "tool.scope.list"
    },
    "shape900": {
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
    "shape898": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
        },
        "operation": {
          "$ref": "#/$defs/shape899"
        },
        "payload": {
          "$ref": "#/$defs/shape900"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape902": {
      "const": "provider-management-pending"
    },
    "shape901": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape902"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape904": {
      "const": "provider-management-recover"
    },
    "shape907": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape847"
        },
        {
          "$ref": "#/$defs/shape861"
        }
      ]
    },
    "shape906": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape7"
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
        "connection",
        "expectedRevision",
        "operation",
        "project",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape909": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape871"
        },
        {
          "$ref": "#/$defs/shape874"
        },
        {
          "$ref": "#/$defs/shape877"
        },
        {
          "$ref": "#/$defs/shape879"
        },
        {
          "$ref": "#/$defs/shape881"
        }
      ]
    },
    "shape908": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape909"
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
    "shape911": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape892"
        },
        {
          "$ref": "#/$defs/shape896"
        }
      ]
    },
    "shape910": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape765"
        },
        "operation": {
          "$ref": "#/$defs/shape911"
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
    "shape905": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape906"
        },
        {
          "$ref": "#/$defs/shape908"
        },
        {
          "$ref": "#/$defs/shape910"
        }
      ]
    },
    "shape903": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape904"
        },
        "intent": {
          "$ref": "#/$defs/shape905"
        }
      },
      "required": [
        "action",
        "intent"
      ],
      "additionalProperties": false
    },
    "shape913": {
      "const": "information-import-recovery-save"
    },
    "shape915": {
      "const": "plowshare-information-import-recovery/1"
    },
    "shape914": {
      "type": "object",
      "properties": {
        "context": {
          "$ref": "#/$defs/shape6"
        },
        "format": {
          "$ref": "#/$defs/shape915"
        },
        "plan": {
          "$ref": "#/$defs/shape286"
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
    "shape912": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape913"
        },
        "recovery": {
          "$ref": "#/$defs/shape914"
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
          "$ref": "#/$defs/shape221"
        },
        {
          "$ref": "#/$defs/shape224"
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
          "$ref": "#/$defs/shape247"
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
          "$ref": "#/$defs/shape271"
        },
        {
          "$ref": "#/$defs/shape274"
        },
        {
          "$ref": "#/$defs/shape277"
        },
        {
          "$ref": "#/$defs/shape280"
        },
        {
          "$ref": "#/$defs/shape283"
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
          "$ref": "#/$defs/shape391"
        },
        {
          "$ref": "#/$defs/shape394"
        },
        {
          "$ref": "#/$defs/shape399"
        },
        {
          "$ref": "#/$defs/shape402"
        },
        {
          "$ref": "#/$defs/shape405"
        },
        {
          "$ref": "#/$defs/shape408"
        },
        {
          "$ref": "#/$defs/shape415"
        },
        {
          "$ref": "#/$defs/shape418"
        },
        {
          "$ref": "#/$defs/shape421"
        },
        {
          "$ref": "#/$defs/shape424"
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
          "$ref": "#/$defs/shape451"
        },
        {
          "$ref": "#/$defs/shape454"
        },
        {
          "$ref": "#/$defs/shape457"
        },
        {
          "$ref": "#/$defs/shape460"
        },
        {
          "$ref": "#/$defs/shape463"
        },
        {
          "$ref": "#/$defs/shape466"
        },
        {
          "$ref": "#/$defs/shape469"
        },
        {
          "$ref": "#/$defs/shape472"
        },
        {
          "$ref": "#/$defs/shape475"
        },
        {
          "$ref": "#/$defs/shape478"
        },
        {
          "$ref": "#/$defs/shape481"
        },
        {
          "$ref": "#/$defs/shape493"
        },
        {
          "$ref": "#/$defs/shape496"
        },
        {
          "$ref": "#/$defs/shape499"
        },
        {
          "$ref": "#/$defs/shape502"
        },
        {
          "$ref": "#/$defs/shape505"
        },
        {
          "$ref": "#/$defs/shape508"
        },
        {
          "$ref": "#/$defs/shape511"
        },
        {
          "$ref": "#/$defs/shape514"
        },
        {
          "$ref": "#/$defs/shape520"
        },
        {
          "$ref": "#/$defs/shape531"
        },
        {
          "$ref": "#/$defs/shape535"
        },
        {
          "$ref": "#/$defs/shape547"
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
          "$ref": "#/$defs/shape558"
        },
        {
          "$ref": "#/$defs/shape561"
        },
        {
          "$ref": "#/$defs/shape564"
        },
        {
          "$ref": "#/$defs/shape567"
        },
        {
          "$ref": "#/$defs/shape569"
        },
        {
          "$ref": "#/$defs/shape572"
        },
        {
          "$ref": "#/$defs/shape575"
        },
        {
          "$ref": "#/$defs/shape578"
        },
        {
          "$ref": "#/$defs/shape583"
        },
        {
          "$ref": "#/$defs/shape586"
        },
        {
          "$ref": "#/$defs/shape589"
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
          "$ref": "#/$defs/shape607"
        },
        {
          "$ref": "#/$defs/shape609"
        },
        {
          "$ref": "#/$defs/shape611"
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
          "$ref": "#/$defs/shape630"
        },
        {
          "$ref": "#/$defs/shape632"
        },
        {
          "$ref": "#/$defs/shape638"
        },
        {
          "$ref": "#/$defs/shape643"
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
          "$ref": "#/$defs/shape659"
        },
        {
          "$ref": "#/$defs/shape661"
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
          "$ref": "#/$defs/shape670"
        },
        {
          "$ref": "#/$defs/shape672"
        },
        {
          "$ref": "#/$defs/shape679"
        },
        {
          "$ref": "#/$defs/shape681"
        },
        {
          "$ref": "#/$defs/shape685"
        },
        {
          "$ref": "#/$defs/shape687"
        },
        {
          "$ref": "#/$defs/shape693"
        },
        {
          "$ref": "#/$defs/shape695"
        },
        {
          "$ref": "#/$defs/shape697"
        },
        {
          "$ref": "#/$defs/shape707"
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
          "$ref": "#/$defs/shape740"
        },
        {
          "$ref": "#/$defs/shape742"
        },
        {
          "$ref": "#/$defs/shape750"
        },
        {
          "$ref": "#/$defs/shape752"
        },
        {
          "$ref": "#/$defs/shape773"
        },
        {
          "$ref": "#/$defs/shape775"
        },
        {
          "$ref": "#/$defs/shape777"
        },
        {
          "$ref": "#/$defs/shape782"
        },
        {
          "$ref": "#/$defs/shape784"
        },
        {
          "$ref": "#/$defs/shape786"
        },
        {
          "$ref": "#/$defs/shape788"
        },
        {
          "$ref": "#/$defs/shape792"
        },
        {
          "$ref": "#/$defs/shape794"
        },
        {
          "$ref": "#/$defs/shape796"
        },
        {
          "$ref": "#/$defs/shape801"
        },
        {
          "$ref": "#/$defs/shape803"
        },
        {
          "$ref": "#/$defs/shape805"
        },
        {
          "$ref": "#/$defs/shape812"
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
          "$ref": "#/$defs/shape826"
        },
        {
          "$ref": "#/$defs/shape828"
        },
        {
          "$ref": "#/$defs/shape829"
        },
        {
          "$ref": "#/$defs/shape831"
        },
        {
          "$ref": "#/$defs/shape834"
        },
        {
          "$ref": "#/$defs/shape836"
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
          "$ref": "#/$defs/shape860"
        },
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
          "$ref": "#/$defs/shape876"
        },
        {
          "$ref": "#/$defs/shape878"
        },
        {
          "$ref": "#/$defs/shape880"
        },
        {
          "$ref": "#/$defs/shape882"
        },
        {
          "$ref": "#/$defs/shape885"
        },
        {
          "$ref": "#/$defs/shape888"
        },
        {
          "$ref": "#/$defs/shape891"
        },
        {
          "$ref": "#/$defs/shape895"
        },
        {
          "$ref": "#/$defs/shape898"
        },
        {
          "$ref": "#/$defs/shape901"
        },
        {
          "$ref": "#/$defs/shape903"
        },
        {
          "$ref": "#/$defs/shape912"
        }
      ]
    }
  }
}
