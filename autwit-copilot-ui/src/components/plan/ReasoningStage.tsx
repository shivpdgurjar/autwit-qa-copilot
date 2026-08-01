import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { FindingView } from '../../api/client';
import {
  reasoningKey,
  useAddResolution,
  useAnalyzeDocuments,
  useGeneration,
  useGenerateTestPlan,
  useOverrideReasoning,
  useReasoning,
} from '../../hooks/usePlanning';
import { Card, Mono, Spinner } from '../../components/ui';

/**
 * Reasoning — the opt-in pass BEFORE a test plan. Analyze the selected documents for conflicts
 * (contradictions to confirm) and clarifications (gaps to answer), resolve each, re-analyze
 * until clean, then generate. Generation is gated while findings are open, with a recorded
 * "proceed anyway" override. Skipping straight to generate is fine — reasoning is optional.
 */
export function ReasoningStage({
  projectId,
  analyzeGenId,
  onAnalyzeStarted,
  onBack,
  onGenerated,
}: {
  projectId: string;
  analyzeGenId?: string;
  onAnalyzeStarted: (generationId: string) => void;
  onBack: () => void;
  onGenerated: (generationId: string) => void;
}) {
  const qc = useQueryClient();
  const { data: reasoning } = useReasoning(projectId);
  const analyze = useAnalyzeDocuments(projectId);
  const addResolution = useAddResolution(projectId);
  const override = useOverrideReasoning(projectId);
  const generate = useGenerateTestPlan(projectId);

  // Poll the in-flight analysis; when it settles, refresh the reasoning thread.
  const gen = useGeneration(projectId, analyzeGenId);
  const analyzing = analyze.isPending
    || (!!analyzeGenId && gen.data != null && gen.data.status !== 'succeeded' && gen.data.status !== 'failed');
  useEffect(() => {
    if (gen.data?.status === 'succeeded') {
      void qc.invalidateQueries({ queryKey: reasoningKey(projectId) });
    }
  }, [gen.data?.status, projectId, qc]);

  const status = reasoning?.status;
  const latest = reasoning?.latest ?? null;
  const conflicts = (latest?.findings ?? []).filter((f) => f.kind === 'conflict');
  const clarifications = (latest?.findings ?? []).filter((f) => f.kind === 'clarification');
  const answered = new Set(
    (reasoning?.resolutions ?? []).map((r) => r.finding_id).filter((id): id is string => !!id),
  );
  const answerFor = (findingId: string) =>
    (reasoning?.resolutions ?? []).find((r) => r.finding_id === findingId)?.answer;

  const gateOpen = status === 'open';
  const analysisFailed = !!analyzeGenId && gen.data?.status === 'failed';

  function startAnalyze() {
    analyze.mutate(undefined, { onSuccess: (g) => onAnalyzeStarted(g.generation_id) });
  }

  return (
    <div className="mx-auto max-w-3xl">
      <h2 className="text-lg font-semibold">Reason over the documents</h2>
      <p className="mt-1 mb-5 text-[13px] text-ink-400">
        Before we draft a plan, check the selected documents for contradictions and gaps. Resolve
        what comes back, then re-analyze — the plan is only as sound as the inputs it's built on.
        This step is optional; you can generate directly.
      </p>

      {/* Not started yet, and nothing running → the invitation to analyze. */}
      {!reasoning && !analyzing && (
        <Card>
          <p className="text-[13px] text-ink-200">No analysis yet.</p>
          <p className="mt-1 text-[12px] text-ink-400">
            Run a reasoning pass to surface conflicts between your documents and information the
            plan will need but the material doesn't settle.
          </p>
          <button
            onClick={startAnalyze}
            disabled={analyze.isPending}
            className="mt-3 rounded bg-sky-700 px-4 py-2 text-[13px] font-medium text-white hover:bg-sky-600 disabled:opacity-40"
          >
            Analyze documents for conflicts &amp; gaps
          </button>
          {analyze.error != null && (
            <p className="mt-2 text-[11px] text-red-300">
              {(analyze.error as { detail?: string }).detail ?? 'Could not start the analysis.'}
            </p>
          )}
        </Card>
      )}

      {analyzing && (
        <p className="flex items-center gap-2 py-10 text-[13px] text-ink-400">
          <Spinner /> Analyzing the documents… (a real analysis is a ~60s model call)
        </p>
      )}

      {analysisFailed && !analyzing && (
        <p className="py-4 text-[13px] text-red-300">
          Analysis failed: {String(gen.data?.error?.detail ?? 'unknown error')}
        </p>
      )}

      {/* A completed round. */}
      {reasoning && !analyzing && (
        <div className="space-y-4">
          <StatusBanner
            status={status}
            conflicts={conflicts.length}
            clarifications={clarifications.length}
            round={reasoning.round}
            overrideReason={reasoning.override_reason}
          />

          {conflicts.length > 0 && (
            <FindingGroup
              title="Conflicts to confirm"
              accent="text-red-400"
              findings={conflicts}
              answered={answered}
              answerFor={answerFor}
              onSave={(f, answer) =>
                addResolution.mutate({ finding_id: f.finding_id, kind: 'conflict', prompt: f.title, answer })
              }
              saving={addResolution.isPending}
            />
          )}

          {clarifications.length > 0 && (
            <FindingGroup
              title="Clarifications needed"
              accent="text-amber-400"
              findings={clarifications}
              answered={answered}
              answerFor={answerFor}
              onSave={(f, answer) =>
                addResolution.mutate({ finding_id: f.finding_id, kind: 'clarification', prompt: f.title, answer })
              }
              saving={addResolution.isPending}
            />
          )}

          <div className="flex flex-wrap items-center gap-2">
            <button
              onClick={startAnalyze}
              className="rounded border border-ink-700 px-3 py-2 text-[13px] hover:border-ink-600"
            >
              Re-analyze with answers
            </button>
            {gateOpen && (
              <ProceedAnyway
                onConfirm={(reason) => override.mutate({ reason })}
                pending={override.isPending}
              />
            )}
          </div>
        </div>
      )}

      <div className="mt-6 flex items-center gap-2 border-t border-ink-800 pt-4">
        <button onClick={onBack} className="rounded border border-ink-700 px-3 py-2 text-[13px] hover:border-ink-600">
          ← Back
        </button>
        <div className="ml-auto flex flex-col items-end gap-1">
          <button
            onClick={() => generate.mutate(undefined, { onSuccess: (g) => onGenerated(g.generation_id) })}
            disabled={gateOpen || generate.isPending}
            title={gateOpen ? 'Resolve the open findings or choose “proceed anyway” first' : undefined}
            className="rounded bg-sky-700 px-4 py-2 text-[13px] font-medium text-white hover:bg-sky-600 disabled:opacity-40"
          >
            {generate.isPending ? 'Starting…' : 'Generate test plan →'}
          </button>
          {gateOpen && (
            <span className="text-[11px] text-ink-500">Resolve findings or proceed anyway to continue</span>
          )}
          {generate.error != null && (
            <span className="text-[11px] text-red-300">
              {(generate.error as { detail?: string }).detail ?? 'Could not start generation.'}
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

function StatusBanner({
  status,
  conflicts,
  clarifications,
  round,
  overrideReason,
}: {
  status?: string;
  conflicts: number;
  clarifications: number;
  round: number;
  overrideReason?: string | null;
}) {
  const base = 'rounded-lg border px-3 py-2 text-[12.5px]';
  if (status === 'clean') {
    return (
      <div className={`${base} border-emerald-700/40 bg-emerald-700/10 text-emerald-300`}>
        ✓ No conflicts or gaps found (round {round}) — ready to generate.
      </div>
    );
  }
  if (status === 'overridden') {
    return (
      <div className={`${base} border-amber-700/40 bg-amber-700/10 text-amber-300`}>
        Proceeding despite unresolved items.
        {overrideReason ? <span className="text-amber-200/80"> — {overrideReason}</span> : null}
      </div>
    );
  }
  const total = conflicts + clarifications;
  return (
    <div className={`${base} border-sky-700/40 bg-sky-700/10 text-sky-300`}>
      {total} item{total === 1 ? '' : 's'} to resolve (round {round}): {conflicts} conflict
      {conflicts === 1 ? '' : 's'}, {clarifications} clarification{clarifications === 1 ? '' : 's'}.
    </div>
  );
}

function FindingGroup({
  title,
  accent,
  findings,
  answered,
  answerFor,
  onSave,
  saving,
}: {
  title: string;
  accent: string;
  findings: FindingView[];
  answered: Set<string>;
  answerFor: (findingId: string) => string | undefined;
  onSave: (f: FindingView, answer: string) => void;
  saving: boolean;
}) {
  return (
    <div>
      <h3 className={`mb-2 text-[12px] font-semibold uppercase tracking-wide ${accent}`}>{title}</h3>
      <div className="space-y-2.5">
        {findings.map((f) => (
          <FindingCard
            key={f.finding_id}
            finding={f}
            isAnswered={answered.has(f.finding_id)}
            existingAnswer={answerFor(f.finding_id)}
            onSave={(answer) => onSave(f, answer)}
            saving={saving}
          />
        ))}
      </div>
    </div>
  );
}

function FindingCard({
  finding,
  isAnswered,
  existingAnswer,
  onSave,
  saving,
}: {
  finding: FindingView;
  isAnswered: boolean;
  existingAnswer?: string;
  onSave: (answer: string) => void;
  saving: boolean;
}) {
  const [answer, setAnswer] = useState('');
  const sources = (finding.sources ?? []) as Array<{ doc_title?: string; quote?: string }>;
  const options = finding.options ?? [];

  return (
    <Card>
      <div className="flex items-start justify-between gap-2">
        <p className="text-[13px] font-medium text-ink-100">{finding.title}</p>
        {isAnswered && <span className="shrink-0 text-[11px] text-emerald-400">✓ answered</span>}
      </div>
      {finding.detail && <p className="mt-1 text-[12px] text-ink-400">{finding.detail}</p>}

      {sources.length > 0 && (
        <ul className="mt-2 space-y-1">
          {sources.map((s, i) => (
            <li key={i} className="text-[11px] text-ink-500">
              <Mono className="text-ink-400">{s.doc_title}</Mono>
              {s.quote ? <span className="italic text-ink-400"> — “{s.quote}”</span> : null}
            </li>
          ))}
        </ul>
      )}

      {isAnswered ? (
        <p className="mt-2 rounded bg-ink-850 px-2 py-1.5 text-[12px] text-ink-200">
          <span className="text-ink-500">Your answer: </span>
          {existingAnswer}
        </p>
      ) : (
        <div className="mt-2.5">
          {options.length > 0 && (
            <div className="mb-1.5 flex flex-wrap gap-1.5">
              {options.map((o, i) => (
                <button
                  key={i}
                  onClick={() => setAnswer(o)}
                  className={`rounded border px-2 py-0.5 text-[11.5px] ${
                    answer === o
                      ? 'border-sky-600 bg-sky-700/20 text-sky-300'
                      : 'border-ink-700 text-ink-300 hover:border-ink-600'
                  }`}
                >
                  {o}
                </button>
              ))}
            </div>
          )}
          <div className="flex gap-2">
            <input
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && answer.trim() && onSave(answer.trim())}
              placeholder={options.length > 0 ? 'Confirm the correct value…' : 'Answer this…'}
              className="flex-1 rounded border border-ink-700 bg-ink-950 px-2 py-1.5 text-[12px] outline-none focus:border-sky-700"
            />
            <button
              onClick={() => answer.trim() && onSave(answer.trim())}
              disabled={!answer.trim() || saving}
              className="rounded border border-ink-700 px-3 text-[12px] hover:border-ink-600 disabled:opacity-40"
            >
              Save
            </button>
          </div>
        </div>
      )}
    </Card>
  );
}

function ProceedAnyway({ onConfirm, pending }: { onConfirm: (reason: string) => void; pending: boolean }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  if (!open) {
    return (
      <button
        onClick={() => setOpen(true)}
        className="rounded border border-amber-700/50 px-3 py-2 text-[13px] text-amber-300 hover:border-amber-600"
      >
        Proceed anyway
      </button>
    );
  }
  return (
    <div className="flex items-center gap-2">
      <input
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder="Why proceed with open items? (recorded)"
        className="w-72 rounded border border-ink-700 bg-ink-950 px-2 py-1.5 text-[12px] outline-none focus:border-amber-700"
      />
      <button
        onClick={() => onConfirm(reason.trim())}
        disabled={pending}
        className="rounded bg-amber-700 px-3 py-1.5 text-[12px] font-medium text-white hover:bg-amber-600 disabled:opacity-40"
      >
        Confirm
      </button>
    </div>
  );
}
