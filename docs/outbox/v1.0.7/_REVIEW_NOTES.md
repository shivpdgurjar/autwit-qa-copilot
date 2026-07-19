# Review notes for the v1.0.7 draft — read before sending

Not part of the message. Leave behind when copying to
`autwit-ai-orchestration/message-from-qa-copilot/v1.0.7/`.

## What actually changed in our repo

- `docs/SKILL_CONTRACT.md` — **replaced wholesale** with their v0.1.3, then three
  restorations: our `### Still open` changelog block, and the three §10 details (fixture
  path + rationale, the `medium` on `invoke_partial`, the catalog row's mutating/disabled
  note).
- `docs/openapi.yaml` — cursor description and example → `order.events` / epoch millis,
  plus a note that the value is an opaque ordering token.
- Nothing else. No Java, no fixtures, no tests.

## Verification done, and its limits

**Verified by reading files:**

- Every load-bearing concept from our v0.1.1 survives in v0.1.3 (checked by grep for
  10 specific phrases — code-point ordering, scale preservation, `USE_BIG_DECIMAL_FOR_FLOATS`,
  base64/binary, tiebreaker, ADR-001, `max_attempts = 1`, ≥24h, "not actionable", `medium`).
  This is why wholesale adoption is safe **now** and was not safe at v1.0.4.
- `source_offset` is `String` at all five layers; `EventRepository:59` orders by
  `captured_at, event_id`.
- Their §10 path is wrong in both the v1.0.6 snapshot and the live
  `transfer_from_qa_copilot/` copy (both line 514, byte-identical).
- All 8 fixtures are in `main/resources`; `src/test/resources/fixtures/orchestrator/`
  does not exist.

- **Suite green: 177 tests, 0 failures, 0 errors, 0 skipped**, counted from
  `target/surefire-reports/` restricted to files written by this run. Note the raw
  directory total is 178 — `DumpReportTest.txt` is a stale report from an earlier run and
  must be excluded, or the count silently overstates by one.

**Not verified:**

- The claim that `test/resources` fixtures "are not on the runtime classpath" is standard
  Maven behaviour, not something reproduced here.
- Surefire logged `going to kill self fork JVM` after `System.exit(0)`, from
  `PgNotificationListener` retrying against the stopped Testcontainers Postgres. Exit code
  was 0 and every test passed, so this is shutdown noise, not a failure — but it is
  pre-existing and worth fixing separately so it stops masking real exit problems.

## Judgement calls worth a second opinion

1. **Wholesale adoption over layering.** Reverses the v1.0.3 position. Justified because
   their body now genuinely carries the amendments — but it means their document is now
   upstream of ours, and any future regression on their side lands in our tree by default.
   The alternative was to keep layering forever, which is worse.
2. **Leaving `V1__init.sql:188` stale.** Flyway checksum on an applied migration. The
   comment is wrong until some future migration touches it. Defensible, but it is a known
   wrong string left in the tree deliberately — if that is not acceptable, the fix is a
   `V2__` migration that only rewrites the comment.
3. **Leaving fixtures + 3 tests old-shape.** Reasoning in §3 of the draft: a
   topic-only rename makes them internally inconsistent. If you would rather we go first
   and regenerate the fixtures ourselves rather than wait, that is a different and larger
   piece of work.
