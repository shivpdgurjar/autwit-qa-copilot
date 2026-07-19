# `logs/` — committed integration logs

`copilot-api.log` is written here by `logback-spring.xml` and is **meant to be
committed**. That is unusual, and it is deliberate.

The orchestrator reaches upstream APIs (the Event Store, the OMS) that are not available
from every machine. So an integration run happens on a laptop that has them, and is
diagnosed on one that does not. The log is the only thing that crosses. Whatever it does
not record cannot be recovered afterwards — there is no attaching a debugger to a run
that finished yesterday on someone else's machine.

## Producing one

```bash
cd autwit-copilot-api
DB_URL=jdbc:postgresql://localhost:55432/autwit DB_USER=autwit DB_PASSWORD=autwit \
ORCHESTRATOR_URL=http://localhost:9090 ORCHESTRATOR_TOKEN=… \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=all,integration
```

Note **`all,integration`** and the absence of `fake`. Without `integration` the file is
still written, but at INFO — no request or response bodies, which is usually the thing
you needed. With `fake` you are testing the fixtures, not the orchestrator.

Then drive whatever failed, and:

```bash
git add autwit-copilot-api/logs/copilot-api.log
git commit -m "Integration log: <what you ran>"
```

The log lands in `autwit-copilot-api/logs/` because that is the process working
directory. Override with `-DLOG_DIR=…` if you need it elsewhere.

## What the `integration` profile records

- **The full HTTP exchange with the orchestrator** — request and response bodies for
  `/invoke` and `/skills/{name}/execute`, at DEBUG on the `…orchestrator.wire` logger.
  This is the integration surface, and it is what settles "which side was wrong"
  arguments. Bodies are truncated at 16KB with the full length announced.
- **Every artifact's `content_hash`** — declared vs computed, plus the leading canonical
  bytes on a mismatch. A canonical-form disagreement is diagnosed from the bytes we
  produced, and by the time anyone reads this the body is long gone. This is not
  hypothetical: a null-dropping defect in `ContentHasher` rejected every `event_batch`
  the orchestrator sent, and the mismatch was invisible until the two hashers met.
- **Run lifecycle** — enqueue, claim, invoke, persist, terminal status.

## What it never records

**The bearer token.** `WireLog` prints only whether one was present. That distinction is
itself a diagnostic: copilot-api still defaults `ORCHESTRATOR_TOKEN` to empty, and since
v0.1.6 the orchestrator fails closed, so an unset variable yields `Bearer ` and a 401 that
is easy to mistake for a network problem. The log says
`authorization=Bearer <EMPTY - no token configured>` when that is what happened.

## Rotation

Capped at 20MB with three rotations. `copilot-api.1.log` and friends are gitignored —
commit the live file. If a run needs more than 20MB of log to explain itself, capture the
window that matters rather than committing the lot.
