import { array, line, number, quote, record, text } from './values.js'
function unit(value: unknown): string {
    if (value == null) return '(in no section)'
    const row = record(value)
    return row['synthetic'] || !line(row['title']) ? '(unnamed segment)' : line(row['title'])
}
function score(value: unknown): string { return number(value).toFixed(2) }
export function searched(value: unknown, limit: number | undefined): string {
    const row = record(value), hits = array(row['hits']).map(record), searchable = number(row['searchable']), unsearchable = number(row['unsearchable'])
    let out: string
    if (!hits.length) {
        if (searchable > 0) out = 'Nothing found — no passage in the corpus is close to that question. (no passages)\n\nSearch matches on meaning, so a differently framed question can still find something.'
        else if (!unsearchable) out = 'The corpus is empty: no document has been ingested, so there is nothing to search. (no passages)\n\nThis says nothing about the question. Documents are uploaded to POST /v1/documents; this surface reads them and does not add one.'
        else out = `Nothing in the corpus can be searched. (no passages)\n\nAll ${unsearchable} passage(s) hold their text and no vector, which is what an ingest leaves behind when the embedding endpoint could not be reached. Ingesting those documents again embeds them and re-derives nothing. This says nothing about the question.`
    } else {
        out = `${hits.length}${hits.length === 1 ? ' passage' : ' passages'} of the corpus, best first, out of ${searchable} that can be searched. Each one names the paragraph to cite it by. It also names the document that paragraph is in: that id is not a citation — it is what document_ask takes.\n`
        for (const hit of hits) out += `\n${line(hit['sourceName'])} — paragraph ${number(hit['paragraphOrdinal'])} of "${line(hit['title'])}" — similarity ${score(hit['similarity'])}\ncite paragraph ${text(hit['paragraphId'])}\nask document ${text(hit['documentId'])}\n${quote(hit['text'], false)}\n`
    }
    if (limit !== undefined && limit > number(row['limit'])) out += `\n\nYou asked for ${limit}; the server returned ${row['limit']}, which is the most it gives at once.`
    if (unsearchable && searchable) out += `\n\n${unsearchable} passage(s) of the corpus could not be searched at all: they hold their text and no vector, so no question reaches them however it is phrased. Ingesting those documents again embeds them.`
    return out
}
export function citations(value: unknown): string {
    const row = record(value), citations = array(row['citations']).map(record)
    if (!citations.length) return (row['scope'] === 'conversation' ? 'That conversation has cited nothing.' : row['scope'] === 'document' ? 'Nothing has cited that document.' : 'Nothing in this corpus has been cited yet.') + ' (no citations)\n\nThis is a record of past answers and says nothing about what the documents contain: an uncited corpus and an empty one look the same here and are not the same thing. document_search is what asks the documents.'
    let out = `${citations.length}${citations.length === 1 ? ' citation' : ' citations'}, ${row['scope'] === 'conversation' ? 'in the order this conversation made them' : 'most recently made first'}. A citation is what an answer said it took from the corpus, not what a search returned.\n`
    for (const cited of citations) {
        out += `\n${line(cited['sourceName'])} — paragraph ${number(cited['paragraphOrdinal'])}${cited['title'] == null ? '' : ' of "' + line(cited['title']) + '"'} — cited by ${line(cited['agent'])} on ${cited['citedAt']}`
        if (cited['standing'] === 'resolves') out += `\ncite paragraph ${text(cited['paragraphId'])}\n${quote(cited['paragraphText'] ?? '', false)}`
        else if (cited['standing'] === 'paragraph_gone') out += '\nSTALE: a later ingest of this document edited or removed that paragraph, so there are no words to show. The document is still in the corpus; search it again for what it says now.'
        else if (cited['standing'] === 'document_gone') out += '\nSTALE: that document is no longer in the corpus, so there are no words to show and nothing to search.'
        else out += '\nCitation standing is unrecognized: ' + line(cited['standing']) + '. No paragraph text is presented as evidence.'
        out += '\n'
    }
    return out
}
export function asked(value: unknown, document: string): string {
    const row = record(value)
    return `Started ${line(row['id'])}, a deliberation over document ${line(document)}.\n\nThree agents run in series on the server and this call did not wait for them: a proposer drafts an answer from the document's whole structure and its most relevant passages, a critic challenges that draft from what the document argues as a whole and nothing below it, and a synthesiser writes the final answer. Ask agent_poll whether it has finished, then agent_result for the answer.\n\nThe answer names the paragraph and quotes the words each claim rests on, and every quotation is checked against the paragraph it names — so an attribution that failed is reported in the answer rather than dropped.`
}
export function retrieved(value: unknown, question: string): string {
    const row = record(value), hits = array(row['hits']).map(record), scope = row['document'] == null ? 'the whole corpus' : 'document ' + line(row['document'])
    if (!hits.length) return `Nothing in ${scope} is close to: ${line(question)}\n\nThat is an answer about this corpus and not about the question. document_list says what the corpus holds; a passage stored while the embedding endpoint was down holds its text and no vector, and document_search reports how many of those there are.`
    let out = `${hits.length}${hits.length === 1 ? ' passage from ' : ' passages from '}${scope} for: ${line(question)}`
    for (const hit of hits) {
        const chunk = record(hit['chunk'])
        out += `\n\n${line(chunk['sourceName'])} — "${line(chunk['title'])}" — paragraph ${number(chunk['paragraphOrdinal'])} — similarity ${score(hit['score'])}\ncite paragraph ${text(chunk['paragraphId'])}`
        if (chunk['documentSummary'] != null) out += '\nthe document argues: ' + line(chunk['documentSummary'])
        if (chunk['chapter'] != null) { const chapter = record(chunk['chapter']); out += '\nin ' + unit(chapter); if (chapter['summary'] != null) out += ': ' + line(chapter['summary']) }
        out += '\nin ' + unit(chunk['section'])
        if (chunk['section'] != null && record(chunk['section'])['summary'] != null) out += ': ' + line(record(chunk['section'])['summary'])
        if (chunk['paragraphSummary'] != null) out += '\nthe paragraph claims: ' + line(chunk['paragraphSummary'])
        out += '\n' + quote(chunk['text'])
    }
    return out
}
export function listed(value: unknown): string {
    const page = record(value), rows = array(page['documents']).map(record), total = number(page['total'])
    if (!rows.length) return page['naming'] == null ? 'The corpus holds no documents.' : `No document in the corpus is named anything like: ${line(page['naming'])}\n\nThe corpus holds ${total} that match that and some that do not; call this again without a naming to see everything.`
    let out = `${rows.length} of ${total}${page['naming'] == null ? '' : ' named like ' + line(page['naming'])}${total > rows.length ? ' — ask again with a larger limit or an offset for the rest.' : ' — that is all of them.'}`
    for (const row of rows) out += `\n\n${line(row['sourceName'])} — "${line(row['title'])}"\nid ${text(row['documentId'])}\n${number(row['paragraphs'])} paragraphs in ${number(row['sections'])} sections, ${number(row['chapters'])} chapters, ${number(row['chunks'])} searchable passages` + (row['summary'] == null ? '\nnothing has summarised it yet, so an ask over it has no macro evidence to reason from.' : '\nit argues: ' + line(row['summary']))
    return out
}
export function ranked(value: unknown, question: string): string {
    const row = record(value), rows = array(row['documents']).map(record), rankable = number(row['rankable']), unranked = number(row['unranked'])
    const how = !unranked ? `${rankable} documents in the corpus could be ranked.` : `${rankable} documents could be ranked and ${unranked} have a summary that has not been embedded yet, so they were not compared whatever the question was.`
    if (!rows.length) return `No document in the corpus is about: ${line(question)}\n\n${how}\n\nThat is an answer about this corpus and not about the question. document_list says what is here.`
    let out = `${rows.length}${rows.length === 1 ? ' document' : ' documents'} for: ${line(question)}\n${how}`
    for (const row of rows) out += `\n\n${line(row['sourceName'])} — "${line(row['title'])}" — relevance ${score(row['score'])}\nid ${text(row['documentId'])}${row['summary'] == null ? '' : '\nit argues: ' + line(row['summary'])}`
    return out
}
export function outline(value: unknown): string {
    const row = record(value), chapters = array(row['chapters']).map(record)
    let out = `${line(row['sourceName'])} — "${line(row['title'])}"${row['summary'] == null ? '' : '\nit argues: ' + line(row['summary'])}\n\n${chapters.length} ${row['vocabulary'] == null ? 'chapters' : text(row['vocabulary']).toLowerCase() + 's'}:`
    for (const chapter of chapters) {
        out += '\n\n' + unit(chapter)
        if (chapter['summary'] != null) out += '\n  ' + line(chapter['summary'])
        for (const value of array(chapter['sections'])) { const section = record(value); out += '\n  - ' + unit(section); if (section['summary'] != null) out += '\n      ' + line(section['summary']) }
    }
    return out
}
