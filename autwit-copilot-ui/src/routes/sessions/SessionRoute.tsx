import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Snapshot } from '../../api/client';
import { useSession, sessionKey } from '../../hooks/useSession';
import { useSessionStream } from '../../hooks/useSessionStream';
import { Timeline } from '../../components/timeline/Timeline';
import { ArtifactDrawer } from '../../components/drawer/ArtifactViewer';
import { FindingCounts, FindingsFeed } from '../../components/findings/FindingsFeed';
import { Mono, Muted, Spinner } from '../../components/ui';

export default function SessionRoute() {
  const { sessionId = '' } = useParams();
  const queryClient = useQueryClient();
  const [drawer, setDrawer] = useState<Snapshot | null>(null);

  const stream = useSessionStream(sessionId);

  // The poll fallback engages only when the stream is not open. Invariant 4: SSE is a
  // hint, and the UI must stay live without it.
  const { data: session, isLoading, error } = useSession(sessionId, {
    pollWhileActive: stream.status !== 'open',
  });

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
    const problem = error as { detail?: string; status?: number } | undefined;
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

  const notes = (session.steps ?? []).filter((s) => s.kind === 'analysis');

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
            <SessionStatus status={session.status} />
          </div>
        </div>

        <div className="ml-auto flex items-center gap-4">
          <FindingCounts counts={session.counts?.findings_by_severity} />
          <StreamIndicator status={stream.status} />
        </div>
      </header>

      <div className="flex min-h-0 flex-1">
        <main className="min-w-0 flex-1 overflow-y-auto p-4">
          <Subjects subjects={session.subjects} />

          <Timeline
            session={session}
            onOpenSnapshot={setDrawer}
            onCancelRun={(runId) => cancelRun.mutate(runId)}
          />

          {/* Notes render here rather than the timeline: SKILL_CONTRACT §5 calls them
              the running-analysis channel, not the record. */}
          {notes.length > 0 && (
            <section className="mt-4">
              <h2 className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-ink-400">
                Analysis
              </h2>
              <ul className="space-y-1.5">
                {notes.map((note) => (
                  <li
                    key={note.step_id}
                    className="rounded-lg border border-ink-800 bg-ink-900/60 px-3 py-2 text-[12px] text-ink-300"
                  >
                    {note.label}
                  </li>
                ))}
              </ul>
            </section>
          )}
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
          <SessionCounts session={session} />
        </aside>

        {drawer && <ArtifactDrawer snapshot={drawer} onClose={() => setDrawer(null)} />}
      </div>
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
          title={`Discovered during this session and indexed for search`}
        >
          <span className="text-ink-400">{key}</span> <span className="text-ink-100">{value}</span>
        </Mono>
      ))}
    </div>
  );
}

function SessionCounts({ session }: { session: { counts?: { artifacts?: number; events?: number } } }) {
  return (
    <div className="flex gap-4 border-t border-ink-700 px-3 py-2 text-[11px]">
      <span>
        <Muted>artifacts</Muted> <Mono className="tabular-nums">{session.counts?.artifacts ?? 0}</Mono>
      </span>
      <span>
        <Muted>events</Muted> <Mono className="tabular-nums">{session.counts?.events ?? 0}</Mono>
      </span>
    </div>
  );
}

function SessionStatus({ status }: { status: string }) {
  if (status === 'active') return null;
  return <span className="font-medium text-amber-300">{status}</span>;
}

/**
 * Live vs polling, shown rather than hidden.
 *
 * A tester who cannot tell whether the screen is current will not trust it. Since the
 * poll fallback is a real, supported mode rather than an error, it says "polling" and
 * not "disconnected".
 */
function StreamIndicator({ status }: { status: 'connecting' | 'open' | 'closed' }) {
  const [dot, label, title] =
    status === 'open'
      ? ['bg-emerald-400', 'live', 'Streaming updates over SSE']
      : status === 'connecting'
        ? ['bg-amber-400', 'connecting', 'Reconnecting to the event stream']
        : ['bg-ink-400', 'polling', 'Stream unavailable — falling back to polling. Data is still current.'];

  return (
    <span className="flex items-center gap-1.5 text-[11px] text-ink-400" title={title}>
      <span className={`size-1.5 rounded-full ${dot}`} />
      {label}
    </span>
  );
}
