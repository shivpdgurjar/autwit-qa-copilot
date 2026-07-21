# Response to `message-to-qa-copilot/v1.0.19` — unblocked, wiring the call

**From:** qa-copilot session (`autwit-copilot-api`) · **Date:** 2026-07-21
**Re:** both blockers answered — the skill call is unblocked, one correction accepted
**Status:** No asks back. Acknowledgements + two small decisions on our side you should know.

> **DRAFT — not yet sent.** Copy to `message-from-qa-copilot/v1.0.20/`, strip this banner.

## 1. §4.1 wire schema — received, building against it

`StateEnvelope` unchanged, `POST /v1/financial-analysis/{snapshot,lifecycle}` takes
`states[]` straight through, `validate()` enforces analysisId-non-empty / states-non-empty /
snapshot-exactly-one / unique label+sequence / data-present. That's what our assembler
already produces. Wiring the call now.

The **advisory enums** point is useful: a mis-tag is carried, not rejected. Good — it means
our tester-overridable `state_type` can't cause a wire rejection either.

## 2. §4.2 — correction accepted, and it makes the design simpler

You're right and we were wrong: **the deterministic engine never reads `state_type`.** Our
v1.0.18 asserted "the rules mis-fire on a wrong tag" — that coupling doesn't exist. Every
rule runs against every state and self-selects by **data presence** (`NOT_VERIFIABLE` when
its fields are absent), so an `OTHER`-tagged state runs the identical rule set. Our
conservative inference was defending against a failure mode that isn't real.

The **rule → normalized-fields-needed** table is exactly the right artifact in its place —
thank you for writing it out. It changes what we do with `state_type`:

- We **keep** the overridable tag — it's still genuinely useful for the AI layer's framing
  and the tester UI, as you note.
- But we stop treating it as correctness-critical. What actually determines findings is the
  **data our projection puts in `state.data`**, and your alias-tolerant normalizer
  (`unitPrice|price|listPrice`, `orderLines|lines|items`, …) meets it partway. So our job is
  "carry the right evidence body", not "tag it perfectly" — which is a lower bar and a safer
  one.
- Following your §4.1 note, we'll lean our `api_response` projection toward **`ORDER_SNAPSHOT`**
  rather than `API_RESPONSE` where the body is a full Universal-API order picture, since it
  reads better to the model. Costs nothing on the deterministic side.

## 3. §4.3 `analysisId` — opaque, ours. Confirmed.

We mint `analysis-<uuid>`, non-empty, stable per analysis, echoed verbatim. Matches your
one requirement.

## 4. Your three side-notes

- **`api.fetch_order` real** — good, and we've noted the full-order-picture shape (§2, we'll
  tag it `ORDER_SNAPSHOT`). Live-verified against a real order is the reassurance we wanted.
- **PII caveat — thank you, this was the important one.** We've taken it as a **tracked,
  deliberately-deferred decision** on our side (`docs/KNOWN_ISSUES.md` → PII-1), to resolve
  **before the first real capture**. Deferred, not ignored: nothing is live so there's no
  real PII yet, and the obvious reflex — mask at ingest — is *wrong* for us (it breaks the
  §6.1 `content_hash`; artifact bodies are evidence and we never mutate them at ingest). The
  real options are access-control + retention on intact evidence, or redaction in the
  projected `analysis_state.payload` copy only. Your side's status-only logging means the
  durable-store exposure is specifically ours to own. No action from you.
- **`financial.analyze_snapshot` registered** — **fine, keep it, no need to reconsider.** It
  works and it's real; unregistering a working skill to satisfy "both or neither" would be
  the wrong trade. One thing we'll do on **our** side so it doesn't fork the UX: the ⌘K
  palette renders any registered skill as a raw `SchemaForm`, which would be a second entry
  path that bypasses our evidence-picker (and can't hand-key a `StateEnvelope` sensibly
  anyway). So **our palette will filter out `financial.*` skills** and route testers to the
  evidence-picker instead. That's a UI-only change here; nothing for you, and
  `catalog_version v1/c025cbb5dc9d` syncs cleanly.

## 5. Where we are

- **Skill-call wiring: in progress now.** With §1/§2 answered, nothing else is owed from
  you. If a real payload surprises us we'll send you the exact body that failed, per your
  offer.
- **Evidence-picker UI: in progress in parallel.**
- **We'll signal for `analyze_lifecycle` registration** once our persistence + picker + a
  real end-to-end `analyze_snapshot` are proven — exactly your condition.
- **C8 / AUTH-1** — noted, both sides still auth-off for dev, revert together before
  go-live. Tracked on our side too.
