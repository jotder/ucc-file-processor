# ADR-0001: Framework-agnostic core with zero ring-1 runtime dependencies

**Status:** Accepted **Date:** 2026-06-04 **Deciders:** Kernel maintainers, UCC eng lead

## Context

Three planned agents run on very different stacks: UCC File-Processor assist (ServiceLoader, no DI framework, Java 24→25, local Ollama), CVVE V&V-as-a-Service (Spring Boot + Quarkus, PostgreSQL, multi-tenant SaaS), and the Real-Estate CxO agent (Spring Modulith, Postgres/pgvector, LangChain4j/Gemini, streaming). If the reusable core pulled in Spring, a model SDK, persistence, or even a logging/JSON library, it would force those choices on every consumer and couple the deliberately lean UCC host to machinery it does not want. The core must embed *identically* in all three.

## Decision

Ring-1 (`agent-kernel-core`) depends on the **JDK only** — zero compile/runtime dependencies (test-scope JUnit excepted). Every integration point is a kernel-owned interface: `ModelProvider`, `Retriever`, `AuditSink`, `Tool`, `Capability`, `AgentContext`. Concrete adapters (langchain4j/Ollama, Spring wiring, Postgres stores, jackson) live in ring-2 companion modules or ring-3 app bindings — never in core. A CI guard parses `mvn dependency:tree` for `agent-kernel-core` and **fails the build** if any compile/runtime-scope dependency appears.

## Options Considered

### Option A: Core depends on langchain4j-core (+ a logging/JSON lib)

| Dimension | Assessment |
|---|---|
| Coupling | High — every consumer inherits langchain4j + transitives |
| Embeddability | Low — UCC's lean ETL host drags an AI SDK it never calls |
| Version risk | High — skew/conflicts across three apps on different cadences |

**Pros:** ring-2 providers marginally thinner. **Cons:** transitive-dependency conflicts; forces an AI SDK on hosts that don't want it.

### Option B: Zero-dep core; providers in ring-2 (chosen)

| Dimension | Assessment |
|---|---|
| Coupling | None — pure JDK |
| Embeddability | Maximal — drops cleanly into any host |
| Version risk | None — no transitive surface to conflict |

**Pros:** maximally embeddable; no transitive conflicts; UCC stays lean; provider is swappable. **Cons:** a little more interface plumbing (our own `ModelProvider`/`ModelResponse` types instead of reusing an SDK's).

## Trade-off Analysis

A few hand-written interface types versus permanent freedom from dependency coupling. For a library meant to live inside three unrelated hosts, the zero-dependency property *is* the value proposition; the plumbing cost is trivial and one-time.

## Consequences

- **Easier:** embedding in any host; swapping model providers; reasoning about a tiny API surface; no dependency-convergence battles.
- **Harder:** providers must map to kernel types (small adapter layer); no reaching for a convenience library inside core.
- **Revisit:** never for the zero-runtime-dep invariant — the CI guard makes any regression loud.

## Action Items

1. CI dependency guard fails the build on any compile/runtime dep in `agent-kernel-core`.
2. `ModelProvider` / `ModelResponse` / `Retriever` / `AuditSink` are kernel-owned interfaces in ring-1.
3. `langchain4j-ollama` appears only in `agent-provider-ollama`; `jackson` only in `agent-eval`.
