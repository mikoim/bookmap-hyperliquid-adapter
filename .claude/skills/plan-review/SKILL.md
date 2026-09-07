---
name: plan-review
description: Use when an implementation plan based on an approved specification is complete and needs review before implementation begins.
---

# Plan Review

Review a completed implementation plan against its approved specification before implementation.

**REQUIRED BACKGROUND:** Use the plan conventions from `superpowers:writing-plans`.

Do not implement code.
Do not silently redesign the approved specification.
Do not add speculative functionality or unrelated refactoring.

## Goal

Determine whether an engineer or coding agent with limited prior context can execute the plan task-by-task and faithfully implement the approved specification.

The goal is not discovering every possible improvement.

## Review

Perform one comprehensive review covering:

1. **Spec Coverage**
   - every material spec requirement maps to one or more tasks
   - important failure behavior is covered
   - required migrations, configuration, documentation, and verification are included where relevant

2. **Scope Fidelity**
   - no unauthorized features
   - no speculative abstractions
   - no unrelated refactors
   - YAGNI is preserved

3. **Files and Responsibilities**
   - referenced paths match the repository
   - files have coherent responsibilities
   - planned structure follows existing conventions where appropriate

4. **Task Boundaries**
   - each task produces a meaningful independently reviewable increment
   - each implementation task has its own test/verification cycle
   - unrelated work is not bundled together

5. **Dependencies and Ordering**
   - prerequisites precede consumers
   - no undeclared or circular dependencies
   - execution order produces a viable progression

6. **Interface Consistency**
   Verify exact consistency across tasks for:
   - functions
   - methods
   - parameters
   - return types
   - classes/types
   - properties
   - schemas
   - database fields
   - routes
   - configuration keys

7. **Executability**
   Reject vague instructions that require material design decisions, such as:
   - "handle errors"
   - "add appropriate validation"
   - "add tests"
   - "update as necessary"
   - "similar to Task N"
   - TBD/TODO

8. **Verification**
   - critical behavior has an identifiable test path
   - tasks follow the intended failing-test → minimal implementation → passing-test cycle where applicable
   - final integration/build/lint/typecheck/regression checks exist when materially useful

## Severity

### BLOCKER
The plan cannot safely be executed.

Examples:
- impossible dependency order
- incompatible core interfaces
- plan structure cannot produce the required feature

Must be fixed.

### MAJOR
Likely to cause missing behavior, incorrect implementation, or substantial rework.

Examples:
- uncovered spec requirement
- inconsistent function/type names
- missing critical test
- missing required migration/configuration step

Must be fixed.

### MINOR
Does not materially affect correctness or executability.

Minor findings do not block progression.

### SPEC_BLOCKER
The root defect belongs to the approved specification rather than the plan.

Examples:
- contradictory spec requirements
- undefined core behavior
- architecture cannot satisfy a stated constraint

Never silently repair a SPEC_BLOCKER inside the plan.

Return it to the specification phase.

## Fix Impact

For plan-local findings:

- **Local** → fix and proceed
- **Cross-Cutting** → targeted re-check of affected tasks/interfaces/dependents
- **Structural** → one additional comprehensive review allowed

Structural includes major task decomposition, core-interface, execution-order, or implementation-strategy changes.

Do not perform more than two comprehensive reviews.

If structural defects persist after the second review, stop and surface the root cause instead of starting another autonomous review loop.

## Stop Condition

Stop when:

- no BLOCKER remains
- no MAJOR remains
- no SPEC_BLOCKER remains
- material spec coverage is complete
- task dependencies/interfaces are consistent
- the plan can be executed without unstated material design decisions
- required targeted re-checks have passed

MINOR findings may remain.

Never continue merely because another review might find another improvement.

## Result

Finish with:

## Plan Review Result

**Verdict:** READY | RETURN_TO_SPEC | NEEDS_HUMAN_DECISION

**Comprehensive reviews:** 1 or 2  
**Remaining BLOCKERs:** N  
**Remaining MAJORs:** N  
**Remaining MINORs:** N  
**SPEC_BLOCKERs:** N  
**Spec coverage:** COMPLETE | INCOMPLETE  
**Structural changes:** Yes/No  
**Targeted re-check:** Yes/No

**Human decisions required:**
- None
or
- explicit decisions

**Why review stops here:** concise explanation.

Use:

- `READY` when the plan faithfully and executably implements the approved spec.
- `RETURN_TO_SPEC` when one or more SPEC_BLOCKERs exist.
- `NEEDS_HUMAN_DECISION` when a material implementation choice cannot safely be derived.

If `READY`, stop. Do not implement as part of this review.
