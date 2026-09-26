import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api, type PaymentStatus } from '../api'
import { money, relativeFuture, shortId, statusLabel, stepLabel, time, targetLabel } from '../format'
import { Button, ErrorNotice, Loading, PageHeader, Panel, StatusPill } from '../components/ui'

const STATUSES: PaymentStatus[] = ['RECEIVED', 'PROCESSING', 'RETRY_SCHEDULED', 'COMPLETED', 'FAILED', 'DEAD_LETTERED']

export function Payments() {
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const bankCode = params.get('bankCode') ?? ''
  const page = Number(params.get('page') ?? 0)
  const navigate = useNavigate()

  const banks = useQuery({ queryKey: ['banks'], queryFn: api.banks })
  const payments = useQuery({
    queryKey: ['payments', status, bankCode, page],
    queryFn: () => api.payments({ status, bankCode, page, size: 25 }),
    refetchInterval: 2000,
    placeholderData: keepPreviousData,
  })

  const update = (key: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    if (key !== 'page') next.delete('page')
    setParams(next)
  }

  const info = payments.data?.page

  return (
    <>
      <PageHeader title="Payments" description="Newest first. Updates live. Open a payment to see every call PayRelay made for it." />
      {payments.error && <ErrorNotice error={payments.error} />}
      <Panel
        title={info ? `${info.totalElements.toLocaleString('en-IN')} payments` : 'Payments'}
        action={
          <div className="flex flex-wrap gap-2">
            <Filter label="Status" value={status} onChange={(v) => update('status', v)}
              options={STATUSES.map((s) => ({ value: s, label: statusLabel[s] }))} />
            <Filter label="Bank" value={bankCode} onChange={(v) => update('bankCode', v)}
              options={(banks.data ?? []).map((b) => ({ value: b.code, label: b.displayName }))} />
          </div>
        }
      >
        {payments.isLoading && <Loading />}
        {payments.data && payments.data.content.length === 0 && (
          <p className="px-5 py-10 text-sm text-muted">
            No payments match these filters. {status || bankCode ? 'Clear a filter to see more.' : 'Send test traffic from the Overview page.'}
          </p>
        )}
        {payments.data && payments.data.content.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[820px] text-sm">
              <thead className="text-left text-xs text-muted">
                <tr className="border-b border-line">
                  <th className="px-5 py-2.5 font-medium">Received</th>
                  <th className="px-3 py-2.5 font-medium">Payment</th>
                  <th className="px-3 py-2.5 font-medium">Beneficiary</th>
                  <th className="px-3 py-2.5 font-medium">Bank</th>
                  <th className="px-3 py-2.5 text-right font-medium">Amount</th>
                  <th className="px-3 py-2.5 font-medium">Status</th>
                  <th className="px-5 py-2.5 font-medium">Where it is</th>
                </tr>
              </thead>
              <tbody>
                {payments.data.content.map((p) => (
                  <tr
                    key={p.id}
                    onClick={() => navigate(`/payments/${p.id}`)}
                    className="cursor-pointer border-b border-line last:border-0 hover:bg-canvas"
                  >
                    <td className="px-5 py-3 text-muted">{time(p.createdAt)}</td>
                    <td className="px-3 py-3">
                      <Link to={`/payments/${p.id}`} className="font-mono text-xs text-brand" onClick={(e) => e.stopPropagation()}>
                        {shortId(p.id)}
                      </Link>
                    </td>
                    <td className="px-3 py-3">{p.beneficiaryName}</td>
                    <td className="px-3 py-3">{targetLabel(p.bankCode)}</td>
                    <td className="px-3 py-3 text-right font-medium">{money(p.amount)}</td>
                    <td className="px-3 py-3"><StatusPill status={p.status} /></td>
                    <td className="px-5 py-3 text-muted">
                      {p.status === 'COMPLETED' && <span>Paid, ref {p.bankReference}</span>}
                      {p.status === 'RETRY_SCHEDULED' && p.currentStep && (
                        <span>{stepLabel[p.currentStep]}, retry {relativeFuture(p.nextAttemptAt)}</span>
                      )}
                      {(p.status === 'PROCESSING' || p.status === 'RECEIVED') && p.currentStep && <span>{stepLabel[p.currentStep]}</span>}
                      {(p.status === 'FAILED' || p.status === 'DEAD_LETTERED') && (
                        <span className="line-clamp-1" title={p.failureReason ?? ''}>{p.failureReason}</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {info && info.totalPages > 1 && (
          <footer className="flex items-center justify-between border-t border-line px-5 py-3 text-sm">
            <span className="text-muted">Page {info.number + 1} of {info.totalPages}</span>
            <div className="flex gap-2">
              <Button variant="secondary" disabled={page === 0} onClick={() => update('page', String(page - 1))}>Previous</Button>
              <Button variant="secondary" disabled={page + 1 >= info.totalPages} onClick={() => update('page', String(page + 1))}>Next</Button>
            </div>
          </footer>
        )}
      </Panel>
    </>
  )
}

function Filter({ label, value, onChange, options }: {
  label: string
  value: string
  onChange: (v: string) => void
  options: { value: string; label: string }[]
}) {
  return (
    <label className="flex items-center gap-2 text-xs text-muted">
      {label}
      <select value={value} onChange={(e) => onChange(e.target.value)} className="rounded-md border border-line bg-panel px-2 py-1.5 text-sm text-ink">
        <option value="">All</option>
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </label>
  )
}
