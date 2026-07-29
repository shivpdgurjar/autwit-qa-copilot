import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useProject } from '../../hooks/usePlanning';
import { Mono, Spinner } from '../../components/ui';
import { InputsStage } from '../../components/plan/InputsStage';
import { FetchStage } from '../../components/plan/FetchStage';
import { PlanStage } from '../../components/plan/PlanStage';
import { DataStage } from '../../components/plan/DataStage';

export type Stage = 'inputs' | 'connect' | 'plan' | 'data';

const STEPS: { id: Stage; title: string; sub: string }[] = [
  { id: 'inputs', title: 'Add inputs', sub: 'Docs & test cases' },
  { id: 'connect', title: 'Fetch context', sub: 'Jira & Confluence' },
  { id: 'plan', title: 'Test plan', sub: 'Review & export' },
  { id: 'data', title: 'Test data', sub: 'Generate datasets' },
];

/** The Test Plan & Data Studio wizard — a 4-step flow over one planning project. */
export default function PlanRoute() {
  const { projectId = '' } = useParams();
  const { data: project, isLoading, error } = useProject(projectId);
  const [stage, setStage] = useState<Stage>('inputs');
  // The generation ids the later stages poll. Set when a generate action fires.
  const [planGenId, setPlanGenId] = useState<string>();
  const [dataGenId, setDataGenId] = useState<string>();

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-ink-400">
        <Spinner /> Loading project…
      </div>
    );
  }
  if (error || !project) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2">
        <p className="text-red-300">Could not load this project.</p>
        <Link to="/plan" className="text-sky-400 hover:text-sky-300">← All projects</Link>
      </div>
    );
  }

  const stepIndex = STEPS.findIndex((s) => s.id === stage);
  const current = STEPS[stepIndex] ?? STEPS[0]!;

  return (
    <div className="flex h-full">
      {/* 4-step tracker — the planning flavor's own rail. */}
      <aside className="flex w-56 shrink-0 flex-col border-r border-ink-700 bg-ink-900/40 px-3 py-5">
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
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center gap-3 border-b border-ink-700 bg-ink-900 px-6 py-3">
          <h1 className="text-sm font-semibold">{current.title}</h1>
          {project.feature_key && (
            <Mono className="rounded border border-sky-700/40 bg-sky-700/10 px-2 py-0.5 text-sky-400">
              {project.feature_key}
              {project.title ? ` · ${project.title}` : ''}
            </Mono>
          )}
          {project.created_by && (
            <span className="ml-auto text-[12px] text-ink-400">
              Signed in as <span className="text-ink-200">{project.created_by}</span>
            </span>
          )}
        </header>

        <main className="min-h-0 flex-1 overflow-y-auto p-6">
          {stage === 'inputs' && <InputsStage projectId={projectId} onNext={() => setStage('connect')} />}
          {stage === 'connect' && (
            <FetchStage
              projectId={projectId}
              onBack={() => setStage('inputs')}
              onGenerated={(genId) => {
                setPlanGenId(genId);
                setStage('plan');
              }}
            />
          )}
          {stage === 'plan' && (
            <PlanStage
              projectId={projectId}
              generationId={planGenId}
              onBack={() => setStage('connect')}
              onNext={() => setStage('data')}
            />
          )}
          {stage === 'data' && (
            <DataStage
              projectId={projectId}
              dataGenId={dataGenId}
              onGenerated={setDataGenId}
              onBack={() => setStage('plan')}
            />
          )}
        </main>
      </div>
    </div>
  );
}
