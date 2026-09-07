---
name: spec-review
description: Use when a written architectural specification is complete and needs review before human approval or implementation planning.
---

# Spec Review

Review a completed specification before implementation planning.

**REQUIRED BACKGROUND:** Use the design principles from `superpowers:brainstorming`.

Do not implement code.
Do not create the implementation plan.
Do not expand scope merely because additional features might be useful.

## Goal

Determine whether the spec is sufficiently correct, coherent, bounded, explicit, and testable to become the authoritative input to implementation planning.

The goal is not perfection and not discovering every possible improvement.

## Review

Perform one comprehensive review covering:

1. **Requirements**
   - purpose and success criteria are explicit
   - required behavior is present
   - important assumptions are explicit
   - no TBD/TODO/placeholders or materially vague requirements

2. **Consistency**
   - requirements, architecture, components, data flow, interfaces, error behavior, and testing do not contradict each other

3. **Scope**
   - the spec represents one coherent implementation effort
   - independent subsystems are decomposed
   - speculative functionality and YAGNI violations are excluded

4. **Architecture**
   - components have clear responsibilities
   - dependencies and interfaces are understandable
   - design is compatible with relevant existing repository patterns

5. **Behavior**
   - important data/control flows are defined
   - material failure and edge behavior is defined
   - important requirements can be objectively tested

## Severity

Classify every material finding.

### BLOCKER
Cannot safely proceed.

Examples:
- contradictory requirements
- unresolved architecture/product decision
- scope requires decomposition
- impossible or undefined core interface

Must be resolved before proceeding.

### MAJOR
Likely to cause incorrect implementation or substantial rework.

Examples:
- missing material requirement
- important error behavior unspecified
- incompatible component responsibilities
- important behavior not testable from the spec

Must be resolved before proceeding.

### MINOR
Does not materially affect correctness, scope, architecture, or implementability.

Examples:
- wording
- formatting
- optional examples
- equivalent naming improvements

Minor findings do not block progression.

## Fix Impact

After the comprehensive review, fix known BLOCKER and MAJOR findings that can be resolved from approved context.

Classify fixes as:

- **Local** — isolated section/requirement change → fix and proceed
- **Cross-Cutting** — affects multiple related sections → targeted re-check only
- **Structural** — changes architecture, scope, core interfaces, or subsystem boundaries → one additional comprehensive review allowed

Do not perform more than two comprehensive reviews.

If a second comprehensive review still exposes a BLOCKER requiring another structural redesign, stop and surface the root decision instead of entering another review loop.

## Human Decision Rule

Do not invent product or architecture decisions.

If a BLOCKER requires human judgment, leave it unresolved and return `NEEDS_HUMAN_DECISION`.

## Stop Condition

Stop reviewing when:

- no known BLOCKER remains
- no known MAJOR remains
- required checklist areas have been checked
- material fixes have been applied
- any required targeted re-check has passed

MINOR findings may remain.

Never continue solely because another review might discover another improvement.

## Result

Finish with:

## Spec Review Result

**Verdict:** READY | NEEDS_HUMAN_DECISION | NEEDS_DECOMPOSITION

**Comprehensive reviews:** 1 or 2  
**Remaining BLOCKERs:** N  
**Remaining MAJORs:** N  
**Remaining MINORs:** N  
**Structural changes:** Yes/No  
**Targeted re-check:** Yes/No

**Human decisions required:**
- None
or
- explicit decisions

**Why review stops here:** concise explanation.

Use:

- `READY` when no BLOCKER or MAJOR remains and the spec is ready for implementation planning.
- `NEEDS_HUMAN_DECISION` when a material decision cannot safely be inferred.
- `NEEDS_DECOMPOSITION` when the scope should be split into independent spec → plan → implementation cycles.

If `READY`, stop. The normal Superpowers human spec-approval gate still applies before `writing-plans`.
