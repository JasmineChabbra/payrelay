import type { AttemptOutcome, AuthType, PaymentStatus, PaymentStep } from './api'

const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 })
const inrCompact = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', notation: 'compact', maximumFractionDigits: 1 })

export const money = (n: number) => inr.format(n)
export const moneyCompact = (n: number) => inrCompact.format(n)

export const shortId = (id: string) => id.slice(0, 8)

export function time(iso: string | null) {
  if (!iso) return '—'
  return new Date(iso).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

export function dateTime(iso: string | null) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

export function duration(ms: number | null | undefined) {
  if (ms === null || ms === undefined) return '—'
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`
  return `${Math.floor(ms / 60_000)} min ${Math.round((ms % 60_000) / 1000)} s`
}

export function relativeFuture(iso: string | null) {
  if (!iso) return ''
  const s = Math.round((new Date(iso).getTime() - Date.now()) / 1000)
  return s <= 0 ? 'now' : `in ${s} s`
}

export const pct = (n: number) => `${(n * 100).toFixed(1)}%`

export const statusLabel: Record<PaymentStatus, string> = {
  RECEIVED: 'Received',
  PROCESSING: 'Processing',
  RETRY_SCHEDULED: 'Retrying',
  COMPLETED: 'Completed',
  FAILED: 'Rejected',
  DEAD_LETTERED: 'Needs review',
}

export const stepLabel: Record<PaymentStep, string> = {
  KYC_CHECK: 'KYC check',
  RISK_CHECK: 'Risk check',
  BANK_TRANSFER: 'Bank transfer',
}

export const outcomeLabel: Record<AttemptOutcome, string> = {
  SUCCESS: 'Succeeded',
  RETRYABLE_FAILURE: 'Failed, will retry',
  REJECTED: 'Rejected',
  CONFIG_ERROR: 'Configuration error',
}

export const authLabel: Record<AuthType, string> = {
  API_KEY: 'API key',
  OAUTH2_CLIENT_CREDENTIALS: 'OAuth2 client credentials',
  HMAC_SHA256: 'HMAC-SHA256 signature',
}

export const targetLabel = (t: string) =>
  t === 'KYC' ? 'KYC registry' : t === 'RISK' ? 'Risk engine' : `${t.charAt(0)}${t.slice(1).toLowerCase()} Bank`
