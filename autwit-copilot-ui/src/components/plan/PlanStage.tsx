import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { TestScenarioView } from '../../api/client';
import { useGeneration, useLatestTestPlan } from '../../hooks/usePlanning';
import { Spinner } from '../../components/ui';

/** Step 3 — the generated test plan, once its generation settles. */
export function PlanStage({
  projectId,
  generationId,
  onBack,
  onNext,
}: {
  projectId: string;
  generationId?: string;
  onBack: () => void;
  onNext: () => void;
}) {
  const qc = useQueryClient();
  const gen = useGeneration(projectId, generationId);
  const { data: plan } = useLatestTestPlan(projectId);

  // When the generation lands, refetch the persisted plan.
  useEffect(() => {
    if (gen.data?.status === 'succeeded') {
      void qc.invalidateQueries({ queryKey: ['planning', 'test-plan', projectId] });
    }
  }, [gen.data?.status, projectId, qc]);

  const running = generationId && gen.data && gen.data.status !== 'succeeded' && gen.data.status !== 'failed';
  const failed = gen.data?.status === 'failed';

  return (
    <div className="mx-auto max-w-3xl">
      {running && (
        <p className="flex items-center gap-2 py-16 text-[13px] text-ink-400">
          <Spinner /> Generating the test plan… (a real generation is a ~60s model call)
        </p>
      )}
      {failed && (
        <p className="py-16 text-[13px] text-red-300">
          Generation failed: {String(gen.data?.error?.detail ?? 'unknown error')}
        </p>
      )}

      {plan && !running && (
        <article className="rounded-xl border border-ink-700 bg-ink-900 p-8">
          <div className="text-[11px] font-mono uppercase tracking-wider text-sky-500">
            Test Plan · Auto-generated
          </div>
          <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-ink-400">
            {(plan.provenance?.sources as string[] | undefined)?.length ? (
              <span>Source: {(plan.provenance!.sources as string[]).join(', ')}</span>
            ) : null}
            {plan.provenance?.doc_version ? <span>Design doc: {String(plan.provenance.doc_version)}</span> : null}
          </div>

          {plan.overview && (
            <>
              <h3 className="mt-6 text-[12px] font-semibold uppercase tracking-wide text-sky-500">Overview</h3>
              <p className="mt-1.5 text-[14px] leading-relaxed text-ink-100">{plan.overview}</p>
            </>
          )}
          {plan.scope && (
            <>
              <h3 className="mt-5 text-[12px] font-semibold uppercase tracking-wide text-sky-500">Scope</h3>
              <p className="mt-1.5 text-[14px] leading-relaxed text-ink-100">{plan.scope}</p>
            </>
          )}

          <h3 className="mt-6 mb-2 text-[12px] font-semibold uppercase tracking-wide text-sky-500">
            Test scenarios
          </h3>
          <table className="w-full border-collapse text-[13px]">
            <thead>
              <tr className="border-b border-ink-600 text-left text-[11px] uppercase text-ink-400">
                <th className="py-1.5 pr-3">ID</th>
                <th className="py-1.5 pr-3">Scenario</th>
                <th className="py-1.5 pr-3">Priority</th>
                <th className="py-1.5">Source</th>
              </tr>
            </thead>
            <tbody>
              {plan.scenarios.map((s: TestScenarioView) => (
                <tr key={s.scenario_key} className="border-b border-ink-800">
                  <td className="py-2 pr-3 font-mono text-ink-400">{s.scenario_key}</td>
                  <td className="py-2 pr-3">{s.title}</td>
                  <td className={`py-2 pr-3 font-medium ${priorityClass(s.priority)}`}>{s.priority}</td>
                  <td className="py-2 text-ink-400">{s.source}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </article>
      )}

      <div className="mt-6 flex items-center gap-2">
        <button onClick={onBack} className="rounded border border-ink-700 px-3 py-2 text-[13px] hover:border-ink-600">
          ← Back
        </button>
        <div className="ml-auto flex gap-2">
          {plan && (
            <button
              onClick={() => downloadHtml(plan)}
              className="rounded border border-ink-700 px-3 py-2 text-[13px] hover:border-ink-600"
            >
              Download HTML
            </button>
          )}
          <button
            onClick={onNext}
            disabled={!plan}
            className="rounded bg-sky-700 px-4 py-2 text-[13px] font-medium text-white hover:bg-sky-600 disabled:opacity-40"
          >
            Continue to test data →
          </button>
        </div>
      </div>
    </div>
  );
}

function priorityClass(p?: string | null) {
  return p === 'High' ? 'text-red-400' : p === 'Medium' ? 'text-amber-400' : 'text-emerald-400';
}

function downloadHtml(plan: NonNullable<ReturnType<typeof useLatestTestPlan>['data']>) {
  const rows = plan.scenarios
    .map(
      (s) =>
        `<tr><td>${s.scenario_key}</td><td>${escapeHtml(s.title)}</td><td>${s.priority ?? ''}</td><td>${
          escapeHtml(s.source ?? '')
        }</td></tr>`,
    )
    .join('');
  const html = `<!doctype html><meta charset="utf-8"><title>Test Plan</title>
<h1>Test Plan</h1>
${plan.overview ? `<h2>Overview</h2><p>${escapeHtml(plan.overview)}</p>` : ''}
${plan.scope ? `<h2>Scope</h2><p>${escapeHtml(plan.scope)}</p>` : ''}
<h2>Scenarios</h2><table border="1" cellpadding="6" cellspacing="0">
<tr><th>ID</th><th>Scenario</th><th>Priority</th><th>Source</th></tr>${rows}</table>`;
  const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = 'test-plan.html';
  a.click();
  URL.revokeObjectURL(url);
}

function escapeHtml(s: string) {
  return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!));
}
