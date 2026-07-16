import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, unwrap, type Artifact, type Snapshot } from '../../api/client';
import { Mono, Muted, Spinner } from '../ui';

/**
 * The artifact drawer: pick a part, read its body.
 *
 * Bodies are fetched one at a time and only when opened. GET /sessions/{id}/artifacts
 * returns metadata and never bodies, precisely so that a 9-part snapshot does not drag
 * 9 bodies across the wire to render a list.
 */
export function ArtifactDrawer({
  snapshot,
  onClose,
}: {
  snapshot: Snapshot;
  onClose: () => void;
}) {
  const parts = snapshot.parts ?? [];
  const [selected, setSelected] = useState<string | undefined>(parts[0]?.artifact_id);

  return (
    <aside className="flex h-full w-[38rem] shrink-0 flex-col border-l border-ink-700 bg-ink-900">
      <header className="flex items-center gap-2 border-b border-ink-700 px-3 py-2">
        <span className="text-sm font-medium">{snapshot.label}</span>
        <Mono className="rounded bg-ink-800 px-1.5 py-0.5 text-ink-300">{snapshot.scope}</Mono>
        <button
          onClick={onClose}
          className="ml-auto rounded px-2 py-0.5 text-ink-400 hover:bg-ink-800 hover:text-ink-100"
          aria-label="Close"
        >
          ✕
        </button>
      </header>

      <nav className="flex max-h-40 flex-wrap gap-1 overflow-y-auto border-b border-ink-700 p-2">
        {parts.map((part) => (
          <button
            key={part.part_key}
            onClick={() => setSelected(part.artifact_id)}
            className={`rounded border px-1.5 py-0.5 font-mono text-[11px] transition-colors ${
              selected === part.artifact_id
                ? 'border-sky-700 bg-sky-950/50 text-sky-200'
                : 'border-ink-700 bg-ink-850 text-ink-300 hover:border-ink-600'
            }`}
          >
            {part.part_key}
            <span className="ml-1 text-ink-400">{part.row_count ?? '—'}</span>
          </button>
        ))}
      </nav>

      {selected ? (
        <ArtifactBody artifactId={selected} />
      ) : (
        <p className="p-4 text-sm text-ink-400 italic">This snapshot has no parts.</p>
      )}
    </aside>
  );
}

function ArtifactBody({ artifactId }: { artifactId: string }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['artifact', artifactId],
    queryFn: async ({ signal }) =>
      unwrap(
        await api.GET('/artifacts/{artifactId}', {
          params: { path: { artifactId } },
          signal,
        }),
      ) as Artifact,
    // Artifact bodies are immutable once written -- nothing ever updates one in place.
    staleTime: Infinity,
  });

  if (isLoading) {
    return (
      <div className="flex items-center gap-2 p-4 text-sm text-ink-400">
        <Spinner /> Loading body…
      </div>
    );
  }

  if (error) {
    const problem = error as { status?: number; code?: string; detail?: string };
    // 410 is not 404: the artifact existed and its metadata still does. Retention took
    // the body; the trace survives. Saying "not found" here would be a lie.
    const purged = problem.status === 410 || problem.code === 'artifact_purged';
    return (
      <div className="p-4 text-sm">
        <p className={purged ? 'text-amber-300' : 'text-red-300'}>
          {purged ? 'Body purged by retention' : 'Could not load this artifact'}
        </p>
        {problem.detail && <p className="mt-1 text-[12px] text-ink-400">{problem.detail}</p>}
      </div>
    );
  }

  if (!data) return null;

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <ArtifactMeta artifact={data} />
      <pre className="min-h-0 flex-1 overflow-auto bg-ink-950 p-3 font-mono text-[11px] leading-relaxed text-ink-200">
        {render(data)}
      </pre>
    </div>
  );
}

function ArtifactMeta({ artifact }: { artifact: Artifact }) {
  const meta = (artifact.meta ?? {}) as {
    pk_columns?: string[];
    ignore_columns?: string[];
    masked_columns?: string[];
    query?: string;
  };

  return (
    <div className="space-y-1.5 border-b border-ink-700 px-3 py-2 text-[11px]">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <Mono className="text-ink-100">{artifact.logical_name}</Mono>
        <Muted>{artifact.source_system}</Muted>
        <Muted>{artifact.format}</Muted>
        <Muted>{artifact.row_count ?? 0} rows</Muted>
        <Muted>{formatBytes(artifact.size_bytes)}</Muted>
      </div>

      {/* Verified on the way in -- a mismatch is rejected, which is what catches a
          truncated body that still parses as valid JSON. */}
      <Mono className="block truncate text-ink-400" title={artifact.content_hash}>
        {artifact.content_hash}
      </Mono>

      <div className="flex flex-wrap gap-x-3 gap-y-1">
        {meta.pk_columns && (
          <span>
            <Muted>key</Muted> <Mono className="text-ink-300">{meta.pk_columns.join(', ')}</Mono>
          </span>
        )}
        {/*
          Ignore rules are surfaced here as well as in the diff. BUILD_BRIEF §7: "If
          updated_at diffs vanish without explanation, nobody trusts the report and the
          tool dies. This is a product requirement, not a nicety."
        */}
        {meta.ignore_columns && meta.ignore_columns.length > 0 && (
          <span>
            <Muted>ignored</Muted>{' '}
            <Mono className="text-amber-300/80">{meta.ignore_columns.join(', ')}</Mono>
          </span>
        )}
        {meta.masked_columns && meta.masked_columns.length > 0 && (
          <span>
            <Muted>masked</Muted>{' '}
            <Mono className="text-ink-300">{meta.masked_columns.join(', ')}</Mono>
          </span>
        )}
      </div>

      {meta.query && (
        <Mono className="block truncate text-ink-400" title={meta.query}>
          {meta.query}
        </Mono>
      )}

      <a
        href={`/api/v1/artifacts/${artifact.artifact_id}/raw`}
        target="_blank"
        rel="noreferrer"
        className="inline-block text-sky-400 hover:text-sky-300"
      >
        Download raw ↗
      </a>
    </div>
  );
}

/** Bodies arrive shaped by format: parsed JSON, a string, or base64. */
function render(artifact: Artifact): string {
  const body = artifact.body;
  if (body === null || body === undefined) return '(no body)';
  if (typeof body === 'string') return body;
  return JSON.stringify(body, null, 2);
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
