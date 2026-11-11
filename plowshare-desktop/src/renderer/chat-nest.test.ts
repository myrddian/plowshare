import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  conversationsForProject,
  mountChat,
  restoreChat,
} from './chat-nest.ts';

type NestNode = {
  parentElement: NestNode | null;
  children: NestNode[];
  classList: { add(name: string): void; remove(name: string): void };
  classes: Set<string>;
  append(...nodes: NestNode[]): void;
};

function node(): NestNode {
  const self: NestNode = {
    parentElement: null,
    children: [],
    classes: new Set(),
    classList: {
      add(name) {
        self.classes.add(name);
      },
      remove(name) {
        self.classes.delete(name);
      },
    },
    append(...nodes) {
      for (const child of nodes) {
        if (child.parentElement) {
          const siblings = child.parentElement.children;
          const index = siblings.indexOf(child);
          if (index >= 0) siblings.splice(index, 1);
        }
        child.parentElement = self;
        self.children.push(child);
      }
    },
  };
  return self;
}

await test('mountChat moves the chat into the nest and marks it nested', () => {
  const home = node();
  const slot = node();
  const chat = node();
  home.append(chat);
  mountChat(chat, slot);
  assert.equal(chat.parentElement, slot);
  assert.equal(home.children.includes(chat), false);
  assert.equal(chat.classes.has('application-nested'), true);
});

await test('restoreChat returns the chat to its home host', () => {
  const home = node();
  const slot = node();
  const chat = node();
  home.append(chat);
  mountChat(chat, slot);
  restoreChat(chat, home);
  assert.equal(chat.parentElement, home);
  assert.equal(slot.children.includes(chat), false);
  assert.equal(chat.classes.has('application-nested'), false);
});

await test('conversationsForProject keeps only that Application scope', () => {
  assert.deepEqual(
    conversationsForProject(
      [
        { id: 'a', project: 'Managed app', title: 'Alpha' },
        { id: 'b', project: 'Other', title: 'Beta' },
        { id: 'c', project: 'Managed app' },
        { id: 'd' },
      ],
      'Managed app',
      (id) => (id === 'c' ? 'Untitled' : id),
    ),
    [
      { id: 'a', title: 'Alpha', current: false },
      { id: 'c', title: 'Untitled', current: false },
    ],
  );
  assert.deepEqual(
    conversationsForProject(
      [{ id: 'a', project: 'Managed app', title: 'Alpha' }],
      'Managed app',
      () => 'Alpha',
      'a',
    ),
    [{ id: 'a', title: 'Alpha', current: true }],
  );
});
