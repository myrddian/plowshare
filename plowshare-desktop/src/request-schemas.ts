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
      "const": "tags.groups"
    },
    "shape395": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape216"
      }
    },
    "shape394": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape395"
        }
      ]
    },
    "shape393": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape394"
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
    "shape397": {
      "const": "inventory"
    },
    "shape398": {
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
    "shape396": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape397"
        },
        "payload": {
          "$ref": "#/$defs/shape398"
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
      "const": "acquisitions"
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
      "const": "status"
    },
    "shape404": {
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
      "const": "await"
    },
    "shape410": {
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
    "shape411": {
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
    "shape409": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape410"
        },
        {
          "$ref": "#/$defs/shape411"
        }
      ]
    },
    "shape408": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape409"
      }
    },
    "shape407": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape408"
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
    "shape413": {
      "const": "read"
    },
    "shape414": {
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
    "shape412": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape413"
        },
        "payload": {
          "$ref": "#/$defs/shape414"
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
      "const": "outline"
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
      "const": "symbols"
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
      "const": "search"
    },
    "shape423": {
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
      "const": "rank"
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
      "const": "ask"
    },
    "shape429": {
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
      "const": "evidence.record"
    },
    "shape432": {
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
      "const": "evidence.read"
    },
    "shape435": {
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
      "const": "record.report"
    },
    "shape442": {
      "const": "holds"
    },
    "shape443": {
      "const": "weakened"
    },
    "shape444": {
      "const": "refuted"
    },
    "shape445": {
      "const": "not_checked"
    },
    "shape441": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape442"
        },
        {
          "$ref": "#/$defs/shape443"
        },
        {
          "$ref": "#/$defs/shape444"
        },
        {
          "$ref": "#/$defs/shape445"
        }
      ]
    },
    "shape440": {
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
          "$ref": "#/$defs/shape441"
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
    "shape439": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape440"
      }
    },
    "shape447": {
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
    "shape446": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape447"
      }
    },
    "shape438": {
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
          "$ref": "#/$defs/shape439"
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
          "$ref": "#/$defs/shape446"
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
    "shape449": {
      "const": "finalise"
    },
    "shape450": {
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
    "shape448": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape449"
        },
        "payload": {
          "$ref": "#/$defs/shape450"
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
      "const": "link"
    },
    "shape453": {
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
      "const": "unlink"
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
      "const": "share"
    },
    "shape459": {
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
      "const": "unshare"
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
      "const": "withdraw"
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
      "const": "exclude"
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
      "const": "unexclude"
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
      "const": "restore"
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
      "const": "delete"
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
      "const": "retry"
    },
    "shape483": {
      "const": "extract"
    },
    "shape484": {
      "const": "derive"
    },
    "shape485": {
      "const": "embed"
    },
    "shape486": {
      "const": "summarise"
    },
    "shape487": {
      "const": "summary_embed"
    },
    "shape488": {
      "const": "autoTag"
    },
    "shape489": {
      "const": "tagGroups"
    },
    "shape482": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape489"
        }
      ]
    },
    "shape481": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape482"
      }
    },
    "shape480": {
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
          "$ref": "#/$defs/shape481"
        },
        "view": {
          "$ref": "#/$defs/shape199"
        }
      },
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
    "shape491": {
      "const": "rebuild"
    },
    "shape492": {
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
          "$ref": "#/$defs/shape481"
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
    "shape490": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape196"
        },
        "operation": {
          "$ref": "#/$defs/shape491"
        },
        "payload": {
          "$ref": "#/$defs/shape492"
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
      "const": "allowance"
    },
    "shape495": {
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
      "const": "events"
    },
    "shape498": {
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
      "const": "migration.list"
    },
    "shape501": {
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
      "const": "migration.adopt"
    },
    "shape504": {
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
      "const": "migration.inspect"
    },
    "shape507": {
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
      "const": "migration.release"
    },
    "shape510": {
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
    "shape513": {
      "const": "bootstrap"
    },
    "shape514": {
      "const": "demo"
    },
    "shape515": {
      "const": "disconnect"
    },
    "shape516": {
      "const": "approvals-refresh"
    },
    "shape512": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape375"
        },
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
    "shape511": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape512"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape518": {
      "const": "project-access"
    },
    "shape520": {
      "const": "project.access"
    },
    "shape521": {
      "const": "project.member.add"
    },
    "shape522": {
      "const": "project.member.remove"
    },
    "shape523": {
      "const": "project.member.role"
    },
    "shape519": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape520"
        },
        {
          "$ref": "#/$defs/shape521"
        },
        {
          "$ref": "#/$defs/shape522"
        },
        {
          "$ref": "#/$defs/shape523"
        }
      ]
    },
    "shape525": {
      "const": "VIEWER"
    },
    "shape526": {
      "const": "CONTRIBUTOR"
    },
    "shape527": {
      "const": "MANAGER"
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
        }
      ]
    },
    "shape517": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape518"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape519"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape524"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape529": {
      "const": "server-admin"
    },
    "shape530": {
      "const": "admin.pricing.list"
    },
    "shape531": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape243"
      }
    },
    "shape528": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
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
      "const": "admin.pricing.set"
    },
    "shape536": {
      "const": "TOKEN"
    },
    "shape537": {
      "const": "INCLUDED"
    },
    "shape538": {
      "const": "ZERO_RATE"
    },
    "shape539": {
      "const": "UNPRICED"
    },
    "shape535": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape536"
        },
        {
          "$ref": "#/$defs/shape537"
        },
        {
          "$ref": "#/$defs/shape538"
        },
        {
          "$ref": "#/$defs/shape539"
        }
      ]
    },
    "shape540": {
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
    "shape542": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape28"
        },
        "rates": {
          "$ref": "#/$defs/shape543"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape541": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape542"
      }
    },
    "shape534": {
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
          "$ref": "#/$defs/shape535"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "rates": {
          "$ref": "#/$defs/shape540"
        },
        "requestFee": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "tiers": {
          "$ref": "#/$defs/shape541"
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
    "shape532": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
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
    "shape545": {
      "const": "admin.accounts"
    },
    "shape544": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
        },
        "operation": {
          "$ref": "#/$defs/shape545"
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
    "shape547": {
      "const": "admin.account.create"
    },
    "shape548": {
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
    "shape546": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
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
      "const": "admin.account.update"
    },
    "shape551": {
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
    "shape549": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
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
      "const": "admin.account.reset"
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
          "$ref": "#/$defs/shape529"
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
      "const": "admin.sessions"
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
          "$ref": "#/$defs/shape529"
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
      "const": "admin.session.revoke"
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
          "$ref": "#/$defs/shape529"
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
      "const": "admin.audit"
    },
    "shape563": {
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
    "shape561": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
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
      "const": "admin.service.accounts"
    },
    "shape564": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
        },
        "operation": {
          "$ref": "#/$defs/shape565"
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
    "shape567": {
      "const": "admin.service.account.create"
    },
    "shape568": {
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
    "shape566": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
        },
        "operation": {
          "$ref": "#/$defs/shape567"
        },
        "payload": {
          "$ref": "#/$defs/shape568"
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
      "const": "admin.service.account.update"
    },
    "shape571": {
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
    "shape569": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
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
      "const": "admin.service.tokens"
    },
    "shape574": {
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
    "shape572": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
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
      "const": "admin.service.token.create"
    },
    "shape579": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape524"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape578": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape579"
      }
    },
    "shape577": {
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
          "$ref": "#/$defs/shape578"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape575": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
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
    "shape581": {
      "const": "admin.service.token.rotate"
    },
    "shape582": {
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
    "shape580": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
        },
        "operation": {
          "$ref": "#/$defs/shape581"
        },
        "payload": {
          "$ref": "#/$defs/shape582"
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
      "const": "admin.service.token.revoke"
    },
    "shape585": {
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
    "shape583": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
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
      "const": "server-project-create"
    },
    "shape589": {
      "const": "MANAGED"
    },
    "shape590": {
      "const": "DISJOINT"
    },
    "shape588": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape589"
        },
        {
          "$ref": "#/$defs/shape590"
        }
      ]
    },
    "shape591": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape592": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape586": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape587"
        },
        "applicationRoot": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape588"
        },
        "workspace": {
          "$ref": "#/$defs/shape6"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape591"
        },
        "writePaths": {
          "$ref": "#/$defs/shape592"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape594": {
      "const": "server-setup"
    },
    "shape593": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape594"
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
    "shape596": {
      "const": "connect"
    },
    "shape595": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape596"
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
    "shape598": {
      "const": "connection-select"
    },
    "shape597": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape598"
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
    "shape600": {
      "const": "connection-preferences"
    },
    "shape602": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape603": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape29"
      }
    },
    "shape601": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape602"
        },
        "drafts": {
          "$ref": "#/$defs/shape602"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape603"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape603"
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
    "shape599": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape6"
        },
        "action": {
          "$ref": "#/$defs/shape600"
        },
        "preference": {
          "$ref": "#/$defs/shape601"
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
    "shape605": {
      "const": "connection-rename"
    },
    "shape604": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape605"
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
    "shape607": {
      "const": "connection-remove"
    },
    "shape606": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape607"
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
    "shape609": {
      "const": "personal-section"
    },
    "shape611": {
      "const": "In"
    },
    "shape612": {
      "const": "Out"
    },
    "shape613": {
      "const": "Resources"
    },
    "shape614": {
      "const": "Archive"
    },
    "shape615": {
      "const": "Planning"
    },
    "shape616": {
      "const": "Bots"
    },
    "shape610": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape616"
        }
      ]
    },
    "shape608": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape609"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "section": {
          "$ref": "#/$defs/shape610"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape618": {
      "const": "personal-bots"
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
      "const": "personal-recreate"
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
    "shape622": {
      "const": "files-choose"
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
        "action"
      ],
      "additionalProperties": false
    },
    "shape624": {
      "const": "files-withdraw"
    },
    "shape623": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape624"
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
    "shape626": {
      "const": "sync-refresh"
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
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape628": {
      "const": "sync-inspect"
    },
    "shape627": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape628"
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
    "shape630": {
      "const": "sync-change"
    },
    "shape632": {
      "const": "on"
    },
    "shape633": {
      "const": "off"
    },
    "shape634": {
      "const": "now"
    },
    "shape631": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape632"
        },
        {
          "$ref": "#/$defs/shape633"
        },
        {
          "$ref": "#/$defs/shape634"
        }
      ]
    },
    "shape629": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape630"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape631"
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
    "shape636": {
      "const": "sync-resolve"
    },
    "shape638": {
      "const": "theirs"
    },
    "shape639": {
      "const": "done"
    },
    "shape637": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape61"
        },
        {
          "$ref": "#/$defs/shape638"
        },
        {
          "$ref": "#/$defs/shape639"
        }
      ]
    },
    "shape635": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape636"
        },
        "how": {
          "$ref": "#/$defs/shape637"
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
    "shape642": {
      "const": "project-open"
    },
    "shape643": {
      "const": "project-remove"
    },
    "shape641": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape642"
        },
        {
          "$ref": "#/$defs/shape643"
        }
      ]
    },
    "shape640": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape641"
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
    "shape645": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape265"
        },
        {
          "$ref": "#/$defs/shape324"
        }
      ]
    },
    "shape644": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape645"
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
    "shape647": {
      "const": "history"
    },
    "shape646": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape647"
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
    "shape649": {
      "const": "select"
    },
    "shape648": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape649"
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
    "shape651": {
      "const": "trajectory"
    },
    "shape650": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape651"
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
    "shape653": {
      "const": "board-swarm-types"
    },
    "shape652": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape653"
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
    "shape655": {
      "const": "board-post-topics"
    },
    "shape654": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape655"
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
    "shape657": {
      "const": "board-create"
    },
    "shape656": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape657"
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
    "shape658": {
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
    "shape660": {
      "const": "board-retry"
    },
    "shape659": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape660"
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
    "shape662": {
      "const": "board-post"
    },
    "shape661": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape662"
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
    "shape664": {
      "const": "workspace-chat"
    },
    "shape663": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape664"
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
    "shape666": {
      "const": "workspace-layout"
    },
    "shape665": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape666"
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
    "shape668": {
      "const": "workspace-refresh"
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
      "const": "library"
    },
    "shape672": {
      "const": "sources"
    },
    "shape673": {
      "const": "documents"
    },
    "shape674": {
      "const": "memories"
    },
    "shape675": {
      "const": "manual"
    },
    "shape671": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape422"
        },
        {
          "$ref": "#/$defs/shape672"
        },
        {
          "$ref": "#/$defs/shape673"
        },
        {
          "$ref": "#/$defs/shape674"
        },
        {
          "$ref": "#/$defs/shape675"
        }
      ]
    },
    "shape669": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape670"
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
          "$ref": "#/$defs/shape671"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape677": {
      "const": "library-view"
    },
    "shape676": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape677"
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
          "$ref": "#/$defs/shape671"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape680": {
      "const": "library-refresh"
    },
    "shape681": {
      "const": "library-citations"
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
    "shape678": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape679"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape683": {
      "const": "library-documents"
    },
    "shape682": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape683"
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
    "shape686": {
      "const": "library-document"
    },
    "shape687": {
      "const": "library-memory"
    },
    "shape688": {
      "const": "library-chunk"
    },
    "shape689": {
      "const": "library-conversation"
    },
    "shape685": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape686"
        },
        {
          "$ref": "#/$defs/shape687"
        },
        {
          "$ref": "#/$defs/shape688"
        },
        {
          "$ref": "#/$defs/shape689"
        }
      ]
    },
    "shape684": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape685"
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
      "const": "library-source-text"
    },
    "shape690": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape691"
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
    "shape693": {
      "const": "library-stance"
    },
    "shape692": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape693"
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
    "shape695": {
      "const": "library-search"
    },
    "shape697": {
      "const": "retrieve"
    },
    "shape698": {
      "const": "recall"
    },
    "shape699": {
      "const": "navigate"
    },
    "shape696": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape425"
        },
        {
          "$ref": "#/$defs/shape178"
        },
        {
          "$ref": "#/$defs/shape673"
        },
        {
          "$ref": "#/$defs/shape697"
        },
        {
          "$ref": "#/$defs/shape698"
        },
        {
          "$ref": "#/$defs/shape699"
        }
      ]
    },
    "shape701": {
      "const": "lexical"
    },
    "shape702": {
      "const": "semantic"
    },
    "shape703": {
      "const": "hybrid"
    },
    "shape700": {
      "anyOf": [
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
    "shape694": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape695"
        },
        "kind": {
          "$ref": "#/$defs/shape696"
        },
        "mode": {
          "$ref": "#/$defs/shape700"
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
    "shape705": {
      "const": "library-maintain"
    },
    "shape707": {
      "const": "invalidate"
    },
    "shape708": {
      "const": "resolve"
    },
    "shape709": {
      "const": "reembed"
    },
    "shape710": {
      "const": "reconsider"
    },
    "shape706": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape707"
        },
        {
          "$ref": "#/$defs/shape708"
        },
        {
          "$ref": "#/$defs/shape709"
        },
        {
          "$ref": "#/$defs/shape710"
        }
      ]
    },
    "shape704": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape29"
        },
        "action": {
          "$ref": "#/$defs/shape705"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape706"
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
    "shape712": {
      "const": "builder-outputs"
    },
    "shape711": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape712"
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
    "shape714": {
      "const": "builder-trajectory"
    },
    "shape713": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape714"
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
    "shape716": {
      "const": "builder-prepare"
    },
    "shape715": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape716"
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
    "shape718": {
      "const": "builder-start"
    },
    "shape717": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape718"
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
    "shape720": {
      "const": "activity"
    },
    "shape722": {
      "const": "inbox"
    },
    "shape723": {
      "const": "runs"
    },
    "shape724": {
      "const": "definitions"
    },
    "shape725": {
      "const": "schedules"
    },
    "shape726": {
      "const": "builder"
    },
    "shape721": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape726"
        }
      ]
    },
    "shape719": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape720"
        },
        "view": {
          "$ref": "#/$defs/shape721"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape728": {
      "const": "activity-view"
    },
    "shape727": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape728"
        },
        "view": {
          "$ref": "#/$defs/shape721"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape730": {
      "const": "activity-refresh"
    },
    "shape729": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape730"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape732": {
      "const": "question-refresh"
    },
    "shape731": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape732"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape734": {
      "const": "inbox-read"
    },
    "shape733": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape734"
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
    "shape736": {
      "const": "inbox-more"
    },
    "shape735": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape736"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape738": {
      "const": "run-detail"
    },
    "shape737": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape738"
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
    "shape740": {
      "const": "schedule-refresh"
    },
    "shape742": {
      "const": "next"
    },
    "shape743": {
      "const": "previous"
    },
    "shape744": {
      "const": "first"
    },
    "shape741": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape742"
        },
        {
          "$ref": "#/$defs/shape743"
        },
        {
          "$ref": "#/$defs/shape744"
        }
      ]
    },
    "shape746": {
      "const": "files"
    },
    "shape745": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape725"
        },
        {
          "$ref": "#/$defs/shape746"
        }
      ]
    },
    "shape739": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape740"
        },
        "direction": {
          "$ref": "#/$defs/shape741"
        },
        "section": {
          "$ref": "#/$defs/shape745"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape748": {
      "const": "schedule-preview"
    },
    "shape747": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape748"
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
    "shape750": {
      "const": "schedule-save"
    },
    "shape754": {
      "const": "skill"
    },
    "shape755": {
      "const": "orchestration"
    },
    "shape753": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape108"
        },
        {
          "$ref": "#/$defs/shape754"
        },
        {
          "$ref": "#/$defs/shape755"
        }
      ]
    },
    "shape757": {
      "const": "INHERITED"
    },
    "shape758": {
      "const": "SUMMARISED"
    },
    "shape759": {
      "const": "NEW"
    },
    "shape760": {
      "const": "DIRECT"
    },
    "shape756": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape757"
        },
        {
          "$ref": "#/$defs/shape758"
        },
        {
          "$ref": "#/$defs/shape759"
        },
        {
          "$ref": "#/$defs/shape760"
        }
      ]
    },
    "shape752": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape753"
        },
        "mode": {
          "$ref": "#/$defs/shape756"
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
    "shape762": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape28"
        }
      ]
    },
    "shape761": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape762"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape762"
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
    "shape765": {
      "const": "mailbox"
    },
    "shape766": {
      "const": "message"
    },
    "shape764": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape178"
        },
        {
          "$ref": "#/$defs/shape765"
        },
        {
          "$ref": "#/$defs/shape766"
        }
      ]
    },
    "shape763": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape764"
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
    "shape767": {
      "const": 1
    },
    "shape751": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape752"
        },
        "cron": {
          "$ref": "#/$defs/shape6"
        },
        "limits": {
          "$ref": "#/$defs/shape761"
        },
        "paused": {
          "$ref": "#/$defs/shape29"
        },
        "target": {
          "$ref": "#/$defs/shape763"
        },
        "version": {
          "$ref": "#/$defs/shape767"
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
    "shape769": {
      "const": "workspace"
    },
    "shape768": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape62"
        },
        {
          "$ref": "#/$defs/shape769"
        }
      ]
    },
    "shape749": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape750"
        },
        "definition": {
          "$ref": "#/$defs/shape751"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape768"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape771": {
      "const": "schedule-file-save"
    },
    "shape770": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape771"
        },
        "definition": {
          "$ref": "#/$defs/shape751"
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
          "$ref": "#/$defs/shape768"
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
    "shape773": {
      "const": "schedule-sync"
    },
    "shape772": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape773"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape768"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape775": {
      "const": "schedule-change"
    },
    "shape777": {
      "const": "schedule"
    },
    "shape778": {
      "const": "trigger"
    },
    "shape776": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape777"
        },
        {
          "$ref": "#/$defs/shape778"
        }
      ]
    },
    "shape774": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape775"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape776"
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
    "shape780": {
      "const": "schedule-fire"
    },
    "shape779": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape780"
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
    "shape782": {
      "const": "run-definitions"
    },
    "shape781": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape782"
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
    "shape784": {
      "const": "run-record"
    },
    "shape783": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape784"
        },
        "before": {
          "$ref": "#/$defs/shape28"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "kinds": {
          "$ref": "#/$defs/shape592"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape786": {
      "const": "run-answer"
    },
    "shape788": {
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
    "shape787": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape788"
      }
    },
    "shape785": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape786"
        },
        "answer": {
          "$ref": "#/$defs/shape6"
        },
        "choices": {
          "$ref": "#/$defs/shape787"
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
    "shape790": {
      "const": "run-cancel"
    },
    "shape789": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape790"
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
    "shape792": {
      "const": "run-resume"
    },
    "shape791": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape792"
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
    "shape794": {
      "const": "run-trajectory"
    },
    "shape796": {
      "const": "conductor"
    },
    "shape797": {
      "const": "caller"
    },
    "shape795": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape796"
        },
        {
          "$ref": "#/$defs/shape797"
        }
      ]
    },
    "shape793": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape794"
        },
        "actor": {
          "$ref": "#/$defs/shape795"
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
    "shape799": {
      "const": "run-stage-trajectory"
    },
    "shape798": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape799"
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
    "shape801": {
      "const": "delegate-trajectory"
    },
    "shape800": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape801"
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
    "shape804": {
      "const": "board-inspection"
    },
    "shape805": {
      "const": "board-view"
    },
    "shape803": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape804"
        },
        {
          "$ref": "#/$defs/shape805"
        }
      ]
    },
    "shape807": {
      "const": "board"
    },
    "shape808": {
      "const": "swarm"
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
    "shape802": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape803"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape806"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape811": {
      "const": "board-refresh"
    },
    "shape812": {
      "const": "board-more"
    },
    "shape810": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape811"
        },
        {
          "$ref": "#/$defs/shape812"
        }
      ]
    },
    "shape809": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape814": {
      "const": "board-topic"
    },
    "shape813": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape814"
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
    "shape816": {
      "const": "board-trajectory"
    },
    "shape815": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape816"
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
    "shape818": {
      "const": "context"
    },
    "shape817": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape818"
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
    "shape820": {
      "const": "open-link"
    },
    "shape819": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape820"
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
    "shape822": {
      "const": "copy-text"
    },
    "shape821": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape822"
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
    "shape824": {
      "const": "workflow-start"
    },
    "shape823": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape824"
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
    "shape825": {
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
    "shape827": {
      "const": "cancel"
    },
    "shape826": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape827"
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
    "shape829": {
      "const": "answer"
    },
    "shape830": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape177"
        },
        {
          "$ref": "#/$defs/shape179"
        }
      ]
    },
    "shape828": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape829"
        },
        "decision": {
          "$ref": "#/$defs/shape830"
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
    "shape832": {
      "const": "native-mcp"
    },
    "shape831": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape832"
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
    "shape834": {
      "const": "provider-management"
    },
    "shape835": {
      "const": "provider.connection.list"
    },
    "shape836": {
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
    "shape833": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
        },
        "operation": {
          "$ref": "#/$defs/shape835"
        },
        "payload": {
          "$ref": "#/$defs/shape836"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape838": {
      "const": "provider.connection.status"
    },
    "shape839": {
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
    "shape837": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
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
      "const": "provider.connection.receipt"
    },
    "shape842": {
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
    "shape840": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
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
      "const": "provider.connection.save"
    },
    "shape848": {
      "const": "Authorization"
    },
    "shape849": {
      "const": "X-API-Key"
    },
    "shape850": {
      "const": "Api-Key"
    },
    "shape847": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape848"
        },
        {
          "$ref": "#/$defs/shape849"
        },
        {
          "$ref": "#/$defs/shape850"
        }
      ]
    },
    "shape852": {
      "const": "API_KEY"
    },
    "shape853": {
      "const": "OAUTH_PKCE"
    },
    "shape854": {
      "const": "CLIENT_CREDENTIALS"
    },
    "shape851": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape852"
        },
        {
          "$ref": "#/$defs/shape853"
        },
        {
          "$ref": "#/$defs/shape854"
        }
      ]
    },
    "shape856": {
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
    "shape855": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape856"
        }
      ]
    },
    "shape846": {
      "type": "object",
      "properties": {
        "apiKeyHeader": {
          "$ref": "#/$defs/shape847"
        },
        "authentication": {
          "$ref": "#/$defs/shape851"
        },
        "endpoint": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "oauth": {
          "$ref": "#/$defs/shape855"
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
    "shape845": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape846"
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
    "shape843": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
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
    "shape858": {
      "const": "provider.connection.change"
    },
    "shape861": {
      "const": "ENABLE"
    },
    "shape862": {
      "const": "DISABLE"
    },
    "shape863": {
      "const": "DELETE"
    },
    "shape860": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape861"
        },
        {
          "$ref": "#/$defs/shape862"
        },
        {
          "$ref": "#/$defs/shape863"
        }
      ]
    },
    "shape859": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape860"
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
    "shape857": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
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
    "shape865": {
      "const": "provider.credential.status"
    },
    "shape866": {
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
    "shape864": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
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
      "const": "provider.credential.put"
    },
    "shape869": {
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
    "shape867": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
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
      "const": "provider.credential.revoke"
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
    "shape870": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
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
      "const": "provider.credential.rekey"
    },
    "shape873": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
        },
        "operation": {
          "$ref": "#/$defs/shape874"
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
    "shape876": {
      "const": "provider.credential.authorize"
    },
    "shape875": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
        },
        "operation": {
          "$ref": "#/$defs/shape876"
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
    "shape878": {
      "const": "provider.credential.refresh"
    },
    "shape877": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
        },
        "operation": {
          "$ref": "#/$defs/shape878"
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
    "shape880": {
      "const": "tool.scope.host.list"
    },
    "shape881": {
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
    "shape879": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
        },
        "operation": {
          "$ref": "#/$defs/shape880"
        },
        "payload": {
          "$ref": "#/$defs/shape881"
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
      "const": "tool.scope.host.status"
    },
    "shape884": {
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
    "shape882": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
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
      "const": "tool.scope.host.receipt"
    },
    "shape887": {
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
    "shape885": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
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
      "const": "tool.scope.host.save"
    },
    "shape891": {
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
    "shape890": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape891"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape762"
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
    "shape888": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
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
    "shape893": {
      "const": "tool.scope.host.change"
    },
    "shape894": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape860"
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
    "shape892": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
        },
        "operation": {
          "$ref": "#/$defs/shape893"
        },
        "payload": {
          "$ref": "#/$defs/shape894"
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
      "const": "tool.scope.list"
    },
    "shape897": {
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
    "shape895": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
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
      "const": "provider-management-pending"
    },
    "shape898": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape899"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape901": {
      "const": "provider-management-recover"
    },
    "shape904": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape844"
        },
        {
          "$ref": "#/$defs/shape858"
        }
      ]
    },
    "shape903": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape904"
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
    "shape906": {
      "anyOf": [
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
          "$ref": "#/$defs/shape878"
        }
      ]
    },
    "shape905": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape906"
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
    "shape908": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape889"
        },
        {
          "$ref": "#/$defs/shape893"
        }
      ]
    },
    "shape907": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape762"
        },
        "operation": {
          "$ref": "#/$defs/shape908"
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
    "shape902": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape903"
        },
        {
          "$ref": "#/$defs/shape905"
        },
        {
          "$ref": "#/$defs/shape907"
        }
      ]
    },
    "shape900": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape901"
        },
        "intent": {
          "$ref": "#/$defs/shape902"
        }
      },
      "required": [
        "action",
        "intent"
      ],
      "additionalProperties": false
    },
    "shape910": {
      "const": "information-import-recovery-save"
    },
    "shape912": {
      "const": "plowshare-information-import-recovery/1"
    },
    "shape911": {
      "type": "object",
      "properties": {
        "context": {
          "$ref": "#/$defs/shape6"
        },
        "format": {
          "$ref": "#/$defs/shape912"
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
    "shape909": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape910"
        },
        "recovery": {
          "$ref": "#/$defs/shape911"
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
          "$ref": "#/$defs/shape396"
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
          "$ref": "#/$defs/shape412"
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
          "$ref": "#/$defs/shape448"
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
          "$ref": "#/$defs/shape490"
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
          "$ref": "#/$defs/shape517"
        },
        {
          "$ref": "#/$defs/shape528"
        },
        {
          "$ref": "#/$defs/shape532"
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
          "$ref": "#/$defs/shape558"
        },
        {
          "$ref": "#/$defs/shape561"
        },
        {
          "$ref": "#/$defs/shape564"
        },
        {
          "$ref": "#/$defs/shape566"
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
          "$ref": "#/$defs/shape580"
        },
        {
          "$ref": "#/$defs/shape583"
        },
        {
          "$ref": "#/$defs/shape586"
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
          "$ref": "#/$defs/shape604"
        },
        {
          "$ref": "#/$defs/shape606"
        },
        {
          "$ref": "#/$defs/shape608"
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
          "$ref": "#/$defs/shape627"
        },
        {
          "$ref": "#/$defs/shape629"
        },
        {
          "$ref": "#/$defs/shape635"
        },
        {
          "$ref": "#/$defs/shape640"
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
          "$ref": "#/$defs/shape659"
        },
        {
          "$ref": "#/$defs/shape661"
        },
        {
          "$ref": "#/$defs/shape663"
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
          "$ref": "#/$defs/shape676"
        },
        {
          "$ref": "#/$defs/shape678"
        },
        {
          "$ref": "#/$defs/shape682"
        },
        {
          "$ref": "#/$defs/shape684"
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
          "$ref": "#/$defs/shape704"
        },
        {
          "$ref": "#/$defs/shape711"
        },
        {
          "$ref": "#/$defs/shape713"
        },
        {
          "$ref": "#/$defs/shape715"
        },
        {
          "$ref": "#/$defs/shape717"
        },
        {
          "$ref": "#/$defs/shape719"
        },
        {
          "$ref": "#/$defs/shape727"
        },
        {
          "$ref": "#/$defs/shape729"
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
          "$ref": "#/$defs/shape737"
        },
        {
          "$ref": "#/$defs/shape739"
        },
        {
          "$ref": "#/$defs/shape747"
        },
        {
          "$ref": "#/$defs/shape749"
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
          "$ref": "#/$defs/shape779"
        },
        {
          "$ref": "#/$defs/shape781"
        },
        {
          "$ref": "#/$defs/shape783"
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
          "$ref": "#/$defs/shape798"
        },
        {
          "$ref": "#/$defs/shape800"
        },
        {
          "$ref": "#/$defs/shape802"
        },
        {
          "$ref": "#/$defs/shape809"
        },
        {
          "$ref": "#/$defs/shape813"
        },
        {
          "$ref": "#/$defs/shape815"
        },
        {
          "$ref": "#/$defs/shape817"
        },
        {
          "$ref": "#/$defs/shape819"
        },
        {
          "$ref": "#/$defs/shape821"
        },
        {
          "$ref": "#/$defs/shape823"
        },
        {
          "$ref": "#/$defs/shape825"
        },
        {
          "$ref": "#/$defs/shape826"
        },
        {
          "$ref": "#/$defs/shape828"
        },
        {
          "$ref": "#/$defs/shape831"
        },
        {
          "$ref": "#/$defs/shape833"
        },
        {
          "$ref": "#/$defs/shape837"
        },
        {
          "$ref": "#/$defs/shape840"
        },
        {
          "$ref": "#/$defs/shape843"
        },
        {
          "$ref": "#/$defs/shape857"
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
          "$ref": "#/$defs/shape873"
        },
        {
          "$ref": "#/$defs/shape875"
        },
        {
          "$ref": "#/$defs/shape877"
        },
        {
          "$ref": "#/$defs/shape879"
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
          "$ref": "#/$defs/shape892"
        },
        {
          "$ref": "#/$defs/shape895"
        },
        {
          "$ref": "#/$defs/shape898"
        },
        {
          "$ref": "#/$defs/shape900"
        },
        {
          "$ref": "#/$defs/shape909"
        }
      ]
    }
  }
}
