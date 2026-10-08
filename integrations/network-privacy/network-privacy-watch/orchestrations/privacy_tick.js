// plowshare-script v1
// The occurrence is already in schedule.due. This native action finishes without inference.
export const manifest = {
  name: 'privacy_tick',
  description: 'Records that the scheduled occurrence is available to the Python collector.',
  model: 'reasoning', tools: [], calls: [], scopes: [],
  stages: [{id: 'available', 'done-when': 'The scheduler occurrence has been recorded.'}],
  'max-turns': 8, 'max-model-calls': 1
};

export function step(input) {
  const todo = input.todos.find(item => item.stageId === 'available');
  if (!todo) throw new Error('Missing available stage');
  const status = String(todo.status).toLowerCase();
  if (status === 'pending') return {state: null, command: {tool: 'todo_write', arguments: {ops: [{op: 'update', id: todo.id, status: 'in_progress'}]}}};
  if (status === 'in_progress') return {state: null, command: {tool: 'todo_write', arguments: {ops: [{op: 'update', id: todo.id, status: 'done', summary: 'Occurrence available through Relay; collection has not completed.'}]}}};
  if (status !== 'done') throw new Error('Stage transition was refused');
  return {state: null, command: {tool: 'orchestration_finish', arguments: {result: 'Occurrence available through Relay. Inspect the Python collector and its evidence separately.'}}};
}
