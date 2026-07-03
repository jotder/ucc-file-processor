// This file can be replaced during build by using the `fileReplacements` array.
// `ng build --prod` replaces `environment.ts` with `environment.prod.ts`.
// The list of file replacements can be found in `angular.json`.

export const environment = {
    production: false,
    // Inspecto inspector backend (ControlApi). All API calls are prefixed with '/api':
    //  - dev (ng serve): proxy.conf.json rewrites '/api' → '' and forwards to :8080.
    //  - packaged (SPA served same-origin by ControlApi): the backend strips the '/api' prefix
    //    before route matching, so the same build works without a proxy.
    // Keep this as '/api' for both modes. (There are no angular.json fileReplacements, so this
    // environment.ts is the one that actually ships.)
    apiBaseUrl: '/api',
    hmr: false,
    // Prototype-only: serve mocked connect/explore/test/sample for the connection workbench until the
    // real library + control routes land (B2). Gates the connections handler in the unified mock store
    // (inspecto/mock/); flip false in B2.
    mockConnectionProbe: true,
    // Prototype-only (W5): serve the server-global /spaces surface (meta/list/create/delete/datasources)
    // + the /spaces/templates catalog from the mock store, making the multi-space runtime + the
    // Space-Templates gallery fully demoable offline. Flip false against a real multi-space backend.
    mockSpaces: true,
    // Prototype-only: serve the Pipelines graph editor fully offline (node-type palette, authored-flow
    // CRUD, dry-run, per-processor test) from an in-memory store. Flip false / remove the interceptor
    // once the real flow backend is wired.
    mockFlows: true,
    // Prototype-only: serve the operational-intelligence surfaces (events / alerts / objects / enrichment)
    // fully offline from in-memory datasets, so the reusable query panel can be exercised with no backend.
    // Gates the ops handler in the unified mock store; flip false once wired to the real backend.
    mockOps: true,
    // Prototype-only: serve Studio's new component kinds (dataset/chart/dashboard) from an in-memory store
    // until the backend storage enum is widened. Flip false once wired to the real backend (gates the studio kinds in the unified mock store).
    mockStudio: true,
    // Prototype-only: serve the Scheduler's write actions (create/edit/delete/enable/disable/reschedule) and
    // per-run logs/events. The read endpoints (list/runs/trigger) already exist on the backend; the mock
    // seeds jobs so the page works offline. Gates the jobs handler in the unified mock store; flip false
    // once the real Java endpoints land (see the plan's follow-on).
    mockJobs: true,
    // Master demo-mode flag: mocks every remaining endpoint (health, status, pipelines, sources,
    // notifications, catalog, diagnoses, config) so the full UI works with no backend at all.
    // Gates the demo handler in the unified mock store (inspecto/mock/).
    mockDemo: true,
    apiVersion: '/api/v1',
    basePath: '/',
    authVersion:"/oauth",
    appName: 'inspecto',
    appLogo: 'assets/images/logo/inspecto-logo.svg',
    kibanaBase: 'http://p21.pr.pronto/monitoring',
    documentationFrameworkBase: 'http://p20.prod.pronto:3000',
    gatewayUrl: 'http://localhost:4204/',
    appUrl: 'https://demo.gammadev.io/prontoapiserver',

    promptoServerUrl: "https://demo.gammadev.io/promtoapiserver",
    gatewayServerUrl: 'http://68.183.16.242:6601',
    authServerUrl: 'http://68.183.16.242:6600/auth',
    ruleServerUrl: 'https://demo.gammadev.io/fmapiserver',
    caseServerUrl: 'https://demo.gammadev.io/ctapiserver',
    chatUrl: "http://68.183.16.242:5000/",

    mlstudio: "https://demo.gammadev.io/mlapiserver",
    mlstudioGui: "https://demo.gammadev.io/mlstudio/",
    agenticAiUrl: "https://demo.gammadev.io/agenticaiapiserver",
    authenticationType: 'token',
    footerText: ' © Powered By Gamma Analytics LLC',
    appLogoutUri: 'logout',
    appClientId: '8825302933668759552',
    appClientSecret: 'c6ef2c22-134b-4c13-bcaa-4a36a7b5462c',
    appLogoutLogo: 'assets/images/logo/inspecto-logo.svg',
    chatLogo: 'assets/images/logos/assistant.png',
    iamAppUrl: 'https://app1.pronto.lebara.sa/iam-server',
    promptoUrl: 'http://68.183.16.242/apps/newchat',
    caseTrackerGuiUrl: '/casetracker/',
    authServerAuthentication: true,
    //iam details
    iamClientId: "1070682796450139008",
    iamClientSecret: "f6f69f63-95b3-4aa3-93c8-1539666e65c1",
    dataFormat: "YYYYMMDD",
    notificationSount: 'assets/sound/notification_2.mp3'
};

// c6ef2c22-134b-4c13-bcaa-4a36a7b5462c

/*
 * For easier debugging in development mode, you can import the following file
 * to ignore zone related error stack frames such as `zone.run`, `zoneDelegate.invokeTask`.
 *
 * This import should be commented out in production mode because it will have a negative impact
 * on performance if an error is thrown.
 */
// import 'zone.js/plugins/zone-error';  // Included with Angular CLI.
