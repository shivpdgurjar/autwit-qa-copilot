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
import { Badge, Button, Input, Mono, Spinner } from '../../components/ui';

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
      <h2 className="text-lg font-semibold tracking-tight">Reason over the documents</h2>
      <p className="mt-1 mb-5 max-w-[70ch] text-[13px] text-ink-400">
        Before we draft a plan, check the selected documents for contradictions and gaps. Resolve
        what comes back, then re-analyze — the plan is only as sound as the inputs it's built on.
        This step is optional; you can generate directly.
      </p>

      {/* Not started yet, and nothing running → the invitation to analyze. */}
      {!reasoning && !analyzing && (
        <div className="rounded-xl border border-ink-700 bg-ink-900 p-5 shadow-sm">
          <p className="text-[13px] font-medium text-ink-100">No analysis yet.</p>
          <p className="mt-1 text-[12.5px] text-ink-400">
            Run a reasoning pass to surface conflicts between your documents and information the
            plan will need but the material doesn't settle.
          </p>
          <Button className="mt-4" onClick={startAnalyze} disabled={analyze.isPending}>
            <ScanIcon /> Analyze documents for conflicts &amp; gaps
          </Button>
          {analyze.error != null && (
            <p className="mt-2 text-[11px] text-red-300">
              {(analyze.error as { detail?: string }).detail ?? 'Could not start the analysis.'}
            </p>
          )}
        </div>
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
        <div className="space-y-5">
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
              tone="red"
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
              tone="amber"
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
            <Button variant="ghost" onClick={startAnalyze}>
              <RefreshIcon /> Re-analyze with answers
            </Button>
            {gateOpen && (
              <ProceedAnyway
                onConfirm={(reason) => override.mutate({ reason })}
                pending={override.isPending}
              />
            )}
          </div>
        </div>
      )}

      <div className="mt-6 flex items-center gap-2 border-t border-ink-700 pt-4">
        <Button variant="ghost" onClick={onBack}>
          <ChevronLeft /> Back
        </Button>
        <div className="ml-auto flex flex-col items-end gap-1">
          <Button
            onClick={() => generate.mutate(undefined, { onSuccess: (g) => onGenerated(g.generation_id) })}
            disabled={gateOpen || generate.isPending}
            title={gateOpen ? 'Resolve the open findings or choose “proceed anyway” first' : undefined}
          >
            {generate.isPending ? 'Starting…' : 'Generate test plan'} <ArrowRight />
          </Button>
          {gateOpen && (
            <span className="text-[11px] text-ink-400">Resolve findings or proceed anyway to continue</span>
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
  const base = 'flex items-center gap-2.5 rounded-lg border px-4 py-2.5 text-[13px] font-medium';
  if (status === 'clean') {
    return (
      <div className={`${base} border-emerald-900 bg-emerald-950 text-emerald-300`}>
        <CheckIcon /> No conflicts or gaps found (round {round}) — ready to generate.
      </div>
    );
  }
  if (status === 'overridden') {
    return (
      <div className={`${base} border-amber-900 bg-amber-950 text-amber-300`}>
        <AlertIcon /> Proceeding despite unresolved items.
        {overrideReason ? <span className="font-normal opacity-80"> — {overrideReason}</span> : null}
      </div>
    );
  }
  const total = conflicts + clarifications;
  return (
    <div className={`${base} border-sky-900 bg-sky-950 text-sky-200`}>
      <SearchIcon />
      <span>
        <span className="tabular-nums">{total}</span> item{total === 1 ? '' : 's'} to resolve (round{' '}
        {round}): <span className="tabular-nums">{conflicts}</span> conflict{conflicts === 1 ? '' : 's'},{' '}
        <span className="tabular-nums">{clarifications}</span> clarification{clarifications === 1 ? '' : 's'}.
      </span>
    </div>
  );
}

function FindingGroup({
  title,
  tone,
  findings,
  answered,
  answerFor,
  onSave,
  saving,
}: {
  title: string;
  tone: 'red' | 'amber';
  findings: FindingView[];
  answered: Set<string>;
  answerFor: (findingId: string) => string | undefined;
  onSave: (f: FindingView, answer: string) => void;
  saving: boolean;
}) {
  const headTone = tone === 'red' ? 'text-red-300' : 'text-amber-300';
  return (
    <div>
      <h3 className={`mb-2.5 flex items-center gap-2 text-[11px] font-bold uppercase tracking-wider ${headTone}`}>
        {tone === 'red' ? <AlertIcon /> : <HelpIcon />}
        {title}
        <span className="rounded-full border border-ink-700 bg-ink-900 px-2 text-[11px] font-medium normal-case tracking-normal text-ink-400 tabular-nums">
          {findings.length}
        </span>
      </h3>
      <div className="space-y-3">
        {findings.map((f) => (
          <FindingCard
            key={f.finding_id}
            finding={f}
            tone={tone}
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
  tone,
  isAnswered,
  existingAnswer,
  onSave,
  saving,
}: {
  finding: FindingView;
  tone: 'red' | 'amber';
  isAnswered: boolean;
  existingAnswer?: string;
  onSave: (answer: string) => void;
  saving: boolean;
}) {
  const [answer, setAnswer] = useState('');
  const sources = (finding.sources ?? []) as Array<{ doc_title?: string; quote?: string }>;
  const options = finding.options ?? [];
  const stripe = tone === 'red' ? 'bg-red-400' : 'bg-amber-400';

  return (
    <div className="relative rounded-xl border border-ink-700 bg-ink-900 p-4 pl-5 shadow-sm transition-shadow hover:shadow-md">
      <span className={`absolute top-4 bottom-4 left-0 w-[3px] rounded ${stripe}`} />
      <div className="flex items-start justify-between gap-3">
        <p className="text-[14px] font-semibold tracking-tight text-ink-100">{finding.title}</p>
        {isAnswered ? (
          <Badge tone="emerald">✓ Answered</Badge>
        ) : (
          <Badge tone={tone}>{tone === 'red' ? 'Conflict' : 'Clarification'}</Badge>
        )}
      </div>
      {finding.detail && <p className="mt-1 text-[13px] text-ink-400">{finding.detail}</p>}

      {sources.length > 0 && (
        <ul className="mt-3 flex flex-col gap-1.5 border-t border-dashed border-ink-600 pt-3">
          {sources.map((s, i) => (
            <li key={i} className="flex items-baseline gap-2 text-[12px]">
              <Mono className="shrink-0 text-sky-200">{s.doc_title}</Mono>
              {s.quote ? <span className="text-ink-100 italic">“{s.quote}”</span> : null}
            </li>
          ))}
        </ul>
      )}

      {isAnswered ? (
        <p className="mt-3 rounded-lg border border-emerald-900 bg-emerald-950 px-3 py-2 text-[12.5px] text-emerald-300">
          <span className="text-ink-400">Your answer: </span>
          {existingAnswer}
        </p>
      ) : (
        <div className="mt-3.5 flex flex-col gap-2">
          {options.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {options.map((o, i) => (
                <button
                  key={i}
                  onClick={() => setAnswer(o)}
                  className={`rounded-lg border px-3 py-1.5 font-mono text-[12.5px] transition-colors ${
                    answer === o
                      ? 'border-sky-600 bg-sky-950 font-semibold text-sky-200 ring-2 ring-sky-600/15'
                      : 'border-ink-600 bg-ink-900 text-ink-100 hover:border-sky-900 hover:bg-sky-950'
                  }`}
                >
                  {o}
                </button>
              ))}
            </div>
          )}
          <div className="flex gap-2">
            <Input
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && answer.trim() && onSave(answer.trim())}
              placeholder={options.length > 0 ? 'Confirm the correct value…' : 'Answer this…'}
              className="flex-1"
            />
            <Button variant="ghost" size="sm" onClick={() => answer.trim() && onSave(answer.trim())} disabled={!answer.trim() || saving}>
              Save
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

function ProceedAnyway({ onConfirm, pending }: { onConfirm: (reason: string) => void; pending: boolean }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  if (!open) {
    return (
      <Button variant="warn" onClick={() => setOpen(true)}>
        Proceed anyway
      </Button>
    );
  }
  return (
    <div className="flex items-center gap-2">
      <Input
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder="Why proceed with open items? (recorded)"
        className="w-72"
      />
      <Button variant="warn" size="sm" onClick={() => onConfirm(reason.trim())} disabled={pending}>
        Confirm
      </Button>
    </div>
  );
}

/* --- inline lucide-style icons (currentColor, no font/CDN) --- */
const ic = 'inline-block align-[-2px]';
function ScanIcon() {
  return (
    <svg className={ic} width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 7V5a2 2 0 0 1 2-2h2" /><path d="M17 3h2a2 2 0 0 1 2 2v2" /><path d="M21 17v2a2 2 0 0 1-2 2h-2" /><path d="M7 21H5a2 2 0 0 1-2-2v-2" /><path d="M7 12h10" /></svg>
  );
}
function RefreshIcon() {
  return (
    <svg className={ic} width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 12a9 9 0 1 0 9-9 9 9 0 0 0-6.4 2.6L3 8" /><path d="M3 3v5h5" /></svg>
  );
}
function SearchIcon() {
  return (
    <svg className={`${ic} shrink-0`} width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>
  );
}
function AlertIcon() {
  return (
    <svg className={ic} width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="m10.3 3.9-8 13.9A2 2 0 0 0 4 21h16a2 2 0 0 0 1.7-3.2l-8-13.9a2 2 0 0 0-3.4 0Z" /><path d="M12 9v4" /><path d="M12 17h.01" /></svg>
  );
}
function HelpIcon() {
  return (
    <svg className={ic} width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9" /><path d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3" /><path d="M12 17h.01" /></svg>
  );
}
function CheckIcon() {
  return (
    <svg className={`${ic} shrink-0`} width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M20 6 9 17l-5-5" /></svg>
  );
}
function ChevronLeft() {
  return (
    <svg className={ic} width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="m15 18-6-6 6-6" /></svg>
  );
}
function ArrowRight() {
  return (
    <svg className={ic} width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14" /><path d="m12 5 7 7-7 7" /></svg>
  );
}
