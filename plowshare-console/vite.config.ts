import { defineConfig } from 'vitest/config'

/**
 * The console's build, and the dev proxy that keeps the origin check honest.
 *
 * `vitest/config` rather than `vite`, because the `test` block below is
 * Vitest's and importing `defineConfig` from `vite` types it as an unknown
 * key. Vitest reads this same file; there is deliberately no second config.
 */
export default defineConfig({
    build: {
        outDir: 'dist',
        emptyOutDir: true,
    },

    server: {
        proxy: {
            // ---------------------------------------------------------------
            // THE ORIGIN CHECK, AND WHY THIS BLOCK IS SHAPED THE WAY IT IS.
            //
            // `EventChannelConfig` deliberately does not call
            // `setAllowedOrigins`. Spring's default is then its SAME_ORIGIN
            // policy, and on `/v1/events` that absence is the browser's only
            // structural protection: the listener is the one role a page can
            // hold, so any page the operator visits could otherwise open a
            // socket on this server and watch their jobs go past.
            //
            // A Vite dev server is a foreign origin. Its upgrade is refused,
            // and that refusal is CORRECT. **The wrong fix is
            // `setAllowedOrigins("*")` in `EventChannelConfig`**, which
            // silently removes that protection for every deployment, forever,
            // to make one developer's afternoon work.
            // `EventChannelTest.an_upgrade_from_a_foreign_origin_is_refused`
            // fails if it happens. The right fix is here: the browser only ever
            // speaks to the Vite origin, and this proxy presents the server's
            // own origin upstream.
            //
            // WHAT `changeOrigin` ACTUALLY DOES, measured rather than assumed,
            // because the plan for this slice asserted the opposite and would
            // have shipped a dev mode that does not work. A Vite 7.3.6 proxy in
            // front of a header-echoing target, one HTTP request and one
            // WebSocket upgrade through each of three configurations, reading
            // what the target received:
            //
            //   config                                 Host             Origin
            //   -----------------------------------------------------------------
            //   { ws: true }                           localhost:5173   http://localhost:5173
            //   { ws: true, changeOrigin: true }       127.0.0.1:8091   http://localhost:5173
            //   the block below                        127.0.0.1:8091   http://127.0.0.1:8091
            //
            // `changeOrigin` rewrites **Host** and forwards **Origin**
            // untouched. It is named for the origin *server*, not for the
            // `Origin` header. The plan's comment read "changeOrigin rewrites
            // the Origin header to the target's, so the server sees a
            // same-origin upgrade"; the middle row is what that configuration
            // really produces, and it is the one combination of the three that
            // Spring refuses.
            //
            // WHAT SPRING COMPARES, measured against
            // `OriginHandshakeInterceptor` holding the empty allow-list this
            // server leaves it at. That it is the object the container installs
            // is read out of the bytecode rather than assumed:
            // `AbstractWebSocketHandlerRegistration` does
            // `new OriginHandshakeInterceptor(this.allowedOrigins)`, and
            // `allowedOrigins` is empty precisely because `EventChannelConfig`
            // never calls `setAllowedOrigins`. Each row is one call to
            // `beforeHandshake`:
            //
            //   Host=127.0.0.1:8091  Origin absent                 ACCEPTED
            //   Host=127.0.0.1:8091  Origin http://localhost:5173  REFUSED
            //   Host=127.0.0.1:8091  Origin http://127.0.0.1:8091  ACCEPTED
            //   Host=localhost:5173  Origin http://localhost:5173  ACCEPTED
            //   Host=127.0.0.1:8091  Origin http://localhost:8091  REFUSED
            //   Host=127.0.0.1:8091  Origin https://127.0.0.1:8091 REFUSED
            //
            // So the comparison is `Origin` against the scheme, host and port
            // the server believes it is serving on. THE ONE LINK NOT MEASURED
            // HERE is that those come from the `Host` header for a proxied
            // request: the probe above set them on a mock request directly,
            // where a real container derives them from `Host`. What is measured
            // against a real Tomcat is the refusal itself —
            // `EventChannelTest.an_upgrade_from_a_foreign_origin_is_refused`
            // dials a real socket with a foreign `Origin` and gets 403 — and
            // the manual run at the end of this slice is where the whole chain,
            // browser included, gets exercised at once.
            //
            // Three consequences, and each one is a way to get this wrong:
            //   - `localhost` and `127.0.0.1` are different hosts. The `Origin`
            //     below must spell the target the same way `target` does.
            //   - the scheme is compared. `https://` here against a
            //     plain-HTTP dev server is refused on the scheme alone.
            //   - `changeOrigin` and the `Origin` override are a pair. Either
            //     one alone leaves the two headers disagreeing, which is the
            //     refusal.
            //
            // Leaving both off is the fourth row and would also work — the
            // proxy forwards a matching Host and Origin, and Spring accepts.
            // It is not what this file does, for two reasons: the upstream
            // server would see a Host it does not serve, and the arrangement
            // breaks the moment somebody adds the `changeOrigin: true` that
            // every proxy example on the internet recommends. The pair below
            // makes the server see exactly the same-origin request it sees when
            // the console is served from the jar, which is the arrangement
            // production actually runs.
            //
            // 8091 AND NOT 8080: `application.yml` sets
            // `port: ${PLOWSHARE_PORT:8091}`, one digit above Anchor's 8090 so
            // both run on one box. An earlier draft of the plan said 8080,
            // which would point dev mode at a port nothing serves — and the
            // failure looks like a refused connection, not like an origin
            // problem, which is a different wrong thing to go and "fix". A
            // server started with PLOWSHARE_PORT set needs this literal changed
            // to match; there is no env var read here, because a config file
            // that silently follows the environment is a config file whose
            // behaviour differs between two developers' shells.
            //
            // `/v1` and not `/`: everything outside `/v1` is the console's own
            // assets, which in dev are Vite's to serve. `AuthFilter` gates
            // `/v1` and nothing else, for the same split.
            // ---------------------------------------------------------------
            '/v1': {
                target: 'http://127.0.0.1:8091',
                // Both WebSocket paths live under /v1: /v1/events for the
                // listener and /v1/files for the file channel. Without this the
                // upgrade is answered by Vite instead of being forwarded.
                ws: true,
                changeOrigin: true,
                headers: { Origin: 'http://127.0.0.1:8091' },
            },
        },
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
})
