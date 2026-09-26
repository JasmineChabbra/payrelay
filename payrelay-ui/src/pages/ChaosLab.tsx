import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type ChaosConfig, type TargetStats } from '../api'
import { moneyCompact, targetLabel } from '../format'
import { Button, ErrorNotice, Loading, PageHeader, Panel } from '../components/ui'
import { SendTraffic } from '../components/SendTraffic'

const PRESETS = [
  { name: 'calm', label: 'Calm', hint: 'Everything works' },
  { name: 'flaky', label: 'Flaky', hint: '25% errors, 10% lost replies' },
  { name: 'slow', label: 'Slow', hint: '2.5 s delays, some timeouts' },
  { name: 'meltdown', label: 'Meltdown', hint: '60% errors, 20% lost replies' },
]

export function ChaosLab() {
  const qc = useQueryClient()
  const chaos = useQuery({ queryKey: ['chaos'], queryFn: api.chaos })
  const stats = useQuery({ queryKey: ['simStats'], queryFn: api.simStats, refetchInterval: 1500 })

  const preset = useMutation({
    mutationFn: api.preset,
    onSuccess: (data) => qc.setQueryData(['chaos'], data),
  })
  const reset = useMutation({
    mutationFn: async () => {
      await api.resetDemo()
      return api.resetChaos()
    },
    onSuccess: (data) => {
      qc.setQueryData(['chaos'], data)
      qc.invalidateQueries()
    },
  })

  return (
    <>
      <PageHeader
        title="Chaos lab"
        description="Make any external system slow or unreliable, send traffic, and watch PayRelay cope. Changes apply immediately."
        action={<SendTraffic />}
      />
      {(chaos.error || stats.error) && <ErrorNotice error={chaos.error ?? stats.error} />}

      <Panel title="Scenarios" className="mb-6">
        <div className="grid gap-px bg-line sm:grid-cols-2 lg:grid-cols-4">
          {PRESETS.map((p) => (
            <button
              key={p.name}
              onClick={() => preset.mutate(p.name)}
              disabled={preset.isPending}
              className="bg-panel px-5 py-4 text-left hover:bg-canvas disabled:opacity-60"
            >
              <span className="block font-medium">{p.label}</span>
              <span className="text-sm text-muted">{p.hint}</span>
            </button>
          ))}
        </div>
      </Panel>

      <Panel
        title="Systems"
        action={
          <Button variant="danger" onClick={() => reset.mutate()} disabled={reset.isPending}>
            {reset.isPending ? 'Resetting…' : 'Reset demo'}
          </Button>
        }
      >
        {chaos.isLoading && <Loading />}
        {chaos.data && (
          <ul className="divide-y divide-line">
            {Object.entries(chaos.data).map(([target, config]) => (
              <TargetRow key={target} target={target} config={config} stats={stats.data?.[target]} />
            ))}
          </ul>
        )}
        <p className="border-t border-line px-5 py-3 text-xs text-muted">
          A lost reply means the system did the work but PayRelay never heard back, the hardest case to handle safely.
          "Duplicates stopped" counts retries the system answered from its idempotency store instead of doing the work again.
          Reset demo clears all payments and counters.
        </p>
      </Panel>
    </>
  )
}

function TargetRow({ target, config, stats }: { target: string; config: ChaosConfig; stats?: TargetStats }) {
  const qc = useQueryClient()
  const [draft, setDraft] = useState(config)
  const [error, setError] = useState<string | null>(null)
  const dirty = useRef(false)

  // Presets and resets replace the config from outside; follow them unless the user is mid-edit.
  useEffect(() => {
    if (!dirty.current) setDraft(config)
  }, [config])

  // Send edits to the simulator shortly after the user stops dragging.
  useEffect(() => {
    if (!dirty.current) return
    const timer = setTimeout(() => {
      api.setChaos(target, draft)
        .then((data) => {
          dirty.current = false
          setError(null)
          qc.setQueryData(['chaos'], data)
        })
        .catch((e: Error) => setError(e.message))
    }, 250)
    return () => clearTimeout(timer)
  }, [draft, target, qc])

  const edit = (patch: Partial<ChaosConfig>) => {
    dirty.current = true
    setDraft((d) => ({ ...d, ...patch }))
  }

  const isBank = target !== 'KYC' && target !== 'RISK'

  return (
    <li className="grid gap-x-8 gap-y-4 px-5 py-5 lg:grid-cols-[180px_1fr_1fr]">
      <div>
        <p className="font-medium">{targetLabel(target)}</p>
        {error && <p className="mt-1 text-xs text-bad" role="alert">{error}</p>}
      </div>
      <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-1 xl:grid-cols-3">
        <Slider label="Errors" value={Math.round(draft.failureRate * 100)} unit="%" max={100}
          onChange={(v) => edit({ failureRate: v / 100 })} />
        <Slider label="Lost replies" value={Math.round(draft.lostResponseRate * 100)} unit="%" max={100}
          onChange={(v) => edit({ lostResponseRate: v / 100 })} />
        <Slider label="Delay" value={draft.latencyMs} unit=" ms" max={5000} step={50}
          onChange={(v) => edit({ latencyMs: v })} />
      </div>
      <dl className="grid grid-cols-3 gap-3 text-sm sm:grid-cols-5 lg:grid-cols-3 xl:grid-cols-5">
        <Counter label="Requests" value={stats?.requests} />
        <Counter label="Errors sent" value={stats?.injectedFailures} />
        <Counter label="Replies lost" value={stats?.lostResponses} />
        <Counter label="Duplicates stopped" value={stats?.idempotentReplays} highlight />
        {isBank
          ? <Counter label="Money moved" text={stats ? moneyCompact(stats.amountMoved) : undefined} />
          : <Counter label="Checks done" value={stats?.uniqueOperations} />}
      </dl>
    </li>
  )
}

function Slider({ label, value, unit, max, step = 1, onChange }: {
  label: string; value: number; unit: string; max: number; step?: number; onChange: (v: number) => void
}) {
  return (
    <label className="block text-xs text-muted">
      <span className="flex justify-between">
        {label}
        <span className="font-medium text-ink">{value}{unit}</span>
      </span>
      <input type="range" min={0} max={max} step={step} value={value}
        onChange={(e) => onChange(Number(e.target.value))} className="mt-1.5 w-full" />
    </label>
  )
}

function Counter({ label, value, text, highlight }: { label: string; value?: number; text?: string; highlight?: boolean }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className={`font-semibold ${highlight && value ? 'text-ok' : ''}`}>{text ?? value?.toLocaleString('en-IN') ?? '—'}</dd>
    </div>
  )
}
