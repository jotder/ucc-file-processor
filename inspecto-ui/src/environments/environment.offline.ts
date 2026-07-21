// Offline demo profile — swapped in for environment.ts by the angular.json `offline` build
// configuration (`ng serve -c offline` / `npm run start:offline`). EVERY mock* domain is ON, so the
// whole UI runs against the unified mock backend (inspecto/mock/) with NO ControlApi on :8080 — the
// stakeholder / air-gapped demo mode. The shipping environment.ts keeps every flag false ("mocks off
// means REALLY off", 66c672d): that talks to the real backend. Keep the two in sync when adding fields.

export const environment = {
    production: false,
    apiBaseUrl: '/api',
    hmr: false,
    // ── every mock domain ON (the only difference from environment.ts) ──
    mockConnectionProbe: true, // connection workbench: connect · explore · test · sample · CRUD
    mockSpaces: true, // /spaces meta/list/create/delete + Space-Templates gallery
    mockFlows: true, // Pipelines graph editor (palette, authored-flow CRUD, dry-run)
    mockOps: true, // operational-intelligence surfaces (events / alerts / objects / enrichment)
    mockStudio: true, // Studio component kinds + settings + BI template/share/public-dashboard/inv
    mockJobs: true, // Scheduler write actions + per-run logs/events
    mockExchange: true, // cross-space Exchange (/exchange/* offers, grants, requests, snapshots)
    mockAccess: true, // lens access config (/access/catalog + /access/profiles)
    mockDemo: true, // master demo catch-all (health, status, pipelines, catalog, diagnoses, config, …)
    mockDb: true, // per-space Data Browser (/db/catalog|table|query)
    // 'none' → mock /bootstrap reports Personal, so the app boots with NO login. Set 'oidc' (or
    // localStorage['inspecto.mockAuthMode']) to exercise the Standard sign-in UX offline.
    mockAuthMode: 'none' as 'none' | 'oidc',
    oidc: { authorizeUrl: '', clientId: '', scopes: 'openid profile roles', mock: false },
    apiVersion: '/api/v1',
    basePath: '/',
    authVersion: '/oauth',
    appName: 'inspecto',
    appLogo: 'assets/images/logo/inspecto-logo.svg',
    kibanaBase: 'http://p21.pr.pronto/monitoring',
    documentationFrameworkBase: 'http://p20.prod.pronto:3000',
    gatewayUrl: 'http://localhost:4204/',
    appUrl: 'https://demo.gammadev.io/prontoapiserver',

    promptoServerUrl: 'https://demo.gammadev.io/promtoapiserver',
    gatewayServerUrl: 'http://68.183.16.242:6601',
    authServerUrl: 'http://68.183.16.242:6600/auth',
    ruleServerUrl: 'https://demo.gammadev.io/fmapiserver',
    caseServerUrl: 'https://demo.gammadev.io/ctapiserver',
    chatUrl: 'http://68.183.16.242:5000/',

    mlstudio: 'https://demo.gammadev.io/mlapiserver',
    mlstudioGui: 'https://demo.gammadev.io/mlstudio/',
    agenticAiUrl: 'https://demo.gammadev.io/agenticaiapiserver',
    authenticationType: 'token',
    footerText: ' © Powered By Gamma Analytics LLC',
    appLogoutUri: 'logout',
    appClientId: '8825302933668759552',
    appClientSecret: 'c6ef2c22-134b-4c13-bcaa-4a36a7b5462c',
    appLogoutLogo: 'assets/images/logo/inspecto-logo.svg',
    chatLogo: 'assets/images/logos/assistant.png',
    authServerAuthentication: true,
    //iam details
    iamClientId: '1070682796450139008',
    iamClientSecret: 'f6f69f63-95b3-4aa3-93c8-1539666e65c1',
    dataFormat: 'YYYYMMDD',
    notificationSount: 'assets/sound/notification_2.mp3',
};
