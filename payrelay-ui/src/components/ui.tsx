import type { ReactNode } from 'react'
import type { PaymentStatus } from '../api'
import { statusLabel } from '../format'

const statusStyle: Record<PaymentStatus, string> = {
  RECEIVED: 'bg-run-soft text-run',
  PROCESSING: 'bg-run-soft text-run',
  RETRY_SCHEDULED: 'bg-wait-soft text-wait',
  COMPLETED: 'bg-ok-soft text-ok',
  FAILED: 'bg-bad-soft text-bad',
  DEAD_LETTERED: 'bg-dead-soft text-dead',
}

export function StatusPill({ status }: { status: PaymentStatus }) {
  const live = status === 'PROCESSING' || status === 'RECEIVED'
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium whitespace-nowrap ${statusStyle[status]}`}>
      <span className={`size-1.5 rounded-full bg-current ${live ? 'animate-pulse' : ''}`} aria-hidden />
      {statusLabel[status]}
    </span>
  )
}

export function Panel({ title, action, children, className = '' }: {
  title?: string
  action?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={`rounded-lg border border-line bg-panel ${className}`}>
      {(title || action) && (
        <header className="flex items-center justify-between gap-4 border-b border-line px-5 py-3">
          {title && <h2 className="text-sm font-semibold">{title}</h2>}
          {action}
        </header>
      )}
      {children}
    </section>
  )
}

export function PageHeader({ title, description, action }: { title: string; description?: ReactNode; action?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-1 max-w-2xl text-sm text-muted">{description}</p>}
      </div>
      {action}
    </div>
  )
}

export function Button({ children, variant = 'primary', ...props }: React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'danger'
}) {
  const styles = {
    primary: 'bg-brand text-white hover:bg-brand/90',
    secondary: 'bg-panel text-ink border border-line hover:bg-canvas',
    danger: 'bg-panel text-bad border border-bad/30 hover:bg-bad-soft',
  }[variant]
  return (
    <button
      {...props}
      className={`inline-flex items-center justify-center gap-2 rounded-md px-3.5 py-2 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${styles} ${props.className ?? ''}`}
    >
      {children}
    </button>
  )
}

export function ErrorNotice({ error }: { error: unknown }) {
  const message = error instanceof Error ? error.message : 'Something went wrong.'
  return (
    <div role="alert" className="rounded-lg border border-bad/30 bg-bad-soft px-4 py-3 text-sm text-bad">
      <p className="font-medium">Can't reach the PayRelay services.</p>
      <p className="mt-0.5 text-bad/80">{message} Check that payrelay-api (port 8080) and bank-simulator (port 8081) are running.</p>
    </div>
  )
}

export function Loading() {
  return <p className="px-5 py-8 text-sm text-muted">Loading…</p>
}
