import type { Probe } from './system.ts'

/**
 * Asking the terminal what its colours are, before Ink owns the keyboard.
 *
 * <h2>The questions</h2>
 *
 * <p>`OSC 10 ; ?` and `OSC 11 ; ?` ask for the foreground and background,
 * `OSC 4 ; n ; ?` for each of the sixteen palette slots. A terminal that
 * understands answers each in kind — `ESC ] 11 ; rgb:1e1e/2a2a/3b3b BEL` — and
 * one that does not says nothing at all, which is the awkward part: silence
 * cannot be told from slowness.
 *
 * <p><b>So the last question is one every terminal answers.</b> Primary Device
 * Attributes, `CSI c`, has been answered by everything since the VT100. When
 * its reply arrives, every colour reply that was coming has already come, and
 * the wait ends then rather than at a timeout. The timeout is only for a
 * terminal that answers nothing, which in practice is a pipe pretending to be
 * a terminal.
 *
 * <h2>Why before Ink, and why only then</h2>
 *
 * <p>The replies arrive on stdin. Once Ink is reading stdin they would arrive
 * as keypresses — typed into the composer as `]11;rgb:…`. So the probe runs
 * once, at startup, with raw mode taken and given back, and never again.
 */

/** A reply's colour: `rgb:R/G/B` with 1–4 hex digits a channel, or `#rrggbb`. */
function colourOf(spelt: string): string | undefined {
    const hash = /^#([0-9a-f]{6})$/iu.exec(spelt)
    if (hash !== null) {
        return `#${(hash[1] as string).toLowerCase()}`
    }
    const parts = /^rgba?:([0-9a-f]{1,4})\/([0-9a-f]{1,4})\/([0-9a-f]{1,4})/iu.exec(spelt)
    if (parts === null) {
        return undefined
    }
    return `#${parts.slice(1, 4).map((channel) => {
        const scaled = Math.round((Number.parseInt(channel, 16) / (16 ** channel.length - 1)) * 255)
        return scaled.toString(16).padStart(2, '0')
    }).join('')}`
}

/** Every colour reply in `data`, as a probe. Pure, so it is tested without a terminal. */
export function parseReplies(data: string): Probe {
    const palette: (string | undefined)[] = Array.from({ length: 16 }, () => undefined)
    let foreground: string | undefined
    let background: string | undefined
    // eslint-disable-next-line no-control-regex
    const reply = /\](\d+)(?:;(\d+))?;([^]*)(?:|\\)/gu
    for (const found of data.matchAll(reply)) {
        const colour = colourOf(found[3] as string)
        if (colour === undefined) {
            continue
        }
        const code = found[1]
        if (code === '10') {
            foreground = colour
        } else if (code === '11') {
            background = colour
        } else if (code === '4' && found[2] !== undefined) {
            const index = Number(found[2])
            if (index < 16) {
                palette[index] = colour
            }
        }
    }
    return {
        ...(foreground === undefined ? {} : { foreground }),
        ...(background === undefined ? {} : { background }),
        palette,
    }
}

/** The questions, in the order they are asked. The attributes query is last on purpose. */
export const QUESTIONS = `]10;?]11;?${
    Array.from({ length: 16 }, (_, index) => `]4;${index};?`).join('')}[c`

/** The reply to the attributes query, which means the colour replies are all in. */
// eslint-disable-next-line no-control-regex
const ATTRIBUTES = /\[\?[\d;]*c/u

export interface Ends {
    readonly stdin: NodeJS.ReadStream
    readonly stdout: NodeJS.WriteStream
    readonly env: Readonly<Record<string, string | undefined>>
}

/**
 * What the terminal says its colours are, or `undefined` when it cannot be asked.
 *
 * <p>Not asked inside tmux or screen, which answer for themselves rather than
 * for the terminal they run in, or when either end is not a terminal.
 */
export async function probeTerminal(ends: Ends, patience = 400): Promise<Probe | undefined> {
    const { stdin, stdout, env } = ends
    if (stdin.isTTY !== true || stdout.isTTY !== true
        || env['TMUX'] !== undefined || (env['TERM'] ?? '').startsWith('screen')
        || env['TERM'] === 'dumb') {
        return undefined
    }
    const wasRaw = stdin.isRaw
    let heard = ''
    return new Promise((answer) => {
        const finish = (): void => {
            clearTimeout(timer)
            stdin.removeListener('data', listen)
            stdin.setRawMode(wasRaw)
            stdin.pause()
            answer(heard === '' ? undefined : parseReplies(heard))
        }
        const listen = (chunk: Buffer | string): void => {
            heard += chunk.toString()
            if (ATTRIBUTES.test(heard)) {
                finish()
            }
        }
        const timer = setTimeout(finish, patience)
        stdin.setRawMode(true)
        stdin.on('data', listen)
        stdin.resume()
        stdout.write(QUESTIONS)
    })
}
