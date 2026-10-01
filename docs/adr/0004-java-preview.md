# Java version and preview features

Date: 2026-10-01. Status: Accepted.

## Context

The owner wants the latest Java and explicitly permits preview features for this pet project. Java 27 GA and its preview structured-concurrency API were verified against primary release/API documentation. This supersedes the earlier conversation's proposed preview-free kernel.

## Decision

Start with Java 27 and reverify latest GA at build start. Pin the actual working distribution/build. Permit preview APIs/language features in any module when they improve a concrete design. Structured concurrency is the preferred scoped I/O experiment; it is not required for naturally serial work. Use records, sealed outcomes, pattern switches, virtual threads and ScopedValue where their semantics fit.

## Alternatives and consequences

An older LTS or preview-free kernel would simplify deployment but contradict the owner's preference. Preview requires consistent compiler/test/runtime flags, selected-JDK API examples and migration attention. Record each adopted preview in contributor instructions with purpose, tests and upgrade impact. Incubator APIs need separate flags and separate justification.

## Validation and revisit

Clean CI compilation, preview-enabled test JVMs and packaged launcher smoke test must all pass. Test timeout, interruption and child cleanup. On each Java upgrade, recheck API and bytecode compatibility and update the record; do not force every new feature into the design.

Sources: [Java 27 release announcement](https://inside.java/2026/09/15/jdk-27-available/), [StructuredTaskScope API](https://docs.oracle.com/en/java/javase/27/docs/api/java.base/java/util/concurrent/StructuredTaskScope.html).
