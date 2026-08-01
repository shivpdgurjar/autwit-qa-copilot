import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import type { PlanningSessionView } from '../../api/client';
import { useCreateSession, useSessions } from '../../hooks/usePlanning';
import { Ago, Card, EmptyState, Mono, Muted, Spinner } from '../../components/ui';

/**
 * The Planning Copilot landing: a tester's recent, resumable sessions. A session is a
 * history-bearing planning context — reopening one restores its work and lets the next
 * generation build on the last.
 */
export default function PlanListRoute() {
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const { data, isLoading, error } = useSessions();

  return (
    <div className="mx-auto max-w-3xl p-6">
      <img src="/AutwitLogo.png" alt="AutWit" className="mb-5 h-8 w-auto" />
      <div className="mb-1 flex items-center">
        <h1 className="text-lg font-semibold">Test Plan &amp; Data Studio</h1>
        <button
          onClick={() => setCreating(true)}
          className="ml-auto rounded bg-sky-700 px-3 py-1.5 text-[12px] font-medium text-white hover:bg-sky-600"
        >
          New session
        </button>
      </div>
      <p className="mb-4 text-[12px] text-ink-400">
        Resume a planning session, or start a new one. Each session keeps its history so later
        generations build on the earlier ones.
      </p>

      {creating && (
        <NewSessionForm
          onCancel={() => setCreating(false)}
          onCreated={(sessionId) => navigate(`/plan/${sessionId}`)}
        />
      )}

      {isLoading && (
        <p className="flex items-center gap-2 text-sm text-ink-400">
          <Spinner /> Loading…
        </p>
      )}
      {error && <p className="text-sm text-red-300">Could not reach the API. Is it running on :8080?</p>}
      {data && data.length === 0 && <EmptyState>No planning sessions yet.</EmptyState>}

      <ul className="space-y-2">
        {data?.map((s: PlanningSessionView) => (
          <li key={s.session_id}>
            <Link to={`/plan/${s.session_id}`} className="block">
              <Card className="hover:border-ink-600 hover:bg-ink-850">
                <div className="flex items-baseline gap-2">
                  <span className="text-sm font-medium">{s.title ?? 'Planning session'}</span>
                  {s.chainable && (
                    <span
                      title="Has generation history the next request builds on"
                      className="rounded bg-sky-700/15 px-1.5 py-0.5 text-[10px] text-sky-400"
                    >
                      history
                    </span>
                  )}
                  {s.status !== 'active' && <span className="text-[11px] text-amber-300">{s.status}</span>}
                  <span className="ml-auto text-[11px]">
                    <Ago at={s.last_active_at} />
                  </span>
                </div>
                <div className="mt-1 flex items-center gap-2 text-[11px]">
                  {s.env && <Muted>{s.env}</Muted>}
                  {s.tester_id && <Mono className="text-ink-400">{s.tester_id}</Mono>}
                </div>
              </Card>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

function NewSessionForm({
  onCancel,
  onCreated,
}: {
  onCancel: () => void;
  onCreated: (sessionId: string) => void;
}) {
  const [tester, setTester] = useState('');
  const [env, setEnv] = useState('');
  const [title, setTitle] = useState('');
  const [featureKey, setFeatureKey] = useState('');
  const [description, setDescription] = useState('');
  const create = useCreateSession();

  const field =
    'w-full rounded border border-ink-700 bg-ink-950 px-2 py-1 text-[12px] outline-none focus:border-sky-700';

  return (
    <Card className="mb-4">
      <div className="grid grid-cols-2 gap-2">
        <label className="block">
          <span className="mb-1 block text-[11px] text-ink-400">tester</span>
          <input className={field} value={tester} placeholder="you" onChange={(e) => setTester(e.target.value)} />
        </label>
        <label className="block">
          <span className="mb-1 block text-[11px] text-ink-400">env</span>
          <input className={field} value={env} placeholder="qa2" onChange={(e) => setEnv(e.target.value)} />
        </label>
        <label className="block">
          <span className="mb-1 block text-[11px] text-ink-400">Jira epic / feature key</span>
          <input className={field} value={featureKey} placeholder="PAY-2481"
            onChange={(e) => setFeatureKey(e.target.value)} />
        </label>
        <label className="block">
          <span className="mb-1 block text-[11px] text-ink-400">title</span>
          <input className={field} value={title} placeholder="Payment retry plan"
            onChange={(e) => setTitle(e.target.value)} />
        </label>
        <label className="col-span-2 block">
          <span className="mb-1 block text-[11px] text-ink-400">What are we testing?</span>
          <textarea className={`${field} font-sans`} rows={2} value={description}
            placeholder="Payment retry logic — automatic retries with backoff…"
            onChange={(e) => setDescription(e.target.value)} />
        </label>
      </div>

      {create.error != null && (
        <p className="mt-2 text-[11px] text-red-300">
          {(create.error as { detail?: string }).detail ?? 'Could not create the session.'}
        </p>
      )}

      <div className="mt-2.5 flex gap-2">
        <button onClick={onCancel} className="rounded border border-ink-700 px-2 py-1 text-[11px]">
          Cancel
        </button>
        <button
          onClick={() =>
            create.mutate(
              {
                tester_id: tester || undefined,
                env: env || undefined,
                title: title || undefined,
                feature_key: featureKey || undefined,
                feature_description: description || undefined,
              },
              { onSuccess: (r) => onCreated(r.session!.session_id) },
            )
          }
          disabled={create.isPending}
          className="ml-auto rounded bg-sky-700 px-3 py-1 text-[12px] font-medium text-white hover:bg-sky-600 disabled:opacity-40"
        >
          {create.isPending ? 'Creating…' : 'Create session'}
        </button>
      </div>
    </Card>
  );
}
