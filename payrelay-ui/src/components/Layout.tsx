import { NavLink, Outlet } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api'

const nav = [
  { to: '/', label: 'Overview', end: true },
  { to: '/payments', label: 'Payments' },
  { to: '/review', label: 'Needs review', badge: true },
  { to: '/banks', label: 'Banks' },
  { to: '/chaos', label: 'Chaos lab' },
]

export function Layout() {
  const { data: stats } = useQuery({ queryKey: ['stats'], queryFn: api.stats, refetchInterval: 2000 })
  const reviewCount = stats?.byStatus.DEAD_LETTERED ?? 0

  return (
    <div className="min-h-screen md:grid md:grid-cols-[220px_1fr]">
      <aside className="border-b border-line bg-panel md:border-r md:border-b-0">
        <div className="md:sticky md:top-0">
        <div className="flex items-center gap-2.5 px-5 py-4 md:py-6">
          <svg viewBox="0 0 32 32" className="size-7" aria-hidden>
            <rect width="32" height="32" rx="7" className="fill-brand" />
            <path d="M9 21l7-10 7 10" stroke="white" strokeWidth="3" fill="none" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <div className="leading-tight">
            <p className="font-semibold">PayRelay</p>
            <p className="text-xs text-muted">Operations console</p>
          </div>
        </div>
        <nav className="flex gap-1 overflow-x-auto px-3 pb-3 md:flex-col md:overflow-visible" aria-label="Main">
          {nav.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                `flex items-center justify-between gap-3 whitespace-nowrap rounded-md px-3 py-2 text-sm ${
                  isActive ? 'bg-brand-soft font-medium text-brand' : 'text-muted hover:bg-canvas hover:text-ink'
                }`
              }
            >
              {item.label}
              {item.badge && reviewCount > 0 && (
                <span className="rounded-full bg-dead px-2 py-px text-xs font-medium text-white">{reviewCount}</span>
              )}
            </NavLink>
          ))}
        </nav>
        </div>
      </aside>
      <main className="mx-auto w-full max-w-6xl px-4 py-6 md:px-8 md:py-10">
        <Outlet />
      </main>
    </div>
  )
}
