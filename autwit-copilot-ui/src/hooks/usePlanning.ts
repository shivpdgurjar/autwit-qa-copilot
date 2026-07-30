import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, unwrap, type GenerateDataRequest } from '../api/client';

/**
 * Data hooks for the Planning Copilot wizard. Generation is async (the backend returns
 * 202 + a generation id), so the wizard polls the generation until it settles — the same
 * refetch-until-terminal pattern the execution flavor uses for runs.
 */

const GEN_TERMINAL = ['succeeded', 'failed', 'cancelled'];

export const projectKey = (id: string) => ['planning', 'project', id];

export function useProjects() {
  return useQuery({
    queryKey: ['planning', 'projects'],
    queryFn: async ({ signal }) =>
      unwrap(await api.GET('/planning/projects', { params: { query: { limit: 50 } }, signal })),
  });
}

export function useProject(projectId: string) {
  return useQuery({
    queryKey: projectKey(projectId),
    queryFn: async ({ signal }) =>
      unwrap(await api.GET('/planning/projects/{projectId}', {
        params: { path: { projectId } },
        signal,
      })),
    enabled: !!projectId,
  });
}

export function useCreateProject() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: {
      feature_key?: string;
      feature_description?: string;
      title?: string;
      created_by?: string;
      env?: string;
    }) => unwrap(await api.POST('/planning/projects', { body })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['planning', 'projects'] }),
  });
}

export function useDocuments(projectId: string) {
  return useQuery({
    queryKey: ['planning', 'documents', projectId],
    queryFn: async ({ signal }) =>
      unwrap(await api.GET('/planning/projects/{projectId}/documents', {
        params: { path: { projectId } },
        signal,
      })),
    enabled: !!projectId,
  });
}

export function useAddDocument(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: {
      source_type: 'upload' | 'paste';
      title?: string;
      filename?: string;
      mime?: string;
      text: string;
    }) =>
      unwrap(await api.POST('/planning/projects/{projectId}/documents', {
        params: { path: { projectId } },
        body,
      })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['planning', 'documents', projectId] }),
  });
}

export function useSetSelected(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ documentId, selected }: { documentId: string; selected: boolean }) =>
      api.PATCH('/planning/projects/{projectId}/documents/{documentId}', {
        params: { path: { projectId, documentId } },
        body: { selected },
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['planning', 'documents', projectId] }),
  });
}

export function useDeleteDocument(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (documentId: string) =>
      api.DELETE('/planning/projects/{projectId}/documents/{documentId}', {
        params: { path: { projectId, documentId } },
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['planning', 'documents', projectId] }),
  });
}

/**
 * Step-2 searches. Cached by query (staleTime), so leaving and re-entering the fetch stage
 * reuses the results instead of re-hitting the MCP connector on every visit.
 */
export function useJiraSearch(projectId: string, query: string) {
  return useQuery({
    queryKey: ['planning', 'jira-search', projectId, query],
    queryFn: async ({ signal }) =>
      unwrap(await api.GET('/planning/projects/{projectId}/jira-search', {
        params: { path: { projectId }, query: { query } },
        signal,
      })),
    enabled: !!projectId,
    staleTime: 5 * 60 * 1000,
  });
}

export function useConfluenceSearch(projectId: string, query: string) {
  return useQuery({
    queryKey: ['planning', 'confluence-search', projectId, query],
    queryFn: async ({ signal }) =>
      unwrap(await api.GET('/planning/projects/{projectId}/confluence-search', {
        params: { path: { projectId }, query: { query } },
        signal,
      })),
    enabled: !!projectId,
    staleTime: 5 * 60 * 1000,
  });
}

/** Fetch is a mutation (it persists documents + returns the console log). */
export function useFetchContext(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { jira_keys?: string[]; confluence_page_ids?: string[] }) =>
      unwrap(await api.POST('/planning/projects/{projectId}/fetch', {
        params: { path: { projectId } },
        body,
      })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['planning', 'documents', projectId] }),
  });
}

export function useGenerateTestPlan(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () =>
      unwrap(await api.POST('/planning/projects/{projectId}/test-plan', {
        params: { path: { projectId } },
      })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['planning', 'generations', projectId] }),
  });
}

export function useGenerateTestData(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: GenerateDataRequest) =>
      unwrap(await api.POST('/planning/projects/{projectId}/test-data', {
        params: { path: { projectId } },
        body,
      })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['planning', 'generations', projectId] }),
  });
}

/** Poll one generation until it settles. */
export function useGeneration(projectId: string, generationId: string | undefined) {
  return useQuery({
    queryKey: ['planning', 'generation', projectId, generationId],
    queryFn: async ({ signal }) =>
      unwrap(await api.GET('/planning/projects/{projectId}/generations/{generationId}', {
        params: { path: { projectId, generationId: generationId! } },
        signal,
      })),
    enabled: !!generationId,
    refetchInterval: (query) =>
      GEN_TERMINAL.includes(query.state.data?.status ?? '') ? false : 1200,
    // Keep polling even when the tab is backgrounded — a generation is a ~60s wait and
    // testers routinely switch tabs during it; without this the poll pauses on a hidden
    // tab and they return to a stuck "Generating…".
    refetchIntervalInBackground: true,
  });
}

export function useLatestTestPlan(projectId: string) {
  return useQuery({
    queryKey: ['planning', 'test-plan', projectId],
    queryFn: async ({ signal }) => {
      const res = await api.GET('/planning/projects/{projectId}/test-plan', {
        params: { path: { projectId } },
        signal,
      });
      // 204 → no plan yet; openapi-fetch gives undefined data with no error.
      return res.data ?? null;
    },
    enabled: !!projectId,
  });
}

export function useDatasets(projectId: string, generationId: string | undefined) {
  return useQuery({
    queryKey: ['planning', 'datasets', projectId, generationId],
    queryFn: async ({ signal }) =>
      unwrap(await api.GET('/planning/projects/{projectId}/generations/{generationId}/test-data', {
        params: { path: { projectId, generationId: generationId! } },
        signal,
      })),
    enabled: !!generationId,
  });
}
