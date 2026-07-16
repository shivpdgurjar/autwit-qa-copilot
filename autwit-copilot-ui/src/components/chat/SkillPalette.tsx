import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, unwrap, type Skill } from '../../api/client';
import { useInvokeSkill } from '../../hooks/useSubmitRun';
import { Mono, Muted, Spinner } from '../ui';
import { missingRequired, pruneEmpty, SchemaForm, type JsonSchema } from './SchemaForm';

/**
 * The ⌘K palette.
 *
 * Skills come from GET /skills, a cached projection of the orchestrator's registry
 * (SKILL_CONTRACT §2). Nothing here is hard-coded: the list, the forms, and the
 * mutating gate are all read from the catalog, so a skill added in the orchestrator's
 * repo appears here after the next 60s sync with no UI change.
 */
export function SkillPalette({
  sessionId,
  open,
  onClose,
}: {
  sessionId: string;
  open: boolean;
  onClose: () => void;
}) {
  const [filter, setFilter] = useState('');
  const [selected, setSelected] = useState<Skill | null>(null);
  const [input, setInput] = useState<Record<string, unknown>>({});
  const [confirmed, setConfirmed] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);

  const invoke = useInvokeSkill(sessionId);

  const { data, isLoading } = useQuery({
    queryKey: ['skills'],
    queryFn: async ({ signal }) => unwrap(await api.GET('/skills', { signal })),
    // The catalog changes on the orchestrator's schedule, not ours.
    staleTime: 60_000,
    enabled: open,
  });

  // Reset per opening: a palette that remembers last time's half-filled form is a
  // palette that invokes something the tester did not mean to.
  useEffect(() => {
    if (open) {
      setFilter('');
      setSelected(null);
      setInput({});
      setConfirmed(false);
      invoke.reset();
      setTimeout(() => searchRef.current?.focus(), 0);
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        selected ? setSelected(null) : onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, selected, onClose]);

  if (!open) return null;

  const skills = (data?.skills ?? []).filter((s) =>
    `${s.skill_name} ${s.title ?? ''} ${s.description ?? ''}`
      .toLowerCase()
      .includes(filter.toLowerCase()),
  );

  const schema = (selected?.input_schema ?? {}) as JsonSchema;
  const missing = selected ? missingRequired(schema, input) : [];
  const needsConfirm = selected?.side_effects === 'mutating';
  const blocked = missing.length > 0 || (needsConfirm && !confirmed);

  const submit = () => {
    if (!selected || blocked) return;
    invoke.mutate(
      { skillName: selected.skill_name, input: pruneEmpty(input), confirm: confirmed },
      { onSuccess: onClose },
    );
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center bg-black/60 pt-[12vh]"
      onClick={onClose}
    >
      <div
        className="max-h-[70vh] w-[36rem] overflow-hidden rounded-xl border border-ink-700 bg-ink-900 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {!selected ? (
          <>
            <input
              ref={searchRef}
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder="Run a skill…"
              className="w-full border-b border-ink-700 bg-transparent px-3.5 py-3 text-sm outline-none placeholder:text-ink-400"
            />

            {isLoading && (
              <p className="flex items-center gap-2 p-4 text-sm text-ink-400">
                <Spinner /> Loading catalog…
              </p>
            )}

            {data && skills.length === 0 && (
              <p className="p-4 text-sm text-ink-400 italic">No skill matches "{filter}".</p>
            )}

            <ul className="max-h-[50vh] overflow-y-auto">
              {skills.map((skill) => (
                <li key={skill.skill_name}>
                  <button
                    onClick={() => setSelected(skill)}
                    disabled={skill.enabled === false}
                    className="w-full px-3.5 py-2.5 text-left hover:bg-ink-850 disabled:opacity-40"
                  >
                    <div className="flex items-center gap-2">
                      <Mono className="text-ink-100">{skill.skill_name}</Mono>
                      <Muted className="text-[11px]">{skill.version}</Muted>
                      {skill.side_effects === 'mutating' && (
                        <span className="rounded bg-red-950 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-red-300 ring-1 ring-inset ring-red-900">
                          mutating
                        </span>
                      )}
                      {skill.enabled === false && (
                        <Muted className="text-[10px] uppercase">disabled</Muted>
                      )}
                    </div>
                    {skill.description && (
                      <p className="mt-0.5 text-[12px] text-ink-400">{skill.description}</p>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          </>
        ) : (
          <div className="flex max-h-[70vh] flex-col">
            <header className="flex items-center gap-2 border-b border-ink-700 px-3.5 py-2.5">
              <button onClick={() => setSelected(null)} className="text-ink-400 hover:text-ink-100">
                ←
              </button>
              <Mono className="text-ink-100">{selected.skill_name}</Mono>
              <Muted className="text-[11px]">{selected.version}</Muted>
              <button
                onClick={onClose}
                className="ml-auto text-ink-400 hover:text-ink-100"
                aria-label="Close"
              >
                ✕
              </button>
            </header>

            <div className="min-h-0 flex-1 overflow-y-auto p-3.5">
              {/* Generated entirely from the skill's input_schema. */}
              <SchemaForm schema={schema} value={input} onChange={setInput} />

              {needsConfirm && (
                <label className="mt-3.5 flex items-start gap-2 rounded border border-red-900/60 bg-red-950/20 p-2.5">
                  <input
                    type="checkbox"
                    checked={confirmed}
                    onChange={(e) => setConfirmed(e.target.checked)}
                    className="mt-0.5 size-4 accent-red-600"
                  />
                  <span className="text-[12px] text-red-200/90">
                    This skill has <Mono>side_effects: mutating</Mono> — it changes real state in{' '}
                    the environment. It will never be retried automatically, so if it times out
                    you will have to verify the outcome yourself.
                  </span>
                </label>
              )}

              {invoke.error != null && (
                <p className="mt-3 rounded border border-red-900/60 bg-red-950/20 p-2 text-[12px] text-red-300">
                  {(invoke.error as { detail?: string }).detail ?? 'Could not invoke this skill.'}
                </p>
              )}
            </div>

            <footer className="flex items-center gap-2 border-t border-ink-700 px-3.5 py-2.5">
              {missing.length > 0 && (
                <Muted className="text-[11px]">
                  required: <Mono>{missing.join(', ')}</Mono>
                </Muted>
              )}
              <button
                onClick={submit}
                disabled={blocked || invoke.isPending}
                className="ml-auto rounded bg-sky-700 px-3 py-1.5 text-[12px] font-medium text-white hover:bg-sky-600 disabled:cursor-not-allowed disabled:opacity-40"
              >
                {invoke.isPending ? 'Submitting…' : 'Run skill'}
              </button>
            </footer>
          </div>
        )}
      </div>
    </div>
  );
}
