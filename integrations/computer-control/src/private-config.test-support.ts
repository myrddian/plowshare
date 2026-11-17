import { decodeComputerHostConfig } from './config.ts';
export const hostConfigSource = {
  version: 1,
  origin: 'https://fixture.invalid',
  binding: {
    project: 'fixture',
    provider: 'computer',
    account: '@service/11111111-1111-1111-1111-111111111111',
  },
  credential: { kind: 'service-token', environment: 'FIXTURE_TOKEN' },
  journalDirectory: '/fixture/journal',
  catalogue: { leaseSeconds: 300, renewalMs: 30000 },
  browser: {
    executablePath: '/fixture/browser',
    headless: true,
    targets: [
      {
        targetId: 'browser',
        label: 'Browser',
        url: 'https://canvas.invalid',
        viewport: { width: 320, height: 160 },
      },
    ],
  },
};
export const hostConfig = decodeComputerHostConfig(hostConfigSource);
