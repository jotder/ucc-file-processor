# Editions

How Inspecto ships as Personal / Standard / Enterprise — and the binding branch & release policy. The key
idea: **editions are build flavors, never git branches.**

# Concepts

* [Editions model](editions-model.md) - build flavors via Maven profiles + ServiceLoader + -D flags.
* [Auth & security](auth-security.md) - auth removed from the core; the `Authenticator` SPI; the separate write-gate.
* [Branching & release](branching-release.md) - versions=branches, merge-forward propagation, SemVer + Conventional Commits.
