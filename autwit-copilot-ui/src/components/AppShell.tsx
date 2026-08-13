import { useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';

/**
 * The app shell: a fixed navy top bar carrying the AutWit mark + a hamburger, and a
 * slide-in drawer that switches between the three capabilities — Studio (the
 * session/evidence + skills workspace, /sessions), Planner (Test Plan & Data Studio,
 * /plan) and Forge (the native run plane, /automation).
 *
 * Replaces the old always-on left rail: the rail is now a hamburger drawer so the content
 * gets the full width, and the top bar keeps the brand anchored on every route. The three
 * icons are the slide-3 platform marks (toolbox / clipboard / hammer), white line-art shown
 * on a navy chip so they read on light ground.
 */

interface NavItem {
  to: string;
  /** Path prefix that marks this item active. */
  match: string;
  label: string;
  icon: string;
  blurb: string;
}

export const NAV_ITEMS: NavItem[] = [
  { to: '/plan', match: '/plan', label: 'Planner', icon: '/icon-planner.png', blurb: 'Test plans & data from live context' },
  { to: '/automation', match: '/automation', label: 'Forge', icon: '/icon-forge.png', blurb: 'Native, event-driven suite runs' },
  { to: '/sessions', match: '/sessions', label: 'Studio', icon: '/icon-studio.png', blurb: 'Purpose-built validation skills' },
];

export function AppShell() {
  const [open, setOpen] = useState(false);
  const { pathname } = useLocation();
  const close = () => setOpen(false);

  return (
    <div className="flex h-full flex-col">
      {/* Fixed top slice — the brand anchor, on every route. */}
      <header className="fixed inset-x-0 top-0 z-40 flex h-14 items-center gap-2 bg-gradient-to-r from-navy-900 to-navy-950 px-3 text-white shadow-md">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          aria-label={open ? 'Close menu' : 'Open menu'}
          aria-expanded={open}
          className="grid size-10 place-items-center rounded-lg text-navy-100 transition-colors hover:bg-white/10"
        >
          <HamburgerIcon open={open} />
        </button>
        {/* The circular AutWit mark + wordtext (this mark carries no text of its own). */}
        <Link to="/" onClick={close} className="flex items-center gap-2" title="AutWit — Automate. Ace. Accelerate.">
          <img src="/Autwit_Circle.png" alt="AutWit" className="size-9 object-contain" />
          <span className="text-sm font-semibold tracking-wide">AutWit</span>
        </Link>
      </header>

      {/* Drawer + scrim, below the top bar so the brand stays visible while navigating. */}
      {open && <div className="fixed inset-0 top-14 z-30 bg-black/30" onClick={close} aria-hidden="true" />}
      <aside
        className={`fixed left-0 top-14 z-40 h-[calc(100%-3.5rem)] w-64 transform border-r border-slate-200 bg-white shadow-xl transition-transform duration-200 ${
          open ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <nav className="flex flex-col gap-1 p-3">
          {NAV_ITEMS.map((item) => {
            const active = pathname.startsWith(item.match);
            return (
              <NavLink
                key={item.to}
                to={item.to}
                onClick={close}
                className={`flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors ${
                  active ? 'bg-sky-50 text-navy-900' : 'text-slate-600 hover:bg-slate-100'
                }`}
              >
                <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-gradient-to-br from-navy-900 to-navy-950">
                  <img src={item.icon} alt="" className="size-5" />
                </span>
                <span className="min-w-0">
                  <span className="block text-sm font-semibold">{item.label}</span>
                  <span className="block truncate text-xs text-slate-400">{item.blurb}</span>
                </span>
              </NavLink>
            );
          })}
        </nav>
      </aside>

      {/* Content sits below the fixed top bar. */}
      <main className="mt-14 min-h-0 flex-1 overflow-auto">
        <Outlet />
      </main>
    </div>
  );
}

/** Morphs between a hamburger and an X so the toggle reads its own state. */
function HamburgerIcon({ open }: { open: boolean }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" className="size-5">
      {open ? (
        <>
          <path d="M6 6l12 12" />
          <path d="M18 6L6 18" />
        </>
      ) : (
        <>
          <path d="M3 6h18" />
          <path d="M3 12h18" />
          <path d="M3 18h18" />
        </>
      )}
    </svg>
  );
}
