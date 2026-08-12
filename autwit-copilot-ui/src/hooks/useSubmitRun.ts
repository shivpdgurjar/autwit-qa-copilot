import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, unwrap } from '../api/client';
import { sessionKey } from './useSession';

/**
 * Submit-only (invariant 3): every POST here returns 202 and never waits for the work.
 *
 * <h2>On Idempotency-Key</h2>
 *
 * The key is generated once per user action, inside the mutationFn, and NOT per
 * attempt. That distinction is the whole point. openapi.yaml: "Send on every POST.
 * Double-clicking 'Fulfil order' must not place two orders." A key minted per retry
 * would make every retry a fresh run — exactly what it is supposed to prevent.
 *
 * TanStack Query's retry is off for these for the same reason: a mutating skill is
 * never automatically retried anywhere in this system (ADR-001), and the UI should not
 * be the layer that breaks that.
 */
function idempotencyKey(): string {
  // crypto.randomUUID() is gated to secure contexts (HTTPS or localhost). When the UI
  // is opened cross-machine over plain http://<hostname>:5173 it is undefined and would
  // throw before the POST, so mutating actions (invoke skill, message, milestone, end)
  // would fail while reads still work. getRandomValues is NOT secure-context-gated, so
  // fall back to building a v4 UUID from it -- no HTTPS or client config required.
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40; // version 4
  b[8] = (b[8] & 0x3f) | 0x80; // variant 10
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0'));
  return `${h.slice(0, 4).join('')}-${h.slice(4, 6).join('')}-${h.slice(6, 8).join('')}-${h.slice(8, 10).join('')}-${h.slice(10, 16).join('')}`;
}

export function useSubmitMessage(sessionId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    retry: false,
    mutationFn: async (input: { message: string; subjects?: Record<string, string> }) =>
      unwrap(
        await api.POST('/sessions/{sessionId}/messages', {
          params: {
            path: { sessionId },
            header: { 'Idempotency-Key': idempotencyKey() },
          },
          body: { message: input.message, subjects: input.subjects },
        }),
      ),
    // The 202 carries a step_id, so the pending card appears the moment the refetch
    // lands rather than when the work finishes.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: sessionKey(sessionId) }),
  });
}

export function useMarkMilestone(sessionId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    retry: false,
    mutationFn: async (input: { name: string; note?: string }) =>
      unwrap(
        await api.POST('/sessions/{sessionId}/milestones', {
          params: {
            path: { sessionId },
            header: { 'Idempotency-Key': idempotencyKey() },
          },
          body: { name: input.name, scopes: ['order_flow'], capture_events: true, note: input.note },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: sessionKey(sessionId) }),
  });
}

export function useInvokeSkill(sessionId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    retry: false,
    mutationFn: async (input: {
      skillName: string;
      input: Record<string, unknown>;
      confirm: boolean;
    }) =>
      unwrap(
        await api.POST('/sessions/{sessionId}/skills/{skillName}', {
          params: {
            path: { sessionId, skillName: input.skillName },
            header: { 'Idempotency-Key': idempotencyKey() },
          },
          // confirm must be true for side_effects=mutating; the API rejects it otherwise
          // with confirmation_required. The palette gates on the same fact, but the API
          // is the one that enforces it.
          body: { input: input.input, confirm: input.confirm },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: sessionKey(sessionId) }),
  });
}

export function useEndSession(sessionId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    retry: false,
    mutationFn: async (input: { format: 'html' | 'md' | 'both'; notes?: string }) =>
      unwrap(
        await api.POST('/sessions/{sessionId}/end', {
          params: { path: { sessionId }, header: { 'Idempotency-Key': idempotencyKey() } },
          body: { format: input.format, notes: input.notes },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: sessionKey(sessionId) }),
  });
}
