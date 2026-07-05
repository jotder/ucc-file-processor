import { vi } from 'vitest';

// axe-core accessibility assertions (`expectNoA11yViolations`) run inside jsdom, which is slow.
// Under the full suite's parallel load these a11y/init specs intermittently exceed vitest's default
// 5 s per-test timeout — they pass cleanly in isolation, so it's resource contention, not a hang.
// A generous per-file budget keeps the suite baseline stable on loaded CI / dev machines while still
// catching a genuinely stuck test. `vi.setConfig` here applies to every test file that loads this
// setup (wired via the unit-test builder's `setupFiles`).
vi.setConfig({ testTimeout: 15000, hookTimeout: 15000 });
