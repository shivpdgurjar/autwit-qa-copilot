import { useQuery } from '@tanstack/react-query';
import { api, unwrap, type SessionDetail } from '../api/client';

export const sessionKey = (sessionId: string) => ['session', sessionId] as const;

/**
 * GET /sessions/{id} — the single source of truth.
 *
 * openapi.yaml: "THE source of truth. The UI hydrates from this on load and refetches
 * after any run.succeeded / run.failed notification."
 *
 * Everything the session view renders comes from here. No component derives state from
 * an SSE payload, because invariant 4 says a dropped notification must be harmless —
 * and it only is if the notification never carried anything we needed.
 */
export function useSession(sessionId: string, options?: { pollWhileActive?: boolean }) {
  const query = useQuery({
    queryKey: sessionKey(sessionId),
    queryFn: async ({ signal }) =>
      unwrap(
        await api.GET('/sessions/{sessionId}', {
          params: { path: { sessionId } },
          signal,
        }),
      ) as SessionDetail,

    /**
     * The poll fallback, and only a fallback (invariant 4: "If the connection drops,
     * the UI falls back to polling"). While the stream is healthy this stays off and
     * SSE drives the refetches; useSessionStream flips it on when the stream dies.
     *
     * Polling only while runs are active: an idle session is static, and a 3s poll of
     * a full timeline for a tester who went to lunch is pure waste.
     */
    refetchInterval: (query) => {
      if (!options?.pollWhileActive) return false;
      const active = query.state.data?.active_runs?.length ?? 0;
      return active > 0 ? 3000 : 15000;
    },

    // A stale session is worse than a slow one: it shows a snapshot that has moved on.
    staleTime: 0,
  });

  return query;
}
