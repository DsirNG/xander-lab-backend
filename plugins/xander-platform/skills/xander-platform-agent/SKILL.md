---
name: xander-platform-agent
description: Extend or review the DinQorAI agent runtime, including built-in skills, tool routing, durable runs, approvals, and user-authored skill boundaries.
---

# Xander Platform Agent

Read the backend `AGENTS.md` and the agent section of `docs/ARCHITECTURE.md`
before changing the runtime. Built-in skills are trusted Spring beans in
`BuiltinAgentSkills`; user skills are text bodies loaded from `agent_skill`.

## Skill changes

- Keep a skill focused on a workflow: when to load it, which tools are usually
  relevant, and the rules for producing the result.
- A skill is guidance, not a capability or authorization gate. The complete
  registered tool surface remains available each turn.
- Never let a user-authored skill relax approval, ownership, idempotency, or
  authentication rules.
- Keep descriptions short because they are rendered in every turn's catalog;
  put detailed rules in `instructions()`.
- Use only registered tool names as advisory names and keep the stable id in
  kebab-case.

## Runtime changes

Preserve durable run fencing, approval state, idempotency keys, and explicit
user ownership. Network calls stay outside database transactions. Update the
agent tests and architecture documentation when the skill contract or tool
surface changes.
