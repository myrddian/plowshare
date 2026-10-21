"""CLI modes select identity; the public SDK owns user login and renewal.

Excalibur never reads credential files or retries board operations after an auth
or transport failure. A user journal binds the SDK-selected account across renewal.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field

from plowshare import Credentials

from .model import Configuration


def bearer(value: object) -> str:
    if (
        not isinstance(value, str)
        or not 1 <= len(value) <= 1024
        or any(c.isspace() or ord(c) < 33 or ord(c) == 127 for c in value)
    ):
        raise ValueError("Invalid configured credential")
    return value


@dataclass(frozen=True)
class CliCredential:
    token: str = field(repr=False)
    user_account: str | None = None


async def user_credential(
    config: Configuration,
    *,
    account: str | None,
    connection: str | None,
) -> CliCredential:
    credentials = Credentials.resolve(
        config.origin, account=account, connection=connection
    )
    session = await credentials.authenticated()
    return CliCredential(session.tokens.access, session.handle)


def agent_credential(config: Configuration) -> CliCredential:
    token = bearer(os.environ.get(config.token_environment))
    if not token.startswith("pss_"):
        raise ValueError(
            "Agent mode requires a scoped Plowshare service token (pss_). "
            "Use --is-user to reuse your saved Plowshare login."
        )
    return CliCredential(token)
