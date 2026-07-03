/**
 * The environment's mock-mode flags, passed into handler factories so handler modules never import
 * the app environment themselves (keeps them pure and individually testable).
 */
export interface MockFlags {
    mockSpaces?: boolean;
    mockStudio?: boolean;
    mockFlows?: boolean;
    mockJobs?: boolean;
    mockOps?: boolean;
    mockDemo?: boolean;
    mockConnectionProbe?: boolean;
}
