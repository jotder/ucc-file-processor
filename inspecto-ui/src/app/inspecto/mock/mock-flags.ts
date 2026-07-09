/**
 * The environment's mock-mode flags, passed into handler factories so handler modules never import
 * the app environment themselves (keeps them pure and individually testable).
 */
export interface MockFlags {
    mockSpaces?: boolean;
    mockStudio?: boolean;
    mockFlows?: boolean;
    mockJobs?: boolean;
    mockExchange?: boolean;
    mockOps?: boolean;
    mockDemo?: boolean;
    mockConnectionProbe?: boolean;
    /** W6d edition switch: 'oidc' makes the mock /bootstrap advertise Standard + a local (mock) OIDC
     *  login so the sign-in UX runs offline; 'none'/absent = Personal (no login), the default. */
    mockAuthMode?: 'none' | 'oidc';
}
