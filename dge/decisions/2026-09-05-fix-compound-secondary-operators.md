---
status: accepted
date: 2026-09-05
issue: 20
branch: fix/issue-20-compound-secondary-operators
---

# Decision: Fix Compound Secondary `>=` / `<=` Evaluation

## Context

`WatchCondition.CompoundCondition` exposes `greaterThanOrEqual()` and `lessThanOrEqual()` in the fluent API, but runtime evaluation only handled `GREATER_THAN` and `LESS_THAN`. As a result, secondary conditions configured with `>=` or `<=` were effectively treated as always-true.

This mismatch was visible in user-facing docs and architecture notes, so the product was explicitly documenting incorrect behavior in a public API.

## Options Considered

1. Fix secondary evaluation for `>=` / `<=`.
2. Remove those builder methods from the secondary condition API.
3. Leave behavior as-is and keep documenting the limitation.

## Decision

Option 1 is adopted.

## Rationale

- Highest user value for the smallest code change.
- Preserves source compatibility and documented intent.
- Eliminates false-positive watch firing, which is more damaging than feature absence.
- Keeps the library zero-dependency and avoids architectural expansion.

## Consequences

### Positive

- Compound watch behavior matches docs and user expectation.
- Boundary thresholds on secondary metrics become test-covered.
- Architecture docs can remove the known limitation.

### Negative

- Any downstream user who accidentally depended on the buggy false-positive behavior will observe stricter firing.

## tramli / tramli-appspec Evaluation

Rejected for this repo and this change.

- tramli is appropriate when the problem is multi-step flow orchestration with build-time state validation.
- syslenz4j's watch evaluation is a synchronous, in-process comparator over already-collected metric values.
- No flow definition, persistence, external resume, or cross-state contract exists here.
- Therefore tramli/tramli-appspec would add conceptual and runtime weight without reducing risk.

## Revert Plan

Revert the single implementation commit on `fix/issue-20-compound-secondary-operators`. This restores the prior compound secondary behavior and the accompanying docs/tests in one step.
