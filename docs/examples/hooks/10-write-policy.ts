import type { Hook } from '@plowshare/hooks'

// A deliberately narrow content rule, not a complete secret scanner.
// The tool's own grants, containment and write preconditions still apply.
export default {
    name: 'manual-write-policy',
    stages: {
        'tool.pre': {
            tools: ['file_edit'],
            handle(event) {
                const text = String(event.args.content ?? event.args.new ?? '')
                if (/BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY/.test(text)) {
                    return { deny: 'The proposed edit contains a private-key marker.' }
                }
                return undefined
            },
        },
    },
} satisfies Hook
