# Extensibility specification

## MVP extension surfaces

Extensibility begins with explicitly composed Java ports and validated configuration. It does not require a marketplace or a runtime plugin loader. Supported compile-time boundaries are `DecisionEngine`, `ModelProvider`, `ModelCatalog`, `Tool`, `RunEventSink` and time/ID sources where needed for tests. Provider JSON remains in adapters. Do not expand these interfaces to anticipate unimplemented plugin systems.

A developer can add a compiled-in tool/provider/decision adapter and explicitly register it in CLI composition. Alpha supports only the shipped adapter names and explicitly added compiled implementations. Unknown configuration names are errors, not instructions to find/download code. No ServiceLoader/classpath scanning, arbitrary class-name config, reflection-based instantiation or remote jars in MVP.

## Registration and lifecycle

Registry entries have stable name, contract version, descriptor and declared capability/effect. Reject duplicate names even when versions differ: alpha does not negotiate competing implementations. Freeze registries/configuration at session creation. Registries expose descriptions to the driver; they do not own admission authority. Construction/start/close belong to CLI composition; the owning lifetime closes resources on normal completion, failure and interruption.

A new Tool must pass common schema/bounds/authority tests and run through the same admission path as built-ins. Alpha registration rejects non-read-only effect classes. A new provider/decision implementation must pass common outcome, cancellation, credential-separation and metadata tests. Vendor-specific envelopes are a compatibility profile inside an adapter, not changes to core types.

Use versioned contracts for observable formats rather than promising binary-compatible SPIs before release. An incompatible domain contract gets an ADR, release note and updated tests; old trace readers still need explicit schema handling. Do not silently parse an old extension under new semantics.

## Instruction extension

Explicit local instruction files are a data-only extension, governed by [context](context.md). They can add task conventions, styles and domain guidance but cannot enable tools, expand the model pool, escape budgets or execute embedded commands. Repository AGENTS.md discovery through a read tool does not become automatic privileged loading. No SKILL.md interpreter or executable hooks in alpha.

## Proof of extensibility

A26 adds a test-only compiled read tool and fake provider through the documented ports, invokes them through ordinary runtime admission, and confirms the existing loop does not change. Also reject duplicate/unknown/mutating registration and verify cleanup. A28 proves adapter DTO imports do not enter core and public contracts remain small. Include a short real extension example in implementation docs once the interfaces are executable; planned pseudocode is insufficient evidence.

## Future surfaces

M4 may introduce skills, hooks and MCP after user need is demonstrated. Specify capability manifests, trust/source provenance, version negotiation, bounded hook execution, explicit registration, cancellation/resource budgets, failures and secret boundaries before loading third-party code. Read-only observers are easier to add than hooks that can alter requests/authority; do not let plugins rewrite admission policy.

Marketplace signing, installation, updates and remote distribution belong to a later product. Native inference remains an adapter behind the same decision port. Multi-agent specialist registration requires orchestration invariants, not a shortcut through a generic plugin registry.
