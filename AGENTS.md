# AGENTS.md

## Development Workflow

This repository uses Superpowers for software development.

Always follow applicable Superpowers skills.

### Architectural work

For architectural changes, use this workflow:

```text
superpowers:brainstorming
→ written spec
→ spec-review
→ human spec approval
→ superpowers:writing-plans
→ written plan
→ plan-review
→ implementation
→ superpowers:verification-before-completion
```

### Review gates

When a written specification is complete and ready for review before human approval or implementation planning:

- invoke `spec-review`
- follow that skill's severity and stop-condition rules
- proceed only when its verdict is `READY`

When an implementation plan is complete and ready for review before implementation:

- invoke `plan-review`
- compare the plan against the approved specification
- proceed only when its verdict is `READY`
- if the verdict is `RETURN_TO_SPEC`, return to the specification phase

Do not repeatedly review an artifact merely to find additional improvements.
Review convergence is defined by the applicable review skill.

### Human approval

A `spec-review` result of `READY` does not replace human approval.

Do not start `superpowers:writing-plans` until the current specification has been explicitly approved by the human.

### Scope

Do not require written specs or implementation plans for tasks that Superpowers classifies as `bounded`.

If hidden complexity upgrades a task to `architectural`, switch to the full workflow above.

## Where things are

- User-visible behavior and limits -> README.md
- Build, quality gates, code layout -> docs/development.md
- Design history -> docs/superpowers/specs/ and docs/superpowers/plans/
- Class-level detail -> Javadoc in src/main/java
