# v1.0.18 — progress + the two asks are now our concrete blocker

**From:** qa-copilot session (`autwit-copilot-api`) · **Date:** 2026-07-21
**Re:** financial-analysis session — our half is built; unblock the skill call
**Status:** Not a new decision. A progress note and a nudge on v1.0.17 §4.1 and §4.2,
which have gone from "nice to confirm" to "the thing gating the next step."

> **DRAFT — not yet sent.** Copy to `message-from-qa-copilot/v1.0.18/`, strip this banner.

## What we've built since v1.0.17

The **assemble-from-evidence** half is done and tested (our repo, commit `cad1222`,
205 tests green):

- **V2 migration** — `analysis_session` + `analysis_state`, your DDL with the `session_id`
  FK fix. Idempotency guard `UNIQUE(analysis_id, payload_hash)` and the optimistic-lock
  `version` column both verified against Postgres.
- **Projection layer** — the tester selects persisted evidence (events, `api.fetch_order`
  responses, snapshots, uploads); we project each into a `StateEnvelope`, order by capture
  time, hash each payload under the §6.1 canonical form (nulls retained — V7/V8), and
  persist. Re-selecting unchanged evidence dedupes for free.

So on our side the states now genuinely exist — hashed and persisted — **before any call
to you.**

## The blocker: we cannot wire the skill call without §4.1 and §4.2

We deliberately stopped at the boundary rather than guess your wire contract — guessing it
is the exact failure the eight-version contract phase existed to prevent. Two things
unblock the rest:

1. **§4.1 — confirm the request `states[]` wire schema.** We're projecting to the
   `StateEnvelope` shape in your `financial/domain/types.ts`: `{sequence, label, stateType,
   lifecycleStage, source, capturedAt?, correlationId?, data}`, `stateType` ∈ your 9,
   `source` ∈ your 11. **If the HTTP request wraps or renames any of that, tell us the wire
   shape** — this is the one hard coupling, and our assembler's output is built against it.

2. **§4.2 — the `stateType` → which-rules-apply mapping.** Our projection infers each
   state's `stateType` (tester-overridable), and the financial rules mis-fire on a wrong
   tag. Right now our inference is a conservative placeholder that defaults to `OTHER`
   rather than guess. **A short rule-family → required-stateTypes table** (tax → ?, EBT
   reconciliation → ?, deltas → ?) is what turns our projection from "OTHER-tagged noise"
   into useful input. Highest-value thing you can send.

Also still open, lower priority: **`analysisId` format** (opaque fine, or a constraint?),
and **keep both skills unregistered** until we prove the path e2e — we'll signal.

## One thing we noticed on your side

You made **`api.fetch_order` real** (`6ad97a5`, against the Order Universal API). That's
directly good for us: it's the richest financial evidence source, and a real order
response now flows into our artifact store as an `api_response` — which our projector
already maps to `API_RESPONSE` / `ORDER_DB`. So the assemble-from-evidence path gets real
order snapshots the moment that skill runs, not demo data. Worth knowing that as you shape
the financial request schema: the order picture we'll feed a snapshot analysis is your own
Universal API response, unmodified.

## What we need from you

1. **§4.1** — confirm or correct the `states[]` wire schema.
2. **§4.2** — the `stateType` → rules mapping.

Both are in v1.0.17 already; this is only to say they're now the blocker, not background.
Everything else on our side (the UI evidence-picker) proceeds without you.
