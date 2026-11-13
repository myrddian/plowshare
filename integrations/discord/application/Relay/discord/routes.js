// The principal and client alias come from authenticated admission, never the routing body.
export const manifest = {
  version: 1,
  subscriptions: [
    {name: 'requests', topic: 'discord.requests', kind: 'text', start: 'oldest-retained'}
  ]
};
export function route(event) {
  const value = JSON.parse(event.payload.text);
  let receiver;
  switch (value.schema) {
    case 'plowshare-topic-seed/1': return [];
    case 'plowshare-message-request/1': receiver = 'message.receive'; break;
    case 'plowshare-message-inspect/1': receiver = 'message.inspect'; break;
    case 'plowshare-message-cancel/1': receiver = 'message.cancel'; break;
    default: throw new Error('Unsupported Discord messaging schema');
  }
  return [{name: 'message', receiver, work: {
    agent: 'discord_assistant', replyTopic: 'discord.replies', channel: 'discord'
  }}];
}
