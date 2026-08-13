import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  ConfirmationRequired,
  useAutomationRuns,
  useStartAutomationRun,
} from '../../hooks/useAutomation';
import type { AutomationConfirmationRequired, AutomationRun } from '../../api/client';
import { Ago, Badge, Button, Card, EmptyState, Input, Mono, Muted, Spinner } from '../../components/ui';
import { AutomationStatusBadge, passRate } from './AutomationStatusBadge';

/**
 * The automation plane's run list, and the form that starts a run.
 *
 * Counts come from the run record rather than from the report: the run service extracts
 * them once at the end, so a list of fifty runs costs fifty rows, not fifty
 * multi-megabyte reports.
 */
export default function AutomationListRoute() {
  const [env, setEnv] = useState('qa3');
  const [suite, setSuite] = useState('testng.xml');
  const [tags, setTags] = useState('');
  const [startedBy, setStartedBy] = useState('');
  const [pendingConfirm, setPendingConfirm] = useState<AutomationConfirmationRequired | null>(null);

  const runs = useAutomationRuns();
  const start = useStartAutomationRun();

  function submit(confirm: boolean) {
    start.mutate(
      { env, suite, tags: tags || undefined, startedBy: startedBy || 'unknown', confirm },
      {
        onSuccess: () => setPendingConfirm(null),
        onError: (error) => {
          if (error instanceof ConfirmationRequired) {
            setPendingConfirm(error.detail);
          }
        },
      },
    );
  }

  const unavailable =
    start.error && !(start.error instanceof ConfirmationRequired) ? start.error : null;

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-8">
      <header>
        <h1 className="text-xl font-semibold text-slate-900">Automation</h1>
        <Muted>Trigger an AUTWIT suite and watch it run.</Muted>
      </header>

      <Card className="space-y-4 p-5">
        <div className="grid items-start gap-3 sm:grid-cols-4">
          <label className="flex flex-col gap-1">
            <span className="text-xs font-medium text-slate-600">Environment</span>
            <Input className="w-full" value={env} onChange={(e) => setEnv(e.target.value)} placeholder="qa3" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs font-medium text-slate-600">Suite</span>
            <Input className="w-full" value={suite} onChange={(e) => setSuite(e.target.value)} placeholder="testng.xml" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs font-medium text-slate-600">Tags</span>
            <Input
              className="w-full"
              value={tags}
              onChange={(e) => setTags(e.target.value)}
              placeholder="@bopic and not @wip"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs font-medium text-slate-600">Started by</span>
            <Input className="w-full" value={startedBy} onChange={(e) => setStartedBy(e.target.value)} placeholder="you" />
          </label>
        </div>

        <div className="flex items-center gap-3">
          <Button onClick={() => submit(false)} disabled={start.isPending || !env || !suite}>
            {start.isPending ? <Spinner className="mr-2" /> : null}
            Start run
          </Button>
          {unavailable ? (
            <span className="text-sm text-rose-600">
              {(unavailable as { message?: string }).message ?? 'Could not start the run'}
            </span>
          ) : null}
        </div>

        {pendingConfirm ? (
          <ConfirmOverlap
            detail={pendingConfirm}
            busy={start.isPending}
            onCancel={() => setPendingConfirm(null)}
            onConfirm={() => submit(true)}
          />
        ) : null}
      </Card>

      {runs.isLoading ? (
        <Spinner />
      ) : runs.error ? (
        <EmptyState>
          The AUTWIT run service is unreachable. Check that the runner is up — nothing in the
          copilot is broken.
        </EmptyState>
      ) : !runs.data?.length ? (
        <EmptyState>No runs yet.</EmptyState>
      ) : (
        <RunTable runs={runs.data} />
      )}
    </div>
  );
}

/**
 * The soft gate. Not an error dialog — the environment already has a run and overlapping
 * is allowed, so this names who is running and lets the tester decide.
 */
function ConfirmOverlap({
  detail,
  busy,
  onCancel,
  onConfirm,
}: {
  detail: AutomationConfirmationRequired;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <div className="rounded-lg border border-amber-300 bg-amber-50 p-4">
      <p className="text-sm font-medium text-amber-900">{detail.message}</p>
      <ul className="mt-2 space-y-1 text-sm text-amber-800">
        {(detail.active_runs ?? []).map((run) => (
          <li key={run.runId}>
            <Mono>{run.queueKey}</Mono> — started by {run.startedBy} <Ago at={run.startedAt ?? run.queuedAt} />
          </li>
        ))}
      </ul>
      <div className="mt-3 flex gap-2">
        <Button onClick={onConfirm} disabled={busy}>
          Run anyway
        </Button>
        <Button variant="ghost" onClick={onCancel} disabled={busy}>
          Cancel
        </Button>
      </div>
    </div>
  );
}

function RunTable({ runs }: { runs: AutomationRun[] }) {
  return (
    <Card className="overflow-x-auto">
      <table className="w-full min-w-[46rem] text-sm">
        <thead>
          <tr className="border-b border-slate-200 text-left text-xs uppercase tracking-wide text-slate-500">
            <th className="px-4 py-3 font-medium">Run</th>
            <th className="px-4 py-3 font-medium">Env</th>
            <th className="px-4 py-3 font-medium">Started by</th>
            <th className="px-4 py-3 font-medium">Status</th>
            <th className="px-4 py-3 font-medium">Scenarios</th>
            <th className="px-4 py-3 font-medium">Started</th>
          </tr>
        </thead>
        <tbody>
          {runs.map((run) => (
            <tr key={run.runId} className="border-b border-slate-100 last:border-0 hover:bg-slate-50">
              <td className="px-4 py-3">
                <Link to={`/automation/${run.runId}`} className="text-sky-700 hover:underline">
                  <Mono>{run.queueKey}</Mono>
                </Link>
                <div>
                  <Muted>{run.suite}</Muted>
                </div>
              </td>
              <td className="px-4 py-3">
                <Badge>{run.env}</Badge>
              </td>
              <td className="px-4 py-3 text-slate-700">{run.startedBy}</td>
              <td className="px-4 py-3">
                <AutomationStatusBadge status={run.status} />
              </td>
              <td className="px-4 py-3 text-slate-700">{passRate(run.summary)}</td>
              <td className="px-4 py-3">
                <Muted>
                  <Ago at={run.startedAt ?? run.queuedAt} />
                </Muted>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}
