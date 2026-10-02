import { createServer } from 'node:http';

// Deterministic local providers. No endpoint forwards to an inference/search host.
const models = ['mlx-community/gemma-4-e4b-it', 'openai/gpt-oss-120b', 'nomic-embed-text'];
const hits = Array.from({ length: 5 }, (_, index) => ({
  url: `https://fixture.invalid/result/${index + 1}`,
  title: `CI result ${index + 1}`, content: `Deterministic fixture result ${index + 1}`,
}));
createServer(async (request, response) => {
  const url = new URL(request.url, 'http://fixture');
  let body = '';
  for await (const bytes of request) body += bytes;
  const data = body ? JSON.parse(body) : {};
  let answer;
  if (url.pathname === '/health') answer = { status: 'ok' };
  else if (url.pathname === '/v1/models') answer = { object: 'list', data: models.map(id => ({ id, object: 'model' })) };
  else if (url.pathname === '/search') {
    if (url.searchParams.get('format') !== 'json') { response.writeHead(400); response.end(); return; }
    answer = { query: url.searchParams.get('q'), results: hits, unresponsive_engines: [] };
  } else if (url.pathname === '/v1/embeddings') {
    const inputs = Array.isArray(data.input) ? data.input : [data.input];
    answer = { object: 'list', model: data.model, data: inputs.map((_, index) => ({
      index, object: 'embedding', embedding: Array(768).fill(0.01),
    })), usage: { prompt_tokens: 1, total_tokens: 1 } };
  } else if (url.pathname === '/v1/chat/completions') {
    answer = { id: 'ci-fixture', object: 'chat.completion', model: data.model,
      choices: [{ index: 0, message: { role: 'assistant', content: 'CI fixture' }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } };
  } else { response.writeHead(404); response.end(); return; }
  response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(answer));
}).listen(8080, '0.0.0.0');
