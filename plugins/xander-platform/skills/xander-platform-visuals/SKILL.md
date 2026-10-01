---
name: xander-platform-visuals
description: Build visual and interactive deliverables for the Xander Platform frontend or agent workflows. Use for image prompts, visual content, charts, data explorers, demos, and artifact delivery.
---

# Xander Platform Visuals

Use this project skill when a request needs a visual asset or a runnable visual
deliverable. It is intentionally independent from Codex-only image or browser
tools so the workflow remains useful when the project agent runs on its own.

## Choose the deliverable

- Use the platform image tool for a new cover, illustration, or standalone image.
- Use an artifact for a chart, interactive explorer, demo page, or a multi-file
  result the user will save and continue editing.
- Keep a short explanation in the reply for a small code example or a visual
  concept that does not need a saved file.

## Image prompts

Before generating, preserve the user's subject, intended use, audience, visual
style, composition, color constraints, and exact text. Do not add a brand name,
slogan, character, or visual element that the user did not request. If text in
the image matters, keep it short and verify it in the generated result.

## Interactive artifacts

Ask for or infer the data source and the main decision the visualization should
support. Use real platform data when available; label mock data clearly. Deliver
complete files with an entry point and run hint. Keep the result accessible,
keyboard usable, responsive, and free of unnecessary external dependencies.

## Boundaries

Do not claim a visual was generated, saved, or published without a successful
tool result. Image generation and artifact delivery do not authorize publication
or other external side effects; those remain separate platform actions.
