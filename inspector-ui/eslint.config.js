// @ts-check
const eslint = require("@eslint/js");
const { defineConfig } = require("eslint/config");
const tseslint = require("typescript-eslint");
const angular = require("angular-eslint");

// Inspector lint baseline.
//
// Strict angular-eslint + typescript-eslint recommended rules apply to our own code. Two large,
// purely-stylistic migrations are deferred (not correctness issues — see notes inline), and the
// files vendored from the DevExtreme Angular template are held to a looser bar so we stay close to
// upstream. New first-party code should follow the strict rules; the deferred items are tracked
// modernizations, not a license to write new violations.
module.exports = defineConfig([
  {
    files: ["**/*.ts"],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      "@angular-eslint/directive-selector": [
        "error",
        { type: "attribute", prefix: "app", style: "camelCase" },
      ],
      "@angular-eslint/component-selector": [
        "error",
        { type: "element", prefix: "app", style: "kebab-case" },
      ],
    },
  },
  {
    files: ["**/*.html"],
    extends: [
      angular.configs.templateRecommended,
      angular.configs.templateAccessibility,
    ],
    rules: {
      // Deferred modernization: the screens were authored with *ngIf / *ngFor, which remain fully
      // supported in Angular 21. Migrating ~90 sites to the @if / @for built-in control flow is a
      // mechanical-but-broad change that deserves its own per-screen verified pass, so it is not
      // gated here. Re-enable once that migration lands.
      "@angular-eslint/template/prefer-control-flow": "off",
    },
  },

  // --- Vendored DevExtreme Angular template scaffolding -------------------------------------------
  // These files came from the DevExtreme Angular template (layout shell, header/footer/nav, theme
  // switcher, user panel, and the template's helper services). We keep them close to upstream rather
  // than refactoring template code to our style rules, so the template-origin patterns are allowed
  // here only. First-party code (pages/, shared/api/, connect-form, assist-panel) is unaffected.
  {
    files: [
      "src/app/app.ts",
      "src/app/layouts/**/*.ts",
      "src/app/shared/components/header/**/*.ts",
      "src/app/shared/components/footer/**/*.ts",
      "src/app/shared/components/side-navigation-menu/**/*.ts",
      "src/app/shared/components/theme-switcher/**/*.ts",
      "src/app/shared/components/user-panel/**/*.ts",
      "src/app/shared/services/app-info.service.ts",
      "src/app/shared/services/screen.service.ts",
      "src/app/shared/services/theme.service.ts",
      "src/app/app-navigation.ts",
      "src/app/unauthenticated-content.ts",
    ],
    rules: {
      "@angular-eslint/prefer-inject": "off",
      "@angular-eslint/component-selector": "off",
      "@typescript-eslint/no-explicit-any": "off",
      "@typescript-eslint/no-empty-function": "off",
      "@typescript-eslint/no-wrapper-object-types": "off",
      "@typescript-eslint/class-literal-property-style": "off",
      "@typescript-eslint/consistent-type-definitions": "off",
    },
  },
]);
