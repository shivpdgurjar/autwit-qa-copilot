import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Comparison, type Snapshot } from '../../api/client';
import { useSession, sessionKey } from '../../hooks/useSession';
import { useSessionStream } from '../../hooks/useSessionStream';
import { useEndSession } from '../../hooks/useSubmitRun';
import { Composer } from '../../components/chat/Composer';
import { MessageList } from '../../components/chat/MessageList';
import { SkillPalette } from '../../components/chat/SkillPalette';
import { Timeline } from '../../components/timeline/Timeline';
import { EventBatchCard } from '../../components/timeline/EventBatchCard';
import { ArtifactDrawer } from '../../components/drawer/ArtifactViewer';
import { DiffViewer } from '../../components/drawer/DiffViewer';
import { FindingCounts, FindingsFeed } from '../../components/findings/FindingsFeed';
import { Mono, Muted, Spinner } from '../../components/ui';

export default function SessionRoute() {
  const { sessionId = '' } = useParams();
  const queryClient = useQueryClient();
  // One drawer slot: opening a comparison replaces an open artifact and vice versa.
  // Two overlapping drawers would fight for the same edge of the screen.
  const [drawer, setDrawer] = useState<
    { kind: 'snapshot'; snapshot: Snapshot } | { kind: 'comparison'; comparison: Comparison } | null
  >(null);
  const [palette, setPalette] = useState(false);

  const stream = useSessionStream(sessionId);
  const { data: session, isLoading, error } = useSession(sessionId, {
    pollWhileActive: stream.status !== 'open',
  });

  const endSession = useEndSession(sessionId);

  const cancelRun = useMutation({
    mutationFn: async (runId: string) =>
      api.POST('/runs/{runId}/cancel', { params: { path: { runId } } }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: sessionKey(sessionId) }),
  });

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-ink-400">
        <Spinner /> Loading session…
      </div>
    );
  }

  if (error || !session) {
    const problem = error as { detail?: string } | undefined;
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2">
        <p className="text-red-300">Could not load this session.</p>
        {problem?.detail && <p className="text-[12px] text-ink-400">{problem.detail}</p>}
        <Link to="/sessions" className="text-sky-400 hover:text-sky-300">
          ← All sessions
        </Link>
      </div>
    );
  }

  const ended = session.status !== 'active';

  return (
    <div className="flex h-full flex-col">
      <header className="flex items-center gap-3 border-b border-ink-700 bg-ink-900 px-4 py-2.5">
        <Link to="/sessions" className="text-ink-400 hover:text-ink-100">
          ←
        </Link>
        <div className="min-w-0">
          <h1 className="truncate text-sm font-semibold">{session.title ?? 'QA session'}</h1>
          <div className="flex flex-wrap items-center gap-x-2 text-[11px]">
            <Mono className="text-ink-400">{session.correlation_id}</Mono>
            <Muted>{session.env}</Muted>
            <Muted>{session.tester_id}</Muted>
            {ended && <span className="font-medium text-amber-300">{session.status}</span>}
          </div>
        </div>

        <div className="ml-auto flex items-center gap-4">
          <FindingCounts counts={session.counts?.findings_by_severity} />
          {ended ? (
            <a
              href={`/api/v1/sessions/${sessionId}/report`}
              target="_blank"
              rel="noreferrer"
              className="rounded border border-ink-700 px-2 py-1 text-[11px] text-sky-400 hover:border-ink-600"
            >
              Report ↗
            </a>
          ) : (
            <button
              onClick={() => endSession.mutate({ format: 'both' })}
              disabled={endSession.isPending}
              className="rounded border border-ink-700 px-2 py-1 text-[11px] text-ink-300 hover:border-ink-600 hover:text-ink-100 disabled:opacity-40"
            >
              End &amp; report
            </button>
          )}
          <StreamIndicator status={stream.status} />
        </div>
      </header>

      <div className="flex min-h-0 flex-1">
        {/* Chat drives the session; it is what a tester types into. */}
        <section className="flex w-[26rem] shrink-0 flex-col border-r border-ink-700 bg-ink-900/40">
          <div className="min-h-0 flex-1 overflow-y-auto">
            <MessageList session={session} onCancelRun={(id) => cancelRun.mutate(id)} />
          </div>
          <Composer sessionId={sessionId} disabled={ended} onOpenPalette={() => setPalette(true)} />
        </section>

        {/* The timeline is the record: milestones, snapshots, comparisons. */}
        <main className="min-w-0 flex-1 overflow-y-auto p-4">
          <Subjects subjects={session.subjects} />
          <Timeline
            session={session}
            onOpenSnapshot={(snapshot) => setDrawer({ kind: 'snapshot', snapshot })}
            onOpenComparison={(comparison) => setDrawer({ kind: 'comparison', comparison })}
            onCancelRun={(id) => cancelRun.mutate(id)}
          />
          <EventBatchCard sessionId={sessionId} count={session.counts?.events ?? 0} />
        </main>

        <aside className="flex w-80 shrink-0 flex-col border-l border-ink-700 bg-ink-900">
          <div className="border-b border-ink-700 px-3 py-2">
            <h2 className="text-[11px] font-semibold uppercase tracking-wide text-ink-400">
              Findings
            </h2>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto">
            <FindingsFeed findings={session.findings ?? []} />
          </div>
          <div className="flex gap-4 border-t border-ink-700 px-3 py-2 text-[11px]">
            <span>
              <Muted>artifacts</Muted>{' '}
              <Mono className="tabular-nums">{session.counts?.artifacts ?? 0}</Mono>
            </span>
            <span>
              <Muted>events</Muted>{' '}
              <Mono className="tabular-nums">{session.counts?.events ?? 0}</Mono>
            </span>
          </div>
        </aside>

        {/* Overlays rather than pushing: with chat, timeline and findings already on
            screen, a fourth column would squeeze the timeline into a gutter. */}
        {drawer && (
          <div className="absolute inset-y-0 right-0 top-[3.25rem] z-40 shadow-2xl">
            {drawer.kind === 'snapshot' ? (
              <ArtifactDrawer snapshot={drawer.snapshot} onClose={() => setDrawer(null)} />
            ) : (
              <DiffViewer
                comparisonId={drawer.comparison.comparison_id}
                onClose={() => setDrawer(null)}
              />
            )}
          </div>
        )}
      </div>

      <SkillPalette sessionId={sessionId} open={palette} onClose={() => setPalette(false)} />
    </div>
  );
}

function Subjects({ subjects }: { subjects?: Record<string, string> }) {
  const entries = Object.entries(subjects ?? {});
  if (entries.length === 0) return null;

  return (
    <div className="mb-3 flex flex-wrap items-center gap-1.5">
      {entries.map(([key, value]) => (
        <Mono
          key={key}
          className="rounded border border-ink-700 bg-ink-850 px-1.5 py-0.5"
          title="Business identifier under test — GIN-indexed, so this session is findable by it later"
        >
          <span className="text-ink-400">{key}</span> <span className="text-ink-100">{value}</span>
        </Mono>
      ))}
    </div>
  );
}

/**
 * Live vs polling, shown rather than hidden. A tester who cannot tell whether the
 * screen is current will not trust it — and since the poll fallback is a supported
 * mode rather than an error, it says "polling", not "disconnected".
 */
function StreamIndicator({ status }: { status: 'connecting' | 'open' | 'closed' }) {
  const [dot, label, title] =
    status === 'open'
      ? ['bg-emerald-400', 'live', 'Streaming updates over SSE']
      : status === 'connecting'
        ? ['bg-amber-400', 'connecting', 'Reconnecting to the event stream']
        : [
            'bg-ink-400',
            'polling',
            'Stream unavailable — falling back to polling. Data is still current.',
          ];

  return (
    <span className="flex items-center gap-1.5 text-[11px] text-ink-400" title={title}>
      <span className={`size-1.5 rounded-full ${dot}`} />
      {label}
    </span>
  );
}
