"""Validated Application inbox configuration at the source-file boundary."""

from __future__ import annotations

from dataclasses import dataclass

from .contracts import identifier, items, object_fields


@dataclass(frozen=True)
class InboxSettings:
    attention_orchestrations: tuple[str, ...]

    @classmethod
    def decode(cls, value: object) -> InboxSettings:
        row = object_fields(value, {"attentionOrchestrations"})
        names = tuple(
            identifier(name) for name in items(row["attentionOrchestrations"], 64)
        )
        if len(set(names)) != len(names):
            raise ValueError("Attention orchestrations must be unique")
        return cls(names)

    def encode(self) -> dict[str, list[str]]:
        return {"attentionOrchestrations": list(self.attention_orchestrations)}
