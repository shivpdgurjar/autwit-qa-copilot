import { useQuery } from '@tanstack/react-query';
import { api, unwrap, type Comparison } from '../../api/client';
import { Mono, Muted, Spinner } from '../ui';
import { FindingsFeed } from '../findings/FindingsFeed';
import { VerdictBadge } from '../findings/SeverityBadge';

/**
 * A comparison's result: the per-part counts, the ignore rules that were applied, and
 * the findings.
 *
 * <p>This component exists because of BUILD_BRIEF §7, which is blunter than the rest of
 * the brief: "Ignore rules are surfaced in the UI. If updated_at diffs vanish without
 * explanation, nobody trusts the report and the tool dies. This is a product
 * requirement, not a nicety." Without this, a tester sees "5 of 9 parts changed" and has
 * no way to know that updated_at was excluded from that judgement.
 */
export function DiffViewer({
  comparisonId,
  onClose,
}: {
  comparisonId: string;
  onClose: () => void;
}) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['comparison', comparisonId],
    queryFn: async ({ signal }) =>
      unwrap(
        await api.GET('/comparisons/{comparisonId}', {
          params: { path: { comparisonId } },
          signal,
        }),
      ) as Comparison & { part_results?: PartResult[]; findings?: never[] },
    // A finished comparison is immutable; a pending one has nothing to show yet.
    staleTime: Infinity,
  });

  return (
    <aside className="flex h-full w-[42rem] shrink-0 flex-col border-l border-ink-700 bg-ink-900">
      <header className="flex items-center gap-2 border-b border-ink-700 px-3 py-2">
        <span className="text-sm font-medium">Comparison</span>
        {data?.compare_type && (
          <Mono className="rounded bg-ink-800 px-1.5 py-0.5 text-ink-300">{data.compare_type}</Mono>
        )}
        {data?.verdict && <VerdictBadge verdict={data.verdict} />}
        <button
          onClick={onClose}
          className="ml-auto rounded px-2 py-0.5 text-ink-400 hover:bg-ink-800 hover:text-ink-100"
          aria-label="Close"
        >
          ✕
        </button>
      </header>

      {isLoading && (
        <p className="flex items-center gap-2 p-4 text-sm text-ink-400">
          <Spinner /> Loading comparison…
        </p>
      )}
      {error && <p className="p-4 text-sm text-red-300">Could not load this comparison.</p>}

      {data && (
        <div className="min-h-0 flex-1 overflow-y-auto">
          {data.summary && <p className="border-b border-ink-800 px-3 py-2 text-[12px] text-ink-300">{data.summary}</p>}

          <PartResults parts={data.part_results ?? []} />

          <section>
            <h3 className="border-y border-ink-800 bg-ink-950/40 px-3 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-ink-400">
              Findings
            </h3>
            <FindingsFeed findings={data.findings ?? []} />
          </section>
        </div>
      )}
    </aside>
  );
}

type PartResult = {
  part_key?: string;
  rows_added?: number;
  rows_removed?: number;
  rows_modified?: number;
  rows_unchanged?: number;
  ignored_columns?: string[];
  inconclusive?: boolean;
  reason?: string;
};

function PartResults({ parts }: { parts: PartResult[] }) {
  if (parts.length === 0) {
    return <p className="px-3 py-6 text-center text-sm text-ink-400 italic">No parts compared.</p>;
  }

  const ignoredTotal = parts.reduce((n, p) => n + (p.ignored_columns?.length ?? 0), 0);

  return (
    <section>
      <h3 className="flex items-center gap-2 border-b border-ink-800 bg-ink-950/40 px-3 py-1.5">
        <span className="text-[11px] font-semibold uppercase tracking-wide text-ink-400">Parts</span>
        {ignoredTotal > 0 && (
          <Muted className="text-[11px]">
            {ignoredTotal} ignored column{ignoredTotal === 1 ? '' : 's'} applied
          </Muted>
        )}
      </h3>

      <table className="w-full text-[11px]">
        <thead>
          <tr className="text-ink-400">
            <th className="px-3 py-1.5 text-left font-medium">part_key</th>
            <th className="px-1.5 py-1.5 text-right font-medium">+</th>
            <th className="px-1.5 py-1.5 text-right font-medium">−</th>
            <th className="px-1.5 py-1.5 text-right font-medium">~</th>
            <th className="px-1.5 py-1.5 text-right font-medium">=</th>
            <th className="px-3 py-1.5 text-left font-medium">ignored</th>
          </tr>
        </thead>
        <tbody>
          {parts.map((part) => (
            <tr
              key={part.part_key}
              className={`border-t border-ink-800 ${part.inconclusive ? 'bg-amber-950/20' : ''}`}
            >
              <td className="px-3 py-1.5">
                <Mono className={part.inconclusive ? 'text-amber-200' : 'text-ink-200'}>
                  {part.part_key}
                </Mono>
                {part.inconclusive && part.reason && (
                  // Never let an unreadable part read as a clean one.
                  <span className="mt-0.5 block text-[10px] text-amber-300/80">
                    inconclusive — {part.reason}
                  </span>
                )}
              </td>
              <Count value={part.rows_added} inconclusive={part.inconclusive} tone="text-emerald-400" />
              <Count value={part.rows_removed} inconclusive={part.inconclusive} tone="text-red-400" />
              <Count value={part.rows_modified} inconclusive={part.inconclusive} tone="text-amber-400" />
              <Count value={part.rows_unchanged} inconclusive={part.inconclusive} tone="text-ink-400" />
              <td className="px-3 py-1.5">
                {/*
                  Always rendered, even as "none". An empty cell would read as "nothing
                  was hidden from you", which is the one thing this column exists to say
                  out loud.
                */}
                {part.ignored_columns && part.ignored_columns.length > 0 ? (
                  <Mono className="text-amber-300/80">{part.ignored_columns.join(', ')}</Mono>
                ) : (
                  <Muted className="text-[10px]">none</Muted>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/**
 * An inconclusive part renders — rather than 0.
 *
 * The API zeroes the counts when it could not compare a part, so printing them would
 * say "no changes" about a part nobody could read. Those two things must never look
 * alike; that is the whole reason `inconclusive` is on the wire.
 */
function Count({
  value,
  inconclusive,
  tone,
}: {
  value?: number;
  inconclusive?: boolean;
  tone: string;
}) {
  if (inconclusive) {
    return <td className="px-1.5 py-1.5 text-right text-ink-500">—</td>;
  }
  const n = value ?? 0;
  return (
    <td className={`px-1.5 py-1.5 text-right tabular-nums ${n > 0 ? tone : 'text-ink-600'}`}>{n}</td>
  );
}
