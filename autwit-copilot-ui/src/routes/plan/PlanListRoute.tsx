import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import type { PlanningProject } from '../../api/client';
import { useCreateProject, useProjects } from '../../hooks/usePlanning';
import { Ago, Card, EmptyState, Mono, Muted, Spinner } from '../../components/ui';

/** Project list for the Planning Copilot — the entry point to the Test Plan & Data Studio. */
export default function PlanListRoute() {
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const { data, isLoading, error } = useProjects();

  return (
    <div className="mx-auto max-w-3xl p-6">
      <div className="mb-1 flex items-center">
        <h1 className="text-lg font-semibold">Test Plan &amp; Data Studio</h1>
        <button
          onClick={() => setCreating(true)}
          className="ml-auto rounded bg-sky-700 px-3 py-1.5 text-[12px] font-medium text-white hover:bg-sky-600"
        >
          New project
        </button>
      </div>
      <p className="mb-4 text-[12px] text-ink-400">
        Turn requirement docs and Jira/Confluence context into a test plan and test data.
      </p>

      {creating && (
        <NewProjectForm
          onCancel={() => setCreating(false)}
          onCreated={(p) => navigate(`/plan/${p.project_id}`)}
        />
      )}

      {isLoading && (
        <p className="flex items-center gap-2 text-sm text-ink-400">
          <Spinner /> Loading…
        </p>
      )}
      {error && <p className="text-sm text-red-300">Could not reach the API. Is it running on :8080?</p>}
      {data && data.length === 0 && <EmptyState>No planning projects yet.</EmptyState>}

      <ul className="space-y-2">
        {data?.map((p: PlanningProject) => (
          <li key={p.project_id}>
            <Link to={`/plan/${p.project_id}`} className="block">
              <Card className="hover:border-ink-600 hover:bg-ink-850">
                <div className="flex items-baseline gap-2">
                  <span className="text-sm font-medium">{p.title ?? p.feature_key ?? 'Planning project'}</span>
                  {p.feature_key && (
                    <Mono className="rounded border border-ink-700 bg-ink-850 px-1.5 py-0.5 text-sky-400">
                      {p.feature_key}
                    </Mono>
                  )}
                  <span className="ml-auto text-[11px]">
                    <Ago at={p.created_at} />
                  </span>
                </div>
                {p.feature_description && (
                  <p className="mt-1 line-clamp-2 text-[12px] text-ink-400">{p.feature_description}</p>
                )}
                <div className="mt-1 flex items-center gap-2 text-[11px]">
                  {p.env && <Muted>{p.env}</Muted>}
                  {p.created_by && <Muted>{p.created_by}</Muted>}
                </div>
              </Card>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

function NewProjectForm({
  onCancel,
  onCreated,
}: {
  onCancel: () => void;
  onCreated: (p: PlanningProject) => void;
}) {
  const [featureKey, setFeatureKey] = useState('');
  const [description, setDescription] = useState('');
  const [title, setTitle] = useState('');
  const create = useCreateProject();

  const field =
    'w-full rounded border border-ink-700 bg-ink-950 px-2 py-1 text-[12px] outline-none focus:border-sky-700';

  return (
    <Card className="mb-4">
      <div className="grid grid-cols-2 gap-2">
        <label className="block">
          <span className="mb-1 block text-[11px] text-ink-400">Jira epic / feature key</span>
          <input className={field} value={featureKey} placeholder="PAY-2481"
            onChange={(e) => setFeatureKey(e.target.value)} />
        </label>
        <label className="block">
          <span className="mb-1 block text-[11px] text-ink-400">title</span>
          <input className={field} value={title} placeholder="Payment retry plan"
            onChange={(e) => setTitle(e.target.value)} />
        </label>
        <label className="col-span-2 block">
          <span className="mb-1 block text-[11px] text-ink-400">What are we testing?</span>
          <textarea className={`${field} font-sans`} rows={2} value={description}
            placeholder="Payment retry logic — automatic retries with backoff…"
            onChange={(e) => setDescription(e.target.value)} />
        </label>
      </div>

      {create.error != null && (
        <p className="mt-2 text-[11px] text-red-300">
          {(create.error as { detail?: string }).detail ?? 'Could not create the project.'}
        </p>
      )}

      <div className="mt-2.5 flex gap-2">
        <button onClick={onCancel} className="rounded border border-ink-700 px-2 py-1 text-[11px]">
          Cancel
        </button>
        <button
          onClick={() =>
            create.mutate(
              {
                feature_key: featureKey || undefined,
                feature_description: description || undefined,
                title: title || undefined,
              },
              { onSuccess: onCreated },
            )
          }
          disabled={create.isPending}
          className="ml-auto rounded bg-sky-700 px-3 py-1 text-[12px] font-medium text-white hover:bg-sky-600 disabled:opacity-40"
        >
          {create.isPending ? 'Creating…' : 'Create project'}
        </button>
      </div>
    </Card>
  );
}
