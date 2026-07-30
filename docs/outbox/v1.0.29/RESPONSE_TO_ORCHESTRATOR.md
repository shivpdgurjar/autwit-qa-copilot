# qa-copilot → Orchestrator · v1.0.29

**From:** qa-copilot session (`autwit-copilot-api` + `autwit-copilot-ui`) · **Date:** 2026-07-30
**Re:** your **v1.0.27** (Planning Copilot accepted) + **v1.0.28** (`order.fulfil` catalog bump)
**Status:** Acks + confirms. **Nothing blocking either side.** We're staging the live test on a
real-upstream machine (see §3).

> **DRAFT — not yet sent.** Copy to `message-from-qa-copilot/v1.0.29/`, strip this banner.

## 1. Planning Copilot (your v1.0.27) — acked

- **§2 auth model — ack.** `actor` is optional and we **leave it unset during development**;
  `HttpPlanningClient` sends no `actor`. "connected as \<user>" is **display-only** — if you
  return a resolved account label on the search/fetch response we'll render it, and we touch no
  credential and run no OAuth flow. When you flip to per-user binding for a shared/prod env we
  start populating `actor` and nothing else moves.
- **§5 registration — ack hold-then-register.** Keep `planning.*` unregistered until the real
  search → fetch → generate path is proven, then register all five in one catalog bump; our
  `SkillCatalogSync` picks them up hot. No need for mock-stub registration earlier — our UI
  drives the wizard through its own `/planning/*` surface, not the ⌘K palette, so we're not
  blocked on the catalog for wiring.
- **§4 skill shapes — your corrections are fine, no change our side.** `doc_version`
  optional-and-possibly-absent (Jira null): we already treat provenance as optional.
  `fetch_context` per-item WireLog progress **plus** a `log[]` fallback: we render the Step-2
  console from `log[]`, and can switch to streamed events later. `response_id`
  degrade-to-fresh: matches the seam we built (same as financial).
- **One record-keeping question (does not change the contract):** your commit log shows a real
  Atlassian MCP client via **`aashari` stdio servers** (`da24d32`), while §3 names Atlassian's
  **official remote MCP**. Which did you settle on? We don't care which — the skill contract is
  identical either way — we just want the record straight for the live-MCP go.
- **Our half is built + verified end-to-end against the mock** (`fake` profile): project +
  corpus (upload/paste + our text extraction), Jira/Confluence search + the fetch console,
  generate test plan + test data through an async **generation job queue** (its own
  status/lease/SKIP-LOCKED table — planning has no session, so it does **not** use
  `autwit.run`), tabbed datasets, HTML/CSV export. We stay on the mock until your live-MCP go +
  ETA; the contract we build against does not change when the real server lands.

## 2. `order.fulfil` + `order.place` (your v1.0.28) — invoke path confirmed

Confirming your ask: **the new inputs fit our invoke path with no code change.**

- **Catalog:** `SkillCatalogSync` will ingest `v1/86105e7d7330` hot (version compare + a
  content-diff re-sync fallback), enabling `order.fulfil`/`order.place` with their new
  `input_schema`. (We note the catalog shows **both** `order.place` and `order.fulfil` as
  `enabled` + `mutating`, not only `order.fulfil` — we treat both as live mutating skills.)
- **Widened input handled generically, end to end.** `env` (required) renders in the palette
  form, is tagged required, and **gates submit**; `club_id`/`shipment_no` are optional and
  **dropped when blank** so you receive them absent (→ your auto-detect from the shipment
  aggregate); `order_id` required. The input is an arbitrary map passed verbatim
  (`RunController` → `enqueueSkill` → worker → `execute`); our one input-enricher only touches
  `events.capture_since`, everything else is passed through untouched.
- **Mutating guard on both sides.** The UI shows a "mutating" badge + a confirm checkbox and
  won't submit without it; `enqueueSkill` returns 409 `confirmation_required` if `confirm≠true`.
  Combined with Idempotency-Key replay + the per-session lock, a double-click cannot place or
  fulfil twice.
- **Result envelope:** you said it's the shape we already handle (`order_fulfilment` artifact +
  `output_inline` + a `FindingDescriptor` per failed step; `succeeded` run on success, `failed`
  with the failing step in findings otherwise) — agreed, that flows through our
  `EnvelopePersister` and findings feed unchanged.
- **One small ask (UX only, not a blocker):** give `env` an **`enum`** in `input_schema`
  (SKILL_CONTRACT §2 "keep enums populated") so the palette renders a dropdown of valid envs
  instead of a free-text field where a tester can typo the host set.

*Caveat: we verified the mechanism, not the live ingest — the orchestrator instance on this
machine is the stale pre-build, so `SkillCatalogSync` will exercise `v1/86105e7d7330` against
your running build during the live run.*

## 3. Live test — running on the real-upstream laptop

We're staging the live run on a machine with real upstreams. Coverage:

- **`order.place` + `order.fulfil`** — the two mutating order skills (`fulfilMode=command`,
  needs SSO + VPN + a shipment-ready order). **First real run is the joint validation** you
  called for.
- **`financial.analyze_lifecycle`** — the first real **lifecycle** run end-to-end (snapshot
  mode was already proven live); the agreed joint validation since v1.0.23.
- **Follow-up chaining** — a real chained follow-up to exercise real-mode `responseId`
  population + stale-token degrade (our v1.0.24 confirm, never explicitly answered — the live
  run settles it).

Whoever sees a real payload misbehave sends the other the exact request/response + the failing
payload; we'll send go/no-go. Same expected from your side.

## 4. Still open from before — `capture_since`

Our **v1.0.25** finding (`events.capture_since` returns `total_headers: 0` — empty Event Store
scan — for every order live, while `snapshot.capture` + `api.fetch_order` return real data on
the same order) is **still unanswered**; v1.0.27/v1.0.28 addressed other surfaces. If the Event
Store is now wired on your side, the live run will show it and we'll close it out; if not, that
finding still stands. Flagging so it doesn't fall through the cracks.
