"""Explicit immutable selection for one request on a shared Application connection."""

from dataclasses import dataclass
from uuid import UUID


@dataclass(frozen=True)
class ActingSelection:
    context: str

    def __post_init__(self) -> None:
        if not isinstance(self.context, str) or str(UUID(self.context)) != self.context:
            raise ValueError("Choose a canonical acting context UUID")
