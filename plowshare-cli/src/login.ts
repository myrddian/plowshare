import { Usage } from './options.js'
import { createInterface } from 'node:readline/promises'

/** Login alone may prompt. Ordinary commands and MCP remain noninteractive. */
export async function promptLogin(signal: AbortSignal): Promise<{ handle: string; password: string }> {
    if (!process.stdin.isTTY || !process.stderr.isTTY) throw new Usage('Login needs a terminal, or PLOWSHARE_HANDLE and PLOWSHARE_PASSWORD.')
    const line = createInterface({ input: process.stdin, output: process.stderr })
    let handle: string
    try { handle = (await line.question('Handle: ', { signal })).trim() } finally { line.close() }
    if (!handle) throw new Usage('Enter a login handle.')
    return { handle, password: await promptPassword('Password: ', signal) }
}

export async function promptNewPassword(signal: AbortSignal): Promise<string> {
    const password = await promptPassword('New password: ', signal)
    if (password !== await promptPassword('Repeat new password: ', signal)) throw new Usage('The new passwords did not match.')
    return password
}

async function promptPassword(label: string, signal: AbortSignal): Promise<string> {
    if (!process.stdin.isTTY || !process.stderr.isTTY) throw new Usage('Password entry needs a terminal.')
    signal.throwIfAborted()
    const password = await new Promise<string>((resolve, reject) => {
        const raw = process.stdin.isRaw
        let value = ''
        const cleanup = (): void => {
            process.stdin.off('data', data); signal.removeEventListener('abort', aborted)
            process.stdin.setRawMode(raw); process.stdin.pause(); process.stderr.write('\n')
        }
        const aborted = (): void => { cleanup(); reject(new Usage('Login interrupted.')) }
        const data = (chunk: Buffer): void => {
            for (const char of chunk.toString('utf8')) {
                if (char === '\u0003' || char === '\u0004') { aborted(); return }
                if (char === '\r' || char === '\n') { cleanup(); resolve(value); return }
                if (char === '\u007f' || char === '\b') value = value.slice(0, -1)
                else if (char >= ' ') value += char
            }
        }
        process.stdin.setRawMode(true); process.stdin.on('data', data); process.stdin.resume()
        signal.addEventListener('abort', aborted, { once: true })
        if (signal.aborted) aborted()
        else process.stderr.write(label)
    })
    if (!password) throw new Usage('Enter a password.')
    return password
}

export async function promptSetup(signal: AbortSignal): Promise<{ handle: string; password: string }> {
    if (!process.stdin.isTTY || !process.stderr.isTTY) throw new Usage('Setup needs a terminal, or PLOWSHARE_NEW_HANDLE and PLOWSHARE_NEW_PASSWORD.')
    const line = createInterface({ input: process.stdin, output: process.stderr })
    let handle: string
    try { handle = (await line.question('First administrator handle: ', { signal })).trim() } finally { line.close() }
    return { handle, password: await promptNewPassword(signal) }
}
