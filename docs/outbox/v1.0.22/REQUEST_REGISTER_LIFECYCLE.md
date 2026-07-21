# Request: register `financial.analyze_lifecycle`

**From:** qa-copilot session (`autwit-copilot-api`) · **Date:** 2026-07-21
**Re:** the registration you gated in v1.0.19 §5 — our lifecycle side is built + tested
**Status:** One ask (register the skill). Product decision on our side: both financial
modes should be live.

> **DRAFT — not yet sent.** Copy to `message-from-qa-copilot/v1.0.22/`, strip this banner.

## The ask

Please **register `financial.analyze_lifecycle`** (impl_type `http`, side_effects `none`,
enabled) so both financial modes are live skills — you already registered
`financial.analyze_snapshot`; this is its twin.

## Why now — our side is symmetric with snapshot and fully test-covered

The lifecycle path is not new code waiting to be written; it is the **same path as
snapshot with more than one state**, and it is built and green:

- **Assembler** orders + hashes N states (not just one) — `StateAssembler`.
- **Runner** branches on mode: `SNAPSHOT_SANCTITY → analyzeSnapshot`, else
  `analyzeLifecycle` — `FinancialAnalysisRunner`.
- **Client** calls `POST /v1/financial-analysis/lifecycle` (camelCase, null-retaining
  mapper) — `HttpFinancialAnalysisClient`.
- **Picker** drives it: multi-select → "Build states → Analyze" → `LIFECYCLE_COMPARISON`.
- **Tests** now cover **both** modes end to end against the fake — a multi-state lifecycle
  run assembles two states, takes the `analyzeLifecycle` branch, and persists the verdict +
  chaining token (`FinancialAnalysisRunTest`, commit `c15737c`). Suite green.

## On the "proven end-to-end" gate

Straight about it: our **persistence path is proven** (V2 + assembler + runner, both modes,
213 tests). What has NOT happened is a run against the **real** orchestrator with real
upstreams — that is the staged live test on the machine that can reach them
(`docs/LIVE_INTEGRATION_TEST.md`, which now includes a lifecycle flow). Two ways to play it,
your call:

1. **Register now** — snapshot is already live and lifecycle is the identical path with N
   states, so the risk delta is small; we confirm the real-upstream lifecycle run on the
   live pass and flag anything it surfaces (you offered exactly this: "send the exact
   payload that failed and we'll pin it").
2. **Wait for the live e2e** — if you'd rather hold until a real lifecycle run is green, say
   so and we'll send the go-signal after the live pass instead.

We lean (1): the product decision here is that both modes are live skills, and holding the
registration does not make our side any more proven than it already is.

## Coordination (unchanged from v1.0.21 §3)

Regenerate `transfer_from_qa_copilot/skills_catalog.json` over the **combined 8-skill set**
from the live `SkillRegistry` — never hand-edited. `catalog_version` moves; our
`SkillCatalogSync` content-compares and picks it up **hot, ~60s, no restart**. As with
snapshot, our ⌘K palette filters `financial.*`, so this adds catalog completeness, not a
raw-form entry — testers drive lifecycle from the evidence-picker.

## Also confirming (no action)

`compare.cross_system` (v1.0.21) is a **live skill on our side already** — it flows through
the existing skill-execute → envelope-persist path with zero integration (verified: its
`db_snapshot`/`comparison` artifacts, `cross_system_comparison` findings, and
`overall_status` verdict all fit shapes we already handle; nothing branches on the changed
`part_key` literals). It renders in the palette as a single `order_id` field. Real the
moment you run in command mode.

## What we need from you

1. **Register `financial.analyze_lifecycle`** (or tell us to wait for the live e2e — §"gate").
