import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { api, STEPS, type Attempt, type PaymentDetail, type PaymentStep } from '../api'
import { dateTime, duration, money, outcomeLabel, relativeFuture, stepLabel, targetLabel, time } from '../format'
import { Button, ErrorNotice, Loading, Panel, StatusPill } from '../components/ui'

export function PaymentDetailPage() {
  const { id = '' } = useParams()
  const qc = useQueryClient()
  const payment = useQuery({
    queryKey: ['payment', id],
    queryFn: () => api.payment(id),
    refetchInterval: (q) => {
      const s = q.state.data?.status
      return s === 'COMPLETED' || s === 'FAILED' ? false : 1500
    },
  })
  const replay = useMutation({
    mutationFn: () => api.replay(id),
    onSuccess: (data) => {
      qc.setQueryData(['payment', id], data)
      qc.invalidateQueries({ queryKey: ['stats'] })
    },
  })

  if (payment.isLoading) return <Loading />
  if (payment.error) return <ErrorNotice error={payment.error} />
  const p = payment.data!

  return (
    <>
      <Link to="/payments" className="text-sm text-muted hover:text-ink">Back to payments</Link>

      <div className="mt-3 mb-6 flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-semibold tracking-tight">{money(p.amount)} to {p.beneficiaryName}</h1>
            <StatusPill status={p.status} />
          </div>
          <p className="mt-1 text-sm text-muted">
            Via {targetLabel(p.bankCode)}, received {dateTime(p.createdAt)}
          </p>
        </div>
        {p.status === 'DEAD_LETTERED' && (
          <div className="flex flex-col items-end gap-1">
            <Button onClick={() => replay.mutate()} disabled={replay.isPending}>
              {replay.isPending ? 'Replaying…' : `Replay from ${stepLabel[p.currentStep!].toLowerCase()}`}
            </Button>
            {replay.error && <p className="text-xs text-bad" role="alert">{(replay.error as Error).message}</p>}
          </div>
        )}
      </div>

      <Outcome payment={p} />

      <div className="mt-6 grid gap-6 lg:grid-cols-[1fr_300px]">
        <Panel title="Journey">
          <ol className="px-5 py-2">
            {STEPS.map((step, i) => (
              <StepSection key={step} payment={p} step={step} last={i === STEPS.length - 1} />
            ))}
          </ol>
        </Panel>
        <Facts payment={p} />
      </div>
    </>
  )
}

function Outcome({ payment: p }: { payment: PaymentDetail }) {
  const retries = p.totalAttempts - p.attempts.filter((a) => a.outcome === 'SUCCESS').length
  let text: string
  let tone = 'border-line bg-panel'
  switch (p.status) {
    case 'COMPLETED':
      text = retries > 0
        ? `Paid with bank reference ${p.bankReference}, after recovering from ${retries} failed ${retries === 1 ? 'call' : 'calls'}. Each step ran to success exactly once.`
        : `Paid with bank reference ${p.bankReference}. Every step succeeded on the first try.`
      tone = 'border-ok/30 bg-ok-soft text-ok'
      break
    case 'FAILED':
      text = `Stopped: ${p.failureReason}. This is a business decision, so PayRelay does not retry it.`
      tone = 'border-bad/30 bg-bad-soft text-bad'
      break
    case 'DEAD_LETTERED':
      text = `${p.failureReason}. Steps that already succeeded won't run again; replaying resumes at ${stepLabel[p.currentStep!].toLowerCase()}.`
      tone = 'border-dead/30 bg-dead-soft text-dead'
      break
    case 'RETRY_SCHEDULED':
      text = `${stepLabel[p.currentStep!]} failed (${p.failureReason}). Retrying ${relativeFuture(p.nextAttemptAt)}.`
      tone = 'border-wait/30 bg-wait-soft text-wait'
      break
    default:
      text = `Working on ${stepLabel[p.currentStep!].toLowerCase()}.`
  }
  return <p className={`rounded-lg border px-5 py-3 text-sm ${tone}`} role="status">{text}</p>
}

type StepState = 'done' | 'active' | 'retrying' | 'rejected' | 'stuck' | 'pending'

function stepState(p: PaymentDetail, step: PaymentStep): StepState {
  if (p.status === 'COMPLETED') return 'done'
  const current = STEPS.indexOf(p.currentStep!)
  const index = STEPS.indexOf(step)
  if (index < current) return 'done'
  if (index > current) return 'pending'
  if (p.status === 'FAILED') return 'rejected'
  if (p.status === 'DEAD_LETTERED') return 'stuck'
  if (p.status === 'RETRY_SCHEDULED') return 'retrying'
  return 'active'
}

const stateStyle: Record<StepState, { dot: string; label: string; text: string }> = {
  done: { dot: 'bg-ok border-ok', label: 'Done', text: 'text-ok' },
  active: { dot: 'bg-run border-run animate-pulse', label: 'In progress', text: 'text-run' },
  retrying: { dot: 'bg-wait border-wait', label: 'Waiting to retry', text: 'text-wait' },
  rejected: { dot: 'bg-bad border-bad', label: 'Rejected', text: 'text-bad' },
  stuck: { dot: 'bg-dead border-dead', label: 'Retries exhausted', text: 'text-dead' },
  pending: { dot: 'bg-panel border-line', label: 'Not reached', text: 'text-muted' },
}

function stepResult(p: PaymentDetail, step: PaymentStep) {
  if (step === 'KYC_CHECK' && p.kycReference) return `Reference ${p.kycReference}`
  if (step === 'RISK_CHECK' && p.riskScore !== null) return `Score ${p.riskScore}`
  if (step === 'BANK_TRANSFER' && p.bankReference) return `Bank reference ${p.bankReference}`
  return null
}

function StepSection({ payment, step, last }: { payment: PaymentDetail; step: PaymentStep; last: boolean }) {
  const state = stepState(payment, step)
  const style = stateStyle[state]
  const attempts = payment.attempts.filter((a) => a.step === step)
  const result = stepResult(payment, step)

  return (
    <li className="relative flex gap-4 pb-2">
      <div className="flex flex-col items-center pt-4" aria-hidden>
        <span className={`size-3 shrink-0 rounded-full border-2 ${style.dot}`} />
        {!last && <span className="mt-1 w-px flex-1 bg-line" />}
      </div>
      <div className="min-w-0 flex-1 py-3">
        <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
          <h3 className="font-medium">{stepLabel[step]}</h3>
          <p className="text-sm">
            <span className={style.text}>{style.label}</span>
            {result && <span className="ml-3 text-muted">{result}</span>}
          </p>
        </div>
        {attempts.length > 0 ? (
          <ul className="mt-2 divide-y divide-line rounded-md border border-line">
            {attempts.map((a) => <AttemptRow key={a.id} attempt={a} />)}
          </ul>
        ) : (
          <p className="mt-1 text-sm text-muted">No calls made yet.</p>
        )}
      </div>
    </li>
  )
}

const outcomeText: Record<Attempt['outcome'], string> = {
  SUCCESS: 'text-ok',
  RETRYABLE_FAILURE: 'text-wait',
  REJECTED: 'text-bad',
  CONFIG_ERROR: 'text-dead',
}

function AttemptRow({ attempt: a }: { attempt: Attempt }) {
  const [open, setOpen] = useState(false)
  return (
    <li>
      <button
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        className="grid w-full grid-cols-[72px_1fr_auto] items-center gap-3 px-3 py-2 text-left text-sm hover:bg-canvas sm:grid-cols-[72px_80px_1fr_70px_auto]"
      >
        <span className="text-muted">Attempt {a.attemptNumber}</span>
        <span className="hidden text-muted sm:block">{time(a.startedAt)}</span>
        <span className={`truncate ${outcomeText[a.outcome]}`}>
          {outcomeLabel[a.outcome]}
          {a.outcome !== 'SUCCESS' && a.error && <span className="text-muted"> ({a.error})</span>}
        </span>
        <span className="hidden text-right text-muted sm:block">{duration(a.durationMs)}</span>
        <span className="font-mono text-xs text-muted">{a.httpStatus ?? 'no reply'}</span>
      </button>
      {a.note && <p className="px-3 pb-2 text-xs text-run">{a.note}</p>}
      {open && (
        <div className="grid gap-3 border-t border-line bg-canvas px-3 py-3 md:grid-cols-2">
          {a.configVersion !== null && (
            <p className="text-xs text-muted md:col-span-2">Built from bank config version {a.configVersion}</p>
          )}
          <Payload label="Request sent" body={a.request} />
          <Payload label="Response received" body={a.response ?? a.error} />
        </div>
      )}
    </li>
  )
}

function Payload({ label, body }: { label: string; body: string | null }) {
  return (
    <div className="min-w-0">
      <p className="mb-1 text-xs text-muted">{label}</p>
      <pre className="max-h-64 overflow-auto rounded-md border border-line bg-panel p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap break-all">
        {pretty(body) || 'Nothing came back before the timeout.'}
      </pre>
    </div>
  )
}

function pretty(body: string | null) {
  if (!body) return ''
  // Requests are stored as "POST /path\nheaders\n\n{json}"; pretty-print the JSON part.
  const split = body.lastIndexOf('\n\n')
  const [head, json] = split >= 0 ? [body.slice(0, split + 2), body.slice(split + 2)] : ['', body]
  try {
    return head + JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return body
  }
}

function Facts({ payment: p }: { payment: PaymentDetail }) {
  const rows: [string, string, boolean?][] = [
    ['Payment ID', p.id, true],
    ['Idempotency key', p.idempotencyKey, true],
    ['Correlation ID', p.correlationId, true],
    ['Customer', p.customerId],
    ['PAN', p.customerPan],
    ['Account', p.accountNumber],
    ['IFSC', p.ifsc],
    ['Purpose', p.purpose ?? '—'],
    ['Calls made', String(p.totalAttempts)],
    ['Completed', dateTime(p.completedAt)],
  ]
  return (
    <Panel title="Details" className="self-start">
      <dl className="divide-y divide-line text-sm">
        {rows.map(([label, value, mono]) => (
          <div key={label} className="px-5 py-2.5">
            <dt className="text-xs text-muted">{label}</dt>
            <dd className={`mt-0.5 break-all ${mono ? 'font-mono text-xs' : ''}`}>{value}</dd>
          </div>
        ))}
      </dl>
      <p className="border-t border-line px-5 py-3 text-xs text-muted">
        Search logs in both services by the correlation ID to follow this payment end to end.
      </p>
    </Panel>
  )
}
