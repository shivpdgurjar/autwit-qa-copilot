import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  api,
  isAutomationActive,
  unwrap,
  type AutomationConfirmationRequired,
  type AutomationRun,
  type AutomationRunStatus,
  type StartAutomationRunRequest,
} from '../api/client';

/**
 * The automation plane.
 *
 * Runs are owned by the AUTWIT run service and reached through copilot's proxy, so the
 * browser stays on one origin — an embedded report needs that to be framed, and reports
 * can carry the PII api.fetch_order persists, so they go through copilot's auth.
 */

export const automationKeys = {
  all: ['automation'] as const,
  list: (env?: string, status?: string) => ['automation', 'list', env ?? '', status ?? ''] as const,
  run: (runId: string) => ['automation', 'run', runId] as const,
  log: (runId: string) => ['automation', 'log', runId] as const,
};

/** Poll while anything is still moving; stop once every run is terminal. */
function activePollInterval(runs: AutomationRun[] | undefined): number | false {
  return runs?.some((r) => isAutomationActive(r.status)) ? 3000 : false;
}

export function useAutomationRuns(env?: string, status?: AutomationRunStatus) {
  return useQuery({
    queryKey: automationKeys.list(env, status),
    queryFn: async () =>
      unwrap(
        await api.GET('/automation/runs', {
          params: { query: { env: env || undefined, status, limit: 50 } },
        }),
      ),
    refetchInterval: (query) => activePollInterval(query.state.data as AutomationRun[] | undefined),
  });
}

export function useAutomationRun(runId: string | undefined) {
  return useQuery({
    queryKey: automationKeys.run(runId ?? ''),
    enabled: Boolean(runId),
    queryFn: async () =>
      unwrap(await api.GET('/automation/runs/{runId}', { params: { path: { runId: runId! } } })),
    refetchInterval: (query) => {
      const run = query.state.data as AutomationRun | undefined;
      return isAutomationActive(run?.status) ? 2000 : false;
    },
  });
}

/**
 * The live log tail. Deliberately a poll rather than SSE: the run service already keeps a
 * bounded, non-destructive tail, and copilot's SSE carries session events rather than
 * another service's process output.
 */
export function useAutomationRunLog(runId: string | undefined, active: boolean) {
  return useQuery({
    queryKey: automationKeys.log(runId ?? ''),
    enabled: Boolean(runId),
    queryFn: async () =>
      unwrap(await api.GET('/automation/runs/{runId}/log', { params: { path: { runId: runId! } } })),
    refetchInterval: active ? 2000 : false,
  });
}

/** Raised when the per-environment soft gate needs an explicit acknowledgement. */
export class ConfirmationRequired extends Error {
  constructor(readonly detail: AutomationConfirmationRequired) {
    super(detail.message ?? 'A run is already active on this environment');
    this.name = 'ConfirmationRequired';
  }
}

export function useStartAutomationRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (request: StartAutomationRunRequest): Promise<AutomationRun> => {
      const result = await api.POST('/automation/runs', { body: request });
      if (result.error) {
        // 409 is a question, not a failure: the environment already has a run and the
        // tester may proceed anyway. Surfaced as its own type so the caller can show the
        // active runs rather than a generic error toast.
        const problem = result.error as AutomationConfirmationRequired;
        if (problem?.code === 'confirmation_required') {
          throw new ConfirmationRequired(problem);
        }
        throw result.error;
      }
      return result.data as AutomationRun;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: automationKeys.all }),
  });
}

export function useCancelAutomationRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (runId: string) =>
      unwrap(await api.POST('/automation/runs/{runId}/cancel', { params: { path: { runId } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: automationKeys.all }),
  });
}

/** Same-origin report URL, so it can be framed. */
export function reportUrl(runId: string): string {
  return `/api/v1/automation/runs/${runId}/report`;
}
