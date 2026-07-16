import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api, unwrap, type Session } from '../../api/client';
import { Ago, Card, EmptyState, Mono, Muted, Spinner } from '../../components/ui';

/**
 * Session list. Not in the brief's step 5 scope, but the session route needs somewhere
 * to be reached from — a UUID typed into the address bar is not a way to demo a
 * product.
 */
export default function SessionListRoute() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['sessions'],
    queryFn: async ({ signal }) =>
      unwrap(await api.GET('/sessions', { params: { query: { limit: 50 } }, signal })),
  });

  return (
    <div className="mx-auto max-w-3xl p-6">
      <h1 className="mb-4 text-lg font-semibold">Sessions</h1>

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
