/**
 * The console's one stylesheet, as a string, mounted as a `<style>` element's
 * `textContent`.
 *
 * A string and not a `.css` import: the module's `tsconfig.json` sets
 * `"types": []` and has no `vite/client` declarations, so an `import
 * './repl.css'` is a type error, and adding an ambient declaration for one
 * file is more moving parts than one constant. This is also the one place in
 * the console where content is assembled as text before something else parses
 * it -- and nothing is interpolated into it, which is what keeps it out of
 * `escape`'s territory. If a value ever does need to reach a rule here, it
 * belongs in a `data-` attribute the CSS selects on, not in this string.
 *
 * Three rows and no more: the header, the scrollback, the prompt. **The
 * scrollback is the only thing that scrolls**, which is what keeps a long tool
 * result from pushing the prompt off the screen -- a body longer than
 * `CLAMP_AT` is clamped behind its own button as well, so a single enormous
 * line cannot make the scrollback itself unreadable.
 *
 * The seven screens beside the REPL are in the second half of the string and
 * follow the same shape: a fixed header, one scrolling body, and nothing else
 * that scrolls. This file stays under `repl/` because it is still *the*
 * stylesheet and a second one mounted beside it would be two sources for the
 * same custom properties; the module that mounts it is `mountStyles`, which
 * every screen reaches through the shell.
 */
export const STYLES = `
/* THE KEY, WRITTEN DOWN, BECAUSE NOTHING RECORDED IT AND TWO COLOURS HAD DRIFTED
   INTO DOING FIVE JOBS EACH.

   Role colours mark WHO SPOKE and nothing else -- that is what an archive is
   about, and it is why this palette is several hues rather than one accent:
     --you       a person's utterance
     --agent     an answer
     --tool      a tool call or its result
     --runtime   this console's own observation about a run
     --refused   a refusal, and every failure a person must act on
     --seam      a fold, and ONLY a fold

   Two were carrying meanings that are not roles, so a reader could not learn
   the palette. Split out, one meaning each:
     --bound     a limit, a cap, a truncation -- "showing 19 of 9000"
     --cite      the id that is the citation, and never the one that is not

   Every property here is declared twice, once per scheme. A value added to one
   block only renders as the other scheme's on half the machines that open this
   page, which is invisible in review and obvious to a person. */
:root {
    color-scheme: light dark;
    --ink: #1a1a1a;
    --paper: #fbfaf7;
    --dim: #6b6b6b;
    --rule: #d9d5cc;
    --you: #1d4ed8;
    --agent: #166534;
    --tool: #92400e;
    --runtime: #6d28d9;
    --refused: #b91c1c;
    --seam: #0f766e;
    --bound: #a16207;
    --cite: #0e7490;

    /* Four sizes and one named weight. There was one of each, so weight was the
       only emphasis this console had and everything read as equally important.
       Named for the job rather than the size, and every one of them is used:
       --step--1 is meta and every note, --step-0 is the body, --step-1 is a
       section heading, --step-2 is the one title a screen has. There is no
       --plain to go with --strong: 400 is what body already computes to and a
       token nothing referenced was a fifth name for the default. */
    --step--1: 12px;
    --step-0: 14px;
    --step-1: 16px;
    --step-2: 24px;
    --strong: 600;
}
@media (prefers-color-scheme: dark) {
    :root {
        --ink: #e6e3dc;
        --paper: #14161a;
        --dim: #9aa0a6;
        --rule: #2c3038;
        --you: #7aa2f7;
        --agent: #7bc47f;
        --tool: #e0a458;
        --runtime: #c4a7f7;
        --refused: #f47272;
        --seam: #5eead4;
        --bound: #d9a441;
        --cite: #5fb3c7;

        --step--1: 12px;
        --step-0: 14px;
        --step-1: 16px;
        --step-2: 24px;
        --strong: 600;
    }
}
html, body { height: 100%; margin: 0; }
/* The size comes back after the shorthand, never inside it: font resets
   font-size, so a rule that set the token first would lose it to its own
   declaration. The literal in the shorthand is the fallback for a browser that
   dropped the custom property, and --step-0 is the scale saying the same 13px
   by name -- which is what makes the token a real step and not a spare. */
body {
    background: var(--paper);
    color: var(--ink);
    font: 14px/1.5 system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-size: var(--step-0);
}
#console { height: 100%; display: flex; flex-direction: column; }
.banner {
    margin: 0; padding: .5rem .9rem; border-bottom: 1px solid var(--rule); color: var(--refused);
}
.host { flex: 1 1 auto; min-height: 0; }
.repl { display: grid; grid-template-rows: auto 1fr auto auto; height: 100%; }

.repl-head {
    display: flex; flex-wrap: wrap; gap: .75rem 1.25rem; align-items: baseline;
    padding: .6rem .9rem; border-bottom: 1px solid var(--rule);
}
.repl-head label { color: var(--dim); }
.repl-head select, .repl-head input, .repl-head button, .prompt button {
    font: inherit; color: inherit; background: transparent;
    border: 1px solid var(--rule); border-radius: 3px; padding: .15rem .4rem;
}
.repl-head button, .prompt button { cursor: pointer; }
.repl-head button[disabled], .prompt button[disabled] { cursor: default; opacity: .5; }
.budget[data-spent="true"] { color: var(--refused); }

.scrollback { overflow-y: auto; padding: .6rem .9rem; }
.entry { display: flex; gap: .75rem; padding: .2rem 0; align-items: baseline; }
.entry .who {
    flex: 0 0 4.5rem; text-align: right; color: var(--dim);
    text-transform: lowercase; user-select: none;
}
.entry .column { min-width: 0; flex: 1 1 auto; }
.entry .body { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
/* The class and not the tag. render.ts puts two elements behind this: a pre for
   text shown verbatim, and a div for an agent's answer read as the markdown it
   was written in. A pre.body selector clamped one of them, and the one it
   stopped clamping is the one most likely to be long enough to need it. */
.entry .body[data-clamped="true"] {
    max-height: 12em; overflow: hidden;
    -webkit-mask-image: linear-gradient(to bottom, #000 70%, transparent);
    mask-image: linear-gradient(to bottom, #000 70%, transparent);
}

/* AN AGENT'S ANSWER, AS THE MARKDOWN IT WAS WRITTEN IN.

   Every rule here is scoped under .md, which render.ts puts only on an answer
   and a fold's summary -- what a person typed and what this console said about
   itself are still shown as the characters they are, and no rule here can reach
   them.

   Quiet on purpose. The gutter and the palette already say who spoke, and a
   heading that shouted would be a model's wording outranking the console's own
   on its own screen: these are structure, not emphasis, which is why a heading
   here is a step-0 in --dim rather than a size of its own. The margins are
   small for the same reason -- this is a scrollback, and a body that breathed
   like a document would push the next line of the conversation off it. */
.entry .md > :first-child { margin-top: 0; }
.entry .md > :last-child { margin-bottom: 0; }
.entry .md p, .entry .md ul, .entry .md ol, .entry .md blockquote, .entry .md pre {
    margin: .4rem 0;
}
.entry .md h3, .entry .md h4, .entry .md h5, .entry .md h6 {
    margin: .6rem 0 .2rem; font-size: var(--step-0);
    font-weight: var(--strong); color: var(--dim);
}
.entry .md ul, .entry .md ol { padding-left: 1.4rem; }
.entry .md li { margin: .1rem 0; }
.entry .md strong { font-weight: var(--strong); }
.entry .md blockquote {
    padding-left: .6rem; border-left: 2px solid var(--seam); color: var(--dim);
}
/* white-space stated rather than left to the UA: .entry .body sets pre-wrap and
   a reader should not have to know which origin wins to know that a fence keeps
   its own columns. It scrolls in its own box, which is the same bargain the
   scrollback makes -- one long line cannot widen the conversation. */
.entry .md pre {
    padding: .3rem .5rem; border: 1px solid var(--rule); border-radius: 3px;
    white-space: pre; overflow-x: auto;
}
.entry .md code {
    padding: 0 .2rem; border-radius: 2px;
    background: color-mix(in srgb, var(--rule) 45%, transparent);
}
.entry .md hr { margin: .6rem 0; border: 0; border-top: 1px solid var(--rule); }
/* Its own rule and not a member of the control group above, which it used to
   be. The group sets color: inherit and this rule set color: var(--dim), so
   one selector was declaring one property twice and the answer was whichever
   came later in the file -- the same accident the palette split fell to. Said
   once, here. */
.entry .more {
    font: inherit; font-size: .9em; color: var(--dim); background: transparent;
    border: 1px solid var(--rule); border-radius: 3px; padding: .15rem .4rem;
    margin-top: .25rem; cursor: pointer;
}
.entry .meta { color: var(--dim); display: flex; gap: 1rem; flex-wrap: wrap; }

.entry[data-role="utterance"] .who { color: var(--you); }
.entry[data-role="utterance"] .body { color: var(--you); }
.entry[data-role="answer"] .who { color: var(--agent); }
.entry[data-role="tool"] .who, .entry[data-role="model"] .who { color: var(--tool); }
.entry[data-role="runtime"] .who, .entry[data-role="runtime"] .body { color: var(--runtime); }
.entry[data-role="refusal"] .who, .entry[data-role="refusal"] .body { color: var(--refused); }
.entry[data-role="seam"] { border-top: 1px dashed var(--seam); margin-top: .5rem; }
.entry[data-role="seam"] .who { color: var(--seam); }
.entry[data-role="seam"] .seam-head { color: var(--seam); }
.entry[data-role="runtime"], .entry[data-role="refusal"] { font-style: italic; }

.run { border-top: 1px solid var(--rule); padding: .4rem .9rem; }
.run[hidden] { display: none; }
.run-head { color: var(--dim); }
/* The two things a person may do about a run that stopped before it answered.
   Beside the run panel rather than in the scrollback, because the scrollback is
   replaced whole from the server on every refresh and a control built into it
   would not survive one. */
.offer { display: flex; gap: .6rem; align-items: baseline; padding-top: .35rem; }
.offer[hidden] { display: none; }
.offer .offer-note { color: var(--dim); }
.offer button {
    font: inherit; color: inherit; background: transparent; cursor: pointer;
    border: 1px solid var(--rule); border-radius: 3px; padding: .15rem .4rem;
}

/* A question a run asked before running a command. Beside the scrollback, for
   the offer's reason: a control built into the scrollback would not survive the
   refresh that replaces it. --runtime because the question is the harness's,
   put on the model's behalf, and not the agent's answer. */
.approvals { border-top: 1px solid var(--rule); padding: .4rem .9rem; }
.approval { border-left: 2px solid var(--runtime); padding: .2rem 0 .3rem .6rem; margin: .3rem 0; }
.approval[data-answered] { opacity: .7; }
.approval-head, .approval-prefix-head { color: var(--runtime); }
.approval-command { margin: .2rem 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.approval-detail { display: flex; gap: .6rem; }
.approval-detail .label { flex: 0 0 4rem; color: var(--dim); }
.approval-detail .value { min-width: 0; overflow-wrap: anywhere; }
.approval-actions, .approval-chips { display: flex; gap: .4rem; flex-wrap: wrap; padding-top: .35rem; }
.approval button {
    font: inherit; color: inherit; background: transparent; cursor: pointer;
    border: 1px solid var(--rule); border-radius: 3px; padding: .15rem .4rem;
}
.approval button:disabled { cursor: default; color: var(--dim); }
.approval-prefix { padding-top: .35rem; }
.approval .chip[data-selected="true"] { border-color: var(--runtime); color: var(--runtime); }
.approval-covers { margin: .25rem 0; }
.approval-note { margin: .3rem 0 0; color: var(--dim); font-style: italic; }
.approval-note[data-approval-note="refused"] { color: var(--refused); }

.prompt {
    display: flex; flex-wrap: wrap; gap: .6rem; align-items: flex-end;
    border-top: 1px solid var(--rule); padding: .6rem .9rem;
}
.prompt textarea {
    flex: 1 1 auto; font: inherit; color: inherit; background: transparent;
    border: 1px solid var(--rule); border-radius: 3px; padding: .35rem .5rem;
    resize: vertical; min-height: 2.6em;
}
.prompt .hint { color: var(--dim); flex-basis: 100%; }
.chat-commands { flex-basis: 100%; min-width: 0; }

/* Workbench navigation keeps primary work visible and secondary views reachable. */

.shell { display: grid; grid-template-columns: 13rem minmax(0, 1fr); height: 100%; min-height: 0; }
.rail {
    display: flex; flex-direction: column; gap: .6rem;
    padding: 1.2rem .75rem; border-right: 1px solid var(--rule); overflow-y: auto;
}
.rail-group, .rail-primary { display: flex; flex-direction: column; gap: .25rem; }
.console-brand { display: flex; flex-direction: column; padding: .25rem .5rem 1.25rem; }
.console-brand strong { font-size: var(--step-1); }
.rail-browse { margin-top: .75rem; }
.rail-browse summary { padding: .5rem; cursor: pointer; color: var(--dim); }
.rail-browse .rail-group { margin-top: .5rem; }
.rail button {
    font: inherit; color: var(--dim); background: transparent; cursor: pointer;
    text-align: left; border: 1px solid transparent; border-radius: 6px; padding: .55rem .65rem; text-transform: capitalize;
}
.rail button[aria-current="page"] { color: var(--you); border-color: var(--rule); background: color-mix(in srgb, var(--you) 8%, var(--paper)); font-weight: var(--strong); }
/* The socket's state, once for the tab. Pushed down and no longer along:
   margin-top rather than the margin-left it carried inside the REPL's header,
   because the rail is a column and this sits at the foot of it. */
.rail-foot { margin-top: auto; }
.stream { color: var(--dim); }
.stream[data-state="reconnecting"], .stream[data-state="closed"] { color: var(--refused); }
.stage { min-height: 0; overflow: hidden; display: flex; }
.stage > * { flex: 1 1 auto; min-width: 0; }

/* Work records remain readable as the browser panel narrows. */
.workbench { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(18rem, 1fr); gap: 1.5rem; align-items: start; }
.work-summaries { display: grid; gap: 1rem; }
.work-inspector { position: sticky; top: 0; border: 1px solid var(--rule); border-radius: 8px; padding: 1rem; min-width: 0; }
.work-inspector h3 { margin-top: 0; }
.work-inspection { overflow-wrap: anywhere; }
.work-row-title { display: block; margin-bottom: .25rem; }
.work-summaries .work-row > .field, .work-summaries .work-row > .record-links { display: none; }
.work-summaries .work-row > .result-preview { max-height: 4.5rem; font-family: inherit; }
.work-row[data-selected="true"] { border-left: 3px solid var(--you); padding-left: .75rem; }
.work-row > .inspect-record { float: right; margin: 0 0 .5rem .5rem; }
.work-summary h3 { margin-bottom: .25rem; }
.work-status { color: var(--dim); }
.work-admissions { margin-top: 1.5rem; }
.work-overview .screen-body { padding: 1.25rem 1.5rem; }
.command-catalog { border-bottom: 1px solid var(--rule); padding-block: .35rem; }
.command-catalog summary { cursor: pointer; }
.command-catalog input, .command-catalog select { font: inherit; color: inherit; background: var(--paper); border: 1px solid var(--rule); max-width: 100%; padding: .3rem; }
.command-cards { max-height: 16rem; overflow-y: auto; display: grid; gap: .5rem; }
.command-card { padding: .5rem; border: 1px solid var(--rule); border-radius: 4px; }
.command-card p { margin: .3rem 0; }
.command-card button { margin-left: .4rem; }
.command-completions { max-height: 12rem; overflow-y: auto; display: grid; }
.command-completions button { text-align: left; padding: .4rem; }
.command-completions button[aria-selected="true"] { border-color: var(--you); }
.trajectory-summary { display: grid; gap: .5rem; margin-block: 1rem; }
.trajectory-step { padding: .65rem .8rem; border: 1px solid var(--rule); border-radius: 6px; min-width: 0; }
.trajectory-step summary { cursor: pointer; font-weight: var(--strong); }
.trajectory-step[data-standing="fail"] { border-left: 3px solid var(--refused); }
.trajectory-step[data-standing="waiting"] { border-left: 3px solid var(--bound); }
.trajectory-step pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 20rem; overflow: auto; }
.trajectory-full > summary { cursor: pointer; color: var(--dim); padding-block: .75rem; }
pre, code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
a:focus-visible, button:focus-visible, summary:focus-visible, input:focus-visible, select:focus-visible, textarea:focus-visible { outline: 2px solid var(--you); outline-offset: 2px; }
.work-summary, .work-admissions { min-width: 0; }
.work-row, .inbox-item { padding: .6rem 0; border-bottom: 1px solid var(--rule); }
.record-links { display: flex; gap: .75rem; flex-wrap: wrap; margin: .4rem 0; }
.record-links a, .work-overview a { color: var(--you); overflow-wrap: anywhere; }
.result-preview, .inbox-answer { white-space: pre-wrap; overflow-wrap: anywhere; }
.result-preview { max-height: 12rem; overflow: auto; }
.approval-rows { display: grid; gap: .75rem; }
.inbox-paging { display: flex; gap: .75rem; margin: .75rem 0; }
.rail button[data-stale]::after { content: " · stale"; }

/* ---- the chat surface: the tree, then the conversation ----------------- */

/* THREE PANES, AND WHY THIS FILE HAD NONE OF THEM UNTIL NOW. chat.ts shipped
   its DOM with every class name here unstyled -- the whole of its layout was
   deferred to a later slice, and in the meantime .chat-surface fell back to
   display: block, which put a full-width sidebar ABOVE the conversation
   rather than beside it. Measured in a browser on 2026-09-08: the sidebar was
   1088px wide and the conversation began 297px down the page.

   The rail is the system, this tree is the conversations in it, and the third
   pane is the one being read. Exactly one region in each pane scrolls, which is
   the rule the rest of this file already keeps. */
.chat-surface {
    display: grid; grid-template-columns: 15rem minmax(0, 1fr);
    height: 100%; min-height: 0;
}
.chat-side {
    display: flex; flex-direction: column; gap: .5rem; min-height: 0;
    overflow-y: auto; padding: .6rem .7rem;
    border-right: 1px solid var(--rule);
}
/* The tab strip is a row above the pane, and the pane takes the rest. A grid
   and not a flex row: the strip and the pane stack, and the two hosts are
   siblings of it with exactly one of them not hidden at a time. */
.chat-stage {
    display: grid; grid-template-rows: auto minmax(0, 1fr);
    min-height: 0; min-width: 0;
}
.chat-stage > .tabs {
    margin: 0; padding: .4rem .9rem; gap: .3rem;
    border-bottom: 1px solid var(--rule);
}
.chat-repl, .chat-read { min-height: 0; min-width: 0; }
.chat-repl[hidden], .chat-read[hidden] { display: none; }

/* The tree. A project is a row that reads as a heading; its conversations are
   indented under it; .children is the slot a conversation's own children will
   hang from once a route exists for them, and must take no space while empty. */
.picker { display: flex; flex-direction: column; gap: .1rem; }
.project-node { display: flex; flex-direction: column; }
.picker .project-open {
    font: inherit; font-size: var(--step-0); color: var(--ink);
    background: transparent; border: 0; border-radius: 3px;
    text-align: left; cursor: pointer; padding: .2rem .3rem;
}
.picker .project-open::before { content: "▸ "; color: var(--dim); }
.picker .conversations { display: flex; flex-direction: column; padding-left: .8rem; }
.picker .conversations:empty { display: none; }
.picker .children:empty { display: none; }
.picker .conversation {
    font: inherit; font-size: var(--step--1); color: var(--dim);
    background: transparent; border: 0; border-left: 1px solid var(--rule);
    border-radius: 0; text-align: left; cursor: pointer;
    padding: .15rem .4rem; overflow-wrap: anywhere;
}
/* The same treatment the rail's current view has, so "where am I" is one
   visual language in both places rather than two. */
.picker .conversation[aria-current] { color: var(--ink); border-left-color: var(--you); }

/* THE PROSE, DEMOTED RATHER THAN DELETED. This console's discipline is that an
   absence states itself rather than being discovered, and that is right: every
   one of these sentences explains something the wire genuinely cannot do. But
   the chat screen opened with nine paragraphs and 1818 characters of them,
   above the conversation, at the same size and weight as the data -- which
   stopped being honesty and became a wall to read past. They are small, dim,
   and beneath the control they qualify. */
.chat-side .note {
    margin: .2rem 0; font-size: var(--step--1); line-height: 1.45;
    color: var(--dim); font-style: normal; max-width: 60ch;
}
.chat-side label { font-size: var(--step--1); color: var(--dim); }
.chat-side .lifecycle-move, .chat-side select, .chat-side input { font-size: var(--step--1); }
.chat-side select { max-width: 100%; min-width: 0; }

/* A section heading, so the tree has a top level the eye can find. */
.chat-side .side-title {
    margin: .4rem 0 0; font: inherit; font-size: var(--step--1);
    font-weight: var(--strong); color: var(--dim);
}
.chat-side .side-title:first-child { margin-top: 0; }

/* The disclosure the notes moved into. Quiet when shut, which is most of the
   time, and no narrower than the prose needs when open.

   Unscoped, because there are two of them: the sidebar's "about this listing"
   and the trajectory's "about this reading, and filtering the page". Scoped to
   .chat-side these rules dressed the first and left the second wearing the
   browser's own disclosure triangle at the body size, which is the loudest
   thing on a screen whose point is that the strip is the loud thing. The
   sidebar's own placement stays scoped, below. */
.about summary {
    font-size: var(--step--1); color: var(--dim); cursor: pointer;
    list-style: none; padding: .2rem 0;
}
.about summary::before { content: "? "; }
.about summary::-webkit-details-marker { display: none; }
.about[open] summary { color: var(--ink); }
/* Pushed to the foot of the sidebar and nowhere else: the trajectory's sits
   between the rows and the economics, where it was put on purpose. */
.chat-side .about { margin-top: auto; padding-top: .6rem; }

/* The scale, applied. Meta recedes; the two titles carry their sizes on their
   own rules further down rather than here, because those rules set the font
   shorthand and a shorthand resets font-size whatever a rule up here says. */
.entry-row .meta, .hit .meta, .entry .meta { font-size: var(--step--1); }

/* EVERY NOTE THIS CONSOLE PRINTS, DEMOTED IN ONE PLACE. They were at the body
   size, so a paragraph explaining why a filter cannot reach past the page had
   the same weight as the page. Each is still there and still says what it said:
   these screens explain their own absences on purpose, and that is right. What
   changed is that an explanation now reads as an explanation. */
.tab-note, .filter-note, .window-note, .mode-note, .leash-note, .mover-note,
.finality, .partial, .documents .note, .projection .note, .prefix-note,
.unavailable-head, .nothing-priced, .reach, .nothing {
    font-size: var(--step--1); line-height: 1.5; max-width: 78ch;
}

/* --bound and --cite are applied ON THE RULES THAT ALREADY DREW THESE THINGS,
   further down this file, and not in a block up here. That block existed and
   did nothing: five of its selectors were character-identical to rules later in
   the sheet, same specificity, and the later ones won -- so the split was
   written down, committed, and never rendered. A second rule for one thing is
   how that happens, so there is one. Follow --bound to .exclusion,
   .entry-row .bound, .documents [data-no-progress] and .memory-summary,
   and --cite to .hit [data-citation] .value. */

/* THE LOG'S RECORDS: a table, because a log is scanned down a column.

   This was a flex row of .field label/value pairs, and it rendered as five
   columns of one-glyph-per-line letters. .field's label is flex: 0 0 8rem and
   will not shrink, so under a shrinking row every value was driven to its
   min-content width -- which overflow-wrap: anywhere makes a single
   character. A component built to stack vertically cannot be laid sideways.

   Fixed columns instead, and the content column takes what is left.
   minmax(0, 1fr) is the load-bearing part: a bare 1fr floors at min-content
   and would push the row wider than its container instead of clipping. */
.record {
    display: grid;
    grid-template-columns: 1.6ch 4ch 4ch 12ch minmax(0, 1fr) 6ch 11ch;
    gap: 0 .9rem; align-items: baseline;
    border-top: 1px solid var(--rule); padding: .25rem 0;
    font-size: var(--step--1); cursor: pointer;
}
.record:first-child { border-top: 0; }
.record .at, .record .turn, .record .size { text-align: right; font-variant-numeric: tabular-nums; }
.record .kind { color: var(--dim); }
/* One line, clipped. Never wrapped: a wrapping cell re-flows every row below it
   and destroys the alignment the table exists for. The whole of it is one click
   away in .full, so nothing here is unreachable. */
.record .content { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.record .state { color: var(--seam); }
/* A record nothing has happened to. Said, and quietly: the column has to carry
   a word on every row to be scannable, and the ordinary case must not shout. */
.record .state[data-stands] { color: var(--dim); }
.record .open-mark { color: var(--dim); user-select: none; }
/* What the record did besides speak. Dim, on the same line, because it is a
   property of the row and not a second row. */
.record .content .aside { color: var(--dim); }
.record .full { display: none; grid-column: 1 / -1; margin: .3rem 0 .2rem; }
.record[data-open] .full { display: block; }
.record[data-open] .content { white-space: normal; overflow: visible; }
.record .full-body {
    margin: 0 0 .3rem; white-space: pre-wrap; overflow-wrap: anywhere;
    max-height: 22rem; overflow-y: auto;
}
.record .full-meta { color: var(--dim); }
.record-head {
    color: var(--dim); cursor: default;
    border-bottom: 1px solid var(--rule); border-top: 0;
}
.record-head .kind, .record-head .state { color: var(--dim); }
/* Dimmed and never hidden, the same treatment the trajectory reading gives a
   superseded row -- the two must not disagree about what a fold did. */
.record[data-superseded-by] { opacity: .72; border-left: 2px solid var(--seam); padding-left: .5rem; }
.record[data-superseded-by] .content { color: var(--seam); }
/* The one place content is genuinely gone. An absence, not an error. */
.record[data-ejected] .content { color: var(--dim); font-style: italic; }

/* THE STRIP: the conversation as a shape, across the page.

   The one loud thing in this console, and deliberately the only one. Everything
   else here is quiet on purpose so that a person scanning for where a
   conversation spent itself has exactly one place to look. Three lanes, one
   column per turn, and the two that carry width are the two this server
   actually times -- an utterance has no duration and its lane is a tick, not a
   bar. */
.strip {
    display: flex; flex-direction: column; gap: 2px;
    margin: .5rem 0 .7rem; padding: .5rem 0;
    border-top: 1px solid var(--rule); border-bottom: 1px solid var(--rule);
}
.lane { display: flex; align-items: center; gap: .6rem; }
.lane-name {
    flex: 0 0 3.5rem; text-align: right;
    font-size: var(--step--1); color: var(--dim);
}
.lane-track { display: flex; gap: 2px; flex: 1 1 auto; min-width: 0; height: 1.4rem; }
.lane[data-lane="input"] .lane-track { height: .5rem; }
.cell { flex: 1 1 0; min-width: 2px; position: relative; background: var(--rule); }

/* A bar grows from the floor of its cell, so a row of them reads as a profile
   rather than as a row of blocks.

   Gated on [data-fill] and not on a substring of the style attribute, which
   is what it was: this file's own header says a value that has to reach a rule
   belongs in a data- attribute the CSS selects on, and a selector sniffing
   the text of style is that rule broken by the sheet that states it. The
   attribute carries the percentage as a number a person can read in the
   inspector; --fill is still the custom property the height is drawn from,
   because a length is what height needs and attr() cannot give it one. */
.cell[data-fill]::after {
    content: ""; position: absolute; left: 0; right: 0; bottom: 0;
    height: var(--fill); background: currentColor;
}
.lane[data-lane="model"] .cell { color: var(--agent); }
.lane[data-lane="tools"] .cell { color: var(--tool); }

/* Marked, never zero-width: nothing counted and counted-and-found-none are two
   different facts, and a bar of no height would say the second about the
   first. */
.cell[data-unmeasured] {
    background: repeating-linear-gradient(
        45deg, var(--rule), var(--rule) 2px, transparent 2px, transparent 4px);
}
.lane[data-lane="input"] .cell { background: transparent; }
.lane[data-lane="input"] .cell[data-mark="spoke"] {
    background: var(--you); border-radius: 1px;
}

/* A TURN, AND WHAT IT TOOK. The trajectory lists turns and not entries: a flat
   list of every row is complete and unreadable, because the utterance that
   started a turn and the eleven rows answering it look alike. The utterance is
   the handle a person scans for, so it is the summary, and the run's own work
   opens under it. */
.turn { border-top: 1px solid var(--rule); }
.turn:first-child { border-top: 0; }
.turn > .turn-head {
    display: flex; gap: .8rem; align-items: baseline;
    padding: .45rem 0; cursor: pointer; list-style: none;
}
.turn > .turn-head::-webkit-details-marker { display: none; }
.turn > .turn-head::before { content: "▸"; color: var(--dim); }
.turn[open] > .turn-head::before { content: "▾"; }
.turn-ordinal { flex: 0 0 4.5rem; color: var(--dim); font-size: var(--step--1); }
.turn-said {
    flex: 1 1 auto; min-width: 0; color: var(--you);
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* Not the utterance itself but a statement about why there is none to show:
   absent from the page, present and ejected, or present and empty. Dimmed and
   italic, the treatment .record[data-ejected] .value already gives the same
   distinction on the log tab -- the two readings must not disagree about it. */
.turn-said[data-said] { color: var(--dim); font-style: italic; }
.turn-took { flex: 0 0 auto; color: var(--bound); font-size: var(--step--1); }
/* A total that could only be added up over part of the turn is not the turn's
   total, and it says so in words as well as in colour. */
.turn-took[data-total="partial"], .turn-took[data-total="none"] { color: var(--dim); }
.turn > .entry-row { margin-left: 1.2rem; }

/* The axis, one per turn. Scaling across a whole page would make every bar in a
   fast turn invisible beside one slow one; the summary carries the turn's total
   so two turns stay comparable by a number even when their bars are not
   comparable by length. */
.step-track {
    display: block; position: relative; height: .4rem; margin: .2rem 0;
    background: var(--rule); border-radius: 1px;
}
.step-bar {
    position: absolute; top: 0; bottom: 0;
    left: var(--at); width: var(--for); min-width: 2px;
    background: currentColor; border-radius: 1px;
}
.step-track[data-for="tool_result"] { color: var(--tool); }
.step-track[data-for="answer"] { color: var(--agent); }
.step-track[data-for="utterance"] { color: var(--you); }
.step-track[data-for="diagnostic"] { color: var(--runtime); }
/* Recorded at a known moment for an unknown length is a mark, not a bar. */
.step-bar[data-instant] { background: var(--dim); }
/* No recorded moment at all is not "at the beginning". */
.step-track[data-unplaced] {
    background: repeating-linear-gradient(
        45deg, var(--rule), var(--rule) 2px, transparent 2px, transparent 4px);
}

/* WHAT THE MODEL IS SHOWN. Once per conversation and not once per turn: the
   system block is the agent's rather than the turn's, so a copy under each turn
   would be noise -- and the route answers what the NEXT prompt would carry, so
   pinning it to an old turn would assert something this server cannot back.
   Shut by default; it is long by nature. */
.projection { margin: 0 0 .6rem; }
/* Which agent this was computed against, above the panel and outside it, so it
   is legible while the panel is shut. Two projections of one conversation are
   only comparable when they name the same agent, and that is the fact this line
   exists to carry. */
.projection-agent { margin: .2rem 0; font-size: var(--step--1); color: var(--dim); }
.projection-agent[data-agent] { color: var(--runtime); }
/* The panel's own prose, which was at the body size inside a panel whose
   summary is --step--1: the demote list up the file names .tab-note and its
   kin and never a bare .note, and .chat-side .note is sidebar-scoped, so this
   one paragraph was drawn louder than the thing it qualifies. */
.projection .note { margin: .2rem 0; color: var(--dim); font-style: italic; }
/* Whether the block at the front of the panel is a recording or today's file.
   Drawn as a distinction and not only as a longer sentence, because the two say
   opposite things about the one message a reader came to check: --runtime for a
   block this turn was actually sent -- the harness speaking about the run -- and
   --bound for one the server is standing in for it, which is the token that
   already means "this answer is qualified" and is what a substituted block is.
   Not --seam, which this used: the key up the file says --seam is a fold and
   ONLY a fold, an unrecorded block is not a fold, and a second meaning loaded
   onto that token is how the palette stopped being learnable the last time. */
.projection .note[data-block="as-sent"] { color: var(--runtime); }
.projection .note[data-block="not-recorded"] { color: var(--bound); }
.projection-panel > summary {
    font-size: var(--step--1); color: var(--dim); cursor: pointer;
    list-style: none; padding: .25rem 0;
}
.projection-panel > summary::before { content: "▸ "; }
.projection-panel[open] > summary::before { content: "▾ "; }
.projection-panel > summary::-webkit-details-marker { display: none; }
.projection-panel[open] > summary { color: var(--ink); }
.shown {
    display: flex; gap: .7rem; align-items: baseline;
    border-top: 1px solid var(--rule); padding: .3rem 0;
}
.shown-role {
    flex: 0 0 5rem; text-align: right; color: var(--dim);
    font-size: var(--step--1);
}
.shown-body {
    margin: 0; min-width: 0; flex: 1 1 auto;
    white-space: pre-wrap; overflow-wrap: anywhere; font-size: var(--step--1);
}
.shown[data-role="system"] .shown-role { color: var(--runtime); }
.shown[data-role="user"] .shown-role { color: var(--you); }
.shown[data-role="assistant"] .shown-role { color: var(--agent); }
.shown[data-role="tool"] .shown-role { color: var(--tool); }

/* The two kinds that rendered unlegended, so a runtime note read as ordinary. */
.entry-row[data-kind="runtime_note"] .entry-head { color: var(--runtime); }
.entry-row[data-kind="plan"] .entry-head { color: var(--cite); }

.screen { display: grid; grid-template-rows: auto minmax(0, 1fr); height: 100%; min-height: 0; }
.screen-head {
    display: flex; gap: .75rem 1.25rem; align-items: baseline; flex-wrap: wrap;
    padding: .6rem .9rem; border-bottom: 1px solid var(--rule);
}
/* The size AFTER the shorthand. font: inherit resets font-size, so the
   scale has to be re-stated here and cannot be set on a rule of its own
   somewhere above -- which is what it was, at equal specificity and earlier, so
   the title computed to the body size. --step-2 is the one big thing a screen
   has. */
.screen-title {
    margin: 0; font: inherit; font-size: var(--step-2);
    font-weight: var(--strong); text-transform: lowercase;
}
.screen-head button, .screen-body button {
    font: inherit; color: inherit; background: transparent; cursor: pointer;
    border: 1px solid var(--rule); border-radius: 3px; padding: .15rem .4rem;
}
.screen-head button[disabled], .screen-body button[disabled] {
    cursor: default; opacity: .5;
}
.screen-head input, .screen-body input, .screen-body textarea {
    font: inherit; color: inherit; background: transparent;
    border: 1px solid var(--rule); border-radius: 3px; padding: .15rem .4rem;
}
.screen-head label, .screen-body label { color: var(--dim); }
.screen-body { overflow-y: auto; padding: .6rem .9rem; }

.nothing { margin: .6rem 0; color: var(--dim); font-style: italic; }
.trouble { margin: .4rem 0; color: var(--refused); }
.field { display: flex; gap: .6rem; }
.field .label { flex: 0 0 8rem; text-align: right; color: var(--dim); }
.field .value { min-width: 0; overflow-wrap: anywhere; }

.project, .job, .proposal, .memory, .setting {
    border-top: 1px solid var(--rule); padding: .6rem 0;
}
.project:first-child, .job:first-child, .proposal:first-child, .memory:first-child,
.setting:first-child {
    border-top: 0;
}
.project-name, .memory-summary, .proposal-head, .setting-key {
    margin: 0 0 .3rem; font: inherit; font-weight: 600;
}
.leash { margin: .4rem 0 .4rem 8.6rem; }
.leash-head { color: var(--dim); }
/* Commands a person allowed for the whole project: standing, and revocable. */
.approved { margin: .4rem 0 .4rem 8.6rem; }
.approved-head { color: var(--dim); }
.approved-list { margin: .2rem 0; padding-left: 1.1rem; }
.approved-item { display: flex; gap: .6rem; align-items: baseline; flex-wrap: wrap; }
.approved-prefix { overflow-wrap: anywhere; }
.approved-side { color: var(--dim); }
.exclusions { margin: .2rem 0; padding-left: 1.1rem; }
/* --bound and not --tool: an exclusion is a limit on what a workspace reaches,
   which is a cap and not a tool call. */
.exclusion { overflow-wrap: anywhere; color: var(--bound); }
.leash-note, .mover-note, .finality, .partial, .config-note {
    margin: .3rem 0; color: var(--dim); font-style: italic;
}
/* Not dim like the notes above: a pinned key's write is undone at the next
   boot, which is the one fact on this row a person must not skim past. */
.pinned-note { margin: .3rem 0 0 8.6rem; color: var(--tool); }
.mover, .editor { margin: .4rem 0 0 8.6rem; display: flex; gap: .6rem; flex-wrap: wrap; }
.mover input, .editor input { min-width: 22rem; }

.job-head { display: flex; gap: .75rem; align-items: baseline; flex-wrap: wrap; }
.job[data-state="RUNNING"] .state { color: var(--agent); }
.job .cancel-requested { color: var(--refused); }
.job .ending { color: var(--dim); }
.job .body { white-space: pre-wrap; overflow-wrap: anywhere; }
.window-note { color: var(--dim); font-style: italic; margin: 0 0 .5rem; }

.proposal .finality { color: var(--refused); font-style: normal; }
.proposal .rulings { display: flex; gap: .6rem; margin-top: .4rem; flex-wrap: wrap; }
.proposal .body, .memory .body { margin: .2rem 0; white-space: pre-wrap;
    overflow-wrap: anywhere; }

/* --bound: a memory nothing can search is a limit on the recall, not a tool. */
.memory[data-unsearchable="true"] .memory-summary { color: var(--bound); }
.memory .scope { color: var(--dim); }
.recall { display: flex; gap: .6rem; align-items: baseline; flex-wrap: wrap; }
.recall input { min-width: 20rem; }

/* ---- the trajectory screen: two readings of one conversation ----------- */

/* The tabs are the projection/log split and are drawn as a split: the current
   one is the only one that carries the ink colour, and the note under them
   says which reading is on screen rather than leaving it to the word alone. */
.tabs { display: flex; gap: .4rem; margin-bottom: .3rem; }
.tabs .tab { color: var(--dim); border-color: transparent; }
.tabs .tab[aria-current="page"] { color: var(--ink); border-color: var(--rule); }
.tab-note, .filter-note { margin: .2rem 0 .5rem; color: var(--dim); font-style: italic; }
.filters { display: flex; gap: .6rem; align-items: baseline; flex-wrap: wrap; }
.filters input { min-width: 16rem; }
.filters .filter-note { flex-basis: 100%; }
.window { display: flex; gap: .6rem; align-items: baseline; flex-wrap: wrap;
    border-top: 1px solid var(--rule); padding-top: .4rem; }
.window .window-note { flex: 1 1 auto; margin: 0; color: var(--dim); }

.entry-row { border-top: 1px solid var(--rule); padding: .5rem 0; }
.entry-row:first-child { border-top: 0; }
.entry-head { font-weight: 600; }
.entry-row .meta, .entry-row .answers, .entry-row .handle { color: var(--dim); }
.entry-row .body { margin: .2rem 0; white-space: pre-wrap; overflow-wrap: anywhere; }
/* A bound is drawn in its own colour rather than as small print: "showing 19 of
   9000" is the sentence that stops a page being read as a conversation. --bound
   and not --tool, which is what it was: a cut is a limit and the colour has to
   mean one thing, or a reader cannot learn the palette. */
.entry-row .bound, .call .bound { margin: .15rem 0; color: var(--bound); }
.entry-row[data-kind="diagnostic"] .entry-head { color: var(--runtime); }
.entry-row[data-kind="attempt_failed"] .entry-head { color: var(--refused); }
.entry-row[data-kind="refusal"] .entry-head { color: var(--refused); }
.entry-row[data-kind="summary"] .entry-head { color: var(--seam); }
.entry-row[data-kind="utterance"] .entry-head { color: var(--you); }
.entry-row[data-kind="answer"] .entry-head { color: var(--agent); }
.entry-row[data-kind="tool_result"] .entry-head { color: var(--tool); }
/* Which model answered. Dim like the rest of an entry's metadata, so it does
   not compete with the body -- except a fallback, which is the one a reader is
   scanning for and is coloured like the refusal it followed. */
.entry-row .provenance { color: var(--dim); font-size: var(--step--1); }
.entry-row[data-dispatch="fallback"] .provenance { color: var(--refused); }
/* The log table's aside for a fallback, in the one column a reader scans. */
.record[data-dispatch="fallback"] .content .aside { color: var(--refused); }
/* Superseded is dimmed and never hidden: this is the reading that shows what
   the fold took out, so a row it covered has to be legible and marked. */
.entry-row[data-superseded] { opacity: .72; border-left: 2px solid var(--seam);
    padding-left: .5rem; }
.entry-row .superseded { margin: .15rem 0; color: var(--seam); }
.entry-row .ejected { margin: .15rem 0; color: var(--refused); }
.call { margin: .3rem 0 .3rem 1rem; border-left: 1px solid var(--rule);
    padding-left: .6rem; }
.call-head { color: var(--tool); }

.economics { border-top: 1px solid var(--rule); margin-top: .8rem; padding-top: .6rem; }
.economics-title { margin: 0 0 .3rem; font: inherit; font-weight: 600;
    text-transform: lowercase; }
.economics .measured { color: var(--agent); }
.unavailable-head, .prefix-note, .nothing-priced {
    margin: .5rem 0 .3rem; color: var(--dim); font-style: italic;
}
/* The absence is a row with a slot in it, because a client rendering the
   reference layout has to find the slot, read nothing in it, and say so. */
.unavailable { display: block; margin: .3rem 0; }
.unavailable .label { display: inline-block; width: auto; text-align: left;
    color: var(--dim); }
.unavailable .value { color: var(--refused); margin-left: .6rem; }
.unavailable .reason { margin: .1rem 0 0; color: var(--dim); }
.prefix { margin: .3rem 0; }
.prefix-head { color: var(--tool); }
.tool-costs { margin: .2rem 0; padding-left: 1.1rem; }
.tool-cost { color: var(--dim); }

/* ---- the chat view's sidebar: the pick, and what it is honest about ---- */

/* The notes themselves are ruled once, further up, beside the disclosure they
   moved into. There was a second .chat-side .note here, carried over from the
   deleted sessions screen, and being later in the file it silently won: the
   demotion up there set font-style: normal and a .2rem margin and this one
   put both back. Three of those notes say what this listing is NOT -- the state
   is the listing's and not the row's, the origin filter is not a parameter, the
   tree has no endpoint -- and they are still on the page, in the disclosure,
   which is where the size and the weight are decided. */
/* Empty until something goes wrong, or a move lands. Collapsed when empty so
   the sidebar does not carry a permanent gap where a sentence sometimes is. */
.chat-side .complaint:empty { display: none; }

/* The rest of that block is gone rather than re-aimed. The .conversation rules
   still matched something -- picker.ts names its buttons that -- but they were
   written for a row of the deleted listing, and a border-top with block padding
   on a sidebar button is a leftover styling something it was never about. The
   picker keeps the class, which is the honest name for what those buttons are
   and the only place in the console using it now; what it does not keep is a
   dead screen's idea of how a row should look. Stage 4 dresses this view. */

/* ---- the documents screen: the corpus, in and out ---------------------- */

.documents .screen-body { display: grid; gap: 1rem;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); align-items: start; }
/* Size after the shorthand, for .screen-title's reason. --step-1 sits between
   the screen's title and the body, which is what a section heading is. */
.section-title { margin: 0 0 .4rem; font: inherit; font-size: var(--step-1);
    font-weight: var(--strong); text-transform: lowercase; }
.documents label { display: block; margin: .25rem 0; }
.documents .note { margin: .4rem 0; color: var(--dim); font-style: italic; }
/* The one note that is not small print: it stands where a progress bar would
   have been, and it is the reason there is not one. --bound, because that is
   what it says -- there is no progress to report and this is the limit. */
.documents [data-no-progress] { color: var(--bound); font-style: normal; }
.ingest { border-top: 1px solid var(--rule); padding: .5rem 0; }
.ingest:first-child { border-top: 0; }
.ingest-head { font-weight: 600; }
.ingest[data-state="RUNNING"] .ingest-head { color: var(--agent); }
.ingest .running-note { color: var(--dim); }
.ingest .cancel-requested { color: var(--refused); }
.ingest .body { margin: .2rem 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.documents .complaint:empty { display: none; }

.hit { border-top: 1px solid var(--rule); padding: .5rem 0; }
.hit:first-child { border-top: 0; }
.hit-head { font-weight: 600; }
.hit .meta { color: var(--dim); }
.hit .body { margin: .2rem 0; white-space: pre-wrap; overflow-wrap: anywhere; }
/* The chunk is drawn below the paragraph and dimmer than it: what matched is
   worth seeing and is not the thing to cite, and the type is where that shows. */
.hit .chunk { margin: .2rem 0; color: var(--dim); white-space: pre-wrap;
    overflow-wrap: anywhere; }
/* --cite and not --seam: the id that is the citation is not a fold, and --seam
   means a fold and only a fold. */
.hit [data-citation] .value { color: var(--cite); }
.application-files { margin-block: 1rem; }
.application-source { margin-block: .8rem; }
.application-source input { max-width: 100%; }
.application-source button { margin: .25rem; max-width: 100%; overflow-wrap: anywhere; }
.application-entries { display: flex; flex-wrap: wrap; gap: .3rem; margin-block: .8rem; }
.application-editor textarea { width: 100%; min-height: 20rem; resize: vertical; font: inherit; tab-size: 2; }
.application-file-title, .application-permission { overflow-wrap: anywhere; }
.mode-note, .reach { margin: .2rem 0; color: var(--dim); }
@media (max-width: 720px) {
  .shell { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(0, 1fr); }
  .rail { flex-direction: row; flex-wrap: wrap; gap: .25rem; border-right: 0; border-bottom: 1px solid var(--rule); }
  .rail-primary { flex-direction: row; flex-wrap: wrap; flex-basis: 100%; }
  .rail-group { display: flex; }
  .console-brand { flex-direction: row; gap: .5rem; padding: 0 .5rem; align-items: baseline; }
  .rail-browse { margin-top: 0; }
  .rail-browse[open] { flex-basis: 100%; }
  .workbench { grid-template-columns: minmax(0, 1fr); }
  .work-inspector { position: static; }
  .work-overview .screen-body { padding: .75rem; }
  .rail-foot { flex-basis: 100%; margin-top: 0; }
  .chat-surface { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(0, 1fr); }
  .chat-side { max-height: 25vh; border-right: 0; border-bottom: 1px solid var(--rule); }
  .field { flex-wrap: wrap; }
  .jobs .field .label, .pending-approvals .field .label, .work-overview .field .label {
    flex-basis: 100%; text-align: left;
  }
}

`;

/**
 * Put the stylesheet on the page once.
 *
 * Keyed on an id rather than on a module-level flag, so that a second `mount`
 * -- a test building a second REPL into the same document -- does not
 * accumulate copies.
 */
export function mountStyles(doc: Document = document): void {
  const id = 'plowshare-console-styles';
  if (doc.getElementById(id) !== null) {
    return;
  }
  const style = doc.createElement('style');
  style.id = id;
  style.textContent = STYLES;
  doc.head.appendChild(style);
}
