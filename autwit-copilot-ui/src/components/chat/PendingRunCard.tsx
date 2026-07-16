import type { Run } from '../../api/client';
import { Card, Elapsed, Mono, Muted, Spinner } from '../ui';

/**
 * The optimistic card for a run that has not finished.
 *
 * This is what POST returning `step_id` before execution is for (openapi.yaml: "The
 * step_id is known before the work executes, so the UI can render an optimistic
 * pending card"). The tester sees their action land instantly, then watches it.
 *
 * Elapsed time, never a progress bar — the server does not know how long a snapshot
 * will take, and inventing a percentage would be a lie.
 */
export function PendingRunCard({ run, onCancel }: { run: Run; onCancel?: (runId: string) => void }) {
  const queued = run.status === 'queued';

  return (
    <Card className="border-sky-900/60 bg-sky-950/20">
      <div className="flex items-center gap-2">
        <Spinner className="text-sky-400" />
        <span className="text-sm font-medium text-sky-200">
          {queued ? 'Queued' : 'Running'}
          {run.progress?.message ? ` — ${run.progress.message}` : ''}
        </span>
        <span className="ml-auto flex items-center gap-3">
          <Elapsed ms={run.elapsed_ms} />
          {onCancel && !queued && (
            <button
              onClick={() => onCancel(run.run_id)}
              disabled={run.cancel_requested}
              className="rounded border border-ink-700 px-1.5 py-0.5 text-[11px] text-ink-300 hover:border-ink-600 hover:text-ink-100 disabled:opacity-40"
            >
              {run.cancel_requested ? 'Cancelling…' : 'Cancel'}
            </button>
          )}
        </span>
      </div>

      {run.progress?.total !== undefined && run.progress.current !== undefined && (
        <div className="mt-1.5 text-[11px]">
          <Muted>
            {run.progress.current} of {run.progress.total}
          </Muted>
        </div>
      )}

      <div className="mt-1.5 flex gap-3 text-[11px]">
        <Mono className="text-ink-400">{run.run_id.slice(0, 8)}</Mono>
        {run.attempts !== undefined && run.attempts > 1 && (
          <Muted>attempt {run.attempts}</Muted>
        )}
      </div>
    </Card>
  );
}

/**
 * A run that ended without succeeding.
 *
 * timed_out is not failed, and the copy says so. SKILL_CONTRACT §9: it means the
 * outcome is UNKNOWN, so the UI says "may have partially completed; verify before
 * retrying" — the tester must not assume the order was not placed.
 */
export function FailedRunCard({ run }: { run: Run }) {
  const timedOut = run.status === 'timed_out';
  const problem = run.error as { code?: string; detail?: string; retryable?: boolean } | undefined;

  return (
    <Card className={timedOut ? 'border-amber-900/60 bg-amber-950/20' : 'border-red-900/60 bg-red-950/20'}>
      <div className="flex items-center gap-2">
        <span className={`text-sm font-semibold ${timedOut ? 'text-amber-300' : 'text-red-300'}`}>
          {timedOut ? 'Timed out — outcome unknown' : 'Failed'}
        </span>
        {problem?.code && (
          <Mono className="ml-auto rounded bg-ink-800 px-1.5 py-0.5 text-ink-300">{problem.code}</Mono>
        )}
      </div>

      {timedOut ? (
        <p className="mt-1.5 text-[12px] text-amber-200/80">
          This run may have partially completed. Verify the system state before retrying — retrying
          blindly could repeat work that already happened.
        </p>
      ) : (
        problem?.detail && <p className="mt-1.5 text-[12px] text-red-200/80">{problem.detail}</p>
      )}

      {problem?.retryable && !timedOut && (
        <p className="mt-1.5 text-[11px] text-ink-400">
          The orchestrator reported this as retryable.
        </p>
      )}
    </Card>
  );
}
