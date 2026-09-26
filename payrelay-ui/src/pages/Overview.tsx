import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api, type Stats, type ChaosConfig, type TargetStats } from '../api'
import { duration, pct, targetLabel, time } from '../format'
import { ErrorNotice, Loading, PageHeader, Panel } from '../components/ui'
import { SendTraffic } from '../components/SendTraffic'

export function Overview() {
  const stats = useQuery({ queryKey: ['stats'], queryFn: api.stats, refetchInterval: 2000 })
  const chaos = useQuery({ queryKey: ['chaos'], queryFn: api.chaos, refetchInterval: 5000 })
  const sim = useQuery({ queryKey: ['simStats'], queryFn: api.simStats, refetchInterval: 2000 })

  return (
    <>
      <PageHeader
        title="Overview"
        description="Every payment passes KYC, a risk check and a bank transfer. When a system fails, PayRelay retries that step alone and never pays twice."
        action={<SendTraffic />}
      />
      {stats.error && <ErrorNotice error={stats.error} />}
      {stats.isLoading && <Loading />}
      {stats.data && (
        <div className="space-y-6">
          <Summary stats={stats.data} />
          <Throughput stats={stats.data} />
          <Banks stats={stats.data} chaos={chaos.data} sim={sim.data} />
        </div>
      )}
    </>
  )
}

function Summary({ stats }: { stats: Stats }) {
  const s = stats.byStatus
  const inFlight = s.RECEIVED + s.PROCESSING + s.RETRY_SCHEDULED

  if (stats.total === 0) {
    return (
      <Panel>
        <div className="px-6 py-10">
          <p className="text-lg font-medium">No payments yet.</p>
          <p className="mt-1 text-sm text-muted">
            Send test traffic to watch payments flow through, then turn up failures in the{' '}
            <Link to="/chaos" className="text-brand underline underline-offset-2">chaos lab</Link> and send more.
          </p>
        </div>
      </Panel>
    )
  }

  const metrics = [
    { label: 'Success rate', value: pct(stats.successRate), hint: 'of payments that finished' },
    { label: 'In flight', value: inFlight.toLocaleString('en-IN'), hint: `${s.RETRY_SCHEDULED} waiting to retry` },
    { label: 'Needs review', value: s.DEAD_LETTERED.toLocaleString('en-IN'), hint: 'retries exhausted', to: '/review' },
    { label: 'Time to complete', value: duration(stats.latencyP50Ms), hint: `p95 ${duration(stats.latencyP95Ms)}` },
    { label: 'Calls per payment', value: stats.avgAttemptsPerCompleted.toFixed(2), hint: '3.00 means no retries' },
  ]

  return (
    <Panel>
      <div className="px-6 pt-6 pb-5">
        <p className="text-xl leading-snug font-medium md:text-2xl">
          {s.COMPLETED.toLocaleString('en-IN')} of {stats.total.toLocaleString('en-IN')} payments completed.{' '}
          <span className="text-muted">
            {stats.recoveredAfterFailure.toLocaleString('en-IN')} of them recovered from a failure on their own
            {s.FAILED > 0 && <>, and {s.FAILED} were rejected by KYC, risk or the bank</>}.
          </span>
        </p>
      </div>
      <dl className="grid grid-cols-2 border-t border-line sm:grid-cols-3 lg:grid-cols-5">
        {metrics.map((m) => (
          <div key={m.label} className="border-line px-6 py-4 not-last:border-r max-lg:border-b">
            <dt className="text-xs text-muted">{m.label}</dt>
            <dd className="mt-1 text-xl font-semibold">
              {m.to ? <Link to={m.to} className="hover:text-brand">{m.value}</Link> : m.value}
            </dd>
            <dd className="text-xs text-muted">{m.hint}</dd>
          </div>
        ))}
      </dl>
    </Panel>
  )
}

function Throughput({ stats }: { stats: Stats }) {
  const data = stats.throughput.map((b) => ({ ...b, label: time(b.minute).slice(0, 5) }))
  return (
    <Panel title="Payments per minute, last 30 minutes">
      <div className="h-64 px-2 py-4">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ left: 0, right: 16, top: 4, bottom: 0 }}>
            <CartesianGrid stroke="var(--color-line)" vertical={false} />
            <XAxis dataKey="label" tick={{ fontSize: 11, fill: 'var(--color-muted)' }} tickLine={false} axisLine={false} interval={4} />
            <YAxis allowDecimals={false} tick={{ fontSize: 11, fill: 'var(--color-muted)' }} tickLine={false} axisLine={false} width={40} />
            <Tooltip contentStyle={{ borderRadius: 6, borderColor: 'var(--color-line)', fontSize: 12 }} />
            <Legend iconType="plainline" wrapperStyle={{ fontSize: 12 }} />
            <Line type="linear" dataKey="created" name="Received" stroke="var(--color-brand)" strokeWidth={2} dot={false} isAnimationActive={false} />
            <Line type="linear" dataKey="completed" name="Completed" stroke="var(--color-ok)" strokeWidth={2} dot={false} isAnimationActive={false} />
            <Line type="linear" dataKey="failed" name="Rejected or stuck" stroke="var(--color-bad)" strokeWidth={2} dot={false} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </Panel>
  )
}

/**
 * The exactly-once proof: what PayRelay believes it paid, next to what each bank actually executed.
 */
function Banks({ stats, chaos, sim }: { stats: Stats; chaos?: Record<string, ChaosConfig>; sim?: Record<string, TargetStats> }) {
  return (
    <Panel title="Banks and reconciliation">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] text-sm">
          <thead className="text-left text-xs text-muted">
            <tr className="border-b border-line">
              <th className="px-5 py-2.5 font-medium">Bank</th>
              <th className="px-3 py-2.5 font-medium">Current conditions</th>
              <th className="px-3 py-2.5 text-right font-medium">Completed</th>
              <th className="px-3 py-2.5 text-right font-medium">Rejected</th>
              <th className="px-3 py-2.5 text-right font-medium">Needs review</th>
              <th className="px-3 py-2.5 text-right font-medium">Transfers at bank</th>
              <th className="px-3 py-2.5 text-right font-medium">Duplicates stopped</th>
              <th className="px-5 py-2.5 font-medium">Reconciled</th>
            </tr>
          </thead>
          <tbody>
            {stats.banks.map((b) => {
              const c = chaos?.[b.bankCode]
              const t = sim?.[b.bankCode]
              const bankTransfers = t ? t.uniqueOperations - t.businessRejections : undefined
              return (
                <tr key={b.bankCode} className="border-b border-line last:border-0">
                  <td className="px-5 py-3 font-medium">
                    <Link to={`/payments?bankCode=${b.bankCode}`} className="hover:text-brand">{targetLabel(b.bankCode)}</Link>
                  </td>
                  <td className="px-3 py-3"><Conditions config={c} /></td>
                  <td className="px-3 py-3 text-right">{b.completed}</td>
                  <td className="px-3 py-3 text-right">{b.failed}</td>
                  <td className="px-3 py-3 text-right">{b.deadLettered}</td>
                  <td className="px-3 py-3 text-right">{bankTransfers ?? '—'}</td>
                  <td className="px-3 py-3 text-right">{t ? t.idempotentReplays : '—'}</td>
                  <td className="px-5 py-3"><Reconciled completed={b.completed} bankTransfers={bankTransfers} inFlight={b.inFlight} review={b.deadLettered} /></td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <p className="border-t border-line px-5 py-3 text-xs text-muted">
        "Transfers at bank" is counted by the bank simulator itself. When it equals "Completed", no payment was lost or paid twice.
        "Duplicates stopped" counts retried requests the bank answered from its idempotency store instead of paying again.
      </p>
    </Panel>
  )
}

function Conditions({ config }: { config?: ChaosConfig }) {
  if (!config) return <span className="text-muted">—</span>
  const calm = config.failureRate === 0 && config.lostResponseRate === 0 && config.latencyMs < 500
  if (calm) return <span className="text-ok">Healthy</span>
  const parts = []
  if (config.failureRate > 0) parts.push(`${Math.round(config.failureRate * 100)}% errors`)
  if (config.lostResponseRate > 0) parts.push(`${Math.round(config.lostResponseRate * 100)}% lost replies`)
  if (config.latencyMs >= 500) parts.push(`${config.latencyMs} ms delay`)
  return <span className="text-wait">{parts.join(', ')}</span>
}

function Reconciled({ completed, bankTransfers, inFlight, review }: { completed: number; bankTransfers?: number; inFlight: number; review: number }) {
  if (bankTransfers === undefined) return <span className="text-muted">—</span>
  if (bankTransfers === completed) return <span className="font-medium text-ok">Matches</span>
  const gap = bankTransfers - completed
  if (gap > 0 && (inFlight > 0 || review > 0)) {
    return <span className="text-wait">{gap} awaiting confirmation</span>
  }
  return <span className="font-medium text-bad">Off by {gap}</span>
}
