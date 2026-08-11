import { useEffect, useState } from 'react';
import { type PlanningCandidate } from '../../api/client';
import {
  useConfluenceSearch,
  useFetchContext,
  useJiraSearch,
} from '../../hooks/usePlanning';
import { Button, Card, Input, Mono, Spinner, Textarea } from '../../components/ui';

/**
 * Step 2 — gather the Jira tickets and Confluence pages the plan is built from.
 *
 * Two ways in, because one search rarely covers a feature: search by keyword (repeatedly —
 * results and selections accumulate across searches rather than replacing), and paste keys,
 * page ids or links directly for the items you already know. Pasted entries are classified
 * server-side, so an unusable link is reported in the console and everything else still
 * fetches.
 */
export function FetchStage({
  projectId,
  onBack,
  onNext,
}: {
  projectId: string;
  onBack: () => void;
  onNext: () => void;
}) {
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState(''); // '' → auto-searches on open
  const [searches, setSearches] = useState<string[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [seenInit, setSeenInit] = useState<string | null>(null);
  const [manual, setManual] = useState('');
  const [entered, setEntered] = useState<string[]>([]);

  // Everything any search has turned up so far, keyed so a repeat hit merges rather than
  // duplicating. Without this, searching a second keyword silently discarded the first
  // search's results along with whatever had been ticked in them.
  const [found, setFound] = useState<Map<string, PlanningCandidate>>(new Map());

  const jiraQ = useJiraSearch(projectId, submitted);
  const confQ = useConfluenceSearch(projectId, submitted);
  const searching = jiraQ.isFetching || confQ.isFetching;

  const fetchContext = useFetchContext(projectId);
  const log = fetchContext.data?.log ?? [];
  const fetched = fetchContext.isSuccess;

  const candidates = [...found.values()];
  const jira = candidates.filter((c) => c.kind === 'jira');
  const confluence = candidates.filter((c) => c.kind === 'confluence');

  function search() {
    if (!query.trim()) return;
    setSubmitted(query.trim());
  }

  // Merge each completed search into the accumulated set, and pre-select what it turned up —
  // a keyword you deliberately searched is one you meant to add. Existing ticks are untouched.
  useEffect(() => {
    if (!jiraQ.isSuccess || !confQ.isSuccess || seenInit === submitted) return;
    const batch = [...(jiraQ.data ?? []), ...(confQ.data ?? [])];
    setFound((prev) => {
      const next = new Map(prev);
      for (const c of batch) next.set(`${c.kind}:${c.ref}`, c);
      return next;
    });
    setSelected((prev) => new Set([...prev, ...batch.map((c) => `${c.kind}:${c.ref}`)]));
    setSeenInit(submitted);
    if (submitted && !searches.includes(submitted)) setSearches((s) => [...s, submitted]);
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

  /** Split on commas, whitespace and newlines so a pasted list works however it was copied. */
  function addEntered() {
    const parts = manual
      .split(/[\s,]+/)
      .map((s) => s.trim())
      .filter(Boolean);
    if (parts.length === 0) return;
    setEntered((prev) => [...prev, ...parts.filter((p) => !prev.includes(p))]);
    setManual('');
  }

  const total = selected.size + entered.length;

  function runFetch() {
    fetchContext.mutate({
      jira_keys: jira.filter((c) => selected.has(`jira:${c.ref}`)).map((c) => c.ref),
      confluence_page_ids: confluence
        .filter((c) => selected.has(`confluence:${c.ref}`))
        .map((c) => c.ref),
      refs: entered,
    });
  }

  return (
    <div className="mx-auto max-w-4xl">
      <h2 className="text-lg font-semibold">Pull in the relevant context</h2>
      <p className="mt-1 mb-5 text-[13px] text-ink-400">
        Search Jira and Confluence as many times as you need — results add up. Or paste ticket
        keys and page links directly if you already know them.
      </p>

      <div className="grid grid-cols-2 gap-5">
        <div className="space-y-4">
          <div className="flex gap-2">
            <Input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && search()}
              placeholder="Search issues & pages"
              className="flex-1"
            />
            <Button variant="ghost" onClick={search} disabled={!query.trim() || searching}>
              Search
            </Button>
          </div>

          {searches.length > 0 && (
            <p className="text-[11px] text-ink-400">
              Searched: {searches.map((s) => `“${s}”`).join(', ')}
            </p>
          )}

          {searching && (
            <p className="flex items-center gap-2 text-[12px] text-ink-400">
              <Spinner /> Searching…
            </p>
          )}

          <Card>
            <h3 className="text-[12px] font-semibold">Add by key or link</h3>
            <p className="mb-2 text-[11px] text-ink-400">
              Jira keys (PAY-2481), Confluence page ids, or links to either. Separate with
              commas, spaces or new lines.
            </p>
            <Textarea
              value={manual}
              onChange={(e) => setManual(e.target.value)}
              placeholder={'PAY-2481, CAN-1201\nhttps://acuver.atlassian.net/wiki/spaces/OES/pages/123456789/Design'}
              rows={3}
              className="w-full"
            />
            <div className="mt-1.5 flex justify-end">
              <Button variant="ghost" size="sm" onClick={addEntered} disabled={!manual.trim()}>
                Add
              </Button>
            </div>

            {entered.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {entered.map((e) => (
                  <span
                    key={e}
                    className="inline-flex max-w-full items-center gap-1 rounded-full border border-ink-600 bg-ink-850 px-2 py-0.5 text-[11px]"
                  >
                    <span className="truncate font-mono text-sky-400">{e}</span>
                    <button
                      onClick={() => setEntered((prev) => prev.filter((x) => x !== e))}
                      className="text-ink-500 hover:text-red-400"
                      aria-label={`Remove ${e}`}
                    >
                      ✕
                    </button>
                  </span>
                ))}
              </div>
            )}
          </Card>

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
                  <span className={levelClass(l.level)}>{levelGlyph(l.level)}</span>
                  <span className={l.level === 'warn' ? 'text-amber-300' : 'text-ink-300'}>
                    {l.message}
                  </span>
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      <div className="mt-6 flex items-center gap-2">
        <Button variant="ghost" onClick={onBack}>← Back</Button>
        <div className="ml-auto flex gap-2">
          {!fetched ? (
            <Button onClick={runFetch} disabled={total === 0 || fetchContext.isPending}>
              {fetchContext.isPending ? 'Fetching…' : `Fetch selected context (${total})`}
            </Button>
          ) : (
            <Button onClick={onNext}>Continue to reasoning →</Button>
          )}
          {/* Reasoning is optional — let the tester skip straight to it without fetching. */}
          {!fetched && (
            <Button variant="ghost" onClick={onNext}>Skip →</Button>
          )}
        </div>
      </div>
    </div>
  );
}

function levelClass(level?: string | null) {
  return level === 'ok'
    ? 'text-emerald-400'
    : level === 'info'
      ? 'text-sky-400'
      : level === 'warn'
        ? 'text-amber-400'
        : 'text-amber-400';
}

function levelGlyph(level?: string | null) {
  return level === 'ok' ? '✓' : level === 'info' ? 'ℹ' : level === 'warn' ? '!' : '…';
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
  const chosen = items.filter((c) => selected.has(`${c.kind}:${c.ref}`)).length;
  return (
    <Card>
      <div className="mb-2 flex items-center gap-2">
        <span className="flex size-6 items-center justify-center rounded bg-sky-700/15 text-[11px] font-bold text-sky-400">
          {accent}
        </span>
        <h3 className="text-[12px] font-semibold">{title}</h3>
        <span className="text-[11px] text-ink-400">
          {chosen}/{items.length} selected
        </span>
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
