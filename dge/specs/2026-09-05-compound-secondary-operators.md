---
date: 2026-09-05
issue: 20
status: implemented
---

# Spec: Compound Secondary Operator Semantics

## Scope

`WatchCondition.and(metric)` secondary conditions.

## Requirements

1. Secondary `greaterThanOrEqual(v)` must evaluate true only when `metric >= v`.
2. Secondary `lessThanOrEqual(v)` must evaluate true only when `metric <= v`.
3. Exact threshold matches are valid for both operators.
4. If the secondary metric is absent, the compound watch must not fire.
5. Existing chaining behavior must remain unchanged.

## Out of Scope

- Adding new secondary operators beyond the existing API.
- Changing cooldown, callback, or alert serialization behavior.

## Test Matrix

- Normal: primary true + secondary `>=` greater than threshold -> fires.
- Boundary: primary true + secondary `>=` equal threshold -> fires.
- Boundary: primary true + secondary `<=` equal threshold -> fires.
- Failure: primary true + secondary `>=` below threshold -> does not fire.
- Failure: primary true + secondary metric absent -> does not fire.
