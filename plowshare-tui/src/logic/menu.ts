/**
 * The menu that opens as somebody types a command or a mention.
 *
 * <h2>What opens it, which is OpenCode's rule</h2>
 *
 * <ul>
 * <li><b>`/` at the very start of the line</b>, with no space yet: the commands.
 * <li><b>A command that takes an argument, then a space</b>: that command's
 *     arguments — `/theme ` offers the themes.
 * <li><b>`@` starting a word before the cursor</b>: the names this server
 *     serves. A mention is text for whoever answers; it does not change who
 *     that is.
 * </ul>
 *
 * <p>It is a pure function of the line, the cursor and what there is to offer,
 * so it is never out of step with the composer: there is no "menu open" state
 * to forget to clear, only whether this line, with the cursor here, is one that
 * has a menu. The only state is which row is picked and whether Escape closed
 * it, and both belong to the composer.
 *
 * <h2>Matching, which is prefix, then description</h2>
 *
 * <p>Fuzzy matching earns its place over thousands of files. Over a handful of
 * commands it mostly reorders things surprisingly, so names that start with
 * what was typed are offered in the order given; only when none do is a word in
 * a description tried — `/colour` still finds `/theme`.
 *
 * <p>Runtime-neutral, like everything in this directory.
 */

/** One thing that can be offered. */
export interface Offer {
    readonly name: string
    /** A few words about it, shown beside it. */
    readonly detail?: string
}

/** Everything the menu may offer. */
export interface Vocabulary {
    readonly commands: readonly Offer[]
    /** The names `@` offers. */
    readonly names: readonly Offer[]
    /** Per command, what may follow it. A command absent here takes nothing. */
    readonly arguments: Readonly<Record<string, () => readonly Offer[]>>
    /**
     * The commands whose argument is only the start of the line: picking one
     * leaves a space after it for what follows, rather than calling the line
     * complete. `/answer <id> <your answer>` is one — the id is chosen from the
     * menu and the answer is still to be typed.
     */
    readonly openEnded?: readonly string[]
}

/** Whether `menu` offers the argument of a command that more is typed after. */
export function isOpenEnded(menu: Menu, vocabulary: Vocabulary): boolean {
    return menu.kind === 'argument' && menu.command !== undefined
        && (vocabulary.openEnded ?? []).includes(menu.command)
}

export type MenuKind = 'command' | 'argument' | 'mention'

export interface Menu {
    readonly kind: MenuKind
    /** Where in the line the text a choice replaces starts. */
    readonly start: number
    /**
     * Where it ends: the end of the word the cursor is in, so picking with the
     * cursor mid-word replaces the word rather than splicing into it.
     */
    readonly end: number
    readonly choices: readonly Offer[]
    /** For an argument menu, the command it belongs to. */
    readonly command?: string
}

/** How many choices a menu holds at most. */
export const MENU_LIMIT = 50

/**
 * `offers` matching `query`: by name prefix, or — only when no name starts that
 * way — by a word in the description. Mixing the two made `/th` offer `/help`
 * for "<b>th</b>is", which is a menu arguing with what was typed.
 */
export function matching(offers: readonly Offer[], query: string, prefix = ''): Offer[] {
    const wanted = query.toLowerCase()
    const named = offers.filter((offer) => offer.name.toLowerCase().startsWith(prefix + wanted))
    if (named.length > 0 || wanted === '') {
        // A name typed in full goes first: `/project` is whole and also the
        // start of `/projects`, and Tab takes the first row.
        const whole = named.filter((offer) => offer.name.toLowerCase() === prefix + wanted)
        return [...whole, ...named.filter((offer) => !whole.includes(offer))].slice(0, MENU_LIMIT)
    }
    return offers.filter((offer) => (offer.detail ?? '').toLowerCase().split(/\W+/u)
        .some((word) => word.startsWith(wanted))).slice(0, MENU_LIMIT)
}

/** The menu this line has with the cursor at `at`, or `undefined` for none. */
export function menuFor(line: string, at: number, vocabulary: Vocabulary): Menu | undefined {
    const before = line.slice(0, at)
    const end = at + (/^\S*/u.exec(line.slice(at))?.[0].length ?? 0)

    // `/the` — a command being typed, and nothing after it yet.
    const command = /^\/(\S*)$/u.exec(before)
    if (command !== null) {
        const choices = matching(vocabulary.commands, command[1] as string, '/')
        return choices.length === 0 ? undefined : { kind: 'command', start: 0, end, choices }
    }

    // `/theme du` — a command that takes an argument, and the argument so far.
    const argument = /^(\/\S+) +(\S*)$/u.exec(before)
    if (argument !== null) {
        const offers = vocabulary.arguments[argument[1] as string]
        if (offers === undefined) {
            return undefined
        }
        const query = argument[2] as string
        const choices = matching(offers(), query)
        return choices.length === 0 ? undefined : {
            kind: 'argument',
            start: at - query.length,
            end,
            choices,
            command: argument[1] as string,
        }
    }

    // `ask @ari` — a mention, starting a word.
    const mention = /(?:^|\s)@(\S*)$/u.exec(before)
    if (mention !== null) {
        const query = mention[1] as string
        const choices = matching(vocabulary.names, query)
        return choices.length === 0 ? undefined : {
            kind: 'mention',
            start: at - query.length - 1,
            end,
            choices,
        }
    }
    return undefined
}

/** What picking `offer` from `menu` does to the line. */
export function picking(
    line: string,
    menu: Menu,
    offer: Offer,
    vocabulary: Vocabulary,
): { readonly typed: string, readonly at: number, readonly complete: boolean } {
    const takesArgument = menu.kind === 'command' && vocabulary.arguments[offer.name] !== undefined
    const leadsOn = isOpenEnded(menu, vocabulary)
    const inserted = menu.kind === 'mention' ? `@${offer.name} `
        : takesArgument || leadsOn ? `${offer.name} `
            : offer.name
    const after = line.slice(menu.end)
    // A choice that ends in a space does not need a second one from what follows.
    const typed = line.slice(0, menu.start) + inserted
        + (inserted.endsWith(' ') ? after.replace(/^ +/u, '') : after)
    return {
        typed,
        at: menu.start + inserted.length,
        // A whole command, or a command and its argument, is something that can
        // be sent as it stands. A mention is part of a sentence.
        complete: (menu.kind === 'argument' && !leadsOn) || (menu.kind === 'command' && !takesArgument),
    }
}
