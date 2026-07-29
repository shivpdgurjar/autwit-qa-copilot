import { useMemo, useState } from 'react';
import {
  useDatasets,
  useGenerateTestData,
  useGeneration,
  useLatestTestPlan,
} from '../../hooks/usePlanning';
import type { GenerateDataRequest } from '../../api/client';
import { Card } from '../../components/ui';

const EDGE_CASES = ['boundary', 'null', 'negative', 'malformed'];

/** Step 4 — from scenarios to sample rows. */
export function DataStage({
  projectId,
  dataGenId,
  onGenerated,
  onBack,
}: {
  projectId: string;
  dataGenId?: string;
  onGenerated: (generationId: string) => void;
  onBack: () => void;
}) {
  const { data: plan } = useLatestTestPlan(projectId);
  const generate = useGenerateTestData(projectId);
  const gen = useGeneration(projectId, dataGenId);
  const { data: datasets } = useDatasets(
    projectId,
    gen.data?.status === 'succeeded' ? dataGenId : undefined,
  );

  const scenarioKeys = useMemo(() => plan?.scenarios.map((s) => s.scenario_key) ?? [], [plan]);
  const [checked, setChecked] = useState<Set<string>>(() => new Set());
  const [edges, setEdges] = useState<Set<string>>(() => new Set(['boundary', 'null']));
  const [rows, setRows] = useState(8);
  const [example, setExample] = useState('');
  const [activeTab, setActiveTab] = useState<string>();

  // Default-select all scenarios once the plan loads.
  const selected = checked.size === 0 && scenarioKeys.length > 0 ? new Set(scenarioKeys) : checked;

  function toggle(setter: React.Dispatch<React.SetStateAction<Set<string>>>, key: string, base: Set<string>) {
    const next = new Set(base);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    setter(next);
  }

  function run() {
    const scenarios = (plan?.scenarios ?? [])
      .filter((s) => selected.has(s.scenario_key))
      .map((s) => ({ id: s.scenario_key, title: s.title }));
    let exampleRecord: Record<string, unknown> | null = null;
    if (example.trim()) {
      try {
        exampleRecord = JSON.parse(example);
      } catch {
        exampleRecord = null;
      }
    }
    generate.mutate(
      {
        scenarios,
        edge_cases: [...edges] as GenerateDataRequest['edge_cases'],
        rows_per_scenario: rows,
        example_record: exampleRecord,
      },
      { onSuccess: (g) => { onGenerated(g.generation_id); setActiveTab(scenarios[0]?.id); } },
    );
  }

  const generating = dataGenId && gen.data && gen.data.status !== 'succeeded' && gen.data.status !== 'failed';
  const shown = datasets?.find((d) => d.scenario_key === activeTab) ?? datasets?.[0];

  return (
    <div className="mx-auto max-w-4xl">
      <h2 className="text-lg font-semibold">Generate test data</h2>
      <p className="mt-1 mb-5 text-[13px] text-ink-400">
        Pick which scenarios need data. Add a sample record to match your shape, or let us infer one.
      </p>

      <div className="grid grid-cols-2 gap-5">
        <Card>
          <h3 className="text-[13px] font-semibold">Scenarios needing data</h3>
          <p className="mb-3 text-[11px] text-ink-400">Carried over from the test plan</p>
          {!plan && <p className="text-[12px] text-ink-400">Generate a test plan first (Step 3).</p>}
          <div className="space-y-1.5">
            {plan?.scenarios.map((s) => (
              <label
                key={s.scenario_key}
                className="flex items-center gap-2.5 rounded-lg border border-ink-700 bg-ink-850 px-3 py-2"
              >
                <input
                  type="checkbox"
                  checked={selected.has(s.scenario_key)}
                  onChange={() => toggle(setChecked, s.scenario_key, selected)}
                  className="accent-sky-600"
                />
                <span className="font-mono text-[11px] text-sky-400">{s.scenario_key}</span>
                <span className="truncate text-[12.5px]">{s.title}</span>
              </label>
            ))}
          </div>
        </Card>

        <Card>
          <h3 className="text-[13px] font-semibold">
            Example record <span className="font-normal text-ink-400">(optional)</span>
          </h3>
          <p className="mb-2 text-[11px] text-ink-400">Paste a sample so generated rows match your shape</p>
          <textarea
            value={example}
            onChange={(e) => setExample(e.target.value)}
            rows={5}
            placeholder={'{\n  "transaction_id": "txn_8841",\n  "amount": 4899\n}'}
            className="w-full rounded border border-ink-700 bg-ink-950 px-2 py-1.5 font-mono text-[12px] outline-none focus:border-sky-700"
          />
          <div className="mt-3">
            <label className="mb-1 block text-[11px] text-ink-400">Rows per scenario</label>
            <input
              type="number"
              value={rows}
              min={1}
              onChange={(e) => setRows(Number(e.target.value) || 1)}
              className="w-24 rounded border border-ink-700 bg-ink-950 px-2 py-1 text-[12px] outline-none focus:border-sky-700"
            />
          </div>
          <div className="mt-3">
            <label className="mb-1.5 block text-[11px] text-ink-400">Include edge cases</label>
            <div className="flex flex-wrap gap-2">
              {EDGE_CASES.map((e) => (
                <button
                  key={e}
                  onClick={() => toggle(setEdges, e, edges)}
                  className={`rounded-full border px-3 py-1 text-[11.5px] ${
                    edges.has(e)
                      ? 'border-sky-600 bg-sky-700/15 text-sky-400'
                      : 'border-ink-700 text-ink-400 hover:border-ink-600'
                  }`}
                >
                  {e}
                </button>
              ))}
            </div>
          </div>
        </Card>
      </div>

      <div className="mt-6 flex items-center gap-2">
        <button onClick={onBack} className="rounded border border-ink-700 px-3 py-2 text-[13px] hover:border-ink-600">
          ← Back
        </button>
        <button
          onClick={run}
          disabled={!plan || selected.size === 0 || generate.isPending || !!generating}
          className="ml-auto rounded bg-sky-700 px-4 py-2 text-[13px] font-medium text-white hover:bg-sky-600 disabled:opacity-40"
        >
          {generate.isPending || generating ? 'Generating…' : datasets ? 'Regenerate test data' : 'Generate test data'}
        </button>
      </div>

      {datasets && datasets.length > 0 && shown && (
        <div className="mt-6 rounded-xl border border-ink-700 bg-ink-900 p-4">
          <div className="mb-3 flex gap-1 border-b border-ink-700">
            {datasets.map((d) => (
              <button
                key={d.scenario_key}
                onClick={() => setActiveTab(d.scenario_key)}
                className={`border-b-2 px-3 py-2 font-mono text-[11.5px] ${
                  (activeTab ?? shown.scenario_key) === d.scenario_key
                    ? 'border-sky-500 text-sky-400'
                    : 'border-transparent text-ink-400 hover:text-ink-100'
                }`}
              >
                {d.scenario_key}
              </button>
            ))}
          </div>
          <div className="overflow-x-auto">
            <table className="w-full border-collapse font-mono text-[12px]">
              <thead>
                <tr className="text-left text-[10.5px] uppercase text-ink-400">
                  {shown.columns.map((c) => (
                    <th key={c} className="border-b border-ink-700 px-2.5 py-2">{c}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {shown.rows.map((r, i) => (
                  <tr key={i}>
                    {shown.columns.map((c) => (
                      <td key={c} className="border-b border-ink-800 px-2.5 py-1.5 text-ink-300">
                        {formatCell((r as Record<string, unknown>)[c])}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-3 flex justify-end gap-2">
            <button
              onClick={() => downloadCsv(shown.columns, shown.rows as Record<string, unknown>[], shown.scenario_key)}
              className="rounded border border-ink-700 px-2.5 py-1 text-[11px] hover:border-ink-600"
            >
              Download CSV
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function formatCell(v: unknown) {
  return v === null || v === undefined ? '—' : String(v);
}

function downloadCsv(columns: string[], rows: Record<string, unknown>[], name: string) {
  const esc = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`;
  const csv = [columns.join(','), ...rows.map((r) => columns.map((c) => esc(r[c])).join(','))].join('\n');
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = `test-data-${name}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}
