// Heart rate on a workout, from the watch (lib/watch-snapshot.js): { n, avg, min, max } in beats
// per minute, `n` the samples behind it. Kept apart from the watch code because finishing a
// workout reads it too (lib/finish-workout.js).

// Beyond what a heart beats; a sensor that reads 0 or 250 is not on a wrist.
export const BPM_MIN = 30
export const BPM_MAX = 230

const list = v => (Array.isArray(v) ? v : [])
const num = v => (typeof v === 'number' && isFinite(v) ? v : null)
const inRange = b => num(b) != null && b >= BPM_MIN && b <= BPM_MAX

/**
 * `prev` with more samples in it, or null when neither has any. Only the summary is kept: a
 * session at one sample a second would otherwise carry thousands of numbers into every sync.
 */
export function mergeHeart(prev, bpm) {
  const samples = list(bpm).filter(inRange)
  const p = prev && prev.n > 0 ? prev : null
  if (!samples.length) return p ? { ...p } : null
  const n = (p ? p.n : 0) + samples.length
  const sum = (p ? p.avg * p.n : 0) + samples.reduce((a, b) => a + b, 0)
  return {
    n,
    avg: Math.round((sum / n) * 10) / 10,
    min: Math.min(p ? p.min : Infinity, ...samples),
    max: Math.max(p ? p.max : -Infinity, ...samples),
  }
}

/** The heart-rate summary a finished workout keeps, or null when the session had none. */
export function heartOf(active) {
  const h = active?.hr
  if (!h || !(h.n > 0) || num(h.avg) == null || num(h.min) == null || num(h.max) == null) return null
  return { n: h.n, avg: h.avg, min: h.min, max: h.max }
}
