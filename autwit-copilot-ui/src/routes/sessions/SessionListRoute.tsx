import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { api, unwrap, type Session } from '../../api/client';
import { Ago, Card, EmptyState, Mono, Muted, Spinner } from '../../components/ui';

/**
 * Session list. Not in the brief's step 5 scope, but the session route needs somewhere
 * to be reached from — a UUID typed into the address bar is not a way to demo a
 * product.
 */
export default function SessionListRoute() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [creating, setCreating] = useState(false);

  const { data, isLoading, error } = useQuery({
    queryKey: ['sessions'],
    queryFn: async ({ signal }) =>
      unwrap(await api.GET('/sessions', { params: { query: { limit: 50 } }, signal })),
  });

  return (
    <div className="mx-auto max-w-3xl p-6">
      <div className="mb-4 flex items-center">
        <h1 className="text-lg font-semibold">Sessions</h1>
        <button
          onClick={() => setCreating(true)}
          className="ml-auto rounded bg-sky-700 px-3 py-1.5 text-[12px] font-medium text-white hover:bg-sky-600"
        >
          New session
        </button>
      </div>

      {creating && (
        <NewSessionForm
          onCancel={() => setCreating(false)}
          onCreated={(session) => {
            void queryClient.invalidateQueries({ queryKey: ['sessions'] });
            navigate(`/sessions/${session.session_id}`);
          }}
        />
      )}

      {isLoading && (
        <p className="flex items-center gap-2 text-sm text-ink-400">
          <Spinner /> Loading…
        </p>
      )}

      {error && (
        <p className="text-sm text-red-300">
          Could not reach the API. Is it running on :8080?
        </p>
      )}

      {data && data.sessions.length === 0 && (
        <EmptyState>No sessions yet.</EmptyState>
      )}

      <ul className="space-y-2">
        {data?.sessions.map((session: Session) => (
          <li key={session.session_id}>
            <Link to={`/sessions/${session.session_id}`} className="block">
              <Card className="hover:border-ink-600 hover:bg-ink-850">
                <div className="flex items-baseline gap-2">
                  <span className="text-sm font-medium">{session.title ?? 'QA session'}</span>
                  {session.status !== 'active' && (
                    <span className="text-[11px] text-amber-300">{session.status}</span>
                  )}
                  <span className="ml-auto text-[11px]">
                    <Ago at={session.started_at} />
                  </span>
                </div>
                <div className="mt-1 flex flex-wrap items-center gap-x-2 text-[11px]">
                  <Mono className="text-ink-400">{session.correlation_id}</Mono>
                  <Muted>{session.env}</Muted>
                  <Muted>{session.tester_id}</Muted>
                </div>
                {session.subjects && Object.keys(session.subjects).length > 0 && (
                  <div className="mt-1.5 flex flex-wrap gap-1">
                    {Object.entries(session.subjects).map(([key, value]) => (
                      <Mono
                        key={key}
                        className="rounded border border-ink-700 bg-ink-850 px-1.5 py-0.5 text-ink-300"
                      >
                        {key} {value}
                      </Mono>
                    ))}
                  </div>
                )}
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
  onCreated: (session: Session) => void;
}) {
  const [testerId, setTesterId] = useState('priya');
  const [env, setEnv] = useState('qa2');
  const [title, setTitle] = useState('');
  const [orderId, setOrderId] = useState('');

  const create = useMutation({
    retry: false,
    mutationFn: async () =>
      unwrap(
        await api.POST('/sessions', {
          body: {
            tester_id: testerId,
            env,
            title: title || undefined,
            // Subjects are GIN-indexed, which is how this session gets found by order
            // id months later when someone asks "did we ever test this?"
            subjects: orderId ? { order_id: orderId } : undefined,
          },
        }),
      ) as Session,
    onSuccess: onCreated,
  });

  const field = 'w-full rounded border border-ink-700 bg-ink-950 px-2 py-1 text-[12px] outline-none focus:border-sky-700';

  return (
    <Card className="mb-4">
      <div className="grid grid-cols-2 gap-2">
        <label className="block">
          <span className="mb-1 block text-[11px] text-ink-400">tester_id</span>
          <input className={field} value={testerId} onChange={(e) => setTesterId(e.target.value)} />
        </label>
        <label className="block">
          <span className="mb-1 block text-[11px] text-ink-400">env</span>
          <input className={field} value={env} onChange={(e) => setEnv(e.target.value)} />
        </label>
        <label className="block">
          <span className="mb-1 block text-[11px] text-ink-400">title</span>
          <input
            className={field}
            value={title}
            placeholder="Order flow"
            onChange={(e) => setTitle(e.target.value)}
          />
        </label>
        <label className="block">
          <span className="mb-1 block text-[11px] text-ink-400">order_id (subject)</span>
          <input
            className={field}
            value={orderId}
            placeholder="XXXX"
            onChange={(e) => setOrderId(e.target.value)}
          />
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
          onClick={() => create.mutate()}
          disabled={!testerId.trim() || !env.trim() || create.isPending}
          className="ml-auto rounded bg-sky-700 px-3 py-1 text-[12px] font-medium text-white hover:bg-sky-600 disabled:opacity-40"
        >
          {create.isPending ? 'Starting…' : 'Start session'}
        </button>
      </div>
    </Card>
  );
}
