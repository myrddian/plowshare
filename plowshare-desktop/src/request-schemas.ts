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
      "const": "mine"
    },
    "shape70": {
      "const": "server"
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
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "root": {
          "$ref": "#/$defs/shape6"
        },
        "visibility": {
          "$ref": "#/$defs/shape68"
        }
      },
      "additionalProperties": false
    },
    "shape71": {
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
      "const": "proc.inspect"
    },
    "shape72": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape66"
        },
        "payload": {
          "$ref": "#/$defs/shape67"
        },
        "type": {
          "$ref": "#/$defs/shape73"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape75": {
      "const": "proc.calls"
    },
    "shape74": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape66"
        },
        "payload": {
          "$ref": "#/$defs/shape67"
        },
        "type": {
          "$ref": "#/$defs/shape75"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape77": {
      "const": "proc.queues"
    },
    "shape76": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape66"
        },
        "payload": {
          "$ref": "#/$defs/shape67"
        },
        "type": {
          "$ref": "#/$defs/shape77"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape79": {
      "const": "proc.tools"
    },
    "shape78": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape66"
        },
        "payload": {
          "$ref": "#/$defs/shape67"
        },
        "type": {
          "$ref": "#/$defs/shape79"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape81": {
      "const": "proc-trajectory"
    },
    "shape80": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape81"
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
    "shape83": {
      "const": "context-snapshot"
    },
    "shape82": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape83"
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
    "shape85": {
      "const": "relay"
    },
    "shape84": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape85"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape87": {
      "const": "relay-read"
    },
    "shape89": {
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
    "shape90": {
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
    "shape88": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape89"
        },
        {
          "$ref": "#/$defs/shape90"
        }
      ]
    },
    "shape91": {
      "const": "relay.topics"
    },
    "shape86": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape87"
        },
        "payload": {
          "$ref": "#/$defs/shape88"
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
    "shape94": {
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
    "shape95": {
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
    "shape96": {
      "const": "relay.log"
    },
    "shape92": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape87"
        },
        "payload": {
          "$ref": "#/$defs/shape93"
        },
        "type": {
          "$ref": "#/$defs/shape96"
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
      "const": "relay-operate"
    },
    "shape101": {
      "const": "ACKNOWLEDGE_GAP"
    },
    "shape102": {
      "const": "RECONCILE"
    },
    "shape103": {
      "const": "ABANDON"
    },
    "shape104": {
      "const": "REMOVE_SUBSCRIPTION"
    },
    "shape105": {
      "const": "REMOVE_TOPIC"
    },
    "shape100": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape101"
        },
        {
          "$ref": "#/$defs/shape102"
        },
        {
          "$ref": "#/$defs/shape103"
        },
        {
          "$ref": "#/$defs/shape104"
        },
        {
          "$ref": "#/$defs/shape105"
        }
      ]
    },
    "shape99": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape100"
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
    "shape97": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape98"
        },
        "payload": {
          "$ref": "#/$defs/shape99"
        }
      },
      "required": [
        "action",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape107": {
      "const": "relay-trajectory"
    },
    "shape106": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape107"
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
    "shape109": {
      "const": "usage-open"
    },
    "shape113": {
      "const": "day"
    },
    "shape114": {
      "const": "model"
    },
    "shape115": {
      "const": "pool"
    },
    "shape116": {
      "const": "agent"
    },
    "shape117": {
      "const": "operation"
    },
    "shape118": {
      "const": "project"
    },
    "shape119": {
      "const": "run"
    },
    "shape112": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape113"
        },
        {
          "$ref": "#/$defs/shape114"
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
          "$ref": "#/$defs/shape118"
        },
        {
          "$ref": "#/$defs/shape119"
        }
      ]
    },
    "shape111": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape112"
      }
    },
    "shape121": {
      "const": "direct"
    },
    "shape122": {
      "const": "subtree"
    },
    "shape120": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape121"
        },
        {
          "$ref": "#/$defs/shape122"
        }
      ]
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
          "$ref": "#/$defs/shape111"
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
          "$ref": "#/$defs/shape120"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape124": {
      "const": "usage.conversation"
    },
    "shape125": {
      "const": "usage.project"
    },
    "shape126": {
      "const": "usage.agent"
    },
    "shape127": {
      "const": "usage.run"
    },
    "shape128": {
      "const": "usage.orchestration"
    },
    "shape129": {
      "const": "usage.models"
    },
    "shape130": {
      "const": "usage.pools"
    },
    "shape123": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape124"
        },
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
        }
      ]
    },
    "shape108": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape109"
        },
        "filter": {
          "$ref": "#/$defs/shape110"
        },
        "type": {
          "$ref": "#/$defs/shape123"
        }
      },
      "required": [
        "action",
        "filter",
        "type"
      ],
      "additionalProperties": false
    },
    "shape132": {
      "const": "usage-read"
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
          "$ref": "#/$defs/shape111"
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
          "$ref": "#/$defs/shape120"
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
    "shape131": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "payload": {
          "$ref": "#/$defs/shape133"
        },
        "type": {
          "$ref": "#/$defs/shape124"
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
          "$ref": "#/$defs/shape111"
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
          "$ref": "#/$defs/shape120"
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
    "shape134": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "payload": {
          "$ref": "#/$defs/shape135"
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
    "shape137": {
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
          "$ref": "#/$defs/shape111"
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
          "$ref": "#/$defs/shape120"
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
    "shape136": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "payload": {
          "$ref": "#/$defs/shape137"
        },
        "type": {
          "$ref": "#/$defs/shape126"
        }
      },
      "required": [
        "action",
        "payload",
        "type"
      ],
      "additionalProperties": false
    },
    "shape139": {
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
          "$ref": "#/$defs/shape111"
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
          "$ref": "#/$defs/shape120"
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
    "shape138": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "payload": {
          "$ref": "#/$defs/shape139"
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
    "shape141": {
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
          "$ref": "#/$defs/shape111"
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
          "$ref": "#/$defs/shape120"
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
    "shape140": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "payload": {
          "$ref": "#/$defs/shape141"
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
    "shape142": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "payload": {
          "$ref": "#/$defs/shape110"
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
    "shape143": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "payload": {
          "$ref": "#/$defs/shape110"
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
    "shape145": {
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
          "$ref": "#/$defs/shape111"
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
          "$ref": "#/$defs/shape120"
        },
        "to": {
          "$ref": "#/$defs/shape6"
        }
      },
      "additionalProperties": false
    },
    "shape146": {
      "const": "usage.calls"
    },
    "shape144": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
        },
        "payload": {
          "$ref": "#/$defs/shape145"
        },
        "type": {
          "$ref": "#/$defs/shape146"
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
          "$ref": "#/$defs/shape111"
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
          "$ref": "#/$defs/shape123"
        },
        "route": {
          "$ref": "#/$defs/shape6"
        },
        "run": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape120"
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
    "shape149": {
      "const": "usage.subscribe"
    },
    "shape147": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
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
        "subscription": {
          "$ref": "#/$defs/shape6"
        }
      },
      "required": [
        "subscription"
      ],
      "additionalProperties": false
    },
    "shape152": {
      "const": "usage.unsubscribe"
    },
    "shape150": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
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
    "shape155": {
      "const": "conversation.context.count"
    },
    "shape153": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
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
    "shape158": {
      "const": "conversation.context.snapshot"
    },
    "shape156": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape132"
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
      "const": "usage-close"
    },
    "shape159": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape160"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape162": {
      "const": "operator-prepare"
    },
    "shape164": {
      "const": "memory-write"
    },
    "shape165": {
      "const": "memory-digest"
    },
    "shape166": {
      "const": "agent-curate"
    },
    "shape167": {
      "const": "conversation-lifecycle"
    },
    "shape168": {
      "const": "conversation-resume"
    },
    "shape169": {
      "const": "job-limits"
    },
    "shape170": {
      "const": "approval-grant"
    },
    "shape171": {
      "const": "approval-revoke"
    },
    "shape172": {
      "const": "board-topup"
    },
    "shape173": {
      "const": "message-deliveries"
    },
    "shape174": {
      "const": "message-open"
    },
    "shape175": {
      "const": "message-default"
    },
    "shape176": {
      "const": "message-stop"
    },
    "shape177": {
      "const": "message-archive"
    },
    "shape178": {
      "const": "caps"
    },
    "shape163": {
      "anyOf": [
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
        }
      ]
    },
    "shape161": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape162"
        },
        "kind": {
          "$ref": "#/$defs/shape163"
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
    "shape180": {
      "const": "operator-messages"
    },
    "shape179": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape180"
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
    "shape182": {
      "const": "operator-preview"
    },
    "shape185": {
      "const": "once"
    },
    "shape186": {
      "const": "conversation"
    },
    "shape187": {
      "const": "deny"
    },
    "shape184": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape118"
        },
        {
          "$ref": "#/$defs/shape185"
        },
        {
          "$ref": "#/$defs/shape186"
        },
        {
          "$ref": "#/$defs/shape187"
        }
      ]
    },
    "shape189": {
      "const": "steps"
    },
    "shape190": {
      "const": "budget"
    },
    "shape191": {
      "const": "auto-continue"
    },
    "shape192": {
      "const": "time"
    },
    "shape193": {
      "const": "failed-checks"
    },
    "shape194": {
      "const": "auto-increase"
    },
    "shape188": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape189"
        },
        {
          "$ref": "#/$defs/shape190"
        },
        {
          "$ref": "#/$defs/shape191"
        },
        {
          "$ref": "#/$defs/shape192"
        },
        {
          "$ref": "#/$defs/shape193"
        },
        {
          "$ref": "#/$defs/shape194"
        }
      ]
    },
    "shape196": {
      "const": "active"
    },
    "shape197": {
      "const": "archived"
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
    "shape199": {
      "const": "true"
    },
    "shape200": {
      "const": "false"
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
    "shape183": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "body": {
          "$ref": "#/$defs/shape6"
        },
        "decision": {
          "$ref": "#/$defs/shape184"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "key": {
          "$ref": "#/$defs/shape188"
        },
        "lifecycle": {
          "$ref": "#/$defs/shape195"
        },
        "makeDefault": {
          "$ref": "#/$defs/shape198"
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
    "shape181": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape182"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape183"
        }
      },
      "required": [
        "action",
        "identity",
        "input"
      ],
      "additionalProperties": false
    },
    "shape202": {
      "const": "operator-apply"
    },
    "shape201": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape202"
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
    "shape204": {
      "const": "information"
    },
    "shape205": {
      "const": "navigation.capabilities"
    },
    "shape207": {
      "const": "content-types-v1"
    },
    "shape206": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape210": {
      "const": "personal"
    },
    "shape209": {
      "type": "object",
      "properties": {
        "includeGlobal": {
          "$ref": "#/$defs/shape29"
        },
        "kind": {
          "$ref": "#/$defs/shape210"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape212": {
      "const": "global_read"
    },
    "shape211": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape212"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape213": {
      "type": "object",
      "properties": {
        "includeGlobal": {
          "$ref": "#/$defs/shape29"
        },
        "kind": {
          "$ref": "#/$defs/shape118"
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
    "shape215": {
      "const": "00000000-0000-0000-0000-000000000004"
    },
    "shape216": {
      "const": "account"
    },
    "shape214": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape215"
        },
        "kind": {
          "$ref": "#/$defs/shape216"
        }
      },
      "required": [
        "account",
        "kind"
      ],
      "additionalProperties": false
    },
    "shape208": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape209"
        },
        {
          "$ref": "#/$defs/shape211"
        },
        {
          "$ref": "#/$defs/shape213"
        },
        {
          "$ref": "#/$defs/shape214"
        }
      ]
    },
    "shape203": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape205"
        },
        "payload": {
          "$ref": "#/$defs/shape206"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "documents.list"
    },
    "shape221": {
      "const": "outgoing"
    },
    "shape222": {
      "const": "backlinks"
    },
    "shape220": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape221"
        },
        {
          "$ref": "#/$defs/shape222"
        }
      ]
    },
    "shape224": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape226": {
      "const": "source"
    },
    "shape227": {
      "const": "report"
    },
    "shape225": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape226"
        },
        {
          "$ref": "#/$defs/shape227"
        }
      ]
    },
    "shape223": {
      "type": "object",
      "properties": {
        "author": {
          "$ref": "#/$defs/shape6"
        },
        "autoTag": {
          "$ref": "#/$defs/shape224"
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
          "$ref": "#/$defs/shape225"
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
          "$ref": "#/$defs/shape224"
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
    "shape228": {
      "const": "metadata-v1"
    },
    "shape219": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape220"
        },
        "filter": {
          "$ref": "#/$defs/shape223"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "projection": {
          "$ref": "#/$defs/shape228"
        },
        "relatedTo": {
          "$ref": "#/$defs/shape6"
        },
        "relation": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape217": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape218"
        },
        "payload": {
          "$ref": "#/$defs/shape219"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "documents.facets"
    },
    "shape231": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape220"
        },
        "filter": {
          "$ref": "#/$defs/shape223"
        },
        "relatedTo": {
          "$ref": "#/$defs/shape6"
        },
        "relation": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape229": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape230"
        },
        "payload": {
          "$ref": "#/$defs/shape231"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "documents.get"
    },
    "shape235": {
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
    "shape234": {
      "type": "object",
      "properties": {
        "target": {
          "$ref": "#/$defs/shape235"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "target"
      ],
      "additionalProperties": false
    },
    "shape232": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape233"
        },
        "payload": {
          "$ref": "#/$defs/shape234"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "relationships.list"
    },
    "shape238": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape220"
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
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "direction",
        "resource"
      ],
      "additionalProperties": false
    },
    "shape236": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape237"
        },
        "payload": {
          "$ref": "#/$defs/shape238"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "relationships.create"
    },
    "shape241": {
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
          "$ref": "#/$defs/shape235"
        },
        "view": {
          "$ref": "#/$defs/shape207"
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
    "shape239": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape240"
        },
        "payload": {
          "$ref": "#/$defs/shape241"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "relationships.remove"
    },
    "shape244": {
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
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "expectedVersion",
        "relationship",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape242": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape243"
        },
        "payload": {
          "$ref": "#/$defs/shape244"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "policy.get"
    },
    "shape250": {
      "const": "platform"
    },
    "shape251": {
      "forbidden": true
    },
    "shape249": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape250"
        },
        "project": {
          "$ref": "#/$defs/shape251"
        },
        "resource": {
          "$ref": "#/$defs/shape251"
        }
      },
      "required": [
        "kind"
      ],
      "additionalProperties": false
    },
    "shape252": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape118"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "resource": {
          "$ref": "#/$defs/shape251"
        }
      },
      "required": [
        "kind",
        "project"
      ],
      "additionalProperties": false
    },
    "shape254": {
      "const": "document"
    },
    "shape253": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape254"
        },
        "project": {
          "$ref": "#/$defs/shape251"
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
    "shape248": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape249"
        },
        {
          "$ref": "#/$defs/shape252"
        },
        {
          "$ref": "#/$defs/shape253"
        }
      ]
    },
    "shape247": {
      "type": "object",
      "properties": {
        "target": {
          "$ref": "#/$defs/shape248"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "target"
      ],
      "additionalProperties": false
    },
    "shape245": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape246"
        },
        "payload": {
          "$ref": "#/$defs/shape247"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "policy.set"
    },
    "shape259": {
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
    "shape258": {
      "type": "object",
      "properties": {
        "allowOverrides": {
          "$ref": "#/$defs/shape29"
        },
        "defaults": {
          "$ref": "#/$defs/shape259"
        },
        "mandatory": {
          "$ref": "#/$defs/shape259"
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
    "shape257": {
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
          "$ref": "#/$defs/shape258"
        },
        "target": {
          "$ref": "#/$defs/shape248"
        },
        "view": {
          "$ref": "#/$defs/shape207"
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
    "shape255": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape256"
        },
        "payload": {
          "$ref": "#/$defs/shape257"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "views.list"
    },
    "shape262": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape260": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape261"
        },
        "payload": {
          "$ref": "#/$defs/shape262"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "views.get"
    },
    "shape265": {
      "type": "object",
      "properties": {
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "id"
      ],
      "additionalProperties": false
    },
    "shape263": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape264"
        },
        "payload": {
          "$ref": "#/$defs/shape265"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "views.save"
    },
    "shape273": {
      "const": "scope"
    },
    "shape272": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape273"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
      },
      "required": [
        "kind",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape274": {
      "type": "object",
      "properties": {
        "kind": {
          "$ref": "#/$defs/shape254"
        },
        "target": {
          "$ref": "#/$defs/shape235"
        }
      },
      "required": [
        "kind",
        "target"
      ],
      "additionalProperties": false
    },
    "shape276": {
      "const": "traverse"
    },
    "shape275": {
      "type": "object",
      "properties": {
        "direction": {
          "$ref": "#/$defs/shape220"
        },
        "kind": {
          "$ref": "#/$defs/shape276"
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
    "shape278": {
      "const": "filter"
    },
    "shape277": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape223"
        },
        "kind": {
          "$ref": "#/$defs/shape278"
        }
      },
      "required": [
        "filter",
        "kind"
      ],
      "additionalProperties": false
    },
    "shape271": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape272"
        },
        {
          "$ref": "#/$defs/shape274"
        },
        {
          "$ref": "#/$defs/shape275"
        },
        {
          "$ref": "#/$defs/shape277"
        }
      ]
    },
    "shape270": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape271"
      }
    },
    "shape269": {
      "type": "object",
      "properties": {
        "steps": {
          "$ref": "#/$defs/shape270"
        }
      },
      "required": [
        "steps"
      ],
      "additionalProperties": false
    },
    "shape268": {
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
          "$ref": "#/$defs/shape269"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
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
    "shape266": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape267"
        },
        "payload": {
          "$ref": "#/$defs/shape268"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "views.delete"
    },
    "shape281": {
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
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "expectedVersion",
        "id",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape279": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape280"
        },
        "payload": {
          "$ref": "#/$defs/shape281"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "interchange.capabilities"
    },
    "shape284": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape282": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape283"
        },
        "payload": {
          "$ref": "#/$defs/shape284"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "interchange.export"
    },
    "shape287": {
      "type": "object",
      "properties": {
        "revisions": {
          "$ref": "#/$defs/shape224"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "revisions"
      ],
      "additionalProperties": false
    },
    "shape285": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape286"
        },
        "payload": {
          "$ref": "#/$defs/shape287"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "interchange.readable"
    },
    "shape290": {
      "type": "object",
      "properties": {
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape289"
        },
        "payload": {
          "$ref": "#/$defs/shape290"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "interchange.import"
    },
    "shape303": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape302"
      }
    },
    "shape302": {
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
          "$ref": "#/$defs/shape301"
        },
        {
          "$ref": "#/$defs/shape303"
        }
      ]
    },
    "shape301": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape302"
      }
    },
    "shape304": {
      "const": "plowshare-document-metadata/1"
    },
    "shape306": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape305"
      }
    },
    "shape307": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape30"
        },
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape305"
        }
      ]
    },
    "shape308": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape305"
      }
    },
    "shape309": {
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
    "shape310": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape309"
      }
    },
    "shape312": {
      "const": "string"
    },
    "shape313": {
      "const": "number"
    },
    "shape314": {
      "const": "boolean"
    },
    "shape315": {
      "const": "object"
    },
    "shape316": {
      "const": "array"
    },
    "shape317": {
      "const": "integer"
    },
    "shape318": {
      "const": "null"
    },
    "shape320": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape312"
        },
        {
          "$ref": "#/$defs/shape313"
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
        },
        {
          "$ref": "#/$defs/shape318"
        }
      ]
    },
    "shape319": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape320"
      }
    },
    "shape311": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape312"
        },
        {
          "$ref": "#/$defs/shape313"
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
        },
        {
          "$ref": "#/$defs/shape318"
        },
        {
          "$ref": "#/$defs/shape319"
        }
      ]
    },
    "shape305": {
      "type": "object",
      "properties": {
        "$anchor": {
          "$ref": "#/$defs/shape6"
        },
        "$defs": {
          "$ref": "#/$defs/shape306"
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
          "$ref": "#/$defs/shape307"
        },
        "allOf": {
          "$ref": "#/$defs/shape308"
        },
        "anyOf": {
          "$ref": "#/$defs/shape308"
        },
        "const": {
          "$ref": "#/$defs/shape309"
        },
        "contains": {
          "$ref": "#/$defs/shape305"
        },
        "default": {
          "$ref": "#/$defs/shape309"
        },
        "deprecated": {
          "$ref": "#/$defs/shape29"
        },
        "description": {
          "$ref": "#/$defs/shape6"
        },
        "else": {
          "$ref": "#/$defs/shape305"
        },
        "enum": {
          "$ref": "#/$defs/shape310"
        },
        "examples": {
          "$ref": "#/$defs/shape310"
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
          "$ref": "#/$defs/shape305"
        },
        "items": {
          "$ref": "#/$defs/shape305"
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
          "$ref": "#/$defs/shape305"
        },
        "oneOf": {
          "$ref": "#/$defs/shape308"
        },
        "pattern": {
          "$ref": "#/$defs/shape6"
        },
        "patternProperties": {
          "$ref": "#/$defs/shape306"
        },
        "prefixItems": {
          "$ref": "#/$defs/shape308"
        },
        "properties": {
          "$ref": "#/$defs/shape306"
        },
        "readOnly": {
          "$ref": "#/$defs/shape29"
        },
        "required": {
          "$ref": "#/$defs/shape224"
        },
        "then": {
          "$ref": "#/$defs/shape305"
        },
        "title": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape311"
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
    "shape300": {
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
          "$ref": "#/$defs/shape301"
        },
        "profile": {
          "$ref": "#/$defs/shape304"
        },
        "schema": {
          "$ref": "#/$defs/shape305"
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
    "shape299": {
      "type": "object",
      "properties": {
        "contract": {
          "$ref": "#/$defs/shape300"
        },
        "metadata": {
          "$ref": "#/$defs/shape301"
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
    "shape323": {
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
    "shape322": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape323"
      }
    },
    "shape324": {
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
    "shape321": {
      "type": "object",
      "properties": {
        "createdAt": {
          "$ref": "#/$defs/shape6"
        },
        "dependencies": {
          "$ref": "#/$defs/shape224"
        },
        "evidence": {
          "$ref": "#/$defs/shape322"
        },
        "importedFrom": {
          "$ref": "#/$defs/shape324"
        },
        "kind": {
          "$ref": "#/$defs/shape225"
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
    "shape298": {
      "type": "object",
      "properties": {
        "contentHash": {
          "$ref": "#/$defs/shape6"
        },
        "contentType": {
          "$ref": "#/$defs/shape299"
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
          "$ref": "#/$defs/shape321"
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
          "$ref": "#/$defs/shape224"
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
    "shape297": {
      "type": "object",
      "properties": {
        "bytesBase64": {
          "$ref": "#/$defs/shape6"
        },
        "descriptor": {
          "$ref": "#/$defs/shape298"
        }
      },
      "required": [
        "bytesBase64",
        "descriptor"
      ],
      "additionalProperties": false
    },
    "shape296": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape297"
      }
    },
    "shape325": {
      "const": "plowshare-information-bundle/1"
    },
    "shape326": {
      "const": "included-endpoints-only"
    },
    "shape328": {
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
    "shape327": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape328"
      }
    },
    "shape295": {
      "type": "object",
      "properties": {
        "documents": {
          "$ref": "#/$defs/shape296"
        },
        "format": {
          "$ref": "#/$defs/shape325"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "relationshipCoverage": {
          "$ref": "#/$defs/shape326"
        },
        "relationships": {
          "$ref": "#/$defs/shape327"
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
    "shape332": {
      "const": "create"
    },
    "shape331": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape251"
        },
        "mode": {
          "$ref": "#/$defs/shape332"
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
    "shape334": {
      "const": "revise"
    },
    "shape333": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "mode": {
          "$ref": "#/$defs/shape334"
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
    "shape330": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape331"
        },
        {
          "$ref": "#/$defs/shape333"
        }
      ]
    },
    "shape329": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape330"
      }
    },
    "shape336": {
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
    "shape335": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape336"
      }
    },
    "shape338": {
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
    "shape337": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape338"
      }
    },
    "shape294": {
      "type": "object",
      "properties": {
        "bundle": {
          "$ref": "#/$defs/shape295"
        },
        "destinations": {
          "$ref": "#/$defs/shape329"
        },
        "restoreRelationships": {
          "$ref": "#/$defs/shape29"
        },
        "reuse": {
          "$ref": "#/$defs/shape335"
        },
        "types": {
          "$ref": "#/$defs/shape337"
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
    "shape293": {
      "type": "object",
      "properties": {
        "plan": {
          "$ref": "#/$defs/shape294"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "plan",
        "requestId"
      ],
      "additionalProperties": false
    },
    "shape291": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape292"
        },
        "payload": {
          "$ref": "#/$defs/shape293"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "interchange.origin"
    },
    "shape341": {
      "type": "object",
      "properties": {
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape339": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape340"
        },
        "payload": {
          "$ref": "#/$defs/shape341"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "types.capabilities"
    },
    "shape344": {
      "type": "object",
      "properties": {
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape342": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape343"
        },
        "payload": {
          "$ref": "#/$defs/shape344"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "types.list"
    },
    "shape347": {
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
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "view"
      ],
      "additionalProperties": false
    },
    "shape345": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape346"
        },
        "payload": {
          "$ref": "#/$defs/shape347"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "types.get"
    },
    "shape350": {
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
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "version",
        "view"
      ],
      "additionalProperties": false
    },
    "shape348": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape349"
        },
        "payload": {
          "$ref": "#/$defs/shape350"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "types.validate"
    },
    "shape353": {
      "type": "object",
      "properties": {
        "attachedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "metadata": {
          "$ref": "#/$defs/shape301"
        },
        "publishedOnly": {
          "$ref": "#/$defs/shape29"
        },
        "version": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "metadata",
        "version",
        "view"
      ],
      "additionalProperties": false
    },
    "shape351": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape352"
        },
        "payload": {
          "$ref": "#/$defs/shape353"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "types.version.create"
    },
    "shape356": {
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
          "$ref": "#/$defs/shape301"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "schema": {
          "$ref": "#/$defs/shape305"
        },
        "slug": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "requestId",
        "schema",
        "view"
      ],
      "additionalProperties": false
    },
    "shape354": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape355"
        },
        "payload": {
          "$ref": "#/$defs/shape356"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "types.activate"
    },
    "shape359": {
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
          "$ref": "#/$defs/shape207"
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
    "shape357": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape358"
        },
        "payload": {
          "$ref": "#/$defs/shape359"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "types.deprecate"
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
          "$ref": "#/$defs/shape207"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape361"
        },
        "payload": {
          "$ref": "#/$defs/shape362"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "content.get"
    },
    "shape365": {
      "type": "object",
      "properties": {
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "revision",
        "view"
      ],
      "additionalProperties": false
    },
    "shape363": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape364"
        },
        "payload": {
          "$ref": "#/$defs/shape365"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "content.revise"
    },
    "shape371": {
      "const": "set"
    },
    "shape370": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape301"
        },
        "mode": {
          "$ref": "#/$defs/shape371"
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
    "shape373": {
      "const": "inherit"
    },
    "shape372": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape301"
        },
        "mode": {
          "$ref": "#/$defs/shape373"
        },
        "version": {
          "$ref": "#/$defs/shape251"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape375": {
      "const": "clear"
    },
    "shape374": {
      "type": "object",
      "properties": {
        "metadata": {
          "$ref": "#/$defs/shape251"
        },
        "mode": {
          "$ref": "#/$defs/shape375"
        },
        "version": {
          "$ref": "#/$defs/shape251"
        }
      },
      "required": [
        "mode"
      ],
      "additionalProperties": false
    },
    "shape369": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape370"
        },
        {
          "$ref": "#/$defs/shape372"
        },
        {
          "$ref": "#/$defs/shape374"
        }
      ]
    },
    "shape368": {
      "type": "object",
      "properties": {
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape369"
        },
        "view": {
          "$ref": "#/$defs/shape207"
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
    "shape366": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape367"
        },
        "payload": {
          "$ref": "#/$defs/shape368"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "upload"
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
        "text": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape369"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape376": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape377"
        },
        "payload": {
          "$ref": "#/$defs/shape378"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "acquire"
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
        "typing": {
          "$ref": "#/$defs/shape369"
        },
        "url": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "requestId",
        "url"
      ],
      "additionalProperties": false
    },
    "shape379": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape380"
        },
        "payload": {
          "$ref": "#/$defs/shape381"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "refresh"
    },
    "shape384": {
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
          "$ref": "#/$defs/shape369"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape382": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape383"
        },
        "payload": {
          "$ref": "#/$defs/shape384"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
          "$ref": "#/$defs/shape369"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape385": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape334"
        },
        "payload": {
          "$ref": "#/$defs/shape386"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "replace"
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
          "$ref": "#/$defs/shape369"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "requestId",
        "revision",
        "text"
      ],
      "additionalProperties": false
    },
    "shape387": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape388"
        },
        "payload": {
          "$ref": "#/$defs/shape389"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "list"
    },
    "shape392": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape223"
        },
        "kind": {
          "$ref": "#/$defs/shape225"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape390": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape391"
        },
        "payload": {
          "$ref": "#/$defs/shape392"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "facets"
    },
    "shape395": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape223"
        },
        "kind": {
          "$ref": "#/$defs/shape225"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape393": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape394"
        },
        "payload": {
          "$ref": "#/$defs/shape395"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "tags"
    },
    "shape398": {
      "type": "object",
      "properties": {
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "tags": {
          "$ref": "#/$defs/shape224"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "requestId",
        "revision",
        "tags"
      ],
      "additionalProperties": false
    },
    "shape396": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape397"
        },
        "payload": {
          "$ref": "#/$defs/shape398"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "tags.replace"
    },
    "shape401": {
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
          "$ref": "#/$defs/shape224"
        },
        "view": {
          "$ref": "#/$defs/shape207"
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
    "shape399": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape400"
        },
        "payload": {
          "$ref": "#/$defs/shape401"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "tags.groups"
    },
    "shape406": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape224"
      }
    },
    "shape405": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape406"
        }
      ]
    },
    "shape404": {
      "type": "object",
      "properties": {
        "groups": {
          "$ref": "#/$defs/shape405"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "groups",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape402": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape403"
        },
        "payload": {
          "$ref": "#/$defs/shape404"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "inventory"
    },
    "shape409": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape407": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape408"
        },
        "payload": {
          "$ref": "#/$defs/shape409"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "acquisitions"
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
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape410": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape411"
        },
        "payload": {
          "$ref": "#/$defs/shape412"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "status"
    },
    "shape415": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape413": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape414"
        },
        "payload": {
          "$ref": "#/$defs/shape415"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "await"
    },
    "shape421": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape251"
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
    "shape422": {
      "type": "object",
      "properties": {
        "acquisition": {
          "$ref": "#/$defs/shape6"
        },
        "revision": {
          "$ref": "#/$defs/shape251"
        }
      },
      "required": [
        "acquisition"
      ],
      "additionalProperties": false
    },
    "shape420": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape421"
        },
        {
          "$ref": "#/$defs/shape422"
        }
      ]
    },
    "shape419": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape420"
      }
    },
    "shape418": {
      "type": "object",
      "properties": {
        "sources": {
          "$ref": "#/$defs/shape419"
        },
        "view": {
          "$ref": "#/$defs/shape207"
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
    "shape416": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape417"
        },
        "payload": {
          "$ref": "#/$defs/shape418"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "read"
    },
    "shape425": {
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
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "revision"
      ],
      "additionalProperties": false
    },
    "shape423": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape424"
        },
        "payload": {
          "$ref": "#/$defs/shape425"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "outline"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape427"
        },
        "payload": {
          "$ref": "#/$defs/shape428"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "symbols"
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
    "shape429": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape430"
        },
        "payload": {
          "$ref": "#/$defs/shape431"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "search"
    },
    "shape434": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape223"
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
          "$ref": "#/$defs/shape207"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape433"
        },
        "payload": {
          "$ref": "#/$defs/shape434"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "rank"
    },
    "shape437": {
      "type": "object",
      "properties": {
        "filter": {
          "$ref": "#/$defs/shape223"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "query": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape436"
        },
        "payload": {
          "$ref": "#/$defs/shape437"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "ask"
    },
    "shape440": {
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
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "question",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape438": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape439"
        },
        "payload": {
          "$ref": "#/$defs/shape440"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "evidence.record"
    },
    "shape443": {
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
          "$ref": "#/$defs/shape207"
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
    "shape441": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape442"
        },
        "payload": {
          "$ref": "#/$defs/shape443"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "evidence.read"
    },
    "shape446": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "evidence"
      ],
      "additionalProperties": false
    },
    "shape444": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape445"
        },
        "payload": {
          "$ref": "#/$defs/shape446"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "record.report"
    },
    "shape453": {
      "const": "holds"
    },
    "shape454": {
      "const": "weakened"
    },
    "shape455": {
      "const": "refuted"
    },
    "shape456": {
      "const": "not_checked"
    },
    "shape452": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape453"
        },
        {
          "$ref": "#/$defs/shape454"
        },
        {
          "$ref": "#/$defs/shape455"
        },
        {
          "$ref": "#/$defs/shape456"
        }
      ]
    },
    "shape451": {
      "type": "object",
      "properties": {
        "claim": {
          "$ref": "#/$defs/shape6"
        },
        "counterEvidence": {
          "$ref": "#/$defs/shape224"
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
          "$ref": "#/$defs/shape224"
        },
        "verdict": {
          "$ref": "#/$defs/shape452"
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
    "shape450": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape451"
      }
    },
    "shape458": {
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
    "shape457": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape458"
      }
    },
    "shape449": {
      "type": "object",
      "properties": {
        "evidence": {
          "$ref": "#/$defs/shape224"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape6"
        },
        "feedback": {
          "$ref": "#/$defs/shape6"
        },
        "findings": {
          "$ref": "#/$defs/shape450"
        },
        "inputs": {
          "$ref": "#/$defs/shape224"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "objectives": {
          "$ref": "#/$defs/shape224"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "reviews": {
          "$ref": "#/$defs/shape457"
        },
        "scopeChanges": {
          "$ref": "#/$defs/shape224"
        },
        "text": {
          "$ref": "#/$defs/shape6"
        },
        "typing": {
          "$ref": "#/$defs/shape369"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "name",
        "requestId",
        "text"
      ],
      "additionalProperties": false
    },
    "shape447": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape448"
        },
        "payload": {
          "$ref": "#/$defs/shape449"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "finalise"
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
          "$ref": "#/$defs/shape207"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape460"
        },
        "payload": {
          "$ref": "#/$defs/shape461"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "link"
    },
    "shape464": {
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
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "collectionProject",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape462": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape463"
        },
        "payload": {
          "$ref": "#/$defs/shape464"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "unlink"
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
          "$ref": "#/$defs/shape207"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape466"
        },
        "payload": {
          "$ref": "#/$defs/shape467"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "share"
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
          "$ref": "#/$defs/shape207"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape469"
        },
        "payload": {
          "$ref": "#/$defs/shape470"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "unshare"
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
          "$ref": "#/$defs/shape207"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape472"
        },
        "payload": {
          "$ref": "#/$defs/shape473"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "withdraw"
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
          "$ref": "#/$defs/shape207"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape475"
        },
        "payload": {
          "$ref": "#/$defs/shape476"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "exclude"
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
          "$ref": "#/$defs/shape207"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape478"
        },
        "payload": {
          "$ref": "#/$defs/shape479"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "unexclude"
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
          "$ref": "#/$defs/shape207"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape481"
        },
        "payload": {
          "$ref": "#/$defs/shape482"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "restore"
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
          "$ref": "#/$defs/shape207"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape484"
        },
        "payload": {
          "$ref": "#/$defs/shape485"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "delete"
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
          "$ref": "#/$defs/shape207"
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
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape487"
        },
        "payload": {
          "$ref": "#/$defs/shape488"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "retry"
    },
    "shape494": {
      "const": "extract"
    },
    "shape495": {
      "const": "derive"
    },
    "shape496": {
      "const": "embed"
    },
    "shape497": {
      "const": "summarise"
    },
    "shape498": {
      "const": "summary_embed"
    },
    "shape499": {
      "const": "autoTag"
    },
    "shape500": {
      "const": "tagGroups"
    },
    "shape493": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape494"
        },
        {
          "$ref": "#/$defs/shape495"
        },
        {
          "$ref": "#/$defs/shape496"
        },
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
    "shape492": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape493"
      }
    },
    "shape491": {
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
          "$ref": "#/$defs/shape492"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape489": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape490"
        },
        "payload": {
          "$ref": "#/$defs/shape491"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "rebuild"
    },
    "shape503": {
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
          "$ref": "#/$defs/shape492"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "requestId",
        "revision",
        "stage"
      ],
      "additionalProperties": false
    },
    "shape501": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape502"
        },
        "payload": {
          "$ref": "#/$defs/shape503"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "allowance"
    },
    "shape506": {
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
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "maxModelCalls",
        "requestId",
        "revision"
      ],
      "additionalProperties": false
    },
    "shape504": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape505"
        },
        "payload": {
          "$ref": "#/$defs/shape506"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "events"
    },
    "shape509": {
      "type": "object",
      "properties": {
        "after": {
          "$ref": "#/$defs/shape28"
        },
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape507": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape508"
        },
        "payload": {
          "$ref": "#/$defs/shape509"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "migration.list"
    },
    "shape512": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape28"
        },
        "offset": {
          "$ref": "#/$defs/shape28"
        },
        "view": {
          "$ref": "#/$defs/shape207"
        }
      },
      "additionalProperties": false
    },
    "shape510": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape511"
        },
        "payload": {
          "$ref": "#/$defs/shape512"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "migration.adopt"
    },
    "shape515": {
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
          "$ref": "#/$defs/shape207"
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
    "shape513": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape514"
        },
        "payload": {
          "$ref": "#/$defs/shape515"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "migration.inspect"
    },
    "shape518": {
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
          "$ref": "#/$defs/shape207"
        }
      },
      "required": [
        "payload",
        "reason"
      ],
      "additionalProperties": false
    },
    "shape516": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape517"
        },
        "payload": {
          "$ref": "#/$defs/shape518"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
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
      "const": "migration.release"
    },
    "shape521": {
      "type": "object",
      "properties": {
        "inputs": {
          "$ref": "#/$defs/shape224"
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
          "$ref": "#/$defs/shape207"
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
    "shape519": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape204"
        },
        "operation": {
          "$ref": "#/$defs/shape520"
        },
        "payload": {
          "$ref": "#/$defs/shape521"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
        }
      },
      "required": [
        "action",
        "operation",
        "payload",
        "scope"
      ],
      "additionalProperties": false
    },
    "shape524": {
      "const": "bootstrap"
    },
    "shape525": {
      "const": "demo"
    },
    "shape526": {
      "const": "disconnect"
    },
    "shape527": {
      "const": "approvals-refresh"
    },
    "shape523": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape383"
        },
        {
          "$ref": "#/$defs/shape524"
        },
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
    "shape522": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape523"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape529": {
      "const": "project-access"
    },
    "shape531": {
      "const": "project.access"
    },
    "shape532": {
      "const": "project.member.add"
    },
    "shape533": {
      "const": "project.member.remove"
    },
    "shape534": {
      "const": "project.member.role"
    },
    "shape530": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape531"
        },
        {
          "$ref": "#/$defs/shape532"
        },
        {
          "$ref": "#/$defs/shape533"
        },
        {
          "$ref": "#/$defs/shape534"
        }
      ]
    },
    "shape536": {
      "const": "VIEWER"
    },
    "shape537": {
      "const": "CONTRIBUTOR"
    },
    "shape538": {
      "const": "MANAGER"
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
        }
      ]
    },
    "shape528": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape529"
        },
        "handle": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape530"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape535"
        }
      },
      "required": [
        "action",
        "operation",
        "project"
      ],
      "additionalProperties": false
    },
    "shape540": {
      "const": "server-admin"
    },
    "shape541": {
      "const": "admin.pricing.list"
    },
    "shape542": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape251"
      }
    },
    "shape539": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
        },
        "operation": {
          "$ref": "#/$defs/shape541"
        },
        "payload": {
          "$ref": "#/$defs/shape542"
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
      "const": "admin.pricing.set"
    },
    "shape547": {
      "const": "TOKEN"
    },
    "shape548": {
      "const": "INCLUDED"
    },
    "shape549": {
      "const": "ZERO_RATE"
    },
    "shape550": {
      "const": "UNPRICED"
    },
    "shape546": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape547"
        },
        {
          "$ref": "#/$defs/shape548"
        },
        {
          "$ref": "#/$defs/shape549"
        },
        {
          "$ref": "#/$defs/shape550"
        }
      ]
    },
    "shape551": {
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
    "shape553": {
      "type": "object",
      "properties": {
        "fromInputTokens": {
          "$ref": "#/$defs/shape28"
        },
        "rates": {
          "$ref": "#/$defs/shape554"
        }
      },
      "required": [
        "fromInputTokens",
        "rates"
      ],
      "additionalProperties": false
    },
    "shape552": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape553"
      }
    },
    "shape545": {
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
          "$ref": "#/$defs/shape546"
        },
        "model": {
          "$ref": "#/$defs/shape6"
        },
        "rates": {
          "$ref": "#/$defs/shape551"
        },
        "requestFee": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape6"
        },
        "tiers": {
          "$ref": "#/$defs/shape552"
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
    "shape543": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
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
    "shape556": {
      "const": "admin.accounts"
    },
    "shape555": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
        },
        "operation": {
          "$ref": "#/$defs/shape556"
        },
        "payload": {
          "$ref": "#/$defs/shape542"
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
      "const": "admin.account.create"
    },
    "shape559": {
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
    "shape557": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
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
      "const": "admin.account.update"
    },
    "shape562": {
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
    "shape560": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
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
      "const": "admin.account.reset"
    },
    "shape565": {
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
    "shape563": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
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
      "const": "admin.sessions"
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
          "$ref": "#/$defs/shape540"
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
      "const": "admin.session.revoke"
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
          "$ref": "#/$defs/shape540"
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
      "const": "admin.audit"
    },
    "shape574": {
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
    "shape572": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
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
      "const": "admin.service.accounts"
    },
    "shape575": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
        },
        "operation": {
          "$ref": "#/$defs/shape576"
        },
        "payload": {
          "$ref": "#/$defs/shape542"
        }
      },
      "required": [
        "action",
        "operation",
        "payload"
      ],
      "additionalProperties": false
    },
    "shape578": {
      "const": "admin.service.account.create"
    },
    "shape579": {
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
    "shape577": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
        },
        "operation": {
          "$ref": "#/$defs/shape578"
        },
        "payload": {
          "$ref": "#/$defs/shape579"
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
      "const": "admin.service.account.update"
    },
    "shape582": {
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
    "shape580": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
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
      "const": "admin.service.tokens"
    },
    "shape585": {
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
    "shape583": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
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
      "const": "admin.service.token.create"
    },
    "shape590": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "role": {
          "$ref": "#/$defs/shape535"
        }
      },
      "required": [
        "project",
        "role"
      ],
      "additionalProperties": false
    },
    "shape589": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape590"
      }
    },
    "shape588": {
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
          "$ref": "#/$defs/shape589"
        }
      },
      "required": [
        "handle",
        "name",
        "scopes"
      ],
      "additionalProperties": false
    },
    "shape586": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
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
    "shape592": {
      "const": "admin.service.token.rotate"
    },
    "shape593": {
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
    "shape591": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
        },
        "operation": {
          "$ref": "#/$defs/shape592"
        },
        "payload": {
          "$ref": "#/$defs/shape593"
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
      "const": "admin.service.token.revoke"
    },
    "shape596": {
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
    "shape594": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape540"
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
      "const": "server-project-create"
    },
    "shape600": {
      "const": "MANAGED"
    },
    "shape601": {
      "const": "DISJOINT"
    },
    "shape599": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape600"
        },
        {
          "$ref": "#/$defs/shape601"
        }
      ]
    },
    "shape602": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape5"
      }
    },
    "shape603": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape597": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape598"
        },
        "applicationRoot": {
          "$ref": "#/$defs/shape5"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "type": {
          "$ref": "#/$defs/shape599"
        },
        "workspace": {
          "$ref": "#/$defs/shape6"
        },
        "writableAreas": {
          "$ref": "#/$defs/shape602"
        },
        "writePaths": {
          "$ref": "#/$defs/shape603"
        }
      },
      "required": [
        "action",
        "name"
      ],
      "additionalProperties": false
    },
    "shape605": {
      "const": "server-setup"
    },
    "shape604": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape605"
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
    "shape607": {
      "const": "connect"
    },
    "shape606": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape607"
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
    "shape609": {
      "const": "connection-select"
    },
    "shape608": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape609"
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
    "shape611": {
      "const": "connection-preferences"
    },
    "shape613": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape6"
      }
    },
    "shape614": {
      "type": "object",
      "properties": {},
      "additionalProperties": {
        "$ref": "#/$defs/shape29"
      }
    },
    "shape612": {
      "type": "object",
      "properties": {
        "chosenAgents": {
          "$ref": "#/$defs/shape613"
        },
        "drafts": {
          "$ref": "#/$defs/shape613"
        },
        "personalBotExpansion": {
          "$ref": "#/$defs/shape614"
        },
        "projectExpansion": {
          "$ref": "#/$defs/shape614"
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
    "shape610": {
      "type": "object",
      "properties": {
        "account": {
          "$ref": "#/$defs/shape6"
        },
        "action": {
          "$ref": "#/$defs/shape611"
        },
        "preference": {
          "$ref": "#/$defs/shape612"
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
    "shape616": {
      "const": "connection-rename"
    },
    "shape615": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape616"
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
    "shape618": {
      "const": "connection-remove"
    },
    "shape617": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape618"
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
    "shape620": {
      "const": "personal-section"
    },
    "shape622": {
      "const": "In"
    },
    "shape623": {
      "const": "Out"
    },
    "shape624": {
      "const": "Resources"
    },
    "shape625": {
      "const": "Archive"
    },
    "shape626": {
      "const": "Planning"
    },
    "shape627": {
      "const": "Bots"
    },
    "shape621": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape622"
        },
        {
          "$ref": "#/$defs/shape623"
        },
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
    "shape619": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape620"
        },
        "path": {
          "$ref": "#/$defs/shape6"
        },
        "section": {
          "$ref": "#/$defs/shape621"
        }
      },
      "required": [
        "action",
        "section"
      ],
      "additionalProperties": false
    },
    "shape629": {
      "const": "personal-bots"
    },
    "shape628": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape629"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape631": {
      "const": "personal-recreate"
    },
    "shape630": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape631"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape633": {
      "const": "files-choose"
    },
    "shape632": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape633"
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
    "shape635": {
      "const": "files-withdraw"
    },
    "shape634": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape635"
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
    "shape637": {
      "const": "sync-refresh"
    },
    "shape636": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape637"
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
    "shape639": {
      "const": "sync-inspect"
    },
    "shape638": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape639"
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
    "shape641": {
      "const": "sync-change"
    },
    "shape643": {
      "const": "on"
    },
    "shape644": {
      "const": "off"
    },
    "shape645": {
      "const": "now"
    },
    "shape642": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape643"
        },
        {
          "$ref": "#/$defs/shape644"
        },
        {
          "$ref": "#/$defs/shape645"
        }
      ]
    },
    "shape640": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape641"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape642"
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
    "shape647": {
      "const": "sync-resolve"
    },
    "shape649": {
      "const": "theirs"
    },
    "shape650": {
      "const": "done"
    },
    "shape648": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape69"
        },
        {
          "$ref": "#/$defs/shape649"
        },
        {
          "$ref": "#/$defs/shape650"
        }
      ]
    },
    "shape646": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape647"
        },
        "how": {
          "$ref": "#/$defs/shape648"
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
    "shape653": {
      "const": "project-open"
    },
    "shape654": {
      "const": "project-remove"
    },
    "shape652": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape653"
        },
        {
          "$ref": "#/$defs/shape654"
        }
      ]
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
    "shape656": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape273"
        },
        {
          "$ref": "#/$defs/shape332"
        }
      ]
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
        "action"
      ],
      "additionalProperties": false
    },
    "shape658": {
      "const": "history"
    },
    "shape657": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape658"
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
    "shape660": {
      "const": "select"
    },
    "shape659": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape660"
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
    "shape662": {
      "const": "trajectory"
    },
    "shape661": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape662"
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
    "shape664": {
      "const": "board-swarm-types"
    },
    "shape663": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape664"
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
    "shape666": {
      "const": "board-post-topics"
    },
    "shape665": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape666"
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
    "shape668": {
      "const": "board-create"
    },
    "shape667": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape668"
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
    "shape669": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape172"
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
    "shape671": {
      "const": "board-retry"
    },
    "shape670": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape671"
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
    "shape673": {
      "const": "board-post"
    },
    "shape672": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape673"
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
    "shape675": {
      "const": "workspace-chat"
    },
    "shape674": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape675"
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
    "shape677": {
      "const": "workspace-administration"
    },
    "shape676": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape677"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape679": {
      "const": "server-configuration"
    },
    "shape678": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape679"
        },
        "operation": {
          "$ref": "#/$defs/shape391"
        }
      },
      "required": [
        "action",
        "operation"
      ],
      "additionalProperties": false
    },
    "shape680": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape679"
        },
        "key": {
          "$ref": "#/$defs/shape6"
        },
        "operation": {
          "$ref": "#/$defs/shape371"
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
    "shape682": {
      "const": "workspace-layout"
    },
    "shape681": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape682"
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
    "shape684": {
      "const": "workspace-refresh"
    },
    "shape683": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape684"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape686": {
      "const": "library"
    },
    "shape688": {
      "const": "sources"
    },
    "shape689": {
      "const": "documents"
    },
    "shape690": {
      "const": "memories"
    },
    "shape691": {
      "const": "manual"
    },
    "shape687": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape433"
        },
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
        "action": {
          "$ref": "#/$defs/shape686"
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
          "$ref": "#/$defs/shape687"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape693": {
      "const": "library-view"
    },
    "shape692": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape693"
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
          "$ref": "#/$defs/shape687"
        }
      },
      "required": [
        "action",
        "project",
        "view"
      ],
      "additionalProperties": false
    },
    "shape696": {
      "const": "library-refresh"
    },
    "shape697": {
      "const": "library-citations"
    },
    "shape695": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape696"
        },
        {
          "$ref": "#/$defs/shape697"
        }
      ]
    },
    "shape694": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape695"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape699": {
      "const": "library-documents"
    },
    "shape698": {
      "type": "object",
      "properties": {
        "action": {
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
        "query"
      ],
      "additionalProperties": false
    },
    "shape702": {
      "const": "library-document"
    },
    "shape703": {
      "const": "library-memory"
    },
    "shape704": {
      "const": "library-chunk"
    },
    "shape705": {
      "const": "library-conversation"
    },
    "shape701": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape702"
        },
        {
          "$ref": "#/$defs/shape703"
        },
        {
          "$ref": "#/$defs/shape704"
        },
        {
          "$ref": "#/$defs/shape705"
        }
      ]
    },
    "shape700": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape701"
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
    "shape707": {
      "const": "library-source-text"
    },
    "shape706": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape707"
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
    "shape709": {
      "const": "library-stance"
    },
    "shape708": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape709"
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
    "shape711": {
      "const": "library-search"
    },
    "shape713": {
      "const": "retrieve"
    },
    "shape714": {
      "const": "recall"
    },
    "shape715": {
      "const": "navigate"
    },
    "shape712": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape436"
        },
        {
          "$ref": "#/$defs/shape186"
        },
        {
          "$ref": "#/$defs/shape689"
        },
        {
          "$ref": "#/$defs/shape713"
        },
        {
          "$ref": "#/$defs/shape714"
        },
        {
          "$ref": "#/$defs/shape715"
        }
      ]
    },
    "shape717": {
      "const": "lexical"
    },
    "shape718": {
      "const": "semantic"
    },
    "shape719": {
      "const": "hybrid"
    },
    "shape716": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape717"
        },
        {
          "$ref": "#/$defs/shape718"
        },
        {
          "$ref": "#/$defs/shape719"
        }
      ]
    },
    "shape710": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape711"
        },
        "kind": {
          "$ref": "#/$defs/shape712"
        },
        "mode": {
          "$ref": "#/$defs/shape716"
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
    "shape721": {
      "const": "library-maintain"
    },
    "shape723": {
      "const": "invalidate"
    },
    "shape724": {
      "const": "resolve"
    },
    "shape725": {
      "const": "reembed"
    },
    "shape726": {
      "const": "reconsider"
    },
    "shape722": {
      "anyOf": [
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
    "shape720": {
      "type": "object",
      "properties": {
        "accept": {
          "$ref": "#/$defs/shape29"
        },
        "action": {
          "$ref": "#/$defs/shape721"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape722"
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
    "shape728": {
      "const": "builder-outputs"
    },
    "shape727": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape728"
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
    "shape730": {
      "const": "builder-trajectory"
    },
    "shape729": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape730"
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
    "shape732": {
      "const": "builder-prepare"
    },
    "shape731": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape732"
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
    "shape734": {
      "const": "builder-start"
    },
    "shape733": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape734"
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
    "shape736": {
      "const": "activity"
    },
    "shape738": {
      "const": "inbox"
    },
    "shape739": {
      "const": "runs"
    },
    "shape740": {
      "const": "definitions"
    },
    "shape741": {
      "const": "schedules"
    },
    "shape742": {
      "const": "builder"
    },
    "shape737": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape738"
        },
        {
          "$ref": "#/$defs/shape739"
        },
        {
          "$ref": "#/$defs/shape740"
        },
        {
          "$ref": "#/$defs/shape741"
        },
        {
          "$ref": "#/$defs/shape742"
        }
      ]
    },
    "shape735": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape736"
        },
        "view": {
          "$ref": "#/$defs/shape737"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape744": {
      "const": "activity-view"
    },
    "shape743": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape744"
        },
        "view": {
          "$ref": "#/$defs/shape737"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape746": {
      "const": "activity-refresh"
    },
    "shape745": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape746"
        },
        "view": {
          "$ref": "#/$defs/shape737"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape748": {
      "const": "question-refresh"
    },
    "shape747": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape748"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape750": {
      "const": "inbox-read"
    },
    "shape749": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape750"
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
    "shape752": {
      "const": "inbox-more"
    },
    "shape751": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape752"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape754": {
      "const": "run-detail"
    },
    "shape755": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape739"
        },
        {
          "$ref": "#/$defs/shape742"
        }
      ]
    },
    "shape753": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape754"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape755"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape757": {
      "const": "schedule-refresh"
    },
    "shape759": {
      "const": "next"
    },
    "shape760": {
      "const": "previous"
    },
    "shape761": {
      "const": "first"
    },
    "shape758": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape759"
        },
        {
          "$ref": "#/$defs/shape760"
        },
        {
          "$ref": "#/$defs/shape761"
        }
      ]
    },
    "shape763": {
      "const": "files"
    },
    "shape762": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape741"
        },
        {
          "$ref": "#/$defs/shape763"
        }
      ]
    },
    "shape756": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape757"
        },
        "direction": {
          "$ref": "#/$defs/shape758"
        },
        "section": {
          "$ref": "#/$defs/shape762"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape765": {
      "const": "schedule-preview"
    },
    "shape764": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape765"
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
    "shape767": {
      "const": "schedule-save"
    },
    "shape771": {
      "const": "skill"
    },
    "shape772": {
      "const": "orchestration"
    },
    "shape770": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape116"
        },
        {
          "$ref": "#/$defs/shape771"
        },
        {
          "$ref": "#/$defs/shape772"
        }
      ]
    },
    "shape774": {
      "const": "INHERITED"
    },
    "shape775": {
      "const": "SUMMARISED"
    },
    "shape776": {
      "const": "NEW"
    },
    "shape777": {
      "const": "DIRECT"
    },
    "shape773": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape774"
        },
        {
          "$ref": "#/$defs/shape775"
        },
        {
          "$ref": "#/$defs/shape776"
        },
        {
          "$ref": "#/$defs/shape777"
        }
      ]
    },
    "shape769": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape6"
        },
        "input": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape770"
        },
        "mode": {
          "$ref": "#/$defs/shape773"
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
    "shape779": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape28"
        }
      ]
    },
    "shape778": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape779"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape779"
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
    "shape782": {
      "const": "mailbox"
    },
    "shape783": {
      "const": "message"
    },
    "shape781": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape186"
        },
        {
          "$ref": "#/$defs/shape782"
        },
        {
          "$ref": "#/$defs/shape783"
        }
      ]
    },
    "shape780": {
      "type": "object",
      "properties": {
        "conversation": {
          "$ref": "#/$defs/shape7"
        },
        "kind": {
          "$ref": "#/$defs/shape781"
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
    "shape784": {
      "const": 1
    },
    "shape768": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape769"
        },
        "cron": {
          "$ref": "#/$defs/shape6"
        },
        "limits": {
          "$ref": "#/$defs/shape778"
        },
        "paused": {
          "$ref": "#/$defs/shape29"
        },
        "target": {
          "$ref": "#/$defs/shape780"
        },
        "version": {
          "$ref": "#/$defs/shape784"
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
    "shape786": {
      "const": "workspace"
    },
    "shape785": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape70"
        },
        {
          "$ref": "#/$defs/shape786"
        }
      ]
    },
    "shape766": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape767"
        },
        "definition": {
          "$ref": "#/$defs/shape768"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape785"
        }
      },
      "required": [
        "action",
        "identity"
      ],
      "additionalProperties": false
    },
    "shape788": {
      "const": "schedule-file-save"
    },
    "shape787": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape788"
        },
        "definition": {
          "$ref": "#/$defs/shape768"
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
          "$ref": "#/$defs/shape785"
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
    "shape790": {
      "const": "schedule-sync"
    },
    "shape789": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape790"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "source": {
          "$ref": "#/$defs/shape785"
        }
      },
      "required": [
        "action",
        "source"
      ],
      "additionalProperties": false
    },
    "shape792": {
      "const": "schedule-change"
    },
    "shape794": {
      "const": "schedule"
    },
    "shape795": {
      "const": "trigger"
    },
    "shape793": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape794"
        },
        {
          "$ref": "#/$defs/shape795"
        }
      ]
    },
    "shape791": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape792"
        },
        "identity": {
          "$ref": "#/$defs/shape6"
        },
        "kind": {
          "$ref": "#/$defs/shape793"
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
    "shape797": {
      "const": "schedule-fire"
    },
    "shape796": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape797"
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
    "shape799": {
      "const": "run-definitions"
    },
    "shape798": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape799"
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
    "shape801": {
      "const": "run-record"
    },
    "shape800": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape801"
        },
        "before": {
          "$ref": "#/$defs/shape28"
        },
        "id": {
          "$ref": "#/$defs/shape6"
        },
        "kinds": {
          "$ref": "#/$defs/shape603"
        }
      },
      "required": [
        "action",
        "id"
      ],
      "additionalProperties": false
    },
    "shape803": {
      "const": "run-answer"
    },
    "shape805": {
      "type": "object",
      "properties": {
        "chosen": {
          "$ref": "#/$defs/shape224"
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
    "shape804": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape805"
      }
    },
    "shape802": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape803"
        },
        "answer": {
          "$ref": "#/$defs/shape6"
        },
        "choices": {
          "$ref": "#/$defs/shape804"
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
    "shape807": {
      "const": "run-cancel"
    },
    "shape806": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape807"
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
    "shape809": {
      "const": "run-resume"
    },
    "shape808": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape809"
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
    "shape811": {
      "const": "run-trajectory"
    },
    "shape813": {
      "const": "conductor"
    },
    "shape814": {
      "const": "caller"
    },
    "shape812": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape813"
        },
        {
          "$ref": "#/$defs/shape814"
        }
      ]
    },
    "shape810": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape811"
        },
        "actor": {
          "$ref": "#/$defs/shape812"
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
    "shape816": {
      "const": "run-stage-trajectory"
    },
    "shape815": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape816"
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
    "shape818": {
      "const": "delegate-trajectory"
    },
    "shape817": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape818"
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
    "shape821": {
      "const": "board-inspection"
    },
    "shape822": {
      "const": "board-view"
    },
    "shape820": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape821"
        },
        {
          "$ref": "#/$defs/shape822"
        }
      ]
    },
    "shape824": {
      "const": "board"
    },
    "shape825": {
      "const": "swarm"
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
    "shape819": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape820"
        },
        "project": {
          "$ref": "#/$defs/shape6"
        },
        "view": {
          "$ref": "#/$defs/shape823"
        }
      },
      "required": [
        "action",
        "view"
      ],
      "additionalProperties": false
    },
    "shape828": {
      "const": "board-refresh"
    },
    "shape829": {
      "const": "board-more"
    },
    "shape827": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape828"
        },
        {
          "$ref": "#/$defs/shape829"
        }
      ]
    },
    "shape826": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape827"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape831": {
      "const": "board-topic"
    },
    "shape830": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape831"
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
    "shape833": {
      "const": "board-trajectory"
    },
    "shape832": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape833"
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
    "shape835": {
      "const": "context"
    },
    "shape834": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape835"
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
    "shape837": {
      "const": "open-link"
    },
    "shape836": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape837"
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
    "shape839": {
      "const": "copy-text"
    },
    "shape838": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape839"
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
    "shape841": {
      "const": "workflow-start"
    },
    "shape840": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape841"
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
    "shape842": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape119"
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
    "shape844": {
      "const": "cancel"
    },
    "shape843": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape844"
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
    "shape846": {
      "const": "answer"
    },
    "shape847": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape185"
        },
        {
          "$ref": "#/$defs/shape187"
        }
      ]
    },
    "shape845": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape846"
        },
        "decision": {
          "$ref": "#/$defs/shape847"
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
    "shape849": {
      "const": "native-mcp"
    },
    "shape848": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape849"
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
    "shape851": {
      "const": "provider-management"
    },
    "shape852": {
      "const": "provider.connection.list"
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
    "shape850": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
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
      "const": "provider.connection.status"
    },
    "shape856": {
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
    "shape854": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
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
      "const": "provider.connection.receipt"
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
          "$ref": "#/$defs/shape851"
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
      "const": "provider.connection.save"
    },
    "shape865": {
      "const": "Authorization"
    },
    "shape866": {
      "const": "X-API-Key"
    },
    "shape867": {
      "const": "Api-Key"
    },
    "shape864": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape865"
        },
        {
          "$ref": "#/$defs/shape866"
        },
        {
          "$ref": "#/$defs/shape867"
        }
      ]
    },
    "shape869": {
      "const": "API_KEY"
    },
    "shape870": {
      "const": "OAUTH_PKCE"
    },
    "shape871": {
      "const": "CLIENT_CREDENTIALS"
    },
    "shape868": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape869"
        },
        {
          "$ref": "#/$defs/shape870"
        },
        {
          "$ref": "#/$defs/shape871"
        }
      ]
    },
    "shape873": {
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
          "$ref": "#/$defs/shape224"
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
    "shape872": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape8"
        },
        {
          "$ref": "#/$defs/shape873"
        }
      ]
    },
    "shape863": {
      "type": "object",
      "properties": {
        "apiKeyHeader": {
          "$ref": "#/$defs/shape864"
        },
        "authentication": {
          "$ref": "#/$defs/shape868"
        },
        "endpoint": {
          "$ref": "#/$defs/shape6"
        },
        "name": {
          "$ref": "#/$defs/shape6"
        },
        "oauth": {
          "$ref": "#/$defs/shape872"
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
    "shape862": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape863"
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
    "shape860": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
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
    "shape875": {
      "const": "provider.connection.change"
    },
    "shape878": {
      "const": "ENABLE"
    },
    "shape879": {
      "const": "DISABLE"
    },
    "shape880": {
      "const": "DELETE"
    },
    "shape877": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape878"
        },
        {
          "$ref": "#/$defs/shape879"
        },
        {
          "$ref": "#/$defs/shape880"
        }
      ]
    },
    "shape876": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape877"
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
    "shape874": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
        },
        "operation": {
          "$ref": "#/$defs/shape875"
        },
        "payload": {
          "$ref": "#/$defs/shape876"
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
      "const": "provider.credential.status"
    },
    "shape883": {
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
    "shape881": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
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
      "const": "provider.credential.put"
    },
    "shape886": {
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
    "shape884": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
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
      "const": "provider.credential.revoke"
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
    "shape887": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
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
      "const": "provider.credential.rekey"
    },
    "shape890": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
        },
        "operation": {
          "$ref": "#/$defs/shape891"
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
    "shape893": {
      "const": "provider.credential.authorize"
    },
    "shape892": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
        },
        "operation": {
          "$ref": "#/$defs/shape893"
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
    "shape895": {
      "const": "provider.credential.refresh"
    },
    "shape894": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
        },
        "operation": {
          "$ref": "#/$defs/shape895"
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
    "shape897": {
      "const": "tool.scope.host.list"
    },
    "shape898": {
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
    "shape896": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
        },
        "operation": {
          "$ref": "#/$defs/shape897"
        },
        "payload": {
          "$ref": "#/$defs/shape898"
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
      "const": "tool.scope.host.status"
    },
    "shape901": {
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
    "shape899": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
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
      "const": "tool.scope.host.receipt"
    },
    "shape904": {
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
    "shape902": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
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
      "const": "tool.scope.host.save"
    },
    "shape908": {
      "type": "object",
      "properties": {
        "agents": {
          "$ref": "#/$defs/shape224"
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
          "$ref": "#/$defs/shape224"
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
    "shape907": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "definition": {
          "$ref": "#/$defs/shape908"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape779"
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
    "shape905": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
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
    "shape910": {
      "const": "tool.scope.host.change"
    },
    "shape911": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape877"
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
    "shape909": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
        },
        "operation": {
          "$ref": "#/$defs/shape910"
        },
        "payload": {
          "$ref": "#/$defs/shape911"
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
      "const": "tool.scope.list"
    },
    "shape914": {
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
    "shape912": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape851"
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
      "const": "provider-management-pending"
    },
    "shape915": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape916"
        }
      },
      "required": [
        "action"
      ],
      "additionalProperties": false
    },
    "shape918": {
      "const": "provider-management-recover"
    },
    "shape921": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape861"
        },
        {
          "$ref": "#/$defs/shape875"
        }
      ]
    },
    "shape920": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedRevision": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape921"
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
    "shape923": {
      "anyOf": [
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
          "$ref": "#/$defs/shape893"
        },
        {
          "$ref": "#/$defs/shape895"
        }
      ]
    },
    "shape922": {
      "type": "object",
      "properties": {
        "connection": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape7"
        },
        "operation": {
          "$ref": "#/$defs/shape923"
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
    "shape925": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape906"
        },
        {
          "$ref": "#/$defs/shape910"
        }
      ]
    },
    "shape924": {
      "type": "object",
      "properties": {
        "binding": {
          "$ref": "#/$defs/shape6"
        },
        "expectedGeneration": {
          "$ref": "#/$defs/shape779"
        },
        "operation": {
          "$ref": "#/$defs/shape925"
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
    "shape919": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape920"
        },
        {
          "$ref": "#/$defs/shape922"
        },
        {
          "$ref": "#/$defs/shape924"
        }
      ]
    },
    "shape917": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape918"
        },
        "intent": {
          "$ref": "#/$defs/shape919"
        }
      },
      "required": [
        "action",
        "intent"
      ],
      "additionalProperties": false
    },
    "shape927": {
      "const": "information-import-recovery-save"
    },
    "shape929": {
      "const": "plowshare-information-import-recovery/1"
    },
    "shape928": {
      "type": "object",
      "properties": {
        "context": {
          "$ref": "#/$defs/shape6"
        },
        "format": {
          "$ref": "#/$defs/shape929"
        },
        "plan": {
          "$ref": "#/$defs/shape294"
        },
        "requestId": {
          "$ref": "#/$defs/shape6"
        },
        "scope": {
          "$ref": "#/$defs/shape208"
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
    "shape926": {
      "type": "object",
      "properties": {
        "action": {
          "$ref": "#/$defs/shape927"
        },
        "recovery": {
          "$ref": "#/$defs/shape928"
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
          "$ref": "#/$defs/shape80"
        },
        {
          "$ref": "#/$defs/shape82"
        },
        {
          "$ref": "#/$defs/shape84"
        },
        {
          "$ref": "#/$defs/shape86"
        },
        {
          "$ref": "#/$defs/shape92"
        },
        {
          "$ref": "#/$defs/shape97"
        },
        {
          "$ref": "#/$defs/shape106"
        },
        {
          "$ref": "#/$defs/shape108"
        },
        {
          "$ref": "#/$defs/shape131"
        },
        {
          "$ref": "#/$defs/shape134"
        },
        {
          "$ref": "#/$defs/shape136"
        },
        {
          "$ref": "#/$defs/shape138"
        },
        {
          "$ref": "#/$defs/shape140"
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
          "$ref": "#/$defs/shape161"
        },
        {
          "$ref": "#/$defs/shape179"
        },
        {
          "$ref": "#/$defs/shape181"
        },
        {
          "$ref": "#/$defs/shape201"
        },
        {
          "$ref": "#/$defs/shape203"
        },
        {
          "$ref": "#/$defs/shape217"
        },
        {
          "$ref": "#/$defs/shape229"
        },
        {
          "$ref": "#/$defs/shape232"
        },
        {
          "$ref": "#/$defs/shape236"
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
          "$ref": "#/$defs/shape255"
        },
        {
          "$ref": "#/$defs/shape260"
        },
        {
          "$ref": "#/$defs/shape263"
        },
        {
          "$ref": "#/$defs/shape266"
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
          "$ref": "#/$defs/shape360"
        },
        {
          "$ref": "#/$defs/shape363"
        },
        {
          "$ref": "#/$defs/shape366"
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
          "$ref": "#/$defs/shape387"
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
          "$ref": "#/$defs/shape528"
        },
        {
          "$ref": "#/$defs/shape539"
        },
        {
          "$ref": "#/$defs/shape543"
        },
        {
          "$ref": "#/$defs/shape555"
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
          "$ref": "#/$defs/shape577"
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
          "$ref": "#/$defs/shape591"
        },
        {
          "$ref": "#/$defs/shape594"
        },
        {
          "$ref": "#/$defs/shape597"
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
          "$ref": "#/$defs/shape610"
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
          "$ref": "#/$defs/shape646"
        },
        {
          "$ref": "#/$defs/shape651"
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
          "$ref": "#/$defs/shape670"
        },
        {
          "$ref": "#/$defs/shape672"
        },
        {
          "$ref": "#/$defs/shape674"
        },
        {
          "$ref": "#/$defs/shape676"
        },
        {
          "$ref": "#/$defs/shape678"
        },
        {
          "$ref": "#/$defs/shape680"
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
          "$ref": "#/$defs/shape692"
        },
        {
          "$ref": "#/$defs/shape694"
        },
        {
          "$ref": "#/$defs/shape698"
        },
        {
          "$ref": "#/$defs/shape700"
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
          "$ref": "#/$defs/shape720"
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
          "$ref": "#/$defs/shape743"
        },
        {
          "$ref": "#/$defs/shape745"
        },
        {
          "$ref": "#/$defs/shape747"
        },
        {
          "$ref": "#/$defs/shape749"
        },
        {
          "$ref": "#/$defs/shape751"
        },
        {
          "$ref": "#/$defs/shape753"
        },
        {
          "$ref": "#/$defs/shape756"
        },
        {
          "$ref": "#/$defs/shape764"
        },
        {
          "$ref": "#/$defs/shape766"
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
          "$ref": "#/$defs/shape796"
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
          "$ref": "#/$defs/shape806"
        },
        {
          "$ref": "#/$defs/shape808"
        },
        {
          "$ref": "#/$defs/shape810"
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
          "$ref": "#/$defs/shape826"
        },
        {
          "$ref": "#/$defs/shape830"
        },
        {
          "$ref": "#/$defs/shape832"
        },
        {
          "$ref": "#/$defs/shape834"
        },
        {
          "$ref": "#/$defs/shape836"
        },
        {
          "$ref": "#/$defs/shape838"
        },
        {
          "$ref": "#/$defs/shape840"
        },
        {
          "$ref": "#/$defs/shape842"
        },
        {
          "$ref": "#/$defs/shape843"
        },
        {
          "$ref": "#/$defs/shape845"
        },
        {
          "$ref": "#/$defs/shape848"
        },
        {
          "$ref": "#/$defs/shape850"
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
          "$ref": "#/$defs/shape874"
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
          "$ref": "#/$defs/shape890"
        },
        {
          "$ref": "#/$defs/shape892"
        },
        {
          "$ref": "#/$defs/shape894"
        },
        {
          "$ref": "#/$defs/shape896"
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
          "$ref": "#/$defs/shape909"
        },
        {
          "$ref": "#/$defs/shape912"
        },
        {
          "$ref": "#/$defs/shape915"
        },
        {
          "$ref": "#/$defs/shape917"
        },
        {
          "$ref": "#/$defs/shape926"
        }
      ]
    }
  }
}
