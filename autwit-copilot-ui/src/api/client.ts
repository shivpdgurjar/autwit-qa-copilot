import createClient from 'openapi-fetch';
import type { components, paths } from './generated/schema';

/**
 * The API client, typed entirely from docs/openapi.yaml.
 *
 * BUILD_BRIEF §3: "Two languages, two repos. That's fine, but it means no shared
 * types for free — hence the generated client. Do not hand-write API types in the UI."
 *
 * So there are no interfaces declared here. Every type below is an alias into the
 * generated schema, which `npm run generate:api` rebuilds before dev and before build.
 * If the spec changes and this stops compiling, that is the mechanism working.
 */
export const api = createClient<paths>({ baseUrl: '/api/v1' });

type Schemas = components['schemas'];

export type Session = Schemas['Session'];
export type SessionDetail = Schemas['SessionDetail'];
export type Step = Schemas['Step'];
export type Milestone = Schemas['Milestone'];
export type Snapshot = Schemas['Snapshot'];
export type SnapshotPart = Schemas['SnapshotPart'];
export type Artifact = Schemas['Artifact'];
export type ArtifactRef = Schemas['ArtifactRef'];
export type Finding = Schemas['Finding'];
export type Comparison = Schemas['Comparison'];
export type Skill = Schemas['Skill'];
export type Run = Schemas['Run'];
export type RunStatus = Schemas['RunStatus'];
export type StepKind = Schemas['StepKind'];
export type Severity = Schemas['Severity'];
export type Verdict = Schemas['Verdict'];
export type Problem = Schemas['Problem'];

/** SSE event types the stream can emit (documentation-only schema in the spec). */
export type StreamEventType = NonNullable<Schemas['StreamEvent']['type']>;

/** Terminal run statuses. A run in one of these will not change again. */
const TERMINAL: RunStatus[] = ['succeeded', 'failed', 'cancelled', 'timed_out'];

export function isTerminal(status: RunStatus | undefined): boolean {
  return status !== undefined && TERMINAL.includes(status);
}

export function isActive(status: RunStatus | undefined): boolean {
  return status === 'queued' || status === 'running';
}

/**
 * Unwraps openapi-fetch's { data, error } into a thrown Problem, so TanStack Query's
 * error state carries the RFC 7807 body rather than a generic Error.
 */
export function unwrap<T>(result: { data?: T; error?: unknown }): T {
  if (result.error) {
    throw result.error as Problem;
  }
  return result.data as T;
}
