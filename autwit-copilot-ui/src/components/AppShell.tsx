import { NavLink, Outlet, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';

/**
 * The two-flavor shell. A slim deep-navy activity rail switches between the copilot's two
 * flavors — Execute (the session/evidence workspace) and Plan (Test Plan & Data Studio) —
 * each of which keeps its own chrome to the right. Route namespaces: /sessions/* vs /plan/*.
 *
 * The navy rail is the app's one piece of dark chrome: it anchors the light corporate ground
 * and makes the flavor switch read as product-level navigation, above either flavor's own
 * left rail (the planning wizard has a 4-step tracker) which stays light.
 */
export function AppShell() {
  const { pathname } = useLocation();
  // /sessions is the execution flavor; /plan is planning; /automation is the run plane.
  const flavor = pathname.startsWith('/plan')
    ? 'plan'
    : pathname.startsWith('/automation')
      ? 'automation'
      : 'execute';

  return (
    <div className="flex h-full">
      <nav className="flex w-16 shrink-0 flex-col items-center gap-1 bg-gradient-to-b from-navy-900 to-navy-950 py-3">
        {/* The wordmark is ~5:1, so the rail shows just its radial icon, on a light chip so it
            reads on the navy ground regardless of the logo's own colors. */}
        <div
          className="mb-5 flex h-10 w-10 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-white shadow-sm"
          title="AutWit — Automate. Ace. Accelerate."
        >
          <img src="/AutwitLogo.png" alt="AutWit" className="h-8 w-auto max-w-none" />
        </div>
        <FlavorLink to="/sessions" active={flavor === 'execute'} label="Execute" icon={<PlayIcon />} />
        <FlavorLink to="/plan" active={flavor === 'plan'} label="Plan" icon={<PlanIcon />} />
        <FlavorLink
          to="/automation"
          active={flavor === 'automation'}
          label="Runs"
          icon={<AutomationIcon />}
        />
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
  icon,
}: {
  to: string;
  active: boolean;
  label: string;
  icon: ReactNode;
}) {
  return (
    <NavLink
      to={to}
      title={label}
      className={`flex w-12 flex-col items-center gap-1 rounded-xl py-2 text-[10px] font-medium transition-colors ${
        active
          ? 'bg-sky-600/25 text-navy-100'
          : 'text-navy-300 hover:bg-white/5 hover:text-navy-100'
      }`}
    >
      <span className="grid size-[19px] place-items-center">{icon}</span>
      {label}
    </NavLink>
  );
}

/* lucide-style stroke icons, inline so they inherit currentColor and need no font/CDN. */
function PlayIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-[19px]">
      <polygon points="6 4 20 12 6 20 6 4" />
    </svg>
  );
}

function AutomationIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-[19px]">
      <path d="M9 17H7A5 5 0 0 1 7 7h2" />
      <path d="M15 7h2a5 5 0 1 1 0 10h-2" />
      <path d="M8 12h8" />
    </svg>
  );
}

function PlanIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-[19px]">
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
    </svg>
  );
}
