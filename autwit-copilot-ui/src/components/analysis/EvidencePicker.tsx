import { useEffect, useMemo, useState } from 'react';
import { isActive } from '../../api/client';
import type {
  ArtifactRef,
  CreateAnalysisResponse,
  EventRecord,
  Run,
  StateRef,
  Verdict,
} from '../../api/client';
import {
  SOURCES,
  STATE_TYPES,
  useArtifacts,
  useCreateAnalysis,
  useEvidenceEvents,
  useRun,
  type Source,
  type StateType,
} from '../../hooks/useAnalysis';
import { FailedRunCard, PendingRunCard } from '../chat/PendingRunCard';
import { VerdictBadge } from '../findings/SeverityBadge';
import { Ago, Mono, Muted, Spinner } from '../ui';

/**
 * The evidence picker — "assemble from evidence" for financial analysis.
 *
 * A tester picks already-persisted session evidence (artifacts + events) and submits it
 * to POST /sessions/{id}/analyses. Two paths:
 *   - "Analyze this"          → exactly ONE state → SNAPSHOT_SANCTITY.
 *   - "Build states → Analyze"→ an ordered sequence → LIFECYCLE_COMPARISON.
 *
 * Single-select for SNAPSHOT_SANCTITY is enforced here (button enabled only at exactly
 * one selection) so the backend's too_many_states (400) can never be provoked.
 *
 * Each selection is projected into a state with an inferred state_type/source the tester
 * can override. The server re-infers anyway, so an override is sent only when it differs
 * from the inferred default (keeps the request honest about what the tester actually
 * changed).
 *
 * DEFERRED (follow-ons, intentionally not built here):
 *   (a) uploading a NEW event/artifact with tagging from inside this picker;
 *   (b) the "previous-response-context" chaining selector that threads one analysis's
 *       output into the next.
 */

type Kind = StateRef['kind'];

type Selection = {
  kind: Kind;
  id: string;
  /** Short human label for the review list. */
  title: string;
  /** Inferred defaults — the baseline an override is measured against. */
  inferredType: StateType;
  inferredSource: Source;
  /** Current (possibly overridden) values. */
  stateType: StateType;
  source: Source;
  label: string;
  lifecycleStage: string;
};

/** Conservative inference. The server re-infers; this is only the picker's starting guess. */
function inferArtifact(a: ArtifactRef): { type: StateType; source: Source } {
  const type: StateType =
    a.artifact_type === 'api_response'
      ? 'API_RESPONSE'
      : a.artifact_type === 'rdbms_table'
        ? 'ORDER_SNAPSHOT'
        : a.artifact_type === 'event_batch'
          ? 'DOMAIN_EVENT'
          : 'OTHER';
  return { type, source: 'UNKNOWN' };
}

function inferEvent(_e: EventRecord): { type: StateType; source: Source } {
  return { type: 'DOMAIN_EVENT', source: 'KAFKA_EVENT' };
}

/** Only send overrides the tester actually changed; drop empty text fields. */
function toStateRef(s: Selection): StateRef {
  const ref: StateRef = { kind: s.kind, id: s.id };
  if (s.stateType !== s.inferredType) ref.state_type = s.stateType;
  if (s.source !== s.inferredSource) ref.source = s.source;
  if (s.label.trim()) ref.label = s.label.trim();
  if (s.lifecycleStage.trim()) ref.lifecycle_stage = s.lifecycleStage.trim();
  return ref;
}

export function EvidencePicker({
  sessionId,
  open,
  onClose,
  orderId,
}: {
  sessionId: string;
  open: boolean;
  onClose: () => void;
  /** Prefill for order_number, from session.subjects.order_id when present. */
  orderId?: string;
}) {
  const [tab, setTab] = useState<Kind>('ARTIFACT');
  const [orderNumber, setOrderNumber] = useState('');
  // Ordered — for LIFECYCLE_COMPARISON the array order IS the sequence.
  const [selections, setSelections] = useState<Selection[]>([]);

  const artifacts = useArtifacts(sessionId, open);
  const events = useEvidenceEvents(sessionId, open);
  const create = useCreateAnalysis(sessionId);

  // Reset per opening: a picker that remembers last time's half-built selection is one
  // that submits something the tester did not mean to (cf. SkillPalette).
  useEffect(() => {
    if (open) {
      setTab('ARTIFACT');
      setOrderNumber(orderId ?? '');
      setSelections([]);
      create.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const selectedIds = useMemo(() => new Set(selections.map((s) => s.id)), [selections]);

  if (!open) return null;

  const toggleArtifact = (a: ArtifactRef) => {
    setSelections((prev) => {
      if (prev.some((s) => s.id === a.artifact_id)) {
        return prev.filter((s) => s.id !== a.artifact_id);
      }
      const inferred = inferArtifact(a);
      return [
        ...prev,
        {
          kind: 'ARTIFACT',
          id: a.artifact_id,
          title: a.logical_name || a.artifact_type,
          inferredType: inferred.type,
          inferredSource: inferred.source,
          stateType: inferred.type,
          source: inferred.source,
          label: '',
          lifecycleStage: '',
        },
      ];
    });
  };

  const toggleEvent = (e: EventRecord) => {
    setSelections((prev) => {
      if (prev.some((s) => s.id === e.event_id)) {
        return prev.filter((s) => s.id !== e.event_id);
      }
      const inferred = inferEvent(e);
      return [
        ...prev,
        {
          kind: 'EVENT',
          id: e.event_id,
          title: e.event_type || e.topic || e.event_id.slice(0, 8),
          inferredType: inferred.type,
          inferredSource: inferred.source,
          stateType: inferred.type,
          source: inferred.source,
          label: '',
          lifecycleStage: '',
        },
      ];
    });
  };

  const patch = (id: string, next: Partial<Selection>) =>
    setSelections((prev) => prev.map((s) => (s.id === id ? { ...s, ...next } : s)));

  const remove = (id: string) => setSelections((prev) => prev.filter((s) => s.id !== id));

  const move = (id: string, dir: -1 | 1) =>
    setSelections((prev) => {
      const i = prev.findIndex((s) => s.id === id);
      const j = i + dir;
      if (i < 0 || j < 0 || j >= prev.length) return prev;
      const next = [...prev];
      [next[i], next[j]] = [next[j]!, next[i]!];
      return next;
    });

  const orderOk = orderNumber.trim().length > 0;
  const canAnalyzeThis = orderOk && selections.length === 1; // SNAPSHOT_SANCTITY: exactly one.
  const canBuild = orderOk && selections.length >= 2; // LIFECYCLE_COMPARISON: a sequence.

  const submit = (mode: 'SNAPSHOT_SANCTITY' | 'LIFECYCLE_COMPARISON') => {
    create.mutate({
      analysis_mode: mode,
      order_number: orderNumber.trim(),
      states: selections.map(toStateRef),
    });
  };

  const problem = create.error as { detail?: string; title?: string } | undefined;

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center bg-black/60 pt-[8vh]"
      onClick={onClose}
    >
      <div
        className="flex max-h-[84vh] w-[54rem] flex-col overflow-hidden rounded-xl border border-ink-700 bg-ink-900 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="flex items-center gap-2 border-b border-ink-700 px-4 py-2.5">
          <h2 className="text-sm font-semibold text-ink-100">Financial analysis</h2>
          <Muted className="text-[11px]">assemble from evidence</Muted>
          <button
            onClick={onClose}
            className="ml-auto text-ink-400 hover:text-ink-100"
            aria-label="Close"
          >
            ✕
          </button>
        </header>

        {create.isSuccess && create.data ? (
          <ResultView data={create.data} onDone={onClose} onAgain={() => create.reset()} />
        ) : (
          <>
            {/* order number */}
            <div className="flex items-center gap-2 border-b border-ink-700 px-4 py-2.5">
              <label className="flex items-center gap-2">
                <span className="font-mono text-[12px] text-ink-100">order_number</span>
                <span className="text-[10px] text-red-400">required</span>
              </label>
              <input
                value={orderNumber}
                onChange={(e) => setOrderNumber(e.target.value)}
                placeholder="e.g. 100000123"
                className="w-56 rounded border border-ink-700 bg-ink-950 px-2 py-1 font-mono text-[12px] text-ink-100 outline-none focus:border-sky-700"
              />
              {orderId && (
                <Muted className="text-[11px]">prefilled from subjects.order_id</Muted>
              )}
            </div>

            <div className="grid min-h-0 flex-1 grid-cols-[1fr_22rem]">
              {/* left: evidence lists */}
              <div className="flex min-h-0 flex-col border-r border-ink-700">
                <div className="flex gap-1 border-b border-ink-700 px-2 py-1.5">
                  <TabButton
                    active={tab === 'ARTIFACT'}
                    onClick={() => setTab('ARTIFACT')}
                    label="Artifacts"
                    count={artifacts.data?.length}
                  />
                  <TabButton
                    active={tab === 'EVENT'}
                    onClick={() => setTab('EVENT')}
                    label="Events"
                    count={events.data?.events.length}
                  />
                </div>

                <div className="min-h-0 flex-1 overflow-y-auto">
                  {tab === 'ARTIFACT' ? (
                    <ArtifactList
                      query={artifacts}
                      selectedIds={selectedIds}
                      onToggle={toggleArtifact}
                    />
                  ) : (
                    <EventList query={events} selectedIds={selectedIds} onToggle={toggleEvent} />
                  )}
                </div>
              </div>

              {/* right: ordered selection review + overrides */}
              <div className="flex min-h-0 flex-col bg-ink-900/40">
                <div className="border-b border-ink-700 px-3 py-2">
                  <h3 className="text-[11px] font-semibold uppercase tracking-wide text-ink-400">
                    Selected states{' '}
                    <Mono className="text-ink-300 tabular-nums">{selections.length}</Mono>
                  </h3>
                </div>
                <div className="min-h-0 flex-1 overflow-y-auto p-2.5">
                  {selections.length === 0 ? (
                    <p className="px-1 py-4 text-[12px] text-ink-400 italic">
                      Nothing selected yet. Tick evidence on the left; each becomes a state you can
                      re-tag here.
                    </p>
                  ) : (
                    <ul className="space-y-2">
                      {selections.map((s, i) => (
                        <SelectedCard
                          key={s.id}
                          selection={s}
                          sequence={i + 1}
                          isFirst={i === 0}
                          isLast={i === selections.length - 1}
                          onPatch={(next) => patch(s.id, next)}
                          onRemove={() => remove(s.id)}
                          onMove={(dir) => move(s.id, dir)}
                        />
                      ))}
                    </ul>
                  )}
                </div>
              </div>
            </div>

            {problem && (
              <p className="border-t border-red-900/60 bg-red-950/20 px-4 py-2 text-[12px] text-red-300">
                {problem.detail ?? problem.title ?? 'Could not assemble this analysis.'}
              </p>
            )}

            <footer className="flex items-center gap-2 border-t border-ink-700 px-4 py-2.5">
              <Muted className="text-[11px]">
                {selections.length === 1
                  ? 'One state → Analyze this (snapshot sanctity).'
                  : selections.length >= 2
                    ? `${selections.length} ordered states → Build (lifecycle comparison).`
                    : 'Select one to analyze, or two or more to compare.'}
              </Muted>
              <div className="ml-auto flex items-center gap-2">
                <button
                  onClick={() => submit('SNAPSHOT_SANCTITY')}
                  disabled={!canAnalyzeThis || create.isPending}
                  title="One selected state — internal consistency of a single order picture"
                  className="rounded border border-ink-700 px-3 py-1.5 text-[12px] font-medium text-ink-100 hover:border-ink-600 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {create.isPending ? 'Submitting…' : 'Analyze this'}
                </button>
                <button
                  onClick={() => submit('LIFECYCLE_COMPARISON')}
                  disabled={!canBuild || create.isPending}
                  title="Two or more ordered states — compare across the order lifecycle"
                  className="rounded bg-sky-700 px-3 py-1.5 text-[12px] font-medium text-white hover:bg-sky-600 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {create.isPending ? 'Submitting…' : 'Build states → Analyze'}
                </button>
              </div>
            </footer>
          </>
        )}
      </div>
    </div>
  );
}

function TabButton({
  active,
  onClick,
  label,
  count,
}: {
  active: boolean;
  onClick: () => void;
  label: string;
  count?: number;
}) {
  return (
    <button
      onClick={onClick}
      className={`rounded px-2.5 py-1 text-[12px] font-medium ${
        active ? 'bg-ink-800 text-ink-100' : 'text-ink-400 hover:text-ink-100'
      }`}
    >
      {label}
      {count !== undefined && <span className="ml-1.5 text-ink-400 tabular-nums">{count}</span>}
    </button>
  );
}

function Row({
  selected,
  onToggle,
  children,
}: {
  selected: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onToggle}
      className={`flex w-full items-start gap-2.5 border-b border-ink-800 px-3 py-2 text-left hover:bg-ink-850 ${
        selected ? 'bg-sky-950/30' : ''
      }`}
    >
      <input
        type="checkbox"
        readOnly
        checked={selected}
        className="mt-0.5 size-4 accent-sky-600"
        tabIndex={-1}
      />
      <span className="min-w-0 flex-1">{children}</span>
    </button>
  );
}

function ArtifactList({
  query,
  selectedIds,
  onToggle,
}: {
  query: ReturnType<typeof useArtifacts>;
  selectedIds: Set<string>;
  onToggle: (a: ArtifactRef) => void;
}) {
  if (query.isLoading) {
    return (
      <p className="flex items-center gap-2 p-3 text-[12px] text-ink-400">
        <Spinner /> Loading artifacts…
      </p>
    );
  }
  const artifacts = query.data ?? [];
  if (artifacts.length === 0) {
    return <p className="p-4 text-center text-[12px] text-ink-400 italic">No artifacts captured yet.</p>;
  }
  return (
    <ul>
      {artifacts.map((a) => (
        <li key={a.artifact_id}>
          <Row selected={selectedIds.has(a.artifact_id)} onToggle={() => onToggle(a)}>
            <span className="flex items-center gap-2">
              <span className="truncate text-[12px] text-ink-100">{a.logical_name}</span>
              <span className="rounded bg-ink-800 px-1.5 py-0.5 text-[10px] text-ink-300">
                {a.artifact_type}
              </span>
              {a.source_system && <Muted className="text-[11px]">{a.source_system}</Muted>}
              {a.captured_at && (
                <span className="ml-auto text-[11px]">
                  <Ago at={a.captured_at} />
                </span>
              )}
            </span>
            <span className="mt-0.5 flex items-center gap-2 text-[11px]">
              {a.content_hash && (
                <Mono className="truncate text-ink-400" title={a.content_hash}>
                  {a.content_hash}
                </Mono>
              )}
              {a.row_count !== undefined && (
                <Muted className="tabular-nums">{a.row_count} rows</Muted>
              )}
            </span>
          </Row>
        </li>
      ))}
    </ul>
  );
}

function EventList({
  query,
  selectedIds,
  onToggle,
}: {
  query: ReturnType<typeof useEvidenceEvents>;
  selectedIds: Set<string>;
  onToggle: (e: EventRecord) => void;
}) {
  if (query.isLoading) {
    return (
      <p className="flex items-center gap-2 p-3 text-[12px] text-ink-400">
        <Spinner /> Loading events…
      </p>
    );
  }
  const events = query.data?.events ?? [];
  if (events.length === 0) {
    return <p className="p-4 text-center text-[12px] text-ink-400 italic">No events captured yet.</p>;
  }
  return (
    <>
      <ul>
        {events.map((e) => (
          <li key={e.event_id}>
            <Row selected={selectedIds.has(e.event_id)} onToggle={() => onToggle(e)}>
              <span className="flex items-center gap-2">
                <span className="truncate text-[12px] text-ink-100">{e.event_type ?? '—'}</span>
                {e.topic && (
                  <Mono className="truncate text-ink-400" title={e.topic}>
                    {e.topic}
                  </Mono>
                )}
                {e.occurred_at && (
                  <span className="ml-auto text-[11px]">
                    <Ago at={e.occurred_at} />
                  </span>
                )}
              </span>
              <span className="mt-0.5 flex items-center gap-2 text-[11px]">
                {e.event_key && <Muted>key {e.event_key}</Muted>}
                {e.source_offset && (
                  <Mono className="text-ink-400 tabular-nums">@{e.source_offset}</Mono>
                )}
              </span>
            </Row>
          </li>
        ))}
      </ul>
      {query.data?.truncated && (
        <p className="px-3 py-1.5 text-[10px] text-ink-400 italic">
          Showing the first {events.length}. More were captured.
        </p>
      )}
    </>
  );
}

const SELECT_CLASS =
  'w-full rounded border border-ink-700 bg-ink-950 px-1.5 py-1 font-mono text-[11px] text-ink-100 outline-none focus:border-sky-700';

function SelectedCard({
  selection,
  sequence,
  isFirst,
  isLast,
  onPatch,
  onRemove,
  onMove,
}: {
  selection: Selection;
  sequence: number;
  isFirst: boolean;
  isLast: boolean;
  onPatch: (next: Partial<Selection>) => void;
  onRemove: () => void;
  onMove: (dir: -1 | 1) => void;
}) {
  const overridden =
    selection.stateType !== selection.inferredType || selection.source !== selection.inferredSource;
  return (
    <li className="rounded-lg border border-ink-700 bg-ink-900 p-2.5">
      <div className="flex items-center gap-1.5">
        <Mono className="text-ink-400 tabular-nums">#{sequence}</Mono>
        <span className="rounded bg-ink-800 px-1.5 py-0.5 text-[10px] text-ink-300">
          {selection.kind}
        </span>
        <span className="min-w-0 flex-1 truncate text-[12px] text-ink-100">{selection.title}</span>
        <button
          onClick={() => onMove(-1)}
          disabled={isFirst}
          className="px-1 text-ink-400 hover:text-ink-100 disabled:opacity-30"
          aria-label="Move up"
        >
          ▲
        </button>
        <button
          onClick={() => onMove(1)}
          disabled={isLast}
          className="px-1 text-ink-400 hover:text-ink-100 disabled:opacity-30"
          aria-label="Move down"
        >
          ▼
        </button>
        <button
          onClick={onRemove}
          className="px-1 text-ink-400 hover:text-red-300"
          aria-label="Remove"
        >
          ✕
        </button>
      </div>

      <div className="mt-2 grid grid-cols-2 gap-1.5">
        <label className="block">
          <span className="mb-0.5 block text-[10px] text-ink-400">state_type</span>
          <select
            className={SELECT_CLASS}
            value={selection.stateType}
            onChange={(e) => onPatch({ stateType: e.target.value as StateType })}
          >
            {STATE_TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </label>
        <label className="block">
          <span className="mb-0.5 block text-[10px] text-ink-400">source</span>
          <select
            className={SELECT_CLASS}
            value={selection.source}
            onChange={(e) => onPatch({ source: e.target.value as Source })}
          >
            {SOURCES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <label className="block">
          <span className="mb-0.5 block text-[10px] text-ink-400">label (optional)</span>
          <input
            className={SELECT_CLASS}
            value={selection.label}
            placeholder="derived"
            onChange={(e) => onPatch({ label: e.target.value })}
          />
        </label>
        <label className="block">
          <span className="mb-0.5 block text-[10px] text-ink-400">lifecycle_stage (optional)</span>
          <input
            className={SELECT_CLASS}
            value={selection.lifecycleStage}
            placeholder="unspecified"
            onChange={(e) => onPatch({ lifecycleStage: e.target.value })}
          />
        </label>
      </div>
      {overridden ? (
        <p className="mt-1.5 text-[10px] text-amber-300/80">
          Overriding the inferred tags — sent explicitly.
        </p>
      ) : (
        <p className="mt-1.5 text-[10px] text-ink-400">
          Inferred tags — the server re-infers; no override sent.
        </p>
      )}
    </li>
  );
}

function ResultView({
  data,
  onDone,
  onAgain,
}: {
  data: CreateAnalysisResponse;
  onDone: () => void;
  onAgain: () => void;
}) {
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="min-h-0 flex-1 overflow-y-auto p-4">
        <div className="flex flex-wrap items-center gap-2">
          <span className="rounded bg-emerald-950 px-2 py-0.5 text-[11px] font-semibold text-emerald-300 ring-1 ring-inset ring-emerald-900">
            assembled
          </span>
          <Mono className="text-ink-300">{data.analysis_mode}</Mono>
          <Muted className="text-[11px]">order</Muted>
          <Mono className="text-ink-100">{data.order_number}</Mono>
          <span className="ml-auto flex gap-3 text-[11px]">
            <span>
              <Muted>persisted</Muted>{' '}
              <Mono className="text-emerald-300 tabular-nums">{data.persisted}</Mono>
            </span>
            <span>
              <Muted>deduped</Muted>{' '}
              <Mono className="text-ink-300 tabular-nums">{data.deduped}</Mono>
            </span>
          </span>
        </div>

        <Mono className="mt-2 block text-[11px] text-ink-400" title="analysis_id">
          {data.analysis_id}
        </Mono>

        {data.note && (
          <p className="mt-3 rounded border border-ink-700 bg-ink-950 px-3 py-2 text-[12px] text-ink-200">
            {data.note}
          </p>
        )}

        <p className="mt-4 mb-1 text-[11px] font-semibold uppercase tracking-wide text-ink-400">
          Assembled states
        </p>
        <table className="w-full text-[11px]">
          <thead>
            <tr className="text-ink-400">
              <th className="px-2 py-1 text-left font-medium">#</th>
              <th className="px-2 py-1 text-left font-medium">label</th>
              <th className="px-2 py-1 text-left font-medium">state_type</th>
              <th className="px-2 py-1 text-left font-medium">source</th>
              <th className="px-2 py-1 text-left font-medium">lifecycle_stage</th>
            </tr>
          </thead>
          <tbody>
            {data.states.map((s) => (
              <tr key={s.sequence} className="border-t border-ink-800">
                <td className="px-2 py-1">
                  <Mono className="text-ink-400 tabular-nums">{s.sequence}</Mono>
                </td>
                <td className="px-2 py-1 text-ink-100">{s.label}</td>
                <td className="px-2 py-1">
                  <Mono className="text-ink-300">{s.state_type}</Mono>
                </td>
                <td className="px-2 py-1">
                  <Mono className="text-ink-300">{s.source}</Mono>
                </td>
                <td className="px-2 py-1">
                  <Mono className="text-ink-400">{s.lifecycle_stage}</Mono>
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        <p className="mt-4 mb-1 text-[11px] font-semibold uppercase tracking-wide text-ink-400">
          Verdict
        </p>
        <RunVerdict runId={data.run_id} />
      </div>

      <footer className="flex items-center gap-2 border-t border-ink-700 px-4 py-2.5">
        <button
          onClick={onAgain}
          className="rounded border border-ink-700 px-3 py-1.5 text-[12px] text-ink-200 hover:border-ink-600 hover:text-ink-100"
        >
          Assemble another
        </button>
        <button
          onClick={onDone}
          className="ml-auto rounded bg-sky-700 px-3 py-1.5 text-[12px] font-medium text-white hover:bg-sky-600"
        >
          Done
        </button>
      </footer>
    </div>
  );
}

/**
 * overall_status → VerdictBadge. The badge speaks the Comparison vocabulary
 * (pass/fail/warn/inconclusive); an analysis speaks a slightly different one, so we map:
 * PASS_WITH_WARNINGS is a warn (it passed, with caveats), and NOT_VERIFIABLE is
 * inconclusive — the grey, deliberately-not-green badge, because "could not verify" must
 * never read as a mild pass.
 */
const OVERALL_TO_VERDICT: Record<string, Verdict> = {
  PASS: 'pass',
  FAIL: 'fail',
  PASS_WITH_WARNINGS: 'warn',
  NOT_VERIFIABLE: 'inconclusive',
};

/**
 * result_summary is an open map in the generated client ({ [key]: unknown }), so read
 * each field by type rather than trusting a shape — a missing or oddly-typed field
 * simply renders as absent instead of throwing.
 */
type ResultSummary = NonNullable<Run['result_summary']>;

function summaryText(summary: ResultSummary | undefined, key: string): string | undefined {
  const v = summary?.[key];
  return typeof v === 'string' && v.length > 0 ? v : undefined;
}

function summaryNum(summary: ResultSummary | undefined, key: string): number | undefined {
  const v = summary?.[key];
  return typeof v === 'number' && Number.isFinite(v) ? v : undefined;
}

/**
 * Watches the analysis run to its verdict.
 *
 * The picker's POST returns a run_id; the verdict lands on the run asynchronously. This
 * polls the run (useRun) and renders honestly at each stage: an optimistic pending card
 * while it works, the deterministic verdict once it succeeds, and a failed/timed_out card
 * that is NOT dressed up as a verdict when the run did not actually produce one.
 */
function RunVerdict({ runId }: { runId: string }) {
  const run = useRun(runId, true);

  if (run.isLoading || (!run.data && !run.error)) {
    return (
      <p className="flex items-center gap-2 py-2 text-[12px] text-ink-400">
        <Spinner /> Waiting for the analysis run…
      </p>
    );
  }

  if (run.error || !run.data) {
    const problem = run.error as { detail?: string; title?: string } | undefined;
    return (
      <p className="rounded border border-red-900/60 bg-red-950/20 px-3 py-2 text-[12px] text-red-300">
        {problem?.detail ?? problem?.title ?? 'Could not read the analysis run.'}
      </p>
    );
  }

  const r = run.data;

  // Still queued or running — the same optimistic card the timeline uses. No cancel here:
  // this is a read-only watch of an analysis the tester just kicked off.
  if (isActive(r.status)) {
    return <PendingRunCard run={r} />;
  }

  // Failed / timed_out are NOT verdicts. timed_out means the outcome is unknown; failed
  // means it errored. FailedRunCard says exactly that.
  if (r.status === 'failed' || r.status === 'timed_out') {
    return <FailedRunCard run={r} />;
  }

  if (r.status === 'cancelled') {
    return (
      <p className="rounded border border-ink-700 bg-ink-950 px-3 py-2 text-[12px] text-ink-300">
        This analysis run was cancelled before it produced a verdict.
      </p>
    );
  }

  // Terminal & succeeded — read the deterministic verdict off result_summary.
  const summary = r.result_summary;
  const overall = summaryText(summary, 'overall_status');
  const verdict = overall ? OVERALL_TO_VERDICT[overall] : undefined;
  const confidence = summaryNum(summary, 'confidence');
  const executive = summaryText(summary, 'executive_summary');
  const aiStatus = summaryText(summary, 'ai_analysis_status');
  const aiReason = summaryText(summary, 'ai_unavailable_reason');
  const findingsTotal = summaryNum(summary, 'findings_total');
  const findingsFail = summaryNum(summary, 'findings_fail');
  const model = summaryText(summary, 'model');

  return (
    <div className="rounded-lg border border-ink-700 bg-ink-900 p-3">
      <div className="flex flex-wrap items-center gap-2">
        {verdict ? (
          <VerdictBadge verdict={verdict} />
        ) : (
          <span className="rounded bg-ink-800 px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-ink-300 ring-1 ring-inset ring-ink-600">
            {overall ?? 'no verdict reported'}
          </span>
        )}
        {overall && verdict && <Mono className="text-ink-400">{overall}</Mono>}
        {confidence !== undefined && (
          <span className="ml-auto text-[11px]">
            <Muted>confidence</Muted>{' '}
            <Mono className="text-ink-200 tabular-nums">{confidence.toFixed(2)}</Mono>
          </span>
        )}
      </div>

      {executive && <p className="mt-2.5 text-[12px] leading-relaxed text-ink-100">{executive}</p>}

      {/* AI status, muted — deterministic analysis is authoritative; the AI layer is a
          garnish and we say so honestly rather than hiding an UNAVAILABLE behind silence. */}
      {aiStatus && (
        <p className="mt-2.5 text-[11px] text-ink-400">
          AI analysis: <Mono className="text-ink-300">{aiStatus}</Mono>
          {aiStatus !== 'OK' && aiReason ? ` — ${aiReason}` : ''}
        </p>
      )}

      {(findingsTotal !== undefined || findingsFail !== undefined) && (
        <div className="mt-2.5 flex flex-wrap items-center gap-3 text-[11px]">
          {findingsTotal !== undefined && (
            <span>
              <Muted>findings</Muted>{' '}
              <Mono className="text-ink-200 tabular-nums">{findingsTotal}</Mono>
            </span>
          )}
          {findingsFail !== undefined && (
            <span>
              <Muted>failing</Muted>{' '}
              <Mono
                className={`tabular-nums ${findingsFail > 0 ? 'text-red-300' : 'text-ink-200'}`}
              >
                {findingsFail}
              </Mono>
            </span>
          )}
          {model && (
            <span className="ml-auto">
              <Muted>model</Muted> <Mono className="text-ink-400">{model}</Mono>
            </span>
          )}
        </div>
      )}

      <p className="mt-2.5 text-[11px] text-ink-400 italic">
        The individual findings appear in this session's findings feed.
      </p>
    </div>
  );
}
