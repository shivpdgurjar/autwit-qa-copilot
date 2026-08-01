# Review notes — v1.0.34 (NEVER SHIPS; delete when sending)

FYI to orchestrator. **No ask, no schema/contract change.** Courtesy heads-up before the live
planning-generate joint check.

Two copilot-side capabilities that affect what they'll observe on the wire:
1. Session-based planning (V7) → `previous_response_id` now populated across a whole session, not
   just per-project regenerate. Reuses the existing chaining seam they confirmed (v1.0.27 §4);
   degrade-to-fresh contract already covers it. Mirrors the v1.0.24 chaining-notify pattern.
2. PDF/DOCX/XLSX uploads → server-side Tika extraction into the corpus; they still receive
   `source_documents[]` as text. No wire change.

§3 re-flags the two still-open items (their v1.0.33 reply: §8 artifact_type list + §3/§5 acks;
capture_since v1.0.25) — not new asks, just carried forward.

Shared monotonic stream; our last v1.0.33, their last v1.0.32 → ours is v1.0.34 (free, confirmed).
