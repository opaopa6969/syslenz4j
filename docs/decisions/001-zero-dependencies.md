# ADR-001: Zero External Dependencies

**Status**: Accepted  
**Date**: 2026-04-10

## Context

syslenz4j needs to be embeddable in any Java application without causing dependency conflicts. Many monitoring libraries (Micrometer, Prometheus Java client, OpenTelemetry) bring transitive dependencies that can conflict with application frameworks, especially in Spring Boot or Jakarta EE environments.

The library only needs to collect JVM internals and serialize them to a single JSON format. Both of these are well-served by the JDK standard library.

## Decision

syslenz4j uses **no external runtime dependencies**. All functionality is implemented using:

- `java.lang.management` — MXBeans for JVM metrics
- `java.net` — TCP server
- `java.util.concurrent` — thread-safe collections
- `java.util.function` — callback interfaces
- `ProcessHandle` (Java 9+) — process PID

JSON serialization is done via manual string construction in `JsonExporter`, not via Jackson, Gson, or any other library.

## Consequences

**Positive:**
- No classpath conflicts with application dependencies
- Minimal JAR size (< 50 KB)
- No CVE surface from transitive dependencies
- Works in any Java 17+ environment including modular applications

**Negative:**
- `JsonExporter` must be updated manually when the ProcEntry format changes
- No JSON schema validation; malformed values are serialized as-is
- Limited to five value types: `Bytes`, `Integer`, `Float`, `Duration`, `Text`
