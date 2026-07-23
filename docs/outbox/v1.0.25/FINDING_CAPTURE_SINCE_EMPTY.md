# Finding: `events.capture_since` returns 0 events (`total_headers: 0`) in live command mode

**From:** qa-copilot session (`autwit-copilot-api`) · **Date:** 2026-07-23
**Re:** first real-upstream live run (`docs/LIVE_INTEGRATION_TEST.md`), Flow B
**Status:** One finding to check on your side. **Copilot side is proven correct on the wire**
(evidence below) — this is about the Event Store query behind `events.capture_since`.

> **DRAFT — not yet sent.** Copy to `message-from-qa-copilot/v1.0.25/`, strip this banner.

## Symptom

On the live laptop (orchestrator in command mode, real upstreams), **every**
`events.capture_since` call returns zero events — for the real order `2648100002` and for
the mock `XXXX` alike. The invocation succeeds (`exit_code: 0`), just with an empty batch.

The tell is in your `output_inline`: **`total_headers: 0`**. That's the raw header count from
the Event Store scan *before* any `since` filtering, and `filtered: 0` alongside it — so the
source returned nothing to filter. This is not a cursor/`since` cutoff issue; the scan itself
comes back empty.

## Evidence (our integration wire log, run `c920d7ab-…`)

**What we sent you** — note: no `since_producer_time`, empty `event_cursors` (first capture,
no stored cursor → we correctly omit the key, meaning "from the beginning" per §6.3):

```json
POST /skills/events.capture_since/execute
{
  "session_id": "74e1a49f-73d2-4413-b53f-f9fcb9fb490a",
  "run_id": "c920d7ab-117a-4496-aa50-47921724149a",
  "input": { "order_id": "2648100002" },
  "session_context": { "env": "qa2", "milestones": [], "event_cursors": {}, ... }
}
```

**What you returned** — HTTP 200, succeeded, empty:

```json
{
  "status": "succeeded",
  "invocations": [{
    "skill_name": "events.capture_since", "skill_version": "1.2.0",
    "status": "succeeded", "exit_code": 0,
    "output_inline": { "events": 0, "total_headers": 0, "filtered": 0 }
  }],
  "artifacts": [{
    "artifact_type": "event_batch", "source_system": "eventstore",
    "logical_name": "order_events", "body": [], "row_count": 0,
    "content_hash": "sha256:4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945"
  }],
  "events": [], "cursors_advanced": {}, "milestone": null, "error": null
}
```

That empty-batch `content_hash` (`sha256:4f53cda…`) is **byte-identical across every
`events.capture_since` run in the log** — the mock `XXXX` runs and the real `2648100002`
run all produce the same empty `event_batch`.

## Why we're confident it's not our side

- The wire log shows we send the right `input` and **no bogus `since`** — no
  `since_producer_time: null`/`0`, the key is simply absent (there was no cursor to inject).
  `SkillInputs.withEventCursor` only injects a real epoch-millis cursor, never a null.
- We faithfully persisted exactly what you returned: 1 empty `event_batch` artifact, 0 events.
  `Run … succeeded: 1 artifacts, 0 new events`.

## The contrast that localizes it

Your **other** command-mode upstreams are clearly live and returning real data on the same
order, same session lineage:

- `snapshot.capture` on `2648100002` returned **9 real tables** and discovered shipment
  **`SHP-99`** in `shipment_pg` (log: *"Captured 9 tables. Shipment SHP-99 discovered…"*).
- `api.fetch_order` on `2648100002` succeeded and drove a real `financial.analyze_snapshot`
  (verdict FAIL / PASS_WITH_WARNINGS).

So the OMS order API and the compare DBs are wired up — **only the Event Store path behind
`events.capture_since` returns nothing.**

## What we'd ask you to check

1. **Event Store connection in command mode** — is `events.capture_since`'s event source
   actually pointed at the real store, or falling back to an empty/stub default? `total_headers:
   0` for *every* order (including a real one that has snapshot + order data) reads like an
   unconfigured or empty source rather than a genuinely event-less order.
2. **Order → event key/topic mapping** — if the store *is* connected, does `2648100002` have
   events under the key/topic your scan uses? Events may be keyed by something other than the
   order number (a stream id, an internal order key, a different partition/topic).
3. **(Sanity, low priority)** confirm absent `since_producer_time` is treated as "from the
   beginning," not "from now." `total_headers: 0` already rules this out as the cause here
   (nothing was scanned to filter), but worth confirming the semantics match §6.3 while you're
   in there.

## What we need back

Whether `events.capture_since` in your live command-mode env is (a) reaching a real Event
Store, and (b) what key/topic it scans for a given `order_id` — so we can confirm whether
`2648100002` genuinely has no events or the source is misconfigured. If you can reproduce with
a known event-bearing order and send us the resulting `output_inline` + `cursors_advanced`,
that pins it immediately.

No copilot change is implied. If it turns out the store is fine and the order simply has no
events, that's a clean "working as intended" and we'll note it in the runbook.
