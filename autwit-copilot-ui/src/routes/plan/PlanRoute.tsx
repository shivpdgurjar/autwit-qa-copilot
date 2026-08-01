import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useSession } from '../../hooks/usePlanning';
import { Ago, Mono, Muted, Spinner } from '../../components/ui';
import { InputsStage } from '../../components/plan/InputsStage';
import { FetchStage } from '../../components/plan/FetchStage';
import { ReasoningStage } from '../../components/plan/ReasoningStage';
import { PlanStage } from '../../components/plan/PlanStage';
import { DataStage } from '../../components/plan/DataStage';

export type Stage = 'inputs' | 'connect' | 'reasoning' | 'plan' | 'data';

const STEPS: { id: Stage; title: string; sub: string }[] = [
  { id: 'inputs', title: 'Add inputs', sub: 'Docs & test cases' },
  { id: 'connect', title: 'Fetch context', sub: 'Jira & Confluence' },
  { id: 'reasoning', title: 'Reasoning', sub: 'Conflicts & gaps' },
  { id: 'plan', title: 'Test plan', sub: 'Review & export' },
  { id: 'data', title: 'Test data', sub: 'Generate datasets' },
];

/** The Test Plan & Data Studio wizard — a 4-step flow over one resumable planning session. */
export default function PlanRoute() {
  const { sessionId = '' } = useParams();
  const { data: detail, isLoading, error } = useSession(sessionId);

  // Wizard position lives in the URL (reload/deep-link resilient); replace keeps one entry.
  const [params, setParams] = useSearchParams();
  const planGenId = params.get('planGen') ?? undefined;
  const dataGenId = params.get('dataGen') ?? undefined;
  const analyzeGenId = params.get('analyzeGen') ?? undefined;

  const patch = (next: { stage?: Stage; planGen?: string; dataGen?: string; analyzeGen?: string }) =>
    setParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        if (next.stage) p.set('stage', next.stage);
        if (next.planGen) p.set('planGen', next.planGen);
        if (next.dataGen) p.set('dataGen', next.dataGen);
        if (next.analyzeGen) p.set('analyzeGen', next.analyzeGen);
        return p;
      },
      { replace: true },
    );
  const setStage = (s: Stage) => patch({ stage: s });

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-ink-400">
        <Spinner /> Loading session…
      </div>
    );
  }
  if (error || !detail || detail.projects.length === 0) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2">
        <p className="text-red-300">Could not load this session.</p>
        <Link to="/plan" className="text-sky-400 hover:text-sky-300">← All sessions</Link>
      </div>
    );
  }

  const session = detail.session;
  const project = detail.projects[0]!; // v1: one project per session
  const projectId = project.project_id;

  // Resume where the tester left off: URL stage wins; otherwise derive from history.
  const kinds = new Set(detail.activity.map((a) => a.kind));
  const derived: Stage = kinds.has('data_generated')
    ? 'data'
    : kinds.has('plan_generated')
      ? 'plan'
      : kinds.has('documents_analyzed')
        ? 'reasoning'
        : kinds.has('context_fetched') || kinds.has('document_added')
          ? 'connect'
          : 'inputs';
  const rawStage = params.get('stage');
  const stage: Stage = STEPS.some((s) => s.id === rawStage) ? (rawStage as Stage) : derived;
  const stepIndex = STEPS.findIndex((s) => s.id === stage);
  const current = STEPS[stepIndex] ?? STEPS[0]!;

  return (
    <div className="flex h-full">
      <aside className="flex w-60 shrink-0 flex-col border-r border-ink-700 bg-ink-900/40 px-3 py-5">
        <Link to="/plan" className="mb-6 px-1 text-[11px] uppercase tracking-wider text-ink-400 hover:text-ink-100">
          ← Studio
        </Link>
        <ol className="space-y-1">
          {STEPS.map((s, i) => (
            <li key={s.id}>
              <button
                onClick={() => setStage(s.id)}
                className={`flex w-full items-start gap-3 rounded-lg px-2 py-2.5 text-left transition-colors ${
                  s.id === stage ? 'bg-ink-850' : 'hover:bg-ink-850/60'
                }`}
              >
                <span
                  className={`flex size-7 shrink-0 items-center justify-center rounded-full border text-[12px] ${
                    s.id === stage
                      ? 'border-sky-600 bg-sky-700 text-white'
                      : i < stepIndex
                        ? 'border-emerald-600 bg-emerald-700/20 text-emerald-400'
                        : 'border-ink-700 text-ink-400'
                  }`}
                >
                  {i < stepIndex ? '✓' : i + 1}
                </span>
                <span className="pt-0.5">
                  <span className={`block text-[13px] ${s.id === stage ? 'text-sky-400' : 'text-ink-100'}`}>
                    {s.title}
                  </span>
                  <span className="block text-[11px] text-ink-400">{s.sub}</span>
                </span>
              </button>
            </li>
          ))}
        </ol>

        {/* Session history — the accumulating record the next generation builds on. */}
        <div className="mt-6 min-h-0 flex-1 overflow-y-auto border-t border-ink-700 pt-3">
          <h2 className="mb-2 px-1 text-[10px] font-semibold uppercase tracking-wide text-ink-400">History</h2>
          <ul className="space-y-3">
            {detail.activity.length === 0 && <li className="px-1 text-[11px] text-ink-400">No activity yet.</li>}
            {[...detail.activity].reverse().map((a, i) => (
              <li key={a.id} className="relative pl-4 text-[11px] leading-snug">
                <span
                  className={`absolute top-1 left-0 size-2 rounded-full border-2 ${
                    i === 0 ? 'border-sky-600 bg-sky-600' : 'border-ink-600 bg-ink-900'
                  }`}
                />
                <span className="text-ink-300">{a.summary ?? a.kind}</span>
                <span className="mt-0.5 block text-ink-400">
                  <Ago at={a.at} />
                </span>
              </li>
            ))}
          </ul>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center gap-3 border-b border-ink-700 bg-ink-900 px-6 py-3">
          <h1 className="text-sm font-semibold">{current.title}</h1>
          {(project.feature_key || session.title) && (
            <Mono className="rounded border border-sky-700/40 bg-sky-700/10 px-2 py-0.5 text-sky-400">
              {project.feature_key ?? session.title}
              {project.feature_key && session.title ? ` · ${session.title}` : ''}
            </Mono>
          )}
          {session.chainable && (
            <Muted
              className="inline-flex items-center gap-1.5 text-[11px]"
              title="This session has generation history the next request reuses"
            >
              <span className="size-1.5 rounded-full bg-emerald-400" /> reusing session history
            </Muted>
          )}
          {session.tester_id && (
            <span className="ml-auto text-[12px] text-ink-400">
              Signed in as <span className="text-ink-200">{session.tester_id}</span>
            </span>
          )}
        </header>

        <main className="min-h-0 flex-1 overflow-y-auto p-6">
          {stage === 'inputs' && <InputsStage projectId={projectId} onNext={() => setStage('connect')} />}
          {stage === 'connect' && (
            <FetchStage
              projectId={projectId}
              onBack={() => setStage('inputs')}
              onNext={() => setStage('reasoning')}
            />
          )}
          {stage === 'reasoning' && (
            <ReasoningStage
              projectId={projectId}
              analyzeGenId={analyzeGenId}
              onAnalyzeStarted={(genId) => patch({ analyzeGen: genId, stage: 'reasoning' })}
              onBack={() => setStage('connect')}
              onGenerated={(genId) => patch({ planGen: genId, stage: 'plan' })}
            />
          )}
          {stage === 'plan' && (
            <PlanStage
              projectId={projectId}
              generationId={planGenId}
              onBack={() => setStage('reasoning')}
              onNext={() => setStage('data')}
            />
          )}
          {stage === 'data' && (
            <DataStage
              projectId={projectId}
              dataGenId={dataGenId}
              onGenerated={(genId) => patch({ dataGen: genId })}
              onBack={() => setStage('plan')}
            />
          )}
        </main>
      </div>
    </div>
  );
}
