"""Installation recovery reads retained receipts; it never replays an uncertain issuance."""

from __future__ import annotations

import json
from unittest.mock import AsyncMock, patch

from lifecycle_support import FIRST, PRINCIPAL, SECOND, LifecycleFixture, release
from plowshare import Client, Delivery, Reply, TransportError
from plowshare.contracts import (
    ApplicationCredentialDto,
    ApplicationIdentityBindingDto,
    ApplicationIdentityStatusDto,
    ApplicationServiceDeclarationDto,
    ApplicationTokenReceiptDto,
    ProjectAccessDto,
    ProjectGrantDto,
)

from plowshare_privacy.bootstrap import SetupProblem, install
from plowshare_privacy.owned_setup import issue_credential, recover_installation
from plowshare_privacy.setup import prepare, private_write


class OwnedSetupTest(LifecycleFixture):
    def identity(
        self,
        *,
        issued: bool = False,
        request_id: str | None = None,
        expires_at: str = "2026-11-01T00:00:00Z",
    ) -> ApplicationIdentityStatusDto:
        return ApplicationIdentityStatusDto(
            project="network-privacy-watch",
            identity=ApplicationIdentityBindingDto(
                project="network-privacy-watch",
                service=ApplicationServiceDeclarationDto(
                    handle="worker", role="CONTRIBUTOR"
                ),
                token_id=SECOND,
                principal=PRINCIPAL,
                state="ACTIVE",
                credential_issued=issued,
                credential_request_id=request_id,
                expires_at=expires_at,
                revoked_at=None,
            ),
        )

    def access(self) -> ProjectAccessDto:
        return ProjectAccessDto(
            project="network-privacy-watch",
            role="MANAGER",
            permissions=(),
            members=(ProjectGrantDto(handle="manager", role="MANAGER"),),
            history=(),
        )

    async def test_owned_install_deploys_once_then_issues_reserved_token(self) -> None:
        prepare(self.setup, owned=True)
        client = self.client()
        original = client.request.side_effect
        assert callable(original)

        async def request(command: object) -> Reply[object]:
            operation = getattr(command, "operation")
            if operation == "project.access":
                return Reply("OK", None, self.access())
            if operation == "application.identity.status":
                return Reply("OK", None, self.identity())
            if operation == "application.token.issue":
                receipt = ApplicationTokenReceiptDto(
                    project="network-privacy-watch",
                    request_id=getattr(command, "request_id"),
                    token_id=SECOND,
                    principal=PRINCIPAL,
                    expires_at="2026-11-01T00:00:00Z",
                )
                return Reply(
                    "OK",
                    None,
                    ApplicationCredentialDto(
                        receipt=receipt, credential="pss_" + "1" * 48
                    ),
                )
            answer: Reply[object] = await original(command)
            return answer

        client.request.side_effect = request
        with patch("plowshare_privacy.bootstrap.wait_for_schedule", AsyncMock()):
            await install(self.setup, client, "network-privacy-watch", guided=True)
        operations = [call.args[0].operation for call in client.request.call_args_list]
        self.assertEqual(operations.count("application.deploy"), 1)
        self.assertEqual(operations.count("application.token.issue"), 1)
        self.assertEqual(
            (self.setup.output / "service-token").read_text().strip(), "pss_" + "1" * 48
        )
        self.assertFalse((self.setup.output / "identity-intent.json").exists())

    async def test_lost_issue_reply_retains_uuid_and_recovery_does_not_replay(
        self,
    ) -> None:
        prepare(self.setup, owned=True)
        client = AsyncMock(spec=Client)
        client.request.side_effect = [
            Reply("OK", None, self.identity()),
            TransportError(Delivery.UNKNOWN, "lost"),
        ]
        with self.assertRaises(TransportError):
            await issue_credential(self.setup, client)
        issuance = json.loads(
            (self.setup.output / "credential-intent.json").read_text()
        )
        original = ApplicationTokenReceiptDto(
            project="network-privacy-watch",
            request_id=issuance["requestId"],
            token_id=SECOND,
            principal=PRINCIPAL,
            expires_at="2026-11-01T00:00:00Z",
        )
        private_write(
            self.setup.output / "bootstrap-intent.json",
            json.dumps(
                {
                    "project": "network-privacy-watch",
                    "requestId": FIRST,
                    "expectedRevision": None,
                    "destination": {
                        "store": "chosen-store",
                        "path": "network-privacy-watch",
                    },
                }
            ),
        )
        from plowshare.contracts import (
            ApplicationDeploymentReceiptDto,
            ApplicationDeploymentStatusDto,
        )

        client.request.reset_mock()
        client.request.side_effect = [
            Reply(
                "OK",
                None,
                ApplicationDeploymentReceiptDto(
                    project="network-privacy-watch",
                    request_id=FIRST,
                    release=release(FIRST),
                ),
            ),
            Reply(
                "OK",
                None,
                ApplicationDeploymentStatusDto(
                    project="network-privacy-watch",
                    active_revision=FIRST,
                    releases=(release(FIRST),),
                ),
            ),
            Reply("OK", None, self.access()),
            Reply("OK", None, original),
            Reply(
                "OK", None, self.identity(issued=True, request_id=original.request_id)
            ),
        ]
        with self.assertRaisesRegex(SetupProblem, "recover --rotate"):
            await recover_installation(self.setup, client)
        operations = [call.args[0].operation for call in client.request.call_args_list]
        self.assertNotIn("application.token.issue", operations)
        self.assertNotIn("application.deploy", operations)
        self.assertEqual(
            client.request.call_args_list[3].args[0].request_id, original.request_id
        )

    async def test_recovery_refuses_superseded_saved_secret(self) -> None:
        prepare(self.setup, owned=True)
        from plowshare.contracts import (
            ApplicationDeploymentReceiptDto,
            ApplicationDeploymentStatusDto,
        )

        private_write(
            self.setup.output / "bootstrap-intent.json",
            json.dumps(
                {
                    "project": "network-privacy-watch",
                    "requestId": FIRST,
                    "expectedRevision": None,
                    "destination": {
                        "store": "chosen-store",
                        "path": "network-privacy-watch",
                    },
                }
            ),
        )
        private_write(
            self.setup.output / "credential-current.json",
            json.dumps({"intent": "credential-intent.json"}),
        )
        private_write(
            self.setup.output / "credential-intent.json",
            json.dumps(
                {
                    "project": "network-privacy-watch",
                    "requestId": FIRST,
                    "expiresInDays": 30,
                    "rotate": False,
                    "tokenId": SECOND,
                }
            ),
        )
        private_write(
            self.setup.output / ("credential-" + FIRST + ".json"),
            json.dumps({"requestId": FIRST, "credential": "pss_" + "1" * 48}),
        )
        original = ApplicationTokenReceiptDto(
            project="network-privacy-watch",
            request_id=FIRST,
            token_id=SECOND,
            principal=PRINCIPAL,
            expires_at="2026-11-01T00:00:00Z",
        )
        client = AsyncMock(spec=Client)
        client.request.side_effect = [
            Reply(
                "OK",
                None,
                ApplicationDeploymentReceiptDto(
                    project="network-privacy-watch",
                    request_id=FIRST,
                    release=release(FIRST),
                ),
            ),
            Reply(
                "OK",
                None,
                ApplicationDeploymentStatusDto(
                    project="network-privacy-watch",
                    active_revision=FIRST,
                    releases=(release(FIRST),),
                ),
            ),
            Reply("OK", None, self.access()),
            Reply("OK", None, original),
            Reply("OK", None, self.identity(issued=True, request_id=SECOND)),
        ]
        original_responses = list(client.request.side_effect)
        expired_responses = original_responses[:-1] + [
            Reply(
                "OK",
                None,
                self.identity(
                    issued=True, request_id=FIRST, expires_at="2020-01-01T00:00:00Z"
                ),
            )
        ]
        for responses in (original_responses, expired_responses):
            client.request.side_effect = responses
            with self.assertRaisesRegex(SetupProblem, "superseded, revoked or expired"):
                await recover_installation(self.setup, client)
        self.assertFalse((self.setup.output / "service-token").exists())
        self.assertNotIn(
            "application.token.issue",
            [call.args[0].operation for call in client.request.call_args_list],
        )
