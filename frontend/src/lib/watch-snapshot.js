// The watch app (frontend/android/wear, docs/MOBILE.md "Wear OS watch"): what the phone hands it
// and what it hands back. The native side only carries JSON; every decision about the session
// is made here, so it can be tested without a watch.
//
// Phone → watch: watchSnapshot() — the running workout, cut down to what a watch face shows.
// Watch → phone: readWatchEvent() checks an event, then
//   - a set ticked on the watch goes through the workout screen's own toggle when that screen
//     is up and the tick is fresh (lib/watch-sync.js), or through tickFromWatch() here when it
//     is not: the set is marked, nothing else happens (no rest, no navigation) — the same rule a
//     hold that ends off screen follows (store/useUI.js ownerDone);
//   - heart-rate samples go through addHeartSamples(), onto the running session or, when they
//     arrive after the finish, onto the workout they were recorded in.
import { modeOf, supersetUnits, unitOf } from './history.js'
import { isWarmupRow, isSideSet, toggleSide } from './workout-model.js'
import { restSecFor, warmupRestSecFor } from './supersetFlow.js'
import { pyramidRestFor } from './pyramid.js'
import { BPM_MIN, BPM_MAX, mergeHeart } from './heart-rate.js'

export const WATCH_SNAPSHOT_VERSION = 1
// A tick older than this was queued while the phone's page was frozen: it is logged, but the
// rest it would have started is long over and the screen must not jump to the next exercise.
export const WATCH_FRESH_MS = 15_000
const MAX_SAMPLES_PER_EVENT = 2000

const list = v => (Array.isArray(v) ? v : [])
const num = v => (typeof v === 'number' && isFinite(v) ? v : null)

/** Whether `active` is a workout happening now: not a past one being logged or edited. */
export const isLiveSession = active => !!active && !active.editingWorkoutId && !active.backfill && active.start > 0

/**
 * The rest the watch starts after a set ticked on it. The phone starts its own when it hears of
 * the tick and the watch then follows the phone's, so this only has to be right while the phone
 * is asleep. In a superset the break comes after the round, so only the group's last member rests.
 */
export function restAfterOnWatch(entries, units, idx, i, defaultRestSec) {
  const entry = entries[idx]
  if (!entry) return 0
  const unit = unitOf(units, idx)
  if (unit.length > 1 && unit[unit.length - 1] !== idx) return 0
  const own = { idx, sec: pyramidRestFor(entry.target, entry.sets, i) }
  return warmupRestSecFor(entry, i, restSecFor(entries, unit, defaultRestSec, own))
}

/**
 * The running workout as the watch shows it, or null when there is none to show. `timer` is
 * useUI's rest timer. `nameOf(entry)` gives an exercise's name in the app's language.
 */
export function watchSnapshot(S, timer, { nameOf = e => String(e?.id ?? '') } = {}) {
  const A = S?.active
  if (!isLiveSession(A)) return null
  const entries = list(A.entries)
  const units = supersetUnits(entries)
  return {
    v: WATCH_SNAPSHOT_VERSION,
    sid: String(A.id),
    name: String(A.name || ''),
    unit: S.unit === 'lb' ? 'lb' : 'kg',
    start: A.start,
    cur: Math.max(0, Math.min(entries.length - 1, A.cur | 0)),
    rest: restOf(timer),
    entries: entries.map((e, idx) => ({
      name: String(nameOf(e) || ''),
      mode: modeOf(e.target || { id: e.id }),
      sets: list(e.sets).map((s, i) => setOf(s, restAfterOnWatch(entries, units, idx, i, S.restSec))),
    })),
  }
}

function restOf(timer) {
  if (!timer || timer.ready || !(timer.total > 0)) return null
  const paused = !!timer.paused
  return {
    endsAt: paused ? null : timer.endsAt,
    total: timer.total,
    ...(paused ? { left: Math.max(0, timer.left | 0) } : {}),
  }
}

function setOf(s, rest) {
  const out = { done: s?.done === true }
  for (const k of ['w', 'r', 'sec', 'km', 'min']) {
    const v = num(s?.[k])
    if (v != null) out[k] = v
  }
  if (isWarmupRow(s)) out.warm = true
  if (isSideSet(s)) out.sides = { L: s.sides.L.done === true, R: s.sides.R.done === true }
  if (rest > 0) out.rest = rest
  return out
}

/**
 * An event from the watch, checked, or null. The watch is ours, but the bytes still crossed a
 * radio and a queue file, and a bad index must not throw inside the store.
 *   { type: 'set', sid, idx, i, done, side?, at }
 *   { type: 'rest', action: 'skip' | 'add', at }
 *   { type: 'heart', sid, bpm: [..], at }
 */
export function readWatchEvent(ev) {
  if (!ev || typeof ev !== 'object') return null
  const at = num(ev.at) ?? 0
  if (ev.type === 'set') {
    const idx = ev.idx, i = ev.i
    if (!Number.isInteger(idx) || !Number.isInteger(i) || idx < 0 || i < 0) return null
    if (typeof ev.sid !== 'string' || !ev.sid) return null
    const side = ev.side === 'L' || ev.side === 'R' ? ev.side : null
    return { type: 'set', sid: ev.sid, idx, i, done: ev.done !== false, side, at }
  }
  if (ev.type === 'rest') {
    if (ev.action !== 'skip' && ev.action !== 'add') return null
    return { type: 'rest', action: ev.action, at }
  }
  if (ev.type === 'heart') {
    if (typeof ev.sid !== 'string' || !ev.sid) return null
    const bpm = list(ev.bpm).slice(0, MAX_SAMPLES_PER_EVENT).filter(b => num(b) != null && b >= BPM_MIN && b <= BPM_MAX)
    return bpm.length ? { type: 'heart', sid: ev.sid, bpm, at } : null
  }
  return null
}

/** Whether a checked event happened recently enough to act on as if it were happening now. */
export const isFresh = (ev, now = Date.now()) => ev.at > 0 && now - ev.at <= WATCH_FRESH_MS

/**
 * The set the event points at in the running session, and whether it needs changing at all —
 * the watch says what the set should be, not "flip it", so a tick delivered twice is one tick.
 * Returns null when the event is for another session or a set that is gone, else
 * { side, change } where `side` is the side to flip on a per-side row.
 */
export function watchSetTarget(active, ev) {
  if (!isLiveSession(active) || String(active.id) !== ev.sid) return null
  const row = list(active.entries)[ev.idx]?.sets?.[ev.i]
  if (!row) return null
  if (isSideSet(row)) {
    // A per-side row is ticked a side at a time: the side the watch named, or the first one open.
    const side = ev.side || (ev.done ? (!row.sides.L.done ? 'L' : 'R') : (row.sides.R.done ? 'R' : 'L'))
    return { side, change: (row.sides[side].done === true) !== ev.done }
  }
  return { side: null, change: (row.done === true) !== ev.done }
}

/**
 * Marks the set the way the watch asked, on a session the workout screen is not driving. Mutates
 * `S` (inside a store update). Returns whether anything changed.
 */
export function tickFromWatch(S, ev) {
  const target = watchSetTarget(S?.active, ev)
  if (!target || !target.change) return false
  const sets = S.active.entries[ev.idx].sets
  const row = target.side ? toggleSide(sets[ev.i], target.side) : { ...sets[ev.i], done: ev.done }
  if (ev.done) row.at = ev.at > 0 ? ev.at : Date.now()
  if (ev.done && row.planSec != null) delete row.planSec
  sets[ev.i] = row
  return true
}

/**
 * Adds a heart event's samples to the session it was recorded in: the running one, or a finished
 * workout with that id (the watch flushes its last batch as the session ends, which can land after
 * the phone saved it). Mutates `S`. Returns the workout it changed, 'active', or null.
 */
export function addHeartSamples(S, ev) {
  if (!S) return null
  if (S.active && String(S.active.id) === ev.sid && !S.active.editingWorkoutId) {
    S.active.hr = mergeHeart(S.active.hr, ev.bpm)
    return 'active'
  }
  const w = list(S.workouts).find(x => x && String(x.id) === ev.sid)
  if (!w) return null
  w.hr = mergeHeart(w.hr, ev.bpm)
  return w
}
