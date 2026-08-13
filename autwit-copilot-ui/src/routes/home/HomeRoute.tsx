import { Link } from 'react-router-dom';

/**
 * The landing page (route "/"). Replaces the old redirect straight to /sessions with a
 * platform overview: three central capability cards — Planner → Forge → Studio, mirroring
 * the "plan → execute → validate" flow — each linking into its workspace. Copy and icons
 * are the slide-3 platform marks.
 */

interface Capability {
  index: number;
  to: string;
  label: string;
  icon: string;
  desc: string;
}

const CAPABILITIES: Capability[] = [
  {
    index: 1,
    to: '/plan',
    label: 'Planner',
    icon: '/icon-planner.png',
    desc: 'Reads design docs, high-level test cases, and pulls live context from Jira & Confluence to generate the test plan and matching test data.',
  },
  {
    index: 2,
    to: '/automation',
    label: 'Forge',
    icon: '/icon-forge.png',
    desc: 'Triggers the Maven build and runs the suite natively — event-driven and truly multi-threaded, with no explicit waits. Full regressions in minutes, not days.',
  },
  {
    index: 3,
    to: '/sessions',
    label: 'Studio',
    icon: '/icon-studio.png',
    desc: 'A growing library of purpose-built skills — DB comparison, order fulfillment, financial reconciliation, UI journey exploration, and failure forensics.',
  },
];

export default function HomeRoute() {
  return (
    <div className="mx-auto max-w-5xl px-6 py-16">
      <div className="text-center">
        <p className="text-xs font-semibold uppercase tracking-[0.2em] text-sky-600">The Platform</p>
        <h1 className="mt-2 text-3xl font-semibold text-navy-900">One platform, three connected capabilities</h1>
        <p className="mx-auto mt-3 max-w-2xl text-slate-500">
          Plan the test. Execute it natively at speed. Validate it against real business outcomes.
        </p>
      </div>

      <div className="mt-12 grid gap-6 sm:grid-cols-3">
        {CAPABILITIES.map((c) => (
          <Link
            key={c.to}
            to={c.to}
            className="group flex flex-col items-center rounded-2xl border border-slate-200 bg-white p-8 text-center shadow-sm transition-all hover:-translate-y-1 hover:border-sky-300 hover:shadow-lg"
          >
            <span className="grid size-20 place-items-center rounded-2xl bg-gradient-to-br from-navy-900 to-navy-950 shadow-inner">
              <img src={c.icon} alt="" className="size-11" />
            </span>
            <span className="mt-3 text-xs font-semibold text-slate-300">{c.index}</span>
            <h2 className="mt-1 text-lg font-semibold text-navy-900 group-hover:text-sky-700">Autwit {c.label}</h2>
            <p className="mt-2 text-sm leading-relaxed text-slate-500">{c.desc}</p>
          </Link>
        ))}
      </div>
    </div>
  );
}
