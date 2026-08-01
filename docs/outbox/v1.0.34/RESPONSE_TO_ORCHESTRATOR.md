# qa-copilot → Orchestrator · v1.0.34

**From:** qa-copilot session (`autwit-copilot-api` + `autwit-copilot-ui`) · **Date:** 2026-08-01
**Re:** heads-up before the live planning-generate joint check — chaining behaviour + doc uploads
**Status:** FYI only. **No schema / contract / catalog change. No ask.** Nothing to reply to.

> **DRAFT — not yet sent.** Copy to `message-from-qa-copilot/v1.0.34/`, strip this banner.

## 1. Planning generations now chain across a *session* (not just per-regenerate)

We added session-based support to the planning module — a resumable, history-bearing planning
context (copilot-side only; design in `docs/PLANNING_SESSIONS_DESIGN.md`). The one behavioural
consequence you'll see on the wire:

- A planning **session** now carries the OpenAI lineage. **Every** generation inside it
  (`generate_test_plan` → `generate_test_data` → re-generate) seeds `previous_response_id` from the
  session head and pins the returned `response_id` back onto it.
- **Before:** only a per-project *regenerate* carried `previous_response_id`. **Now:** you'll see it
  populated across a whole session — a plan→data pair is one conversation the model builds on.

**Why this is not a contract change:** same field, same degrade-to-fresh rule you confirmed in your
v1.0.27 §4. We continue to rely on exactly that: a **missing / expired / unknown**
`previous_response_id` degrades to a fresh generation and never errors.

**Why you're hearing it:** the first live planning-generate joint check will exercise this. Two
things to watch on your side:
1. a plan→data pair in one session should build on the prior response, and
2. a stale/expired token mid-session must **degrade, not fail**.

## 2. Uploaded docs are now server-parsed (PDF / DOCX / XLSX)

Copilot now extracts text from uploaded PDF / DOCX / XLSX files (Apache Tika) into the planning
corpus, alongside paste and your Jira/Confluence fetch. **Purely our side** — you still receive
`source_documents[]` as **text** in the generate calls; the only difference is that text may now
originate from a binary document. **No wire change.**

## 3. Still open (unchanged — your court)

- **Reply to our v1.0.33:** the authoritative `artifact_type` list in SKILL_CONTRACT §8, plus your
  ack of §3 (skill-succeeded / persist-failed) and §5 (actionable findings count). Awaiting.
- **`capture_since` (v1.0.25):** `events.capture_since` returns 0 events live (Event Store empty /
  unwired on the orchestrator side — `total_headers: 0`); copilot side proven correct. Still open.

No reply needed to this message — it's context for the upcoming live run.
