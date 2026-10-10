import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

/**
 * The console's build, and the dev proxy that keeps the origin check honest.
 *
 * `vitest/config` rather than `vite`, because the `test` block below is
 * Vitest's and importing `defineConfig` from `vite` types it as an unknown
 * key. Vitest reads this same file; there is deliberately no second config.
 */
export default defineConfig(({ command, mode }) => {
  const configured = loadEnv(mode, '.', 'PLOWSHARE_')['PLOWSHARE_URL'];
  let proxyOrigin: string | undefined;
  if (command === 'serve' && mode !== 'test') {
    if (!configured?.trim())
      throw new Error(
        'Set PLOWSHARE_URL before starting the development console.',
      );
    const url = new URL(configured);
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.pathname !== '/' ||
      url.search ||
      url.hash
    )
      throw new Error('PLOWSHARE_URL must be a server origin.');
    proxyOrigin = url.origin;
  }
  return {
    // Shared, licensed appearance assets are served from this origin.
    publicDir: '../client-assets',
    build: {
      outDir: 'dist',
      emptyOutDir: true,
    },

    server: {
      ...(proxyOrigin
        ? {
            proxy: {
              '/v1': {
                target: proxyOrigin,
                ws: true,
                changeOrigin: true,
                headers: { Origin: proxyOrigin },
              },
            },
          }
        : {}),
    },

    test: {
      // jsdom, because `auth.ts` reads `location` and calls
      // `history.replaceState`, and `events.ts` builds its URL from
      // `location.protocol` and `location.host`. Node's environment has
      // none of those, and stubbing them would be testing the stubs.
      environment: 'jsdom',
      include: ['src/**/*.test.ts'],
      // A test that reaches the network is a test that fails on a train.
      // Nothing here has a real endpoint: `fetch` and `WebSocket` are both
      // replaced at every call site that uses them.
      restoreMocks: true,
    },
  };
});
