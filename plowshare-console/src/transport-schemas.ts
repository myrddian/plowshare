// Generated from ConsoleReplies; run the SDK schema generator with --console.
import type {Schema} from '../../sdk/typescript/src/operations/schema.ts';
export const CONSOLE_SCHEMAS:{results:Record<string,Schema>;$defs:Record<string,Schema>}= {
  "results": {
    "job.status": {
      "$ref": "#/$defs/shape0"
    },
    "job.list": {
      "$ref": "#/$defs/shape15"
    },
    "job.cancel": {
      "$ref": "#/$defs/shape0"
    },
    "job.limits": {
      "$ref": "#/$defs/shape0"
    },
    "agent.run": {
      "$ref": "#/$defs/shape16"
    },
    "conversation.resume": {
      "$ref": "#/$defs/shape16"
    },
    "project.list": {
      "$ref": "#/$defs/shape17"
    },
    "project.workspace": {
      "$ref": "#/$defs/shape18"
    },
    "conversation.context": {
      "$ref": "#/$defs/shape24"
    },
    "memory.recall": {
      "$ref": "#/$defs/shape42"
    },
    "memory.index": {
      "$ref": "#/$defs/shape53"
    },
    "document.search": {
      "$ref": "#/$defs/shape55"
    }
  },
  "$defs": {
    "shape1": {
      "type": "string"
    },
    "shape3": {
      "const": false
    },
    "shape4": {
      "const": true
    },
    "shape2": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape3"
        },
        {
          "$ref": "#/$defs/shape4"
        }
      ]
    },
    "shape6": {
      "type": "null"
    },
    "shape5": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape1"
        }
      ]
    },
    "shape10": {
      "type": "number"
    },
    "shape9": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape10"
        }
      ]
    },
    "shape11": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape10"
        }
      ]
    },
    "shape8": {
      "type": "object",
      "properties": {
        "maxModelCalls": {
          "$ref": "#/$defs/shape9"
        },
        "maxTurns": {
          "$ref": "#/$defs/shape11"
        },
        "modelCallsSpent": {
          "$ref": "#/$defs/shape10"
        },
        "noBudget": {
          "$ref": "#/$defs/shape2"
        },
        "noTurnCap": {
          "$ref": "#/$defs/shape2"
        }
      },
      "required": [
        "maxModelCalls",
        "maxTurns",
        "modelCallsSpent",
        "noBudget",
        "noTurnCap"
      ],
      "additionalProperties": false
    },
    "shape7": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape8"
        }
      ]
    },
    "shape14": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape3"
        },
        {
          "$ref": "#/$defs/shape4"
        }
      ]
    },
    "shape13": {
      "type": "object",
      "properties": {
        "answered": {
          "$ref": "#/$defs/shape2"
        },
        "detail": {
          "$ref": "#/$defs/shape5"
        },
        "ending": {
          "$ref": "#/$defs/shape1"
        },
        "modelCalls": {
          "$ref": "#/$defs/shape10"
        },
        "resumable": {
          "$ref": "#/$defs/shape14"
        },
        "steps": {
          "$ref": "#/$defs/shape10"
        },
        "text": {
          "$ref": "#/$defs/shape1"
        }
      },
      "required": [
        "answered",
        "detail",
        "ending",
        "modelCalls",
        "resumable",
        "steps",
        "text"
      ],
      "additionalProperties": false
    },
    "shape12": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape13"
        }
      ]
    },
    "shape0": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape1"
        },
        "cancelRequested": {
          "$ref": "#/$defs/shape2"
        },
        "conversation": {
          "$ref": "#/$defs/shape5"
        },
        "id": {
          "$ref": "#/$defs/shape1"
        },
        "limits": {
          "$ref": "#/$defs/shape7"
        },
        "outcome": {
          "$ref": "#/$defs/shape12"
        },
        "state": {
          "$ref": "#/$defs/shape1"
        }
      },
      "required": [
        "agent",
        "cancelRequested",
        "conversation",
        "id",
        "limits",
        "outcome",
        "state"
      ],
      "additionalProperties": false
    },
    "shape15": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape0"
      }
    },
    "shape16": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape1"
        },
        "id": {
          "$ref": "#/$defs/shape1"
        }
      },
      "required": [
        "agent",
        "id"
      ],
      "additionalProperties": false
    },
    "shape20": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape1"
      }
    },
    "shape19": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape20"
        }
      ]
    },
    "shape22": {
      "const": "project"
    },
    "shape23": {
      "const": "personal"
    },
    "shape21": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape22"
        },
        {
          "$ref": "#/$defs/shape23"
        }
      ]
    },
    "shape18": {
      "type": "object",
      "properties": {
        "exclusions": {
          "$ref": "#/$defs/shape19"
        },
        "kind": {
          "$ref": "#/$defs/shape21"
        },
        "lent": {
          "$ref": "#/$defs/shape19"
        },
        "name": {
          "$ref": "#/$defs/shape1"
        },
        "workspace": {
          "$ref": "#/$defs/shape1"
        }
      },
      "required": [
        "exclusions",
        "lent",
        "name",
        "workspace"
      ],
      "additionalProperties": false
    },
    "shape17": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape18"
      }
    },
    "shape27": {
      "type": "object",
      "properties": {
        "grewBy": {
          "$ref": "#/$defs/shape11"
        },
        "promptTokens": {
          "$ref": "#/$defs/shape10"
        },
        "since": {
          "$ref": "#/$defs/shape11"
        },
        "turn": {
          "$ref": "#/$defs/shape10"
        }
      },
      "required": [
        "grewBy",
        "promptTokens",
        "since",
        "turn"
      ],
      "additionalProperties": false
    },
    "shape26": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape27"
      }
    },
    "shape25": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape26"
        }
      ]
    },
    "shape31": {
      "const": "MEASURED"
    },
    "shape32": {
      "const": "BOUND"
    },
    "shape33": {
      "const": "ESTIMATED"
    },
    "shape30": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape31"
        },
        {
          "$ref": "#/$defs/shape32"
        },
        {
          "$ref": "#/$defs/shape33"
        }
      ]
    },
    "shape29": {
      "type": "object",
      "properties": {
        "basis": {
          "$ref": "#/$defs/shape30"
        },
        "how": {
          "$ref": "#/$defs/shape1"
        },
        "tokens": {
          "$ref": "#/$defs/shape10"
        }
      },
      "required": [
        "basis",
        "how",
        "tokens"
      ],
      "additionalProperties": false
    },
    "shape28": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape29"
        }
      ]
    },
    "shape38": {
      "type": "object",
      "properties": {
        "characters": {
          "$ref": "#/$defs/shape10"
        },
        "name": {
          "$ref": "#/$defs/shape1"
        }
      },
      "required": [
        "characters",
        "name"
      ],
      "additionalProperties": false
    },
    "shape37": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape38"
      }
    },
    "shape36": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape37"
        }
      ]
    },
    "shape35": {
      "type": "object",
      "properties": {
        "agent": {
          "$ref": "#/$defs/shape1"
        },
        "model": {
          "$ref": "#/$defs/shape1"
        },
        "systemPromptCharacters": {
          "$ref": "#/$defs/shape10"
        },
        "toolCharacters": {
          "$ref": "#/$defs/shape10"
        },
        "tools": {
          "$ref": "#/$defs/shape36"
        }
      },
      "required": [
        "agent",
        "model",
        "systemPromptCharacters",
        "toolCharacters",
        "tools"
      ],
      "additionalProperties": false
    },
    "shape34": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape35"
        }
      ]
    },
    "shape41": {
      "type": "object",
      "properties": {
        "component": {
          "$ref": "#/$defs/shape1"
        },
        "reason": {
          "$ref": "#/$defs/shape1"
        }
      },
      "required": [
        "component",
        "reason"
      ],
      "additionalProperties": false
    },
    "shape40": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape41"
      }
    },
    "shape39": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape40"
        }
      ]
    },
    "shape24": {
      "type": "object",
      "properties": {
        "cacheHitRate": {
          "$ref": "#/$defs/shape11"
        },
        "measuredTurns": {
          "$ref": "#/$defs/shape25"
        },
        "messageTokens": {
          "$ref": "#/$defs/shape28"
        },
        "prefix": {
          "$ref": "#/$defs/shape34"
        },
        "sent": {
          "$ref": "#/$defs/shape11"
        },
        "sentAtTurn": {
          "$ref": "#/$defs/shape11"
        },
        "systemPromptTokens": {
          "$ref": "#/$defs/shape28"
        },
        "toolTokens": {
          "$ref": "#/$defs/shape28"
        },
        "turns": {
          "$ref": "#/$defs/shape10"
        },
        "turnsMeasured": {
          "$ref": "#/$defs/shape10"
        },
        "unavailable": {
          "$ref": "#/$defs/shape39"
        }
      },
      "required": [
        "cacheHitRate",
        "measuredTurns",
        "messageTokens",
        "prefix",
        "sent",
        "sentAtTurn",
        "systemPromptTokens",
        "toolTokens",
        "turns",
        "turnsMeasured",
        "unavailable"
      ],
      "additionalProperties": false
    },
    "shape47": {
      "type": "object",
      "properties": {
        "at": {
          "$ref": "#/$defs/shape1"
        },
        "by": {
          "$ref": "#/$defs/shape1"
        },
        "where": {
          "$ref": "#/$defs/shape1"
        }
      },
      "required": [
        "at",
        "by",
        "where"
      ],
      "additionalProperties": false
    },
    "shape46": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape47"
        }
      ]
    },
    "shape49": {
      "type": "object",
      "properties": {
        "project": {
          "$ref": "#/$defs/shape5"
        }
      },
      "required": [
        "project"
      ],
      "additionalProperties": false
    },
    "shape48": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape49"
        }
      ]
    },
    "shape51": {
      "type": "object",
      "properties": {
        "at": {
          "$ref": "#/$defs/shape1"
        },
        "by": {
          "$ref": "#/$defs/shape1"
        },
        "reason": {
          "$ref": "#/$defs/shape1"
        }
      },
      "required": [
        "at",
        "by",
        "reason"
      ],
      "additionalProperties": false
    },
    "shape50": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape51"
        }
      ]
    },
    "shape52": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape1"
        }
      ]
    },
    "shape45": {
      "type": "object",
      "properties": {
        "body": {
          "$ref": "#/$defs/shape1"
        },
        "formed": {
          "$ref": "#/$defs/shape46"
        },
        "home": {
          "$ref": "#/$defs/shape48"
        },
        "id": {
          "$ref": "#/$defs/shape1"
        },
        "invalidation": {
          "$ref": "#/$defs/shape50"
        },
        "lastUsed": {
          "$ref": "#/$defs/shape52"
        },
        "pinned": {
          "$ref": "#/$defs/shape2"
        },
        "scope": {
          "$ref": "#/$defs/shape1"
        },
        "state": {
          "$ref": "#/$defs/shape1"
        },
        "summary": {
          "$ref": "#/$defs/shape1"
        },
        "supersededBy": {
          "$ref": "#/$defs/shape52"
        },
        "supersedes": {
          "$ref": "#/$defs/shape52"
        },
        "uses": {
          "$ref": "#/$defs/shape11"
        }
      },
      "required": [
        "body",
        "formed",
        "home",
        "id",
        "invalidation",
        "lastUsed",
        "pinned",
        "scope",
        "state",
        "summary",
        "supersededBy",
        "supersedes",
        "uses"
      ],
      "additionalProperties": false
    },
    "shape44": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape45"
      }
    },
    "shape43": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape44"
        }
      ]
    },
    "shape42": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape10"
        },
        "memories": {
          "$ref": "#/$defs/shape43"
        },
        "question": {
          "$ref": "#/$defs/shape1"
        },
        "unsearchable": {
          "$ref": "#/$defs/shape11"
        }
      },
      "required": [
        "limit",
        "memories",
        "question",
        "unsearchable"
      ],
      "additionalProperties": false
    },
    "shape54": {
      "type": "object",
      "properties": {
        "id": {
          "$ref": "#/$defs/shape1"
        },
        "scope": {
          "$ref": "#/$defs/shape1"
        },
        "summary": {
          "$ref": "#/$defs/shape1"
        },
        "unsearchable": {
          "$ref": "#/$defs/shape2"
        }
      },
      "required": [
        "id",
        "scope",
        "summary",
        "unsearchable"
      ],
      "additionalProperties": false
    },
    "shape53": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape54"
      }
    },
    "shape58": {
      "type": "object",
      "properties": {
        "chunkId": {
          "$ref": "#/$defs/shape1"
        },
        "documentId": {
          "$ref": "#/$defs/shape1"
        },
        "paragraphId": {
          "$ref": "#/$defs/shape1"
        },
        "paragraphOrdinal": {
          "$ref": "#/$defs/shape10"
        },
        "paragraphText": {
          "$ref": "#/$defs/shape1"
        },
        "similarity": {
          "$ref": "#/$defs/shape10"
        },
        "sourceName": {
          "$ref": "#/$defs/shape1"
        },
        "text": {
          "$ref": "#/$defs/shape1"
        },
        "title": {
          "$ref": "#/$defs/shape1"
        }
      },
      "required": [
        "chunkId",
        "documentId",
        "paragraphId",
        "paragraphOrdinal",
        "paragraphText",
        "similarity",
        "sourceName",
        "text",
        "title"
      ],
      "additionalProperties": false
    },
    "shape57": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape58"
      }
    },
    "shape56": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape57"
        }
      ]
    },
    "shape55": {
      "type": "object",
      "properties": {
        "hits": {
          "$ref": "#/$defs/shape56"
        },
        "limit": {
          "$ref": "#/$defs/shape10"
        },
        "mode": {
          "$ref": "#/$defs/shape1"
        },
        "query": {
          "$ref": "#/$defs/shape1"
        },
        "searchable": {
          "$ref": "#/$defs/shape10"
        },
        "unsearchable": {
          "$ref": "#/$defs/shape10"
        }
      },
      "required": [
        "hits",
        "limit",
        "mode",
        "query",
        "searchable",
        "unsearchable"
      ],
      "additionalProperties": false
    }
  }
}
