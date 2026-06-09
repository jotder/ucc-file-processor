// Development: `ng serve` proxies /api → http://localhost:8080 (see proxy.conf.json),
// so calls stay same-origin from the browser's perspective (no CORS needed in the common case).
export const environment = {
  production: false,
  apiBaseUrl: '/api'
};
