import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  api,
  isTerminal,
  unwrap,
  type ArtifactRef,
  type CreateAnalysisRequest,
  type CreateAnalysisResponse,
  type EventRecord,
  type Run,
  type StateRef,
} from '../api/client';
import { sessionKey } from './useSession';

/**
 * The "assemble from evidence" feature (financial analysis).
 *
 * A tester does not hand-type data here — they SELECT already-persisted session evidence
 * (artifacts and events) and submit it. POST /sessions/{id}/analyses projects each
 * selection into an analysis "state" and returns the assembled result (not a verdict —
 * the analysis run itself is wired separately, out of this feature's scope).
 */

/** StateType — the nine values the orchestrator can tag a state with. */
export type StateType = NonNullable<StateRef['state_type']>;
/** SourceSystem — the eleven provenance values. */
export type Source = NonNullable<StateRef['source']>;
export type AnalysisMode = CreateAnalysisRequest['analysis_mode'];

/**
 * Enum arrays for the override dropdowns. Typed against the generated StateRef so the
 * spec is the source of truth: if openapi.yaml adds or removes a value and these fall
 * out of sync, the build stops rather than silently offering a rejected option.
 */
export const STATE_TYPES: StateType[] = [
  'ORDER_SNAPSHOT',
  'API_REQUEST',
  'API_RESPONSE',
  'DOMAIN_EVENT',
  'INVOICE_SNAPSHOT',
  'PAYMENT_SNAPSHOT',
  'REFUND_EVENT',
  'CALCULATION_RESULT',
  'OTHER',
];

export const SOURCES: Source[] = [
  'ORDER_DB',
  'CALCULATE_API',
  'UPDATE_LINES_API',
  'CANCEL_CALCULATE_API',
  'TAX_EXEMPTION_API',
  'ISSUE_CREDIT_API',
  'INVOICE_DB',
  'PAYMENT_DB',
  'KAFKA_EVENT',
  'REFUND_SERVICE',
  'UNKNOWN',
];

/** The session's artifacts — selectable as kind=ARTIFACT. */
export function useArtifacts(sessionId: string, enabled = true) {
  return useQuery({
    // A key of its own so it never collides with any other artifact query's paging.
    queryKey: ['evidence', 'artifacts', sessionId],
    enabled,
    queryFn: async ({ signal }): Promise<ArtifactRef[]> => {
      const page = unwrap(
        await api.GET('/sessions/{sessionId}/artifacts', {
          params: { path: { sessionId } },
          signal,
        }),
      );
      return page.artifacts ?? [];
    },
  });
}

/** The session's events — selectable as kind=EVENT. */
export function useEvidenceEvents(sessionId: string, enabled = true) {
  return useQuery({
    // Distinct from EventBatchCard's ['events', id] so the two limits never clash.
    queryKey: ['evidence', 'events', sessionId],
    enabled,
    queryFn: async ({ signal }): Promise<{ events: EventRecord[]; truncated: boolean }> => {
      const page = unwrap(
        await api.GET('/sessions/{sessionId}/events', {
          params: { path: { sessionId }, query: { limit: 200 } },
          signal,
        }),
      );
      return { events: page.events ?? [], truncated: Boolean(page.next_cursor) };
    },
  });
}

/**
 * POST /sessions/{id}/analyses — assemble selected evidence into an analysis.
 *
 * Mirrors the mutation style in useSubmitRun: retry off (ADR-001; assembly is a write and
 * is never auto-retried) and the session query is invalidated on success so the new
 * analysis's states are reflected on next hydrate. No Idempotency-Key: this operation
 * takes no header (the spec's header is `never`), and re-submitting the same selection
 * dedupes server-side by content hash rather than by key.
 */
export function useCreateAnalysis(sessionId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    retry: false,
    mutationFn: async (input: {
      analysis_mode: AnalysisMode;
      order_number: string;
      states: StateRef[];
    }): Promise<CreateAnalysisResponse> =>
      unwrap(
        await api.POST('/sessions/{sessionId}/analyses', {
          params: { path: { sessionId } },
          body: {
            analysis_mode: input.analysis_mode,
            order_number: input.order_number,
            states: input.states,
          },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: sessionKey(sessionId) }),
  });
}

export const runKey = (runId: string) => ['run', runId] as const;

/**
 * GET /runs/{runId} — watch one run to its verdict.
 *
 * The analysis submitted from the evidence picker runs asynchronously through the
 * worker; POST /analyses returns a run_id and the verdict lands on the RUN later.
 * This polls until the run reaches a terminal status, then stops — the same
 * "refetch only while active" discipline useSession uses (an idle poll of a finished
 * run is pure waste), except keyed off the run's own status rather than a session's
 * active_runs, since a run has no separate stream to fall back from.
 */
export function useRun(runId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: runKey(runId ?? ''),
    enabled: enabled && Boolean(runId),
    queryFn: async ({ signal }): Promise<Run> =>
      unwrap(
        await api.GET('/runs/{runId}', {
          params: { path: { runId: runId! } },
          signal,
        }),
      ),

    // Poll while the run is non-terminal (queued/running); stop the moment it settles.
    // isTerminal(undefined) is false, so the very first fetch is never treated as done.
    refetchInterval: (query) => (isTerminal(query.state.data?.status) ? false : 2000),

    // A stale run is a stale verdict — always show where the run actually is.
    staleTime: 0,
  });
}
