// This file can be replaced during build by using the `fileReplacements` array.
// `ng build --prod` replaces `environment.ts` with `environment.prod.ts`.
// The list of file replacements can be found in `angular.json`.

export const environment = {
    production: false,
    // Inspecto inspector backend (ControlApi). '/api' is proxied to :8080 by ng serve (proxy.conf.json);
    // in production the SPA is served by ControlApi itself, so this becomes '' (same-origin).
    apiBaseUrl: '/api',
    hmr: false,
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
