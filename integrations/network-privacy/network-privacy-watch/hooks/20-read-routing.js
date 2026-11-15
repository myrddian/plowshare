// Recommendations only: no IO, tool calls, changes to grants or retained state.
// Match the original question on each model request. Volatile additions avoid
// accumulating routing notes in a long-lived Ask conversation. User text is
// tested as data and is never copied into the trusted guidance.
const reports = /\b(reports?|findings?|investigations?|assessments?)\b/i;
const references =
  /\b(documentation|manuals?|defaults?|settings?|configur(?:e[ds]?|ation|ing)|fix(?:es|ed|ing)?|errors?|troubleshoot(?:ing)?)\b/i;
const scans = /\b(scans?|probes?|evidence|dns)\b/i;
const coverage =
  /\b(identity_(?:stale|unavailable|ambiguous)|dns_(?:truncated|partial|unavailable)|coverage|freshness)\b/i;

export default {
  name: 'privacy-read-routing',
  stages: {
    'prompt.pre': {
      handle(event) {
        if (event.context.agent !== 'privacy_assistant') return undefined;

        const question = event.utterance;
        const notes = [];
        // A report request wins over scan keywords: reports often mention scans.
        if (reports.test(question)) {
          notes.push(
            "Suggested read path: for existing reports or findings, use skill_run with information-discovery and the user's question in this project. Ask for the latest matching report including drafts, its retained text, revision, date and review status. A scan summary is not a report. Reuse any report already read for this question; if none is found, say so. This recommendation does not request a new investigation.",
          );
        } else if (references.test(question)) {
          notes.push(
            'Suggested read path: use the information-discovery skill to locate and read vendor references before giving product defaults, settings or repair advice. Discover actual device-reference product tags, cite supporting passages and distinguish an unavailable reference from a verified answer. Reuse sources already read for this question.',
          );
        } else if (scans.test(question) || coverage.test(question)) {
          notes.push(
            'Suggested read path: for existing scan observations, use network_scan_list and network_evidence. Read the relevant sections; issues_complete=false requires section issues. Counts are records, not confirmed traffic connections or a count of physical devices. A baseline marker is not an observed change. This recommendation does not request a new scan.',
          );
        }

        if (coverage.test(question)) {
          notes.push(
            'Coverage reminder: a collector coverage flag alone does not establish a Pi-hole fault, a changed DHCP lease, a configured freshness threshold or a remedy. Read the evidence, distinguish collection limits from product behaviour and use vendor references for product advice.',
          );
        }
        if (notes.length === 0) return undefined;
        return { add: notes.join('\n\n'), mode: 'volatile' };
      },
    },
  },
};
