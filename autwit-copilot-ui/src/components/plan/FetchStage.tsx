import { useEffect, useState } from 'react';
import { type PlanningCandidate } from '../../api/client';
import {
  useConfluenceSearch,
  useFetchContext,
  useGenerateTestPlan,
  useJiraSearch,
} from '../../hooks/usePlanning';
import { Card, Mono, Spinner } from '../../components/ui';

/** Step 2 — search Jira & Confluence over MCP, select context, fetch it. */
export function FetchStage({
  projectId,
  onBack,
  onGenerated,
}: {
  projectId: string;
  onBack: () => void;
  onGenerated: (generationId: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState(''); // '' → auto-searches on open
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [selInit, setSelInit] = useState<string | null>(null);

  const jiraQ = useJiraSearch(projectId, submitted);
  const confQ = useConfluenceSearch(projectId, submitted);
  const jira = jiraQ.data ?? [];
  const confluence = confQ.data ?? [];
  const searching = jiraQ.isFetching || confQ.isFetching;

  const fetchContext = useFetchContext(projectId);
  const generate = useGenerateTestPlan(projectId);

  // The fetch mutation already holds its result — derive the console + the fetched flag from
  // it rather than mirroring them into extra state.
  const log = fetchContext.data?.log ?? [];
  const fetched = fetchContext.isSuccess;

  const search = () => setSubmitted(query);

  // Pre-select everything found, once per completed search ("confirm and go"). The searches
  // are cached by query, so re-entering this stage reuses them without re-hitting MCP.
  useEffect(() => {
    if (!jiraQ.isSuccess || !confQ.isSuccess || selInit === submitted) return;
    setSelected(new Set([...jira, ...confluence].map((x) => `${x.kind}:${x.ref}`)));
    setSelInit(submitted);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jiraQ.isSuccess, confQ.isSuccess, submitted]);

  function toggle(c: PlanningCandidate) {
    const key = `${c.kind}:${c.ref}`;
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  function runFetch() {
    const jiraKeys = jira.filter((c) => selected.has(`jira:${c.ref}`)).map((c) => c.ref);
    const pageIds = confluence.filter((c) => selected.has(`confluence:${c.ref}`)).map((c) => c.ref);
    fetchContext.mutate({ jira_keys: jiraKeys, confluence_page_ids: pageIds });
  }

  return (
    <div className="mx-auto max-w-4xl">
      <h2 className="text-lg font-semibold">Pull in the relevant context</h2>
      <p className="mt-1 mb-5 text-[13px] text-ink-400">
        Connected to Jira and Confluence over MCP. Select the tickets and pages that describe this
        feature — they're folded into the plan alongside your uploads.
      </p>

      <div className="grid grid-cols-2 gap-5">
        <div className="space-y-4">
          <div className="flex gap-2">
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && search()}
              placeholder="Search issues & pages"
              className="flex-1 rounded border border-ink-700 bg-ink-950 px-2 py-1.5 text-[12px] outline-none focus:border-sky-700"
            />
            <button
              onClick={search}
              className="rounded border border-ink-700 px-3 text-[12px] hover:border-ink-600"
            >
              Search
            </button>
          </div>

          {searching && (
            <p className="flex items-center gap-2 text-[12px] text-ink-400">
              <Spinner /> Searching…
            </p>
          )}

          <Results title="Jira issues" items={jira} selected={selected} onToggle={toggle} accent="J" />
          <Results title="Confluence pages" items={confluence} selected={selected} onToggle={toggle} accent="C" />
        </div>

        <div>
          <h3 className="mb-2 text-[12px] font-semibold uppercase tracking-wide text-ink-400">
            Fetch activity
          </h3>
          <div className="h-[22rem] overflow-y-auto rounded-lg border border-ink-700 bg-ink-950 p-3 font-mono text-[11.5px]">
            {log.length === 0 ? (
              <p className="pt-32 text-center text-ink-500">
                {fetchContext.isPending ? 'Fetching…' : 'Fetch log appears here once you pull context.'}
              </p>
            ) : (
              log.map((l, i) => (
                <div key={i} className="mb-1.5 flex gap-2">
                  <span className="shrink-0 text-ink-500">{l.ts}</span>
                  <span
                    className={
                      l.level === 'ok'
                        ? 'text-emerald-400'
                        : l.level === 'info'
                          ? 'text-sky-400'
                          : 'text-amber-400'
                    }
                  >
                    {l.level === 'ok' ? '✓' : l.level === 'info' ? 'ℹ' : '…'}
                  </span>
                  <span className="text-ink-300">{l.message}</span>
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      <div className="mt-6 flex items-center gap-2">
        <button onClick={onBack} className="rounded border border-ink-700 px-3 py-2 text-[13px] hover:border-ink-600">
          ← Back
        </button>
        <div className="ml-auto flex gap-2">
          {!fetched ? (
            <button
              onClick={runFetch}
              disabled={selected.size === 0 || fetchContext.isPending}
              className="rounded bg-sky-700 px-4 py-2 text-[13px] font-medium text-white hover:bg-sky-600 disabled:opacity-40"
            >
              {fetchContext.isPending ? 'Fetching…' : `Fetch selected context (${selected.size})`}
            </button>
          ) : (
            <button
              onClick={() => generate.mutate(undefined, { onSuccess: (g) => onGenerated(g.generation_id) })}
              disabled={generate.isPending}
              className="rounded bg-sky-700 px-4 py-2 text-[13px] font-medium text-white hover:bg-sky-600 disabled:opacity-40"
            >
              {generate.isPending ? 'Starting…' : 'Generate test plan →'}
            </button>
          )}
        </div>
      </div>
      {generate.error != null && (
        <p className="mt-2 text-right text-[11px] text-red-300">
          {(generate.error as { detail?: string }).detail ?? 'Could not start generation.'}
        </p>
      )}
    </div>
  );
}

function Results({
  title,
  items,
  selected,
  onToggle,
  accent,
}: {
  title: string;
  items: PlanningCandidate[];
  selected: Set<string>;
  onToggle: (c: PlanningCandidate) => void;
  accent: string;
}) {
  if (items.length === 0) return null;
  return (
    <Card>
      <div className="mb-2 flex items-center gap-2">
        <span className="flex size-6 items-center justify-center rounded bg-sky-700/15 text-[11px] font-bold text-sky-400">
          {accent}
        </span>
        <h3 className="text-[12px] font-semibold">{title}</h3>
      </div>
      <div className="space-y-1.5">
        {items.map((c) => (
          <button
            key={`${c.kind}:${c.ref}`}
            onClick={() => onToggle(c)}
            className="flex w-full items-start gap-2.5 rounded-lg border border-ink-700 bg-ink-850 p-2.5 text-left hover:border-ink-600"
          >
            <input
              type="checkbox"
              readOnly
              checked={selected.has(`${c.kind}:${c.ref}`)}
              className="mt-0.5 accent-sky-600"
            />
            <span className="min-w-0">
              <Mono className="text-sky-400">{c.ref}</Mono>
              <span className="mt-0.5 block truncate text-[12.5px] text-ink-100">{c.title}</span>
              {c.meta && <span className="mt-0.5 block text-[11px] text-ink-500">{c.meta}</span>}
            </span>
          </button>
        ))}
      </div>
    </Card>
  );
}
