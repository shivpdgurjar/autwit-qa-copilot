import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  reportUrl,
  useAutomationRun,
  useAutomationRunLog,
  useCancelAutomationRun,
} from '../../hooks/useAutomation';
import { isAutomationActive } from '../../api/client';
import { Ago, Badge, Button, Card, Elapsed, EmptyState, Mono, Muted, Spinner } from '../../components/ui';
import { AutomationStatusBadge, passRate } from './AutomationStatusBadge';

/**
 * One automation run: what it is, what it did, its live output, and its report.
 *
 * The report is loaded from copilot's own origin. Allure's single-file output is one
 * self-contained document, so it frames cleanly — but only same-origin, which is the
 * reason it is proxied rather than linked straight at the runner.
 */
export default function AutomationRunRoute() {
  const { runId } = useParams<{ runId: string }>();
  const [tab, setTab] = useState<'log' | 'report'>('log');

  const run = useAutomationRun(runId);
  const active = isAutomationActive(run.data?.status);
  const log = useAutomationRunLog(runId, active);
  const cancel = useCancelAutomationRun();

  if (run.isLoading) {
    return <div className="p-8"><Spinner /></div>;
  }
  if (run.error || !run.data) {
    return (
      <div className="p-8">
        <EmptyState>That run could not be loaded.</EmptyState>
      </div>
    );
  }

  const r = run.data;

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-8">
      <div>
        <Link to="/automation" className="text-sm text-sky-700 hover:underline">
          ← All runs
        </Link>
      </div>

      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="flex items-center gap-3 text-xl font-semibold text-slate-900">
            <Mono>{r.queueKey}</Mono>
            <AutomationStatusBadge status={r.status} />
          </h1>
          <Muted>
            {r.suite}
            {r.tags ? ` · ${r.tags}` : ''} · started by {r.startedBy}
          </Muted>
        </div>
        {active ? (
          <Button
            variant="warn"
            onClick={() => runId && cancel.mutate(runId)}
            disabled={cancel.isPending || r.cancelRequested}
          >
            {r.cancelRequested ? 'Cancelling…' : 'Cancel run'}
          </Button>
        ) : null}
      </header>

      <div className="grid gap-4 sm:grid-cols-4">
        <Fact label="Environment"><Badge>{r.env}</Badge></Fact>
        <Fact label="Scenarios">{passRate(r.summary)}</Fact>
        <Fact label="Duration">
          {r.startedAt ? (
            <Elapsed ms={new Date(r.endedAt ?? Date.now()).getTime() - new Date(r.startedAt).getTime()} />
          ) : (
            '—'
          )}
        </Fact>
        <Fact label="Started"><Ago at={r.startedAt ?? r.queuedAt} /></Fact>
      </div>

      <div className="flex gap-2 border-b border-slate-200">
        <Tab active={tab === 'log'} onClick={() => setTab('log')}>Output</Tab>
        <Tab active={tab === 'report'} onClick={() => setTab('report')} disabled={!r.reportUrl}>
          Report
        </Tab>
      </div>

      {tab === 'log' ? (
        <Card className="p-0">
          <pre className="max-h-[32rem] overflow-auto whitespace-pre-wrap break-words p-4 font-mono text-xs leading-relaxed text-slate-700">
            {log.data?.lines?.length ? log.data.lines.join('\n') : 'No output yet.'}
          </pre>
        </Card>
      ) : r.reportUrl && runId ? (
        <Card className="overflow-hidden p-0">
          {/* Same-origin, so it frames. sandbox keeps the report's own scripts from
              reaching the app around it; allow-scripts is required because the
              single-file Allure report renders itself with JavaScript. */}
          <iframe
            title="Allure report"
            src={reportUrl(runId)}
            sandbox="allow-scripts allow-same-origin"
            className="h-[36rem] w-full border-0"
          />
        </Card>
      ) : (
        <EmptyState>This run produced no report.</EmptyState>
      )}
    </div>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Card className="p-4">
      <div className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</div>
      <div className="mt-1 text-sm text-slate-800">{children}</div>
    </Card>
  );
}

function Tab({
  active,
  disabled,
  onClick,
  children,
}: {
  active: boolean;
  disabled?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
        active
          ? 'border-sky-600 text-sky-700'
          : 'border-transparent text-slate-500 hover:text-slate-700 disabled:text-slate-300'
      }`}
    >
      {children}
    </button>
  );
}
