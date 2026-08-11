import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type {
  TestPlanCapability,
  TestPlanView,
  TestScenarioView,
} from '../../api/client';
import { useGeneration, useLatestTestPlan } from '../../hooks/usePlanning';
import { Badge, Button, Spinner } from '../../components/ui';

/**
 * Step 4 — the generated test plan.
 *
 * The plan is a synthesized document, not a scenario summary, so it is rendered as a
 * document: a summary area, then one section per business capability, with each test case
 * collapsed to its identity and expandable to the detail a tester needs to execute it.
 * Native <details> rather than a JS accordion — it is one element, keyboard-accessible for
 * free, and it keeps working inside the sandboxed iframes this app renders reports in.
 *
 * A legacy plan (plan_version 1, written before the upgrade) has no capabilities; it falls
 * back to the original flat table rather than rendering an empty document.
 */
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

      {plan && !running && <PlanDocument plan={plan} />}

      <div className="mt-6 flex items-center gap-2">
        <Button variant="ghost" onClick={onBack}>← Back</Button>
        <div className="ml-auto flex gap-2">
          {plan && (
            <Button variant="ghost" onClick={() => downloadHtml(plan)}>Download HTML</Button>
          )}
          <Button onClick={onNext} disabled={!plan}>Continue to test data →</Button>
        </div>
      </div>
    </div>
  );
}

function PlanDocument({ plan }: { plan: TestPlanView }) {
  const capabilities = plan.capabilities ?? [];
  const caseCount = capabilities.reduce((n, c) => n + (c.test_cases?.length ?? 0), 0);
  const sources = plan.provenance?.sources as string[] | undefined;

  return (
    <article className="rounded-xl border border-ink-700 bg-ink-900 p-8">
      <div className="text-[11px] font-mono uppercase tracking-wider text-sky-500">
        Test Plan · Auto-generated
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] text-ink-400">
        {sources?.length ? <span>Source: {sources.join(', ')}</span> : null}
        {plan.provenance?.doc_version ? <span>Design doc: {String(plan.provenance.doc_version)}</span> : null}
        {caseCount > 0 && (
          <span>
            {caseCount} test case{caseCount === 1 ? '' : 's'} · {capabilities.length} capabilit
            {capabilities.length === 1 ? 'y' : 'ies'}
          </span>
        )}
      </div>

      <Prose title="Overview" text={plan.overview} />

      {plan.architecture_context && <ArchitectureContext context={plan.architecture_context} />}

      {(plan.scope?.in_scope?.length || plan.scope?.out_of_scope?.length) ? (
        <>
          <SectionHeading>Scope</SectionHeading>
          <div className="mt-1.5 grid gap-4 sm:grid-cols-2">
            <BulletList label="In scope" items={plan.scope?.in_scope} />
            <BulletList label="Out of scope" items={plan.scope?.out_of_scope} />
          </div>
        </>
      ) : null}

      {plan.requirements?.length ? (
        <>
          <SectionHeading>Requirements</SectionHeading>
          <ul className="mt-1.5 space-y-2">
            {plan.requirements.map((r) => (
              <li key={r.id} className="text-[13px] leading-relaxed text-ink-100">
                <span className="font-mono text-[12px] text-ink-400">{r.id}</span>{' '}
                {r.statement}
                <span className="ml-2 text-[11px] text-ink-400">
                  {r.category ? `${r.category}` : null}
                  {r.sources?.length ? ` · ${r.sources.join(', ')}` : null}
                </span>
              </li>
            ))}
          </ul>
        </>
      ) : null}

      {plan.test_data_requirements?.length ? (
        <>
          <SectionHeading>Test data required</SectionHeading>
          <ul className="mt-1.5 space-y-2">
            {plan.test_data_requirements.map((d) => (
              <li key={d.id} className="text-[13px] leading-relaxed text-ink-100">
                <span className="font-mono text-[12px] text-ink-400">{d.id}</span> {d.name}
                {d.description ? <span className="text-ink-300"> — {d.description}</span> : null}
                {d.attributes?.length ? (
                  <span className="ml-2 text-[11px] text-ink-400">{d.attributes.join(' · ')}</span>
                ) : null}
              </li>
            ))}
          </ul>
        </>
      ) : null}

      {capabilities.length > 0 ? (
        <>
          <SectionHeading>Test cases by capability</SectionHeading>
          <div className="mt-2 space-y-4">
            {capabilities.map((c) => (
              <CapabilitySection key={c.name} capability={c} />
            ))}
          </div>
        </>
      ) : (
        <LegacyScenarioTable scenarios={plan.scenarios ?? []} />
      )}

      <Prose title="Execution strategy" text={plan.execution_strategy} />

      {plan.risks?.length ? (
        <>
          <SectionHeading>Risks</SectionHeading>
          <ul className="mt-1.5 space-y-2">
            {plan.risks.map((r, i) => (
              <li key={i} className="text-[13px] leading-relaxed text-ink-100">
                <span className="font-medium">{String(r.title ?? '')}</span>
                {r.detail ? <span className="text-ink-300"> — {String(r.detail)}</span> : null}
                {r.mitigation ? (
                  <div className="text-[12px] text-ink-400">Mitigation: {String(r.mitigation)}</div>
                ) : null}
              </li>
            ))}
          </ul>
        </>
      ) : null}

      {/* Gaps are what the material does not settle — deliberately separate from risks. */}
      {plan.gaps?.length ? (
        <>
          <SectionHeading>Open gaps</SectionHeading>
          <ul className="mt-1.5 space-y-2">
            {plan.gaps.map((g, i) => (
              <li key={i} className="text-[13px] leading-relaxed text-ink-100">
                <span className="font-medium">{String(g.title ?? '')}</span>
                {g.blocks_testing === true && (
                  <Badge tone="red" className="ml-2">Blocks testing</Badge>
                )}
                {g.detail ? <div className="text-ink-300">{String(g.detail)}</div> : null}
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </article>
  );
}

function CapabilitySection({ capability }: { capability: TestPlanCapability }) {
  const cases = capability.test_cases ?? [];
  return (
    <section className="rounded-lg border border-ink-700 bg-ink-950/40">
      <header className="border-b border-ink-700 px-4 py-2.5">
        <h4 className="text-[13px] font-semibold text-ink-100">
          {capability.name}
          <span className="ml-2 text-[11px] font-normal text-ink-400">
            {cases.length} case{cases.length === 1 ? '' : 's'}
          </span>
        </h4>
        {capability.description ? (
          <p className="mt-0.5 text-[12px] text-ink-400">{capability.description}</p>
        ) : null}
      </header>
      <div className="divide-y divide-ink-800">
        {cases.map((c) => (
          <TestCase key={c.scenario_key} testCase={c} />
        ))}
      </div>
    </section>
  );
}

function TestCase({ testCase: t }: { testCase: TestScenarioView }) {
  return (
    <details className="group px-4 py-2.5">
      <summary className="flex cursor-pointer list-none items-center gap-2 text-[13px] marker:hidden">
        <span className="text-ink-400 transition-transform group-open:rotate-90" aria-hidden>▸</span>
        <span className="font-mono text-[12px] text-ink-400">{t.scenario_key}</span>
        <span className="flex-1 text-ink-100">{t.title}</span>
        {t.lifecycle_phase ? (
          <span className="hidden text-[11px] text-ink-400 sm:inline">{t.lifecycle_phase}</span>
        ) : null}
        <PriorityBadge priority={t.priority} />
      </summary>

      <div className="mt-3 space-y-3 pl-6">
        {t.objective ? (
          <p className="text-[13px] leading-relaxed text-ink-300">{t.objective}</p>
        ) : null}
        <OrderedDetail label="Preconditions" items={t.preconditions} ordered={false} />
        <OrderedDetail label="Steps" items={t.steps} ordered />
        <OrderedDetail label="Expected results" items={t.expected_results} ordered={false} />
        <OrderedDetail label="Test data" items={t.test_data_requirements} ordered={false} />

        {t.automation_mapping ? (
          <div className="text-[12px] text-ink-400">
            <span className="font-semibold uppercase tracking-wide">Automation</span>{' '}
            <Badge tone="sky">{String(t.automation_mapping.type ?? '')}</Badge>{' '}
            <span className="font-mono">{String(t.automation_mapping.target ?? '')}</span>
            {t.automation_mapping.notes ? <span> — {String(t.automation_mapping.notes)}</span> : null}
          </div>
        ) : null}

        {/* Traceability stays visible but must not dominate the case. */}
        {(t.sources?.length || t.requirement_ids?.length) ? (
          <div className="text-[11px] text-ink-400">
            {t.requirement_ids?.length ? <span>Covers {t.requirement_ids.join(', ')}</span> : null}
            {t.requirement_ids?.length && t.sources?.length ? <span> · </span> : null}
            {t.sources?.length ? <span>Source: {t.sources.join(', ')}</span> : null}
          </div>
        ) : null}
      </div>
    </details>
  );
}

function ArchitectureContext({ context }: { context: Record<string, unknown> }) {
  const participants = (context.participants as Array<Record<string, string>> | undefined) ?? [];
  const phases = (context.lifecycle_phases as Array<Record<string, string>> | undefined) ?? [];
  const injection = (context.feature_injection_points as Array<Record<string, unknown>> | undefined) ?? [];

  return (
    <>
      <SectionHeading>Architecture context</SectionHeading>
      {context.summary ? (
        <p className="mt-1.5 text-[14px] leading-relaxed text-ink-100">{String(context.summary)}</p>
      ) : null}
      {participants.length > 0 && (
        <div className="mt-2 text-[13px] text-ink-300">
          <span className="text-[11px] font-semibold uppercase tracking-wide text-ink-400">Participants</span>
          <ul className="mt-1 space-y-0.5">
            {participants.map((p) => (
              <li key={p.name}>
                <span className="text-ink-100">{p.name}</span> — {p.role}
              </li>
            ))}
          </ul>
        </div>
      )}
      {phases.length > 0 && (
        <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[12px] text-ink-400">
          <span className="text-[11px] font-semibold uppercase tracking-wide">Lifecycle</span>
          {phases.map((p, i) => (
            <span key={p.name} className="flex items-center gap-1.5">
              <Badge>{p.name}</Badge>
              {i < phases.length - 1 && <span aria-hidden>→</span>}
            </span>
          ))}
        </div>
      )}
      {injection.length > 0 && (
        <div className="mt-2 text-[13px] text-ink-300">
          <span className="text-[11px] font-semibold uppercase tracking-wide text-ink-400">
            Where this feature engages
          </span>
          <ul className="mt-1 space-y-0.5">
            {injection.map((p, i) => (
              <li key={i}>
                <span className="text-ink-100">{String(p.phase ?? '')}</span> — {String(p.description ?? '')}
              </li>
            ))}
          </ul>
        </div>
      )}
    </>
  );
}

/** The pre-v2 shape: no capabilities, no per-case detail. Rendered as it always was. */
function LegacyScenarioTable({ scenarios }: { scenarios: TestScenarioView[] }) {
  return (
    <>
      <SectionHeading>Test scenarios</SectionHeading>
      <table className="mt-2 w-full border-collapse text-[13px]">
        <thead>
          <tr className="border-b border-ink-600 text-left text-[11px] uppercase text-ink-400">
            <th className="py-1.5 pr-3">ID</th>
            <th className="py-1.5 pr-3">Scenario</th>
            <th className="py-1.5 pr-3">Priority</th>
            <th className="py-1.5">Source</th>
          </tr>
        </thead>
        <tbody>
          {scenarios.map((s) => (
            <tr key={s.scenario_key} className="border-b border-ink-800">
              <td className="py-2 pr-3 font-mono text-ink-400">{s.scenario_key}</td>
              <td className="py-2 pr-3">{s.title}</td>
              <td className="py-2 pr-3"><PriorityBadge priority={s.priority} /></td>
              <td className="py-2 text-ink-400">{s.source}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

function SectionHeading({ children }: { children: React.ReactNode }) {
  return (
    <h3 className="mt-6 text-[12px] font-semibold uppercase tracking-wide text-sky-500">{children}</h3>
  );
}

function Prose({ title, text }: { title: string; text?: string | null }) {
  if (!text) return null;
  return (
    <>
      <SectionHeading>{title}</SectionHeading>
      <p className="mt-1.5 text-[14px] leading-relaxed text-ink-100">{text}</p>
    </>
  );
}

function BulletList({ label, items }: { label: string; items?: string[] }) {
  if (!items?.length) return null;
  return (
    <div>
      <div className="text-[11px] font-semibold uppercase tracking-wide text-ink-400">{label}</div>
      <ul className="mt-1 list-disc space-y-0.5 pl-4 text-[13px] text-ink-100">
        {items.map((s, i) => <li key={i}>{s}</li>)}
      </ul>
    </div>
  );
}

function OrderedDetail({ label, items, ordered }: { label: string; items?: string[]; ordered: boolean }) {
  if (!items?.length) return null;
  const List = ordered ? 'ol' : 'ul';
  return (
    <div>
      <div className="text-[11px] font-semibold uppercase tracking-wide text-ink-400">{label}</div>
      <List className={`mt-1 space-y-0.5 pl-4 text-[13px] text-ink-100 ${ordered ? 'list-decimal' : 'list-disc'}`}>
        {items.map((s, i) => <li key={i}>{s}</li>)}
      </List>
    </div>
  );
}

/** Priority uses the app-wide Badge tones rather than a bare coloured span. */
function PriorityBadge({ priority }: { priority?: string | null }) {
  if (!priority) return null;
  const tone = priority === 'High' ? 'red' : priority === 'Medium' ? 'amber' : 'emerald';
  return <Badge tone={tone}>{priority}</Badge>;
}

function downloadHtml(plan: TestPlanView) {
  const capabilities = plan.capabilities ?? [];
  const list = (label: string, items?: string[]) =>
    items?.length
      ? `<p><strong>${label}</strong></p><ul>${items.map((s) => `<li>${escapeHtml(s)}</li>`).join('')}</ul>`
      : '';

  const caseHtml = (c: TestScenarioView) => `
<h4>${escapeHtml(c.scenario_key)} — ${escapeHtml(c.title)} <em>(${escapeHtml(c.priority ?? '')})</em></h4>
${c.objective ? `<p>${escapeHtml(c.objective)}</p>` : ''}
${c.lifecycle_phase ? `<p><strong>Lifecycle phase:</strong> ${escapeHtml(c.lifecycle_phase)}</p>` : ''}
${list('Preconditions', c.preconditions)}
${list('Steps', c.steps)}
${list('Expected results', c.expected_results)}
${list('Test data', c.test_data_requirements)}
${c.sources?.length ? `<p><strong>Source:</strong> ${escapeHtml(c.sources.join(', '))}</p>` : ''}`;

  const body = capabilities.length
    ? capabilities
        .map(
          (cap) =>
            `<h2>${escapeHtml(cap.name)}</h2>${
              cap.description ? `<p>${escapeHtml(cap.description)}</p>` : ''
            }${(cap.test_cases ?? []).map(caseHtml).join('')}`,
        )
        .join('')
    : `<table border="1" cellpadding="6" cellspacing="0">
<tr><th>ID</th><th>Scenario</th><th>Priority</th><th>Source</th></tr>${(plan.scenarios ?? [])
        .map(
          (s) =>
            `<tr><td>${escapeHtml(s.scenario_key)}</td><td>${escapeHtml(s.title)}</td><td>${
              escapeHtml(s.priority ?? '')
            }</td><td>${escapeHtml(s.source ?? '')}</td></tr>`,
        )
        .join('')}</table>`;

  const html = `<!doctype html><meta charset="utf-8"><title>Test Plan</title>
<h1>Test Plan</h1>
${plan.overview ? `<h2>Overview</h2><p>${escapeHtml(plan.overview)}</p>` : ''}
${list('In scope', plan.scope?.in_scope)}
${list('Out of scope', plan.scope?.out_of_scope)}
${body}
${plan.execution_strategy ? `<h2>Execution strategy</h2><p>${escapeHtml(plan.execution_strategy)}</p>` : ''}`;

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
