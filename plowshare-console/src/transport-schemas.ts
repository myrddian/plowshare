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
      "$ref": "#/$defs/shape25"
    },
    "memory.recall": {
      "$ref": "#/$defs/shape43"
    },
    "memory.index": {
      "$ref": "#/$defs/shape54"
    },
    "document.search": {
      "$ref": "#/$defs/shape56"
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
      "const": "application"
    },
    "shape24": {
      "const": "personal"
    },
    "shape21": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape22"
        },
        {
          "$ref": "#/$defs/shape23"
        },
        {
          "$ref": "#/$defs/shape24"
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
    "shape28": {
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
    "shape27": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape28"
      }
    },
    "shape26": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape27"
        }
      ]
    },
    "shape32": {
      "const": "MEASURED"
    },
    "shape33": {
      "const": "BOUND"
    },
    "shape34": {
      "const": "ESTIMATED"
    },
    "shape31": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape32"
        },
        {
          "$ref": "#/$defs/shape33"
        },
        {
          "$ref": "#/$defs/shape34"
        }
      ]
    },
    "shape30": {
      "type": "object",
      "properties": {
        "basis": {
          "$ref": "#/$defs/shape31"
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
    "shape29": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape30"
        }
      ]
    },
    "shape39": {
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
    "shape38": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape39"
      }
    },
    "shape37": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape38"
        }
      ]
    },
    "shape36": {
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
          "$ref": "#/$defs/shape37"
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
    "shape35": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape36"
        }
      ]
    },
    "shape42": {
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
    "shape41": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape42"
      }
    },
    "shape40": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape41"
        }
      ]
    },
    "shape25": {
      "type": "object",
      "properties": {
        "cacheHitRate": {
          "$ref": "#/$defs/shape11"
        },
        "measuredTurns": {
          "$ref": "#/$defs/shape26"
        },
        "messageTokens": {
          "$ref": "#/$defs/shape29"
        },
        "prefix": {
          "$ref": "#/$defs/shape35"
        },
        "sent": {
          "$ref": "#/$defs/shape11"
        },
        "sentAtTurn": {
          "$ref": "#/$defs/shape11"
        },
        "systemPromptTokens": {
          "$ref": "#/$defs/shape29"
        },
        "toolTokens": {
          "$ref": "#/$defs/shape29"
        },
        "turns": {
          "$ref": "#/$defs/shape10"
        },
        "turnsMeasured": {
          "$ref": "#/$defs/shape10"
        },
        "unavailable": {
          "$ref": "#/$defs/shape40"
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
    "shape48": {
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
    "shape47": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape48"
        }
      ]
    },
    "shape50": {
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
    "shape49": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape50"
        }
      ]
    },
    "shape52": {
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
    "shape51": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape52"
        }
      ]
    },
    "shape53": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape1"
        }
      ]
    },
    "shape46": {
      "type": "object",
      "properties": {
        "body": {
          "$ref": "#/$defs/shape1"
        },
        "formed": {
          "$ref": "#/$defs/shape47"
        },
        "home": {
          "$ref": "#/$defs/shape49"
        },
        "id": {
          "$ref": "#/$defs/shape1"
        },
        "invalidation": {
          "$ref": "#/$defs/shape51"
        },
        "lastUsed": {
          "$ref": "#/$defs/shape53"
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
          "$ref": "#/$defs/shape53"
        },
        "supersedes": {
          "$ref": "#/$defs/shape53"
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
    "shape45": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape46"
      }
    },
    "shape44": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape45"
        }
      ]
    },
    "shape43": {
      "type": "object",
      "properties": {
        "limit": {
          "$ref": "#/$defs/shape10"
        },
        "memories": {
          "$ref": "#/$defs/shape44"
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
    "shape55": {
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
    "shape54": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape55"
      }
    },
    "shape59": {
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
    "shape58": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/shape59"
      }
    },
    "shape57": {
      "anyOf": [
        {
          "$ref": "#/$defs/shape6"
        },
        {
          "$ref": "#/$defs/shape58"
        }
      ]
    },
    "shape56": {
      "type": "object",
      "properties": {
        "hits": {
          "$ref": "#/$defs/shape57"
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
