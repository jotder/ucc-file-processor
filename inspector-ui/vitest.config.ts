import { defineConfig } from 'vitest/config';

/**
 * Vitest runner config consumed by the Angular `@angular/build:unit-test` builder (via the
 * `runnerConfig` option). Its sole job: force DevExtreme through Vite's transform pipeline so its
 * deep "directory import" entry points (e.g. `devextreme/common/ai-integration`) resolve under the
 * Node/jsdom test environment. Without this, importing any component that pulls in a
 * `devextreme-angular/ui/*` module fails to load with
 * "Directory import ... is not supported resolving ES modules" — which is why component specs that
 * import a page component need it, while the pure API/service specs do not.
 */
export default defineConfig({
  test: {
    server: {
      deps: {
        inline: [/devextreme/, /devextreme-angular/],
      },
    },
  },
});
