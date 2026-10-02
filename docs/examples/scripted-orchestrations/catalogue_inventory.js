// plowshare-script v1
// Complete example for docs/scripted-orchestrations.md. No model call or document mutation.
export const manifest = {
  name: 'catalogue_inventory',
  description: 'Inspect the first page of the current scoped information catalogue.',
  model: 'fast', tools: ['information_read'], calls: [], scopes: [],
  stages: [{id: 'inspect', 'done-when': 'A catalogue page has been checked.'}],
  'max-turns': 8, 'max-model-calls': 1
};

function status(todo) { return String(todo.status).toLowerCase(); }
function emit(state, pending, tool, args) {
  state.pending = pending;
  return {state, command: {tool, arguments: args}};
}

export function step(input) {
  const state = input.state || {pending: null, entered: false, page: null, done: false};
  const todo = input.todos.find(item => item.stageId === 'inspect');
  if (!todo) throw new Error('Missing seeded inspect stage');

  // Consume only the result belonging to the saved continuation.
  if (state.pending === 'enter') {
    if (status(todo) !== 'in_progress') throw new Error('Stage entry was refused');
    state.entered = true;
  } else if (state.pending === 'catalogue') {
    let page;
    try { page = JSON.parse(input.result); }
    catch (_) { throw new Error('Catalogue result is not JSON; inspect the retained refusal'); }
    if (!Array.isArray(page) || page.some(row => !row || typeof row.id !== 'string')) {
      throw new Error('Catalogue result is not a revision page');
    }
    state.page = page.map(row => ({revision: row.id, name: row.source_name || row.title || row.id}));
  } else if (state.pending === 'exit') {
    if (status(todo) !== 'done') throw new Error('Stage completion was refused');
    state.done = true;
  }
  state.pending = null;

  if (!state.entered) {
    return emit(state, 'enter', 'todo_write', {
      ops: [{op: 'update', id: todo.id, status: 'in_progress'}]
    });
  }
  if (state.page === null) {
    return emit(state, 'catalogue', 'information_read', {operation: 'list', offset: 0, limit: 20});
  }
  if (!state.done) {
    return emit(state, 'exit', 'todo_write', {
      ops: [{op: 'update', id: todo.id, status: 'done', summary: state.page.length + ' revisions inspected.'}]
    });
  }
  return emit(state, 'finished', 'orchestration_finish', {
    result: 'First catalogue page (' + state.page.length + ' revisions):\n' + JSON.stringify(state.page, null, 2)
  });
}
