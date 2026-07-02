---
name: smoke
description: >
  Live smoke test of the Inspecto control plane. Trigger on "SMOKE" or "smoke test", optionally
  with a focus area ("SMOKE spaces", "SMOKE flows"). Boots ControlApi over a sample config,
  probes /health plus the focus endpoints, optionally runs the e2e suite, ALWAYS stops the
  server, and reports concrete evidence (HTTP codes, key response bodies).
---

# /smoke — control-plane smoke test

Prove the built artifact actually serves, not just that tests pass.

## Steps

1. **Ensure the JAR exists** — `mvn -o clean package -q` if `inspecto/target/file-processor-*.jar`
   is missing/stale (delegate to the `verify-runner` agent if a full build is needed).
2. **Launch** (auth-free core — no token):
   ```powershell
   java --enable-native-access=ALL-UNNAMED -cp inspecto\target\file-processor-*.jar `
        com.gamma.control.ControlApi inspecto\config
   ```
   Run in the background; default port :8080.
3. **Probe** — `GET /health` must be 200, then the focus endpoints for this smoke
   (e.g. `/spaces`, `/flows/node-types`, `/views`). Use `curl -s` and capture status + body.
4. **Optional e2e** — `E2E_BASE_URL=http://localhost:8080 npm run test:ci -- --include src/e2e/**`
   in `inspecto-ui/` when the focus is UI-visible.
5. **Always stop the server** — even on failure.
6. **Report evidence** — endpoint → status → one-line body summary. "Smoke passed" without the
   probe table doesn't count.
