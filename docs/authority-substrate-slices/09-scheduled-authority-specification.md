# Existing schedule action and recovery specification

Recorded before slice 09 enforcement, 18 October 2026.

A trigger's existing defining/execution principal supplies ordinary work authority.
It does not impersonate a targeted conversation's creator. The event remains the
recorded speaker. Schedule managers/controllers retain their definition/control
eligibility; managing a schedule does not lend execution grants to its executor.

| Existing firing family | Actions and bounds | Retained budget / recovery |
| --- | --- | --- |
| Untargeted agent/skill/command | Resolve configured Personal/Project home; require current READ/WRITE and RUN_MODEL for model work, plus CREATE for the new log; native scripts do not acquire inference | Existing explicit trigger/worker allowance, event job and conversation receipt; slice 06 admission and slice 07 call claims. |
| Conversation-targeted turn | Resolve the existing conversation; require current WRITE/work eligibility for the trigger executor; preserve event speaker and separately carry that executor into common admission | Conversation's shared allowance and existing ceilings, never a fresh trigger allowance. |
| Scheduled message | Existing source/definition validation and message publication authority | Message receipt and firing ID; receiver execution is independent and handled by the message/Relay boundaries. |
| Restart/uncertainty | Existing firing claim, original job/message receipt and source identity | Join firing -> job -> retained admission -> budget claims when present. Missing/unsupported authority is refused/held, not reconstructed from creator or later grants. Existing abandoned-start policy does not replay the old effect. |

The slice adds no scheduler, CRON/EXECUTE_JOB/CONTROL_EXECUTION action, public API,
new pause/resume control or generic job recovery. Existing registration, Application
execution-account/manager restrictions, source validation, sessions, cron/time-zone
semantics, claim receipts and unknown outcomes remain in their owning contracts.

Source/deadline or permission changes at firing refuse; queue-time and model/tool
checks continue through common retained authority. Published message contents and
model output cannot select a different executor or SYSTEM. A source that cannot
resolve an existing supported principal/home/allowance remains refused.
