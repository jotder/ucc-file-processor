# Editions

How Inspecto ships as Personal / Standard / Enterprise — and the binding branch & release policy. The key
idea: **editions are build flavors, never git branches.**

# Concepts

* [Editions model](editions-model.md) - build flavors via Maven profiles + ServiceLoader + -D flags.
* [Auth & security](auth-security.md) - auth-free core; the Authenticator/Subject/AccessDecider SPIs; Standard RBAC (data-driven roles, Access-Profile + sharing enforcement, OIDC/gateway); the Enterprise `inspecto-policy` ABAC engine (authored Access Policies, space isolation, decision audit); the separate write-gate.
* [Branching & release](branching-release.md) - versions=branches, merge-forward propagation, SemVer + Conventional Commits.
