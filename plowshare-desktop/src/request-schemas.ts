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
      "const": "application-activity"
    },
    "shape47": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape48"
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
      "const": "application-activity-open"
    },
    "shape49": {
      "type": "object",
      "properties": {
        "action": {
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
    "shape52": {
      "const": "application-runs"
    },
    "shape51": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape52"
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
    "shape54": {
      "const": "application-run-detail"
    },
    "shape53": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape54"
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
    "shape56": {
      "const": "application-files"
    },
    "shape55": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape56"
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
    "shape58": {
      "const": "application-file-read"
    },
    "shape57": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape58"
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
    "shape60": {
      "const": "application-file-save"
    },
    "shape59": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape60"
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
    "shape62": {
      "const": "usage"
    },
    "shape61": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape62"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape64": {
      "const": "proc"
    },
    "shape63": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape64"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape66": {
      "const": "proc-read"
    },
    "shape69": {
      "const": "newest"
    },
    "shape70": {
      "const": "oldest"
    },
    "shape68": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape69"
        },
        {
          "$ref": "#/$defs/shape70"
        }
      ]
    },
    "shape72": {
      "const": "mine"
    },
    "shape73": {
      "const": "server"
    },
    "shape71": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape72"
        },
        {
          "$ref": "#/$defs/shape73"
        }
      ]
    },
    "shape67": {
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
        "order": {
          "$ref": "#/$defs/shape68"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "root": {
          "$ref": "#/$defs/shape6"
        },
        "visibility": {
          "$ref": "#/$defs/shape71"
        }
      },
      "additionalProperties": false
    },
    "shape74": {
      "const": "proc.list"
    },
    "shape65": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape66"
        },
        "payload": {
          "$ref": "#/$defs/shape67"
        },
        "type": {
          "$ref": "#/$defs/shape74"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape76": {
      "const": "proc.inspect"
    },
    "shape75": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape66"
        },
        "payload": {
          "$ref": "#/$defs/shape67"
        },
        "type": {
          "$ref": "#/$defs/shape76"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape78": {
      "const": "proc.calls"
    },
    "shape77": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape66"
        },
        "payload": {
          "$ref": "#/$defs/shape67"
        },
        "type": {
          "$ref": "#/$defs/shape78"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape80": {
      "const": "proc.queues"
    },
    "shape79": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape66"
        },
        "payload": {
          "$ref": "#/$defs/shape67"
        },
        "type": {
          "$ref": "#/$defs/shape80"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape82": {
      "const": "proc.tools"
    },
    "shape81": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape66"
        },
        "payload": {
          "$ref": "#/$defs/shape67"
        },
        "type": {
          "$ref": "#/$defs/shape82"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape84": {
      "const": "proc-trajectory"
    },
    "shape83": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape84"
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
    "shape86": {
      "const": "context-snapshot"
    },
    "shape85": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape86"
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
    "shape88": {
      "const": "relay"
    },
    "shape87": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape88"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape90": {
      "const": "relay-read"
    },
    "shape92": {
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
    "shape93": {
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
    "shape91": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape92"
        },
        {
          "$ref": "#/$defs/shape93"
        }
      ]
    },
    "shape94": {
      "const": "relay.topics"
    },
    "shape89": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape90"
        },
        "payload": {
          "$ref": "#/$defs/shape91"
        },
        "type": {
          "$ref": "#/$defs/shape94"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape97": {
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
    "shape98": {
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
    "shape96": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape97"
        },
        {
          "$ref": "#/$defs/shape98"
        }
      ]
    },
    "shape99": {
      "const": "relay.log"
    },
    "shape95": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape90"
        },
        "payload": {
          "$ref": "#/$defs/shape96"
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
    "shape101": {
      "const": "relay-operate"
    },
    "shape104": {
      "const": "ACKNOWLEDGE_GAP"
    },
    "shape105": {
      "const": "RECONCILE"
    },
    "shape106": {
      "const": "ABANDON"
    },
    "shape107": {
      "const": "REMOVE_SUBSCRIPTION"
    },
    "shape108": {
      "const": "REMOVE_TOPIC"
    },
    "shape103": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape104"
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
          "$ref": "#/$defs/shape108"
        }
      ]
    },
    "shape102": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape103"
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
    "shape100": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape101"
        },
        "payload": {
          "$ref": "#/$defs/shape102"
        }
      },
      "required": [
        "action",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape110": {
      "const": "relay-trajectory"
    },
    "shape109": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape110"
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
    "shape112": {
      "const": "usage-open"
    },
    "shape116": {
      "const": "day"
    },
    "shape117": {
      "const": "model"
    },
    "shape118": {
      "const": "pool"
    },
    "shape119": {
      "const": "agent"
    },
    "shape120": {
      "const": "operation"
    },
    "shape121": {
      "const": "project"
    },
    "shape122": {
      "const": "run"
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
    "shape114": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape115"
      }
    },
    "shape124": {
      "const": "direct"
    },
    "shape125": {
      "const": "subtree"
    },
    "shape123": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape124"
        },
        {
          "$ref": "#/$defs/shape125"
        }
      ]
    },
    "shape113": {
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
          "$ref": "#/$defs/shape114"
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
          "$ref": "#/$defs/shape123"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape127": {
      "const": "usage.conversation"
    },
    "shape128": {
      "const": "usage.project"
    },
    "shape129": {
      "const": "usage.agent"
    },
    "shape130": {
      "const": "usage.run"
    },
    "shape131": {
      "const": "usage.orchestration"
    },
    "shape132": {
      "const": "usage.models"
    },
    "shape133": {
      "const": "usage.pools"
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
        }
      ]
    },
    "shape111": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape112"
        },
        "filter": {
          "$ref": "#/$defs/shape113"
        },
        "type": {
          "$ref": "#/$defs/shape126"
        }
      },
      "required": [
        "action",
        "filter",
        "type"
      ],
      "additionalProperties": false
    },
    "shape135": {
      "const": "usage-read"
    },
    "shape136": {
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
          "$ref": "#/$defs/shape114"
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
          "$ref": "#/$defs/shape123"
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
    "shape134": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape135"
        },
        "payload": {
          "$ref": "#/$defs/shape136"
        },
        "type": {
          "$ref": "#/$defs/shape127"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape138": {
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
          "$ref": "#/$defs/shape114"
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
          "$ref": "#/$defs/shape123"
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
    "shape137": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape135"
        },
        "payload": {
          "$ref": "#/$defs/shape138"
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
          "$ref": "#/$defs/shape114"
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
          "$ref": "#/$defs/shape123"
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
    "shape139": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape135"
        },
        "payload": {
          "$ref": "#/$defs/shape140"
        },
        "type": {
          "$ref": "#/$defs/shape129"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape142": {
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
          "$ref": "#/$defs/shape114"
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
          "$ref": "#/$defs/shape123"
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
    "shape141": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape135"
        },
        "payload": {
          "$ref": "#/$defs/shape142"
        },
        "type": {
          "$ref": "#/$defs/shape130"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape144": {
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
          "$ref": "#/$defs/shape114"
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
          "$ref": "#/$defs/shape123"
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
    "shape143": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape135"
        },
        "payload": {
          "$ref": "#/$defs/shape144"
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
    "shape145": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape135"
        },
        "payload": {
          "$ref": "#/$defs/shape113"
        },
        "type": {
          "$ref": "#/$defs/shape132"
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
        "action": {
          "$ref": "#/$defs/shape135"
        },
        "payload": {
          "$ref": "#/$defs/shape113"
        },
        "type": {
          "$ref": "#/$defs/shape133"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape148": {
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
          "$ref": "#/$defs/shape114"
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
          "$ref": "#/$defs/shape123"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape149": {
      "const": "usage.calls"
    },
    "shape147": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape135"
        },
        "payload": {
          "$ref": "#/$defs/shape148"
        },
        "type": {
          "$ref": "#/$defs/shape149"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape151": {
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
          "$ref": "#/$defs/shape114"
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
          "$ref": "#/$defs/shape126"
        },
        "route": {
          "$ref": "#/$defs/shape6"
        },
        "run": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape123"
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
    "shape152": {
      "const": "usage.subscribe"
    },
    "shape150": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape135"
        },
        "payload": {
          "$ref": "#/$defs/shape151"
        },
        "type": {
          "$ref": "#/$defs/shape152"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape154": {
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
    "shape155": {
      "const": "usage.unsubscribe"
    },
    "shape153": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape135"
        },
        "payload": {
          "$ref": "#/$defs/shape154"
        },
        "type": {
          "$ref": "#/$defs/shape155"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape157": {
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
    "shape158": {
      "const": "conversation.context.count"
    },
    "shape156": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape135"
        },
        "payload": {
          "$ref": "#/$defs/shape157"
        },
        "type": {
          "$ref": "#/$defs/shape158"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape160": {
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
    "shape161": {
      "const": "conversation.context.snapshot"
    },
    "shape159": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape135"
        },
        "payload": {
          "$ref": "#/$defs/shape160"
        },
        "type": {
          "$ref": "#/$defs/shape161"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape163": {
      "const": "usage-close"
    },
    "shape162": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape163"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape165": {
      "const": "operator-prepare"
    },
    "shape167": {
      "const": "memory-write"
    },
    "shape168": {
      "const": "memory-digest"
    },
    "shape169": {
      "const": "agent-curate"
    },
    "shape170": {
      "const": "conversation-lifecycle"
    },
    "shape171": {
      "const": "conversation-resume"
    },
    "shape172": {
      "const": "job-limits"
    },
    "shape173": {
      "const": "approval-grant"
    },
    "shape174": {
      "const": "approval-revoke"
    },
    "shape175": {
      "const": "board-topup"
    },
    "shape176": {
      "const": "message-deliveries"
    },
    "shape177": {
      "const": "message-open"
    },
    "shape178": {
      "const": "message-default"
    },
    "shape179": {
      "const": "message-stop"
    },
    "shape180": {
      "const": "message-archive"
    },
    "shape181": {
      "const": "caps"
    },
    "shape166": {
      "anyOf": [
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
        },
        {
          "$ref": "#/$defs/shape171"
        },
        {
          "$ref": "#/$defs/shape172"
        },
        {
          "$ref": "#/$defs/shape173"
        },
        {
          "$ref": "#/$defs/shape174"
        },
        {
          "$ref": "#/$defs/shape175"
        },
        {
          "$ref": "#/$defs/shape176"
        },
        {
          "$ref": "#/$defs/shape177"
        },
        {
          "$ref": "#/$defs/shape178"
        },
        {
          "$ref": "#/$defs/shape179"
        },
        {
          "$ref": "#/$defs/shape180"
        },
        {
          "$ref": "#/$defs/shape181"
        }
      ]
    },
    "shape164": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape165"
        },
        "kind": {
          "$ref": "#/$defs/shape166"
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
    "shape183": {
      "const": "operator-messages"
    },
    "shape182": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape183"
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
    "shape185": {
      "const": "operator-preview"
    },
    "shape188": {
      "const": "once"
    },
    "shape189": {
      "const": "conversation"
    },
    "shape190": {
      "const": "deny"
    },
    "shape187": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape121"
        },
        {
          "$ref": "#/$defs/shape188"
        },
        {
          "$ref": "#/$defs/shape189"
        },
        {
          "$ref": "#/$defs/shape190"
        }
      ]
    },
    "shape192": {
      "const": "steps"
    },
    "shape193": {
      "const": "budget"
    },
    "shape194": {
      "const": "auto-continue"
    },
    "shape195": {
      "const": "time"
    },
    "shape196": {
      "const": "failed-checks"
    },
    "shape197": {
      "const": "auto-increase"
    },
    "shape191": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape192"
        },
        {
          "$ref": "#/$defs/shape193"
        },
        {
          "$ref": "#/$defs/shape194"
        },
        {
          "$ref": "#/$defs/shape195"
        },
        {
          "$ref": "#/$defs/shape196"
        },
        {
          "$ref": "#/$defs/shape197"
        }
      ]
    },
    "shape199": {
      "const": "active"
    },
    "shape200": {
      "const": "archived"
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
    "shape202": {
      "const": "true"
    },
    "shape203": {
      "const": "false"
    },
    "shape201": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape202"
        },
        {
          "$ref": "#/$defs/shape203"
        }
      ]
    },
    "shape186": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "body": {
          "$ref": "#/$defs/shape6"
        },
        "decision": {
          "$ref": "#/$defs/shape187"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "key": {
          "$ref": "#/$defs/shape191"
        },
        "lifecycle": {
          "$ref": "#/$defs/shape198"
        },
        "makeDefault": {
          "$ref": "#/$defs/shape201"
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
    "shape184": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape185"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape186"
        }
      },
      "required": [
        "action",
        "identity",
        "input"
      ],
      "additionalProperties": false
    },
    "shape205": {
      "const": "operator-apply"
    },
    "shape204": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape205"
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
    "shape207": {
      "const": "information"
    },
    "shape208": {
      "const": "navigation.capabilities"
    },
    "shape210": {
      "const": "content-types-v1"
    },
    "shape209": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape213": {
      "const": "personal"
    },
    "shape212": {
      "type": "object",
      "properties": {
        "includeGlobal": {
          "$ref": "#/$defs/shape29"
        },
        "kind": {
          "$ref": "#/$defs/shape213"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape215": {
      "const": "global_read"
    },
    "shape214": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape215"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape216": {
      "type": "object",
      "properties": {
        "includeGlobal": {
          "$ref": "#/$defs/shape29"
        },
        "kind": {
          "$ref": "#/$defs/shape121"
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
    "shape218": {
      "const": "00000000-0000-0000-0000-000000000004"
    },
    "shape219": {
      "const": "account"
    },
    "shape217": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape218"
        },
        "kind": {
          "$ref": "#/$defs/shape219"
        }
      },
      "required": [
        "account",
        "kind"
      ],
      "additionalProperties": false
    },
    "shape211": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape212"
        },
        {
          "$ref": "#/$defs/shape214"
        },
        {
          "$ref": "#/$defs/shape216"
        },
        {
          "$ref": "#/$defs/shape217"
        }
      ]
    },
    "shape206": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape208"
        },
        "payload": {
          "$ref": "#/$defs/shape209"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "documents.list"
    },
    "shape224": {
      "const": "outgoing"
    },
    "shape225": {
      "const": "backlinks"
    },
    "shape223": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape224"
        },
        {
          "$ref": "#/$defs/shape225"
        }
      ]
    },
    "shape227": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape229": {
      "const": "source"
    },
    "shape230": {
      "const": "report"
    },
    "shape228": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape229"
        },
        {
          "$ref": "#/$defs/shape230"
        }
      ]
    },
    "shape226": {
      "type": "object",
      "properties": {
        "author": {
          "$ref": "#/$defs/shape6"
        },
        "autoTag": {
          "$ref": "#/$defs/shape227"
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
          "$ref": "#/$defs/shape228"
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
          "$ref": "#/$defs/shape227"
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
    "shape231": {
      "const": "metadata-v1"
    },
    "shape222": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape223"
        },
        "filter": {
          "$ref": "#/$defs/shape226"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "projection": {
          "$ref": "#/$defs/shape231"
        },
        "relatedTo": {
          "$ref": "#/$defs/shape6"
        },
        "relation": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape220": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape221"
        },
        "payload": {
          "$ref": "#/$defs/shape222"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "documents.facets"
    },
    "shape234": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape223"
        },
        "filter": {
          "$ref": "#/$defs/shape226"
        },
        "relatedTo": {
          "$ref": "#/$defs/shape6"
        },
        "relation": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape232": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape233"
        },
        "payload": {
          "$ref": "#/$defs/shape234"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "documents.get"
    },
    "shape238": {
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
    "shape237": {
      "type": "object",
      "properties": {
        "target": {
          "$ref": "#/$defs/shape238"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "target"
      ],
      "additionalProperties": false
    },
    "shape235": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape236"
        },
        "payload": {
          "$ref": "#/$defs/shape237"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "relationships.list"
    },
    "shape241": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape223"
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
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "direction",
        "resource"
      ],
      "additionalProperties": false
    },
    "shape239": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape240"
        },
        "payload": {
          "$ref": "#/$defs/shape241"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "relationships.create"
    },
    "shape244": {
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
          "$ref": "#/$defs/shape238"
        },
        "view": {
          "$ref": "#/$defs/shape210"
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
    "shape242": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape243"
        },
        "payload": {
          "$ref": "#/$defs/shape244"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "relationships.remove"
    },
    "shape247": {
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
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "expectedVersion",
        "relationship",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape245": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape246"
        },
        "payload": {
          "$ref": "#/$defs/shape247"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "policy.get"
    },
    "shape253": {
      "const": "platform"
    },
    "shape254": {
      "forbidden": true
    },
    "shape252": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape253"
        },
        "project": {
          "$ref": "#/$defs/shape254"
        },
        "resource": {
          "$ref": "#/$defs/shape254"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape255": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape121"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "resource": {
          "$ref": "#/$defs/shape254"
        }
      },
      "required": [
        "kind",
        "project"
      ],
      "additionalProperties": false
    },
    "shape257": {
      "const": "document"
    },
    "shape256": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape257"
        },
        "project": {
          "$ref": "#/$defs/shape254"
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
    "shape251": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape252"
        },
        {
          "$ref": "#/$defs/shape255"
        },
        {
          "$ref": "#/$defs/shape256"
        }
      ]
    },
    "shape250": {
      "type": "object",
      "properties": {
        "target": {
          "$ref": "#/$defs/shape251"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "target"
      ],
      "additionalProperties": false
    },
    "shape248": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape249"
        },
        "payload": {
          "$ref": "#/$defs/shape250"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "policy.set"
    },
    "shape262": {
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
    "shape261": {
      "type": "object",
      "properties": {
        "allowOverrides": {
          "$ref": "#/$defs/shape29"
        },
        "defaults": {
          "$ref": "#/$defs/shape262"
        },
        "mandatory": {
          "$ref": "#/$defs/shape262"
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
    "shape260": {
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
          "$ref": "#/$defs/shape261"
        },
        "target": {
          "$ref": "#/$defs/shape251"
        },
        "view": {
          "$ref": "#/$defs/shape210"
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
    "shape258": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape259"
        },
        "payload": {
          "$ref": "#/$defs/shape260"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "views.list"
    },
    "shape265": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape263": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape264"
        },
        "payload": {
          "$ref": "#/$defs/shape265"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "views.get"
    },
    "shape268": {
      "type": "object",
      "properties": {
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "id"
      ],
      "additionalProperties": false
    },
    "shape266": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape267"
        },
        "payload": {
          "$ref": "#/$defs/shape268"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "views.save"
    },
    "shape276": {
      "const": "scope"
    },
    "shape275": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape276"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "kind",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape277": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape257"
        },
        "target": {
          "$ref": "#/$defs/shape238"
        }
      },
      "required": [
        "kind",
        "target"
      ],
      "additionalProperties": false
    },
    "shape279": {
      "const": "traverse"
    },
    "shape278": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape223"
        },
        "kind": {
          "$ref": "#/$defs/shape279"
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
    "shape281": {
      "const": "filter"
    },
    "shape280": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape226"
        },
        "kind": {
          "$ref": "#/$defs/shape281"
        }
      },
      "required": [
        "filter",
        "kind"
      ],
      "additionalProperties": false
    },
    "shape274": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape275"
        },
        {
          "$ref": "#/$defs/shape277"
        },
        {
          "$ref": "#/$defs/shape278"
        },
        {
          "$ref": "#/$defs/shape280"
        }
      ]
    },
    "shape273": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape274"
      }
    },
    "shape272": {
      "type": "object",
      "properties": {
        "steps": {
          "$ref": "#/$defs/shape273"
        }
      },
      "required": [
        "steps"
      ],
      "additionalProperties": false
    },
    "shape271": {
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
          "$ref": "#/$defs/shape272"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
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
    "shape269": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape270"
        },
        "payload": {
          "$ref": "#/$defs/shape271"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "views.delete"
    },
    "shape284": {
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
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "expectedVersion",
        "id",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape282": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape283"
        },
        "payload": {
          "$ref": "#/$defs/shape284"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "interchange.capabilities"
    },
    "shape287": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape285": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape286"
        },
        "payload": {
          "$ref": "#/$defs/shape287"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "interchange.export"
    },
    "shape290": {
      "type": "object",
      "properties": {
        "revisions": {
          "$ref": "#/$defs/shape227"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "revisions"
      ],
      "additionalProperties": false
    },
    "shape288": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape289"
        },
        "payload": {
          "$ref": "#/$defs/shape290"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "interchange.readable"
    },
    "shape293": {
      "type": "object",
      "properties": {
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
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
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape292"
        },
        "payload": {
          "$ref": "#/$defs/shape293"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "interchange.import"
    },
    "shape306": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape305"
      }
    },
    "shape305": {
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
          "$ref": "#/$defs/shape304"
        },
        {
          "$ref": "#/$defs/shape306"
        }
      ]
    },
    "shape304": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape305"
      }
    },
    "shape307": {
      "const": "plowshare-document-metadata/1"
    },
    "shape309": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape308"
      }
    },
    "shape310": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape30"
        },
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape308"
        }
      ]
    },
    "shape311": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape308"
      }
    },
    "shape312": {
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
    "shape313": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape312"
      }
    },
    "shape315": {
      "const": "string"
    },
    "shape316": {
      "const": "number"
    },
    "shape317": {
      "const": "boolean"
    },
    "shape318": {
      "const": "object"
    },
    "shape319": {
      "const": "array"
    },
    "shape320": {
      "const": "integer"
    },
    "shape321": {
      "const": "null"
    },
    "shape323": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape315"
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
        },
        {
          "$ref": "#/$defs/shape320"
        },
        {
          "$ref": "#/$defs/shape321"
        }
      ]
    },
    "shape322": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape323"
      }
    },
    "shape314": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape315"
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
        },
        {
          "$ref": "#/$defs/shape320"
        },
        {
          "$ref": "#/$defs/shape321"
        },
        {
          "$ref": "#/$defs/shape322"
        }
      ]
    },
    "shape308": {
      "type": "object",
      "properties": {
        "$anchor": {
          "$ref": "#/$defs/shape6"
        },
        "$defs": {
          "$ref": "#/$defs/shape309"
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
          "$ref": "#/$defs/shape310"
        },
        "allOf": {
          "$ref": "#/$defs/shape311"
        },
        "anyOf": {
          "$ref": "#/$defs/shape311"
        },
        "const": {
          "$ref": "#/$defs/shape312"
        },
        "contains": {
          "$ref": "#/$defs/shape308"
        },
        "default": {
          "$ref": "#/$defs/shape312"
        },
        "deprecated": {
          "$ref": "#/$defs/shape29"
        },
        "description": {
          "$ref": "#/$defs/shape6"
        },
        "else": {
          "$ref": "#/$defs/shape308"
        },
        "enum": {
          "$ref": "#/$defs/shape313"
        },
        "examples": {
          "$ref": "#/$defs/shape313"
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
          "$ref": "#/$defs/shape308"
        },
        "items": {
          "$ref": "#/$defs/shape308"
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
          "$ref": "#/$defs/shape308"
        },
        "oneOf": {
          "$ref": "#/$defs/shape311"
        },
        "pattern": {
          "$ref": "#/$defs/shape6"
        },
        "patternProperties": {
          "$ref": "#/$defs/shape309"
        },
        "prefixItems": {
          "$ref": "#/$defs/shape311"
        },
        "properties": {
          "$ref": "#/$defs/shape309"
        },
        "readOnly": {
          "$ref": "#/$defs/shape29"
        },
        "required": {
          "$ref": "#/$defs/shape227"
        },
        "then": {
          "$ref": "#/$defs/shape308"
        },
        "title": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape314"
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
    "shape303": {
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
          "$ref": "#/$defs/shape304"
        },
        "profile": {
          "$ref": "#/$defs/shape307"
        },
        "schema": {
          "$ref": "#/$defs/shape308"
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
    "shape302": {
      "type": "object",
      "properties": {
        "contract": {
          "$ref": "#/$defs/shape303"
        },
        "metadata": {
          "$ref": "#/$defs/shape304"
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
    "shape326": {
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
    "shape325": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape326"
      }
    },
    "shape327": {
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
    "shape324": {
      "type": "object",
      "properties": {
        "createdAt": {
          "$ref": "#/$defs/shape6"
        },
        "dependencies": {
          "$ref": "#/$defs/shape227"
        },
        "evidence": {
          "$ref": "#/$defs/shape325"
        },
        "importedFrom": {
          "$ref": "#/$defs/shape327"
        },
        "kind": {
          "$ref": "#/$defs/shape228"
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
    "shape301": {
      "type": "object",
      "properties": {
        "contentHash": {
          "$ref": "#/$defs/shape6"
        },
        "contentType": {
          "$ref": "#/$defs/shape302"
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
          "$ref": "#/$defs/shape324"
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
          "$ref": "#/$defs/shape227"
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
    "shape300": {
      "type": "object",
      "properties": {
        "bytesBase64": {
          "$ref": "#/$defs/shape6"
        },
        "descriptor": {
          "$ref": "#/$defs/shape301"
        }
      },
      "required": [
        "bytesBase64",
        "descriptor"
      ],
      "additionalProperties": false
    },
    "shape299": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape300"
      }
    },
    "shape328": {
      "const": "plowshare-information-bundle/1"
    },
    "shape329": {
      "const": "included-endpoints-only"
    },
    "shape331": {
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
    "shape330": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape331"
      }
    },
    "shape298": {
      "type": "object",
      "properties": {
        "documents": {
          "$ref": "#/$defs/shape299"
        },
        "format": {
          "$ref": "#/$defs/shape328"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "relationshipCoverage": {
          "$ref": "#/$defs/shape329"
        },
        "relationships": {
          "$ref": "#/$defs/shape330"
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
    "shape335": {
      "const": "create"
    },
    "shape334": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape254"
        },
        "mode": {
          "$ref": "#/$defs/shape335"
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
    "shape337": {
      "const": "revise"
    },
    "shape336": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "mode": {
          "$ref": "#/$defs/shape337"
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
    "shape333": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape334"
        },
        {
          "$ref": "#/$defs/shape336"
        }
      ]
    },
    "shape332": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape333"
      }
    },
    "shape339": {
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
    "shape338": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape339"
      }
    },
    "shape341": {
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
    "shape340": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape341"
      }
    },
    "shape297": {
      "type": "object",
      "properties": {
        "bundle": {
          "$ref": "#/$defs/shape298"
        },
        "destinations": {
          "$ref": "#/$defs/shape332"
        },
        "restoreRelationships": {
          "$ref": "#/$defs/shape29"
        },
        "reuse": {
          "$ref": "#/$defs/shape338"
        },
        "types": {
          "$ref": "#/$defs/shape340"
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
    "shape296": {
      "type": "object",
      "properties": {
        "plan": {
          "$ref": "#/$defs/shape297"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "plan",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape294": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape295"
        },
        "payload": {
          "$ref": "#/$defs/shape296"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "interchange.origin"
    },
    "shape344": {
      "type": "object",
      "properties": {
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape342": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape343"
        },
        "payload": {
          "$ref": "#/$defs/shape344"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "types.capabilities"
    },
    "shape347": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape345": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape346"
        },
        "payload": {
          "$ref": "#/$defs/shape347"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "types.list"
    },
    "shape350": {
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
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "view"
      ],
      "additionalProperties": false
    },
    "shape348": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape349"
        },
        "payload": {
          "$ref": "#/$defs/shape350"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "types.get"
    },
    "shape353": {
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
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "version",
        "view"
      ],
      "additionalProperties": false
    },
    "shape351": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape352"
        },
        "payload": {
          "$ref": "#/$defs/shape353"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "types.validate"
    },
    "shape356": {
      "type": "object",
      "properties": {
        "attachedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "metadata": {
          "$ref": "#/$defs/shape304"
        },
        "publishedOnly": {
          "$ref": "#/$defs/shape29"
        },
        "version": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "metadata",
        "version",
        "view"
      ],
      "additionalProperties": false
    },
    "shape354": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape355"
        },
        "payload": {
          "$ref": "#/$defs/shape356"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "types.version.create"
    },
    "shape359": {
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
          "$ref": "#/$defs/shape304"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "schema": {
          "$ref": "#/$defs/shape308"
        },
        "slug": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "requestId",
        "schema",
        "view"
      ],
      "additionalProperties": false
    },
    "shape357": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape358"
        },
        "payload": {
          "$ref": "#/$defs/shape359"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape361": {
      "const": "types.activate"
    },
    "shape362": {
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
          "$ref": "#/$defs/shape210"
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
    "shape360": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape361"
        },
        "payload": {
          "$ref": "#/$defs/shape362"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape364": {
      "const": "types.deprecate"
    },
    "shape365": {
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
          "$ref": "#/$defs/shape210"
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
    "shape363": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape364"
        },
        "payload": {
          "$ref": "#/$defs/shape365"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "content.get"
    },
    "shape368": {
      "type": "object",
      "properties": {
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "revision",
        "view"
      ],
      "additionalProperties": false
    },
    "shape366": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape367"
        },
        "payload": {
          "$ref": "#/$defs/shape368"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "content.revise"
    },
    "shape374": {
      "const": "set"
    },
    "shape373": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape304"
        },
        "mode": {
          "$ref": "#/$defs/shape374"
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
    "shape376": {
      "const": "inherit"
    },
    "shape375": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape304"
        },
        "mode": {
          "$ref": "#/$defs/shape376"
        },
        "version": {
          "$ref": "#/$defs/shape254"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape378": {
      "const": "clear"
    },
    "shape377": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape254"
        },
        "mode": {
          "$ref": "#/$defs/shape378"
        },
        "version": {
          "$ref": "#/$defs/shape254"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape372": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape373"
        },
        {
          "$ref": "#/$defs/shape375"
        },
        {
          "$ref": "#/$defs/shape377"
        }
      ]
    },
    "shape371": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape372"
        },
        "view": {
          "$ref": "#/$defs/shape210"
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
    "shape369": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape370"
        },
        "payload": {
          "$ref": "#/$defs/shape371"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "upload"
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
        "text": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape372"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape379": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape380"
        },
        "payload": {
          "$ref": "#/$defs/shape381"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "acquire"
    },
    "shape384": {
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
          "$ref": "#/$defs/shape372"
        },
        "url": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "requestId",
        "url"
      ],
      "additionalProperties": false
    },
    "shape382": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape383"
        },
        "payload": {
          "$ref": "#/$defs/shape384"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "refresh"
    },
    "shape387": {
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
          "$ref": "#/$defs/shape372"
        },
        "view": {
          "$ref": "#/$defs/shape210"
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
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape386"
        },
        "payload": {
          "$ref": "#/$defs/shape387"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
          "$ref": "#/$defs/shape372"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape388": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape337"
        },
        "payload": {
          "$ref": "#/$defs/shape389"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "replace"
    },
    "shape392": {
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
          "$ref": "#/$defs/shape372"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape390": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape391"
        },
        "payload": {
          "$ref": "#/$defs/shape392"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape394": {
      "const": "list"
    },
    "shape395": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape226"
        },
        "kind": {
          "$ref": "#/$defs/shape228"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape393": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape394"
        },
        "payload": {
          "$ref": "#/$defs/shape395"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "facets"
    },
    "shape398": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape226"
        },
        "kind": {
          "$ref": "#/$defs/shape228"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape396": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape397"
        },
        "payload": {
          "$ref": "#/$defs/shape398"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "tags"
    },
    "shape401": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "tags": {
          "$ref": "#/$defs/shape227"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "requestId",
        "revision",
        "tags"
      ],
      "additionalProperties": false
    },
    "shape399": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape400"
        },
        "payload": {
          "$ref": "#/$defs/shape401"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "tags.replace"
    },
    "shape404": {
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
          "$ref": "#/$defs/shape227"
        },
        "view": {
          "$ref": "#/$defs/shape210"
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
    "shape402": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape403"
        },
        "payload": {
          "$ref": "#/$defs/shape404"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "tags.groups"
    },
    "shape409": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape227"
      }
    },
    "shape408": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape409"
        }
      ]
    },
    "shape407": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape408"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "groups",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape405": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape406"
        },
        "payload": {
          "$ref": "#/$defs/shape407"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "inventory"
    },
    "shape412": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape410": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape411"
        },
        "payload": {
          "$ref": "#/$defs/shape412"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "acquisitions"
    },
    "shape415": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape413": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape414"
        },
        "payload": {
          "$ref": "#/$defs/shape415"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "status"
    },
    "shape418": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape416": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape417"
        },
        "payload": {
          "$ref": "#/$defs/shape418"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape420": {
      "const": "await"
    },
    "shape424": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape254"
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
    "shape425": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape254"
        }
      },
      "required": [
        "acquisition"
      ],
      "additionalProperties": false
    },
    "shape423": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape424"
        },
        {
          "$ref": "#/$defs/shape425"
        }
      ]
    },
    "shape422": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape423"
      }
    },
    "shape421": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape422"
        },
        "view": {
          "$ref": "#/$defs/shape210"
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
    "shape419": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape420"
        },
        "payload": {
          "$ref": "#/$defs/shape421"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "read"
    },
    "shape428": {
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
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape426": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape427"
        },
        "payload": {
          "$ref": "#/$defs/shape428"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "outline"
    },
    "shape431": {
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
    "shape429": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape430"
        },
        "payload": {
          "$ref": "#/$defs/shape431"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "symbols"
    },
    "shape434": {
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
    "shape432": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape433"
        },
        "payload": {
          "$ref": "#/$defs/shape434"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "search"
    },
    "shape437": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape226"
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
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape435": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape436"
        },
        "payload": {
          "$ref": "#/$defs/shape437"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape439": {
      "const": "rank"
    },
    "shape440": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape226"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "query": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "query"
      ],
      "additionalProperties": false
    },
    "shape438": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape439"
        },
        "payload": {
          "$ref": "#/$defs/shape440"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape442": {
      "const": "ask"
    },
    "shape443": {
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
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "question",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape441": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape442"
        },
        "payload": {
          "$ref": "#/$defs/shape443"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape445": {
      "const": "evidence.record"
    },
    "shape446": {
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
          "$ref": "#/$defs/shape210"
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
    "shape444": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape445"
        },
        "payload": {
          "$ref": "#/$defs/shape446"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "evidence.read"
    },
    "shape449": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "evidence"
      ],
      "additionalProperties": false
    },
    "shape447": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape448"
        },
        "payload": {
          "$ref": "#/$defs/shape449"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "record.report"
    },
    "shape456": {
      "const": "holds"
    },
    "shape457": {
      "const": "weakened"
    },
    "shape458": {
      "const": "refuted"
    },
    "shape459": {
      "const": "not_checked"
    },
    "shape455": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape456"
        },
        {
          "$ref": "#/$defs/shape457"
        },
        {
          "$ref": "#/$defs/shape458"
        },
        {
          "$ref": "#/$defs/shape459"
        }
      ]
    },
    "shape454": {
      "type": "object",
      "properties": {
        "claim": {
          "$ref": "#/$defs/shape6"
        },
        "counterEvidence": {
          "$ref": "#/$defs/shape227"
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
          "$ref": "#/$defs/shape227"
        },
        "verdict": {
          "$ref": "#/$defs/shape455"
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
    "shape453": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape454"
      }
    },
    "shape461": {
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
    "shape460": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape461"
      }
    },
    "shape452": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape227"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "feedback": {
          "$ref": "#/$defs/shape6"
        },
        "findings": {
          "$ref": "#/$defs/shape453"
        },
        "inputs": {
          "$ref": "#/$defs/shape227"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "objectives": {
          "$ref": "#/$defs/shape227"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "reviews": {
          "$ref": "#/$defs/shape460"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape227"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape372"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape450": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape451"
        },
        "payload": {
          "$ref": "#/$defs/shape452"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "finalise"
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
          "$ref": "#/$defs/shape210"
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
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape463"
        },
        "payload": {
          "$ref": "#/$defs/shape464"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "link"
    },
    "shape467": {
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
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape465": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape466"
        },
        "payload": {
          "$ref": "#/$defs/shape467"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "unlink"
    },
    "shape470": {
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
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape468": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape469"
        },
        "payload": {
          "$ref": "#/$defs/shape470"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "share"
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
          "$ref": "#/$defs/shape210"
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
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape472"
        },
        "payload": {
          "$ref": "#/$defs/shape473"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "unshare"
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
          "$ref": "#/$defs/shape210"
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
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape475"
        },
        "payload": {
          "$ref": "#/$defs/shape476"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "withdraw"
    },
    "shape479": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape477": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape478"
        },
        "payload": {
          "$ref": "#/$defs/shape479"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape481": {
      "const": "exclude"
    },
    "shape482": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape480": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape481"
        },
        "payload": {
          "$ref": "#/$defs/shape482"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape484": {
      "const": "unexclude"
    },
    "shape485": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape483": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape484"
        },
        "payload": {
          "$ref": "#/$defs/shape485"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape487": {
      "const": "restore"
    },
    "shape488": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape486": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape487"
        },
        "payload": {
          "$ref": "#/$defs/shape488"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "delete"
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
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape489": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape490"
        },
        "payload": {
          "$ref": "#/$defs/shape491"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "retry"
    },
    "shape497": {
      "const": "extract"
    },
    "shape498": {
      "const": "derive"
    },
    "shape499": {
      "const": "embed"
    },
    "shape500": {
      "const": "summarise"
    },
    "shape501": {
      "const": "summary_embed"
    },
    "shape502": {
      "const": "autoTag"
    },
    "shape503": {
      "const": "tagGroups"
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
    "shape495": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape496"
      }
    },
    "shape494": {
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
          "$ref": "#/$defs/shape495"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape492": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape493"
        },
        "payload": {
          "$ref": "#/$defs/shape494"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "rebuild"
    },
    "shape506": {
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
          "$ref": "#/$defs/shape495"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "requestId",
        "revision",
        "stage"
      ],
      "additionalProperties": false
    },
    "shape504": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape505"
        },
        "payload": {
          "$ref": "#/$defs/shape506"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
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
      "const": "allowance"
    },
    "shape509": {
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
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "maxModelCalls",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape507": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape508"
        },
        "payload": {
          "$ref": "#/$defs/shape509"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape511": {
      "const": "events"
    },
    "shape512": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape28"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape510": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape511"
        },
        "payload": {
          "$ref": "#/$defs/shape512"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape514": {
      "const": "migration.list"
    },
    "shape515": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape210"
        }
      },
      "additionalProperties": false
    },
    "shape513": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape514"
        },
        "payload": {
          "$ref": "#/$defs/shape515"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape517": {
      "const": "migration.adopt"
    },
    "shape518": {
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
          "$ref": "#/$defs/shape210"
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
    "shape516": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape517"
        },
        "payload": {
          "$ref": "#/$defs/shape518"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape520": {
      "const": "migration.inspect"
    },
    "shape521": {
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
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "payload",
        "reason"
      ],
      "additionalProperties": false
    },
    "shape519": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape520"
        },
        "payload": {
          "$ref": "#/$defs/shape521"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape523": {
      "const": "migration.release"
    },
    "shape524": {
      "type": "object",
      "properties": {
        "inputs": {
          "$ref": "#/$defs/shape227"
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
          "$ref": "#/$defs/shape210"
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
    "shape522": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape207"
        },
        "operation": {
          "$ref": "#/$defs/shape523"
        },
        "payload": {
          "$ref": "#/$defs/shape524"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape527": {
      "const": "bootstrap"
    },
    "shape528": {
      "const": "demo"
    },
    "shape529": {
      "const": "disconnect"
    },
    "shape530": {
      "const": "approvals-refresh"
    },
    "shape526": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape386"
        },
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
        }
      ]
    },
    "shape525": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape526"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape532": {
      "const": "project-access"
    },
    "shape534": {
      "const": "project.access"
    },
    "shape535": {
      "const": "project.member.add"
    },
    "shape536": {
      "const": "project.member.remove"
    },
    "shape537": {
      "const": "project.member.role"
    },
    "shape533": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape534"
        },
        {
          "$ref": "#/$defs/shape535"
        },
        {
          "$ref": "#/$defs/shape536"
        },
        {
          "$ref": "#/$defs/shape537"
        }
      ]
    },
    "shape539": {
      "const": "VIEWER"
    },
    "shape540": {
      "const": "CONTRIBUTOR"
    },
    "shape541": {
      "const": "MANAGER"
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
        }
      ]
    },
    "shape531": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape532"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape533"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape538"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape543": {
      "const": "server-admin"
    },
    "shape544": {
      "const": "admin.pricing.list"
    },
    "shape545": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape254"
      }
    },
    "shape542": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
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
      "const": "admin.pricing.set"
    },
    "shape550": {
      "const": "TOKEN"
    },
    "shape551": {
      "const": "INCLUDED"
    },
    "shape552": {
      "const": "ZERO_RATE"
    },
    "shape553": {
      "const": "UNPRICED"
    },
    "shape549": {
      "anyOf": [
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
    "shape554": {
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
    "shape557": {
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
    "shape556": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape28"
        },
        "rates": {
          "$ref": "#/$defs/shape557"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape555": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape556"
      }
    },
    "shape548": {
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
          "$ref": "#/$defs/shape549"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "rates": {
          "$ref": "#/$defs/shape554"
        },
        "requestFee": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "tiers": {
          "$ref": "#/$defs/shape555"
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
    "shape546": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
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
    "shape559": {
      "const": "admin.accounts"
    },
    "shape558": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
        },
        "operation": {
          "$ref": "#/$defs/shape559"
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
    "shape561": {
      "const": "admin.account.create"
    },
    "shape562": {
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
    "shape560": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
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
      "const": "admin.account.update"
    },
    "shape565": {
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
    "shape563": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
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
      "const": "admin.account.reset"
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
          "$ref": "#/$defs/shape543"
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
      "const": "admin.sessions"
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
          "$ref": "#/$defs/shape543"
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
      "const": "admin.session.revoke"
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
          "$ref": "#/$defs/shape543"
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
      "const": "admin.audit"
    },
    "shape577": {
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
    "shape575": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
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
      "const": "admin.service.accounts"
    },
    "shape578": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
        },
        "operation": {
          "$ref": "#/$defs/shape579"
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
    "shape581": {
      "const": "admin.service.account.create"
    },
    "shape582": {
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
    "shape580": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
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
      "const": "admin.service.account.update"
    },
    "shape585": {
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
    "shape583": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
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
      "const": "admin.service.tokens"
    },
    "shape588": {
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
    "shape586": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
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
      "const": "admin.service.token.create"
    },
    "shape593": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape538"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape592": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape593"
      }
    },
    "shape591": {
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
          "$ref": "#/$defs/shape592"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape589": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
        },
        "operation": {
          "$ref": "#/$defs/shape590"
        },
        "payload": {
          "$ref": "#/$defs/shape591"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape595": {
      "const": "admin.service.token.rotate"
    },
    "shape596": {
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
    "shape594": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
        },
        "operation": {
          "$ref": "#/$defs/shape595"
        },
        "payload": {
          "$ref": "#/$defs/shape596"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape598": {
      "const": "admin.service.token.revoke"
    },
    "shape599": {
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
    "shape597": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape543"
        },
        "operation": {
          "$ref": "#/$defs/shape598"
        },
        "payload": {
          "$ref": "#/$defs/shape599"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape601": {
      "const": "server-project-create"
    },
    "shape603": {
      "const": "MANAGED"
    },
    "shape604": {
      "const": "DISJOINT"
    },
    "shape602": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape603"
        },
        {
          "$ref": "#/$defs/shape604"
        }
      ]
    },
    "shape605": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape606": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape600": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape601"
        },
        "applicationRoot": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape602"
        },
        "workspace": {
          "$ref": "#/$defs/shape6"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape605"
        },
        "writePaths": {
          "$ref": "#/$defs/shape606"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape608": {
      "const": "server-setup"
    },
    "shape607": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape608"
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
    "shape610": {
      "const": "connect"
    },
    "shape609": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape610"
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
    "shape612": {
      "const": "connection-select"
    },
    "shape611": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape612"
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
    "shape614": {
      "const": "connection-preferences"
    },
    "shape616": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape617": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape29"
      }
    },
    "shape615": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape616"
        },
        "drafts": {
          "$ref": "#/$defs/shape616"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape617"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape617"
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
    "shape613": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape6"
        },
        "action": {
          "$ref": "#/$defs/shape614"
        },
        "preference": {
          "$ref": "#/$defs/shape615"
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
    "shape619": {
      "const": "connection-rename"
    },
    "shape618": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape619"
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
    "shape621": {
      "const": "connection-remove"
    },
    "shape620": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape621"
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
    "shape623": {
      "const": "personal-section"
    },
    "shape625": {
      "const": "In"
    },
    "shape626": {
      "const": "Out"
    },
    "shape627": {
      "const": "Resources"
    },
    "shape628": {
      "const": "Archive"
    },
    "shape629": {
      "const": "Planning"
    },
    "shape630": {
      "const": "Bots"
    },
    "shape624": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape625"
        },
        {
          "$ref": "#/$defs/shape626"
        },
        {
          "$ref": "#/$defs/shape627"
        },
        {
          "$ref": "#/$defs/shape628"
        },
        {
          "$ref": "#/$defs/shape629"
        },
        {
          "$ref": "#/$defs/shape630"
        }
      ]
    },
    "shape622": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape623"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "section": {
          "$ref": "#/$defs/shape624"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape632": {
      "const": "personal-bots"
    },
    "shape631": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape632"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape634": {
      "const": "personal-recreate"
    },
    "shape633": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape634"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape636": {
      "const": "files-choose"
    },
    "shape635": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape636"
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
    "shape638": {
      "const": "files-withdraw"
    },
    "shape637": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape638"
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
    "shape640": {
      "const": "sync-refresh"
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
    "shape642": {
      "const": "sync-inspect"
    },
    "shape641": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape642"
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
    "shape644": {
      "const": "sync-change"
    },
    "shape646": {
      "const": "on"
    },
    "shape647": {
      "const": "off"
    },
    "shape648": {
      "const": "now"
    },
    "shape645": {
      "anyOf": [
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
    "shape643": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape644"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape645"
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
    "shape650": {
      "const": "sync-resolve"
    },
    "shape652": {
      "const": "theirs"
    },
    "shape653": {
      "const": "done"
    },
    "shape651": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape72"
        },
        {
          "$ref": "#/$defs/shape652"
        },
        {
          "$ref": "#/$defs/shape653"
        }
      ]
    },
    "shape649": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape650"
        },
        "how": {
          "$ref": "#/$defs/shape651"
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
    "shape656": {
      "const": "project-open"
    },
    "shape657": {
      "const": "project-remove"
    },
    "shape655": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape656"
        },
        {
          "$ref": "#/$defs/shape657"
        }
      ]
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
        "action",
        "project"
      ],
      "additionalProperties": false
    },
    "shape659": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape276"
        },
        {
          "$ref": "#/$defs/shape335"
        }
      ]
    },
    "shape658": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape659"
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
    "shape661": {
      "const": "history"
    },
    "shape660": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape661"
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
    "shape663": {
      "const": "select"
    },
    "shape662": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape663"
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
    "shape665": {
      "const": "trajectory"
    },
    "shape664": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape665"
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
    "shape667": {
      "const": "board-swarm-types"
    },
    "shape666": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape667"
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
    "shape669": {
      "const": "board-post-topics"
    },
    "shape668": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape669"
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
    "shape671": {
      "const": "board-create"
    },
    "shape670": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape671"
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
    "shape672": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape175"
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
    "shape674": {
      "const": "board-retry"
    },
    "shape673": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape674"
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
    "shape676": {
      "const": "board-post"
    },
    "shape675": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape676"
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
    "shape678": {
      "const": "workspace-chat"
    },
    "shape677": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape678"
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
    "shape680": {
      "const": "workspace-administration"
    },
    "shape679": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape680"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape682": {
      "const": "server-configuration"
    },
    "shape681": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape682"
        },
        "operation": {
          "$ref": "#/$defs/shape394"
        }
      },
      "required": [
        "action",
        "operation"
      ],
      "additionalProperties": false
    },
    "shape683": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape682"
        },
        "key": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape374"
        },
        "value": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "action",
        "key",
        "operation",
        "value"
      ],
      "additionalProperties": false
    },
    "shape685": {
      "const": "workspace-layout"
    },
    "shape684": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape685"
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
    "shape687": {
      "const": "workspace-refresh"
    },
    "shape686": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape687"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape689": {
      "const": "library"
    },
    "shape691": {
      "const": "sources"
    },
    "shape692": {
      "const": "documents"
    },
    "shape693": {
      "const": "memories"
    },
    "shape694": {
      "const": "manual"
    },
    "shape690": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape436"
        },
        {
          "$ref": "#/$defs/shape691"
        },
        {
          "$ref": "#/$defs/shape692"
        },
        {
          "$ref": "#/$defs/shape693"
        },
        {
          "$ref": "#/$defs/shape694"
        }
      ]
    },
    "shape688": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape689"
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
          "$ref": "#/$defs/shape690"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape696": {
      "const": "library-view"
    },
    "shape695": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape696"
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
          "$ref": "#/$defs/shape690"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape699": {
      "const": "library-refresh"
    },
    "shape700": {
      "const": "library-citations"
    },
    "shape698": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape699"
        },
        {
          "$ref": "#/$defs/shape700"
        }
      ]
    },
    "shape697": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape698"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape702": {
      "const": "library-documents"
    },
    "shape701": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape702"
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
    "shape705": {
      "const": "library-document"
    },
    "shape706": {
      "const": "library-memory"
    },
    "shape707": {
      "const": "library-chunk"
    },
    "shape708": {
      "const": "library-conversation"
    },
    "shape704": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape705"
        },
        {
          "$ref": "#/$defs/shape706"
        },
        {
          "$ref": "#/$defs/shape707"
        },
        {
          "$ref": "#/$defs/shape708"
        }
      ]
    },
    "shape703": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape704"
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
    "shape710": {
      "const": "library-source-text"
    },
    "shape709": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape710"
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
    "shape712": {
      "const": "library-stance"
    },
    "shape711": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape712"
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
    "shape714": {
      "const": "library-search"
    },
    "shape716": {
      "const": "retrieve"
    },
    "shape717": {
      "const": "recall"
    },
    "shape718": {
      "const": "navigate"
    },
    "shape715": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape439"
        },
        {
          "$ref": "#/$defs/shape189"
        },
        {
          "$ref": "#/$defs/shape692"
        },
        {
          "$ref": "#/$defs/shape716"
        },
        {
          "$ref": "#/$defs/shape717"
        },
        {
          "$ref": "#/$defs/shape718"
        }
      ]
    },
    "shape720": {
      "const": "lexical"
    },
    "shape721": {
      "const": "semantic"
    },
    "shape722": {
      "const": "hybrid"
    },
    "shape719": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape720"
        },
        {
          "$ref": "#/$defs/shape721"
        },
        {
          "$ref": "#/$defs/shape722"
        }
      ]
    },
    "shape713": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape714"
        },
        "kind": {
          "$ref": "#/$defs/shape715"
        },
        "mode": {
          "$ref": "#/$defs/shape719"
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
    "shape724": {
      "const": "library-maintain"
    },
    "shape726": {
      "const": "invalidate"
    },
    "shape727": {
      "const": "resolve"
    },
    "shape728": {
      "const": "reembed"
    },
    "shape729": {
      "const": "reconsider"
    },
    "shape725": {
      "anyOf": [
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
    "shape723": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape29"
        },
        "action": {
          "$ref": "#/$defs/shape724"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape725"
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
    "shape731": {
      "const": "builder-outputs"
    },
    "shape730": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape731"
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
    "shape733": {
      "const": "builder-trajectory"
    },
    "shape732": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape733"
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
    "shape735": {
      "const": "builder-prepare"
    },
    "shape734": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape735"
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
    "shape737": {
      "const": "builder-start"
    },
    "shape736": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape737"
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
    "shape739": {
      "const": "activity"
    },
    "shape741": {
      "const": "inbox"
    },
    "shape742": {
      "const": "runs"
    },
    "shape743": {
      "const": "definitions"
    },
    "shape744": {
      "const": "schedules"
    },
    "shape745": {
      "const": "builder"
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
        },
        {
          "$ref": "#/$defs/shape744"
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
        "view": {
          "$ref": "#/$defs/shape740"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape747": {
      "const": "activity-view"
    },
    "shape746": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape747"
        },
        "view": {
          "$ref": "#/$defs/shape740"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape749": {
      "const": "activity-refresh"
    },
    "shape748": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape749"
        },
        "view": {
          "$ref": "#/$defs/shape740"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape751": {
      "const": "question-refresh"
    },
    "shape750": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape751"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape753": {
      "const": "inbox-read"
    },
    "shape752": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape753"
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
    "shape755": {
      "const": "inbox-more"
    },
    "shape754": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape755"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape757": {
      "const": "run-detail"
    },
    "shape758": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape742"
        },
        {
          "$ref": "#/$defs/shape745"
        }
      ]
    },
    "shape756": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape757"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape758"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape760": {
      "const": "schedule-refresh"
    },
    "shape762": {
      "const": "next"
    },
    "shape763": {
      "const": "previous"
    },
    "shape764": {
      "const": "first"
    },
    "shape761": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape762"
        },
        {
          "$ref": "#/$defs/shape763"
        },
        {
          "$ref": "#/$defs/shape764"
        }
      ]
    },
    "shape766": {
      "const": "files"
    },
    "shape765": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape744"
        },
        {
          "$ref": "#/$defs/shape766"
        }
      ]
    },
    "shape759": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape760"
        },
        "direction": {
          "$ref": "#/$defs/shape761"
        },
        "section": {
          "$ref": "#/$defs/shape765"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape768": {
      "const": "schedule-preview"
    },
    "shape767": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape768"
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
    "shape770": {
      "const": "schedule-save"
    },
    "shape774": {
      "const": "skill"
    },
    "shape775": {
      "const": "orchestration"
    },
    "shape773": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape119"
        },
        {
          "$ref": "#/$defs/shape774"
        },
        {
          "$ref": "#/$defs/shape775"
        }
      ]
    },
    "shape777": {
      "const": "INHERITED"
    },
    "shape778": {
      "const": "SUMMARISED"
    },
    "shape779": {
      "const": "NEW"
    },
    "shape780": {
      "const": "DIRECT"
    },
    "shape776": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape777"
        },
        {
          "$ref": "#/$defs/shape778"
        },
        {
          "$ref": "#/$defs/shape779"
        },
        {
          "$ref": "#/$defs/shape780"
        }
      ]
    },
    "shape772": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape773"
        },
        "mode": {
          "$ref": "#/$defs/shape776"
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
    "shape782": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape28"
        }
      ]
    },
    "shape781": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape782"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape782"
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
    "shape785": {
      "const": "mailbox"
    },
    "shape786": {
      "const": "message"
    },
    "shape784": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape189"
        },
        {
          "$ref": "#/$defs/shape785"
        },
        {
          "$ref": "#/$defs/shape786"
        }
      ]
    },
    "shape783": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape784"
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
    "shape787": {
      "const": 1
    },
    "shape771": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape772"
        },
        "cron": {
          "$ref": "#/$defs/shape6"
        },
        "limits": {
          "$ref": "#/$defs/shape781"
        },
        "paused": {
          "$ref": "#/$defs/shape29"
        },
        "target": {
          "$ref": "#/$defs/shape783"
        },
        "version": {
          "$ref": "#/$defs/shape787"
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
    "shape789": {
      "const": "workspace"
    },
    "shape788": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape73"
        },
        {
          "$ref": "#/$defs/shape789"
        }
      ]
    },
    "shape769": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape770"
        },
        "definition": {
          "$ref": "#/$defs/shape771"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape788"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape791": {
      "const": "schedule-file-save"
    },
    "shape790": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape791"
        },
        "definition": {
          "$ref": "#/$defs/shape771"
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
          "$ref": "#/$defs/shape788"
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
    "shape793": {
      "const": "schedule-sync"
    },
    "shape792": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape793"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape788"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape795": {
      "const": "schedule-change"
    },
    "shape797": {
      "const": "schedule"
    },
    "shape798": {
      "const": "trigger"
    },
    "shape796": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape797"
        },
        {
          "$ref": "#/$defs/shape798"
        }
      ]
    },
    "shape794": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape795"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape796"
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
    "shape800": {
      "const": "schedule-fire"
    },
    "shape799": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape800"
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
    "shape802": {
      "const": "run-definitions"
    },
    "shape801": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape802"
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
    "shape804": {
      "const": "run-record"
    },
    "shape803": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape804"
        },
        "before": {
          "$ref": "#/$defs/shape28"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "kinds": {
          "$ref": "#/$defs/shape606"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape806": {
      "const": "run-answer"
    },
    "shape808": {
      "type": "object",
      "properties": {
        "chosen": {
          "$ref": "#/$defs/shape227"
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
    "shape807": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape808"
      }
    },
    "shape805": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape806"
        },
        "answer": {
          "$ref": "#/$defs/shape6"
        },
        "choices": {
          "$ref": "#/$defs/shape807"
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
    "shape810": {
      "const": "run-cancel"
    },
    "shape809": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape810"
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
    "shape812": {
      "const": "run-resume"
    },
    "shape811": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape812"
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
    "shape814": {
      "const": "run-trajectory"
    },
    "shape816": {
      "const": "conductor"
    },
    "shape817": {
      "const": "caller"
    },
    "shape815": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape816"
        },
        {
          "$ref": "#/$defs/shape817"
        }
      ]
    },
    "shape813": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape814"
        },
        "actor": {
          "$ref": "#/$defs/shape815"
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
    "shape819": {
      "const": "run-stage-trajectory"
    },
    "shape818": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape819"
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
    "shape821": {
      "const": "delegate-trajectory"
    },
    "shape820": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape821"
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
    "shape824": {
      "const": "board-inspection"
    },
    "shape825": {
      "const": "board-view"
    },
    "shape823": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape824"
        },
        {
          "$ref": "#/$defs/shape825"
        }
      ]
    },
    "shape827": {
      "const": "board"
    },
    "shape828": {
      "const": "swarm"
    },
    "shape826": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape827"
        },
        {
          "$ref": "#/$defs/shape828"
        }
      ]
    },
    "shape822": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape823"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape826"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape831": {
      "const": "board-refresh"
    },
    "shape832": {
      "const": "board-more"
    },
    "shape830": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape831"
        },
        {
          "$ref": "#/$defs/shape832"
        }
      ]
    },
    "shape829": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape830"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape834": {
      "const": "board-topic"
    },
    "shape833": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape834"
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
    "shape836": {
      "const": "board-trajectory"
    },
    "shape835": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape836"
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
    "shape838": {
      "const": "context"
    },
    "shape837": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape838"
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
    "shape840": {
      "const": "open-link"
    },
    "shape839": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape840"
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
    "shape842": {
      "const": "copy-text"
    },
    "shape841": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape842"
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
    "shape844": {
      "const": "workflow-start"
    },
    "shape843": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape844"
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
    "shape845": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape122"
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
    "shape847": {
      "const": "cancel"
    },
    "shape846": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape847"
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
    "shape849": {
      "const": "answer"
    },
    "shape850": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape188"
        },
        {
          "$ref": "#/$defs/shape190"
        }
      ]
    },
    "shape848": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape849"
        },
        "decision": {
          "$ref": "#/$defs/shape850"
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
    "shape852": {
      "const": "native-mcp"
    },
    "shape851": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape852"
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
    "shape854": {
      "const": "provider-management"
    },
    "shape855": {
      "const": "provider.connection.list"
    },
    "shape856": {
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
    "shape853": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
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
      "const": "provider.connection.status"
    },
    "shape859": {
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
    "shape857": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
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
      "const": "provider.connection.receipt"
    },
    "shape862": {
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
    "shape860": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
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
    "shape864": {
      "const": "provider.connection.save"
    },
    "shape868": {
      "const": "Authorization"
    },
    "shape869": {
      "const": "X-API-Key"
    },
    "shape870": {
      "const": "Api-Key"
    },
    "shape867": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape868"
        },
        {
          "$ref": "#/$defs/shape869"
        },
        {
          "$ref": "#/$defs/shape870"
        }
      ]
    },
    "shape872": {
      "const": "API_KEY"
    },
    "shape873": {
      "const": "OAUTH_PKCE"
    },
    "shape874": {
      "const": "CLIENT_CREDENTIALS"
    },
    "shape871": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape872"
        },
        {
          "$ref": "#/$defs/shape873"
        },
        {
          "$ref": "#/$defs/shape874"
        }
      ]
    },
    "shape876": {
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
          "$ref": "#/$defs/shape227"
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
    "shape875": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape876"
        }
      ]
    },
    "shape866": {
      "type": "object",
      "properties": {
        "apiKeyHeader": {
          "$ref": "#/$defs/shape867"
        },
        "authentication": {
          "$ref": "#/$defs/shape871"
        },
        "endpoint": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "oauth": {
          "$ref": "#/$defs/shape875"
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
    "shape865": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape866"
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
    "shape863": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
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
    "shape878": {
      "const": "provider.connection.change"
    },
    "shape881": {
      "const": "ENABLE"
    },
    "shape882": {
      "const": "DISABLE"
    },
    "shape883": {
      "const": "DELETE"
    },
    "shape880": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape881"
        },
        {
          "$ref": "#/$defs/shape882"
        },
        {
          "$ref": "#/$defs/shape883"
        }
      ]
    },
    "shape879": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape880"
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
    "shape877": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
        },
        "operation": {
          "$ref": "#/$defs/shape878"
        },
        "payload": {
          "$ref": "#/$defs/shape879"
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
      "const": "provider.credential.status"
    },
    "shape886": {
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
    "shape884": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
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
      "const": "provider.credential.put"
    },
    "shape889": {
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
    "shape887": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
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
    "shape891": {
      "const": "provider.credential.revoke"
    },
    "shape892": {
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
    "shape890": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
        },
        "operation": {
          "$ref": "#/$defs/shape891"
        },
        "payload": {
          "$ref": "#/$defs/shape892"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape894": {
      "const": "provider.credential.rekey"
    },
    "shape893": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
        },
        "operation": {
          "$ref": "#/$defs/shape894"
        },
        "payload": {
          "$ref": "#/$defs/shape892"
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
      "const": "provider.credential.authorize"
    },
    "shape895": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
        },
        "operation": {
          "$ref": "#/$defs/shape896"
        },
        "payload": {
          "$ref": "#/$defs/shape892"
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
      "const": "provider.credential.refresh"
    },
    "shape897": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
        },
        "operation": {
          "$ref": "#/$defs/shape898"
        },
        "payload": {
          "$ref": "#/$defs/shape892"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape900": {
      "const": "tool.scope.host.list"
    },
    "shape901": {
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
    "shape899": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
        },
        "operation": {
          "$ref": "#/$defs/shape900"
        },
        "payload": {
          "$ref": "#/$defs/shape901"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape903": {
      "const": "tool.scope.host.status"
    },
    "shape904": {
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
    "shape902": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
        },
        "operation": {
          "$ref": "#/$defs/shape903"
        },
        "payload": {
          "$ref": "#/$defs/shape904"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape906": {
      "const": "tool.scope.host.receipt"
    },
    "shape907": {
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
    "shape905": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
        },
        "operation": {
          "$ref": "#/$defs/shape906"
        },
        "payload": {
          "$ref": "#/$defs/shape907"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape909": {
      "const": "tool.scope.host.save"
    },
    "shape911": {
      "type": "object",
      "properties": {
        "agents": {
          "$ref": "#/$defs/shape227"
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
          "$ref": "#/$defs/shape227"
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
    "shape910": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape911"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape782"
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
    "shape908": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
        },
        "operation": {
          "$ref": "#/$defs/shape909"
        },
        "payload": {
          "$ref": "#/$defs/shape910"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape913": {
      "const": "tool.scope.host.change"
    },
    "shape914": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape880"
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
    "shape912": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
        },
        "operation": {
          "$ref": "#/$defs/shape913"
        },
        "payload": {
          "$ref": "#/$defs/shape914"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape916": {
      "const": "tool.scope.list"
    },
    "shape917": {
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
    "shape915": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape854"
        },
        "operation": {
          "$ref": "#/$defs/shape916"
        },
        "payload": {
          "$ref": "#/$defs/shape917"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape919": {
      "const": "provider-management-pending"
    },
    "shape918": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape919"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape921": {
      "const": "provider-management-recover"
    },
    "shape924": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape864"
        },
        {
          "$ref": "#/$defs/shape878"
        }
      ]
    },
    "shape923": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape924"
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
    "shape926": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape888"
        },
        {
          "$ref": "#/$defs/shape891"
        },
        {
          "$ref": "#/$defs/shape894"
        },
        {
          "$ref": "#/$defs/shape896"
        },
        {
          "$ref": "#/$defs/shape898"
        }
      ]
    },
    "shape925": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape926"
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
    "shape928": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape909"
        },
        {
          "$ref": "#/$defs/shape913"
        }
      ]
    },
    "shape927": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape782"
        },
        "operation": {
          "$ref": "#/$defs/shape928"
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
    "shape922": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape923"
        },
        {
          "$ref": "#/$defs/shape925"
        },
        {
          "$ref": "#/$defs/shape927"
        }
      ]
    },
    "shape920": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape921"
        },
        "intent": {
          "$ref": "#/$defs/shape922"
        }
      },
      "required": [
        "action",
        "intent"
      ],
      "additionalProperties": false
    },
    "shape930": {
      "const": "information-import-recovery-save"
    },
    "shape932": {
      "const": "plowshare-information-import-recovery/1"
    },
    "shape931": {
      "type": "object",
      "properties": {
        "context": {
          "$ref": "#/$defs/shape6"
        },
        "format": {
          "$ref": "#/$defs/shape932"
        },
        "plan": {
          "$ref": "#/$defs/shape297"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape211"
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
    "shape929": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape930"
        },
        "recovery": {
          "$ref": "#/$defs/shape931"
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
          "$ref": "#/$defs/shape61"
        },
        {
          "$ref": "#/$defs/shape63"
        },
        {
          "$ref": "#/$defs/shape65"
        },
        {
          "$ref": "#/$defs/shape75"
        },
        {
          "$ref": "#/$defs/shape77"
        },
        {
          "$ref": "#/$defs/shape79"
        },
        {
          "$ref": "#/$defs/shape81"
        },
        {
          "$ref": "#/$defs/shape83"
        },
        {
          "$ref": "#/$defs/shape85"
        },
        {
          "$ref": "#/$defs/shape87"
        },
        {
          "$ref": "#/$defs/shape89"
        },
        {
          "$ref": "#/$defs/shape95"
        },
        {
          "$ref": "#/$defs/shape100"
        },
        {
          "$ref": "#/$defs/shape109"
        },
        {
          "$ref": "#/$defs/shape111"
        },
        {
          "$ref": "#/$defs/shape134"
        },
        {
          "$ref": "#/$defs/shape137"
        },
        {
          "$ref": "#/$defs/shape139"
        },
        {
          "$ref": "#/$defs/shape141"
        },
        {
          "$ref": "#/$defs/shape143"
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
          "$ref": "#/$defs/shape150"
        },
        {
          "$ref": "#/$defs/shape153"
        },
        {
          "$ref": "#/$defs/shape156"
        },
        {
          "$ref": "#/$defs/shape159"
        },
        {
          "$ref": "#/$defs/shape162"
        },
        {
          "$ref": "#/$defs/shape164"
        },
        {
          "$ref": "#/$defs/shape182"
        },
        {
          "$ref": "#/$defs/shape184"
        },
        {
          "$ref": "#/$defs/shape204"
        },
        {
          "$ref": "#/$defs/shape206"
        },
        {
          "$ref": "#/$defs/shape220"
        },
        {
          "$ref": "#/$defs/shape232"
        },
        {
          "$ref": "#/$defs/shape235"
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
          "$ref": "#/$defs/shape258"
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
          "$ref": "#/$defs/shape360"
        },
        {
          "$ref": "#/$defs/shape363"
        },
        {
          "$ref": "#/$defs/shape366"
        },
        {
          "$ref": "#/$defs/shape369"
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
          "$ref": "#/$defs/shape390"
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
          "$ref": "#/$defs/shape402"
        },
        {
          "$ref": "#/$defs/shape405"
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
          "$ref": "#/$defs/shape419"
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
          "$ref": "#/$defs/shape438"
        },
        {
          "$ref": "#/$defs/shape441"
        },
        {
          "$ref": "#/$defs/shape444"
        },
        {
          "$ref": "#/$defs/shape447"
        },
        {
          "$ref": "#/$defs/shape450"
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
          "$ref": "#/$defs/shape480"
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
          "$ref": "#/$defs/shape504"
        },
        {
          "$ref": "#/$defs/shape507"
        },
        {
          "$ref": "#/$defs/shape510"
        },
        {
          "$ref": "#/$defs/shape513"
        },
        {
          "$ref": "#/$defs/shape516"
        },
        {
          "$ref": "#/$defs/shape519"
        },
        {
          "$ref": "#/$defs/shape522"
        },
        {
          "$ref": "#/$defs/shape525"
        },
        {
          "$ref": "#/$defs/shape531"
        },
        {
          "$ref": "#/$defs/shape542"
        },
        {
          "$ref": "#/$defs/shape546"
        },
        {
          "$ref": "#/$defs/shape558"
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
          "$ref": "#/$defs/shape580"
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
          "$ref": "#/$defs/shape594"
        },
        {
          "$ref": "#/$defs/shape597"
        },
        {
          "$ref": "#/$defs/shape600"
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
          "$ref": "#/$defs/shape618"
        },
        {
          "$ref": "#/$defs/shape620"
        },
        {
          "$ref": "#/$defs/shape622"
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
          "$ref": "#/$defs/shape641"
        },
        {
          "$ref": "#/$defs/shape643"
        },
        {
          "$ref": "#/$defs/shape649"
        },
        {
          "$ref": "#/$defs/shape654"
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
          "$ref": "#/$defs/shape670"
        },
        {
          "$ref": "#/$defs/shape672"
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
          "$ref": "#/$defs/shape683"
        },
        {
          "$ref": "#/$defs/shape684"
        },
        {
          "$ref": "#/$defs/shape686"
        },
        {
          "$ref": "#/$defs/shape688"
        },
        {
          "$ref": "#/$defs/shape695"
        },
        {
          "$ref": "#/$defs/shape697"
        },
        {
          "$ref": "#/$defs/shape701"
        },
        {
          "$ref": "#/$defs/shape703"
        },
        {
          "$ref": "#/$defs/shape709"
        },
        {
          "$ref": "#/$defs/shape711"
        },
        {
          "$ref": "#/$defs/shape713"
        },
        {
          "$ref": "#/$defs/shape723"
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
          "$ref": "#/$defs/shape759"
        },
        {
          "$ref": "#/$defs/shape767"
        },
        {
          "$ref": "#/$defs/shape769"
        },
        {
          "$ref": "#/$defs/shape790"
        },
        {
          "$ref": "#/$defs/shape792"
        },
        {
          "$ref": "#/$defs/shape794"
        },
        {
          "$ref": "#/$defs/shape799"
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
          "$ref": "#/$defs/shape809"
        },
        {
          "$ref": "#/$defs/shape811"
        },
        {
          "$ref": "#/$defs/shape813"
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
          "$ref": "#/$defs/shape829"
        },
        {
          "$ref": "#/$defs/shape833"
        },
        {
          "$ref": "#/$defs/shape835"
        },
        {
          "$ref": "#/$defs/shape837"
        },
        {
          "$ref": "#/$defs/shape839"
        },
        {
          "$ref": "#/$defs/shape841"
        },
        {
          "$ref": "#/$defs/shape843"
        },
        {
          "$ref": "#/$defs/shape845"
        },
        {
          "$ref": "#/$defs/shape846"
        },
        {
          "$ref": "#/$defs/shape848"
        },
        {
          "$ref": "#/$defs/shape851"
        },
        {
          "$ref": "#/$defs/shape853"
        },
        {
          "$ref": "#/$defs/shape857"
        },
        {
          "$ref": "#/$defs/shape860"
        },
        {
          "$ref": "#/$defs/shape863"
        },
        {
          "$ref": "#/$defs/shape877"
        },
        {
          "$ref": "#/$defs/shape884"
        },
        {
          "$ref": "#/$defs/shape887"
        },
        {
          "$ref": "#/$defs/shape890"
        },
        {
          "$ref": "#/$defs/shape893"
        },
        {
          "$ref": "#/$defs/shape895"
        },
        {
          "$ref": "#/$defs/shape897"
        },
        {
          "$ref": "#/$defs/shape899"
        },
        {
          "$ref": "#/$defs/shape902"
        },
        {
          "$ref": "#/$defs/shape905"
        },
        {
          "$ref": "#/$defs/shape908"
        },
        {
          "$ref": "#/$defs/shape912"
        },
        {
          "$ref": "#/$defs/shape915"
        },
        {
          "$ref": "#/$defs/shape918"
        },
        {
          "$ref": "#/$defs/shape920"
        },
        {
          "$ref": "#/$defs/shape929"
        }
      ]
    }
  }
}
