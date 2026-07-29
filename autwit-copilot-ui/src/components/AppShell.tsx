import { NavLink, Outlet, useLocation } from 'react-router-dom';

/**
 * The two-flavor shell. A slim activity rail switches between the copilot's two flavors —
 * Execute (the session/evidence workspace) and Plan (Test Plan & Data Studio) — each of
 * which keeps its own chrome to the right. Route namespaces: /sessions/* vs /plan/*.
 *
 * Deliberately thin: the flavor switch lives here, above both products, so neither flavor's
 * own left rail (the planning wizard has a 4-step tracker) has to double as a mode switcher.
 */
export function AppShell() {
  const { pathname } = useLocation();
  // /sessions is the execution flavor; everything under /plan is planning.
  const flavor = pathname.startsWith('/plan') ? 'plan' : 'execute';

  return (
    <div className="flex h-full">
      <nav className="flex w-14 shrink-0 flex-col items-center gap-1 border-r border-ink-700 bg-ink-900 py-3">
        <div className="mb-3 text-[10px] font-semibold uppercase tracking-wider text-sky-500">AW</div>
        <FlavorLink to="/sessions" active={flavor === 'execute'} label="Execute" glyph="▶" />
        <FlavorLink to="/plan" active={flavor === 'plan'} label="Plan" glyph="✎" />
      </nav>
      <div className="min-w-0 flex-1">
        <Outlet />
      </div>
    </div>
  );
}

function FlavorLink({
  to,
  active,
  label,
  glyph,
}: {
  to: string;
  active: boolean;
  label: string;
  glyph: string;
}) {
  return (
    <NavLink
      to={to}
      title={label}
      className={`flex w-11 flex-col items-center gap-0.5 rounded-lg py-2 text-[10px] transition-colors ${
        active
          ? 'bg-sky-700/15 text-sky-400'
          : 'text-ink-400 hover:bg-ink-850 hover:text-ink-100'
      }`}
    >
      <span className="text-base leading-none">{glyph}</span>
      {label}
    </NavLink>
  );
}
