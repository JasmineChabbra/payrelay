import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../api'
import { Button } from './ui'

/** Generates realistic payments, including some client retries that reuse an Idempotency-Key. */
export function SendTraffic() {
  const [count, setCount] = useState(100)
  const qc = useQueryClient()
  const send = useMutation({
    mutationFn: () => api.sendTraffic(count),
    onSuccess: () => qc.invalidateQueries(),
  })

  return (
    <div className="flex flex-col items-end gap-1">
      <div className="flex items-center gap-2">
        <label className="sr-only" htmlFor="traffic-count">Number of payments</label>
        <select
          id="traffic-count"
          value={count}
          onChange={(e) => setCount(Number(e.target.value))}
          className="rounded-md border border-line bg-panel px-2.5 py-2 text-sm"
        >
          {[10, 50, 100, 300].map((n) => <option key={n} value={n}>{n} payments</option>)}
        </select>
        <Button onClick={() => send.mutate()} disabled={send.isPending}>
          {send.isPending ? 'Sending…' : 'Send test traffic'}
        </Button>
      </div>
      {send.data && (
        <p className="text-xs text-muted" role="status">
          Sent {send.data.created} payments; {send.data.replayed} duplicate requests returned the original.
        </p>
      )}
      {send.error && <p className="text-xs text-bad" role="alert">{(send.error as Error).message}</p>}
    </div>
  )
}
