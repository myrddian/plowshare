"""Publication gate for the unimplemented native HA system-context handoff.

This is a source capability decision, never an operator configuration switch.
Keep both installation packaging and HA intake closed until the fidelity contract
is implemented and independently verified for Conversation and AI Task.
"""

PROMPT_FIDELITY_AVAILABLE = False
UNAVAILABLE_REASON = (
    "Plowshare Assist is gated: the public SDK cannot yet preserve Home "
    "Assistant's rendered system prompt and context in the required system role."
)


class PromptFidelityUnavailableError(RuntimeError):
    """The required public capability has not been implemented or verified."""


def require_prompt_fidelity() -> None:
    """Refuse intake before storage, transport or other side effects."""
    if not PROMPT_FIDELITY_AVAILABLE:
        raise PromptFidelityUnavailableError(UNAVAILABLE_REASON)
