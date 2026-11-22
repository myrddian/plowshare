"""Configuration keys and deployment-independent resource bounds."""

DOMAIN = "plowshare"
CONF_PROJECT = "project"
CONF_AGENT = "agent"
CONF_REQUEST_TIMEOUT = "request_timeout"
CONF_RESPONSE_TIMEOUT = "response_timeout"
CONF_ALLOW_ANONYMOUS = "allow_anonymous"
CONF_REQUEST_TOPIC = "request_topic"
DEFAULT_REQUEST_TIMEOUT = 2
DEFAULT_RESPONSE_TIMEOUT = 30
CLEANUP_TIMEOUT = 2
MAX_SESSIONS = 128
MAX_TEXT = 16000
CONF_CONVERSATION_ENABLED = "conversation_enabled"
CONF_AI_TASK_ENABLED = "ai_task_enabled"
CONF_CONTROL_ENABLED = "control_enabled"
CONF_READ_ONLY_AGENT = "read_only_agent"
CONF_TASK_AGENT = "task_agent"
CONF_PROMPT = "prompt"
CONF_LLM_API = "llm_api"
MAX_PROMPT_TEMPLATE_BYTES = 32 * 1024
MAX_VALIDATION_PROBES = 4
