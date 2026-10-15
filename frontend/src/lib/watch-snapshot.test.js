import { describe, expect, it } from 'vitest'
import {
  watchSnapshot, readWatchEvent, isFresh, watchSetTarget, tickFromWatch, addHeartSamples,
  restAfterOnWatch, isLiveSession, WATCH_FRESH_MS,
} from './watch-snapshot.js'
import { mergeHeart, heartOf } from './heart-rate.js'
import { buildCompletedWorkout } from './finish-workout.js'
import { supersetUnits } from './history.js'
import { makeSideSet } from './workout-model.js'

const session = (over = {}) => ({
  id: 's1', d: '2026-10-15', start: 1000, name: 'Push', cur: 0, routineIds: [],
  entries: [
    { id: '0025', target: { sets: 2, reps: 5, restSec: 120 }, sets: [
      { w: 40, r: 5, warmup: true, done: true },
      { w: 80, r: 5 },
      { w: 80, r: 5 },
    ] },
    { id: '0001', target: { mode: 'time', sec: 45 }, sets: [{ sec: 45 }] },
  ],
  ...over,
})
const state = (active, over = {}) => ({ unit: 'kg', restSec: 90, active, workouts: [], ...over })

describe('watchSnapshot', () => {
  it('is null without a live session', () => {
    expect(watchSnapshot(state(null), null)).toBeNull()
    expect(watchSnapshot(state(session({ editingWorkoutId: 'w1' })), null)).toBeNull()
    expect(watchSnapshot(state(session({ backfill: { replaceId: null } })), null)).toBeNull()
  })

  it('cuts the session down to names, modes, numbers and ticks', () => {
    const snap = watchSnapshot(state(session()), null, { nameOf: e => 'ex ' + e.id })
    expect(snap).toMatchObject({ v: 1, sid: 's1', name: 'Push', unit: 'kg', start: 1000, cur: 0, rest: null })
    expect(snap.entries[0].name).toBe('ex 0025')
    expect(snap.entries[0].mode).toBe('reps')
    expect(snap.entries[0].sets[0]).toEqual({ done: true, w: 40, r: 5, warm: true, rest: 120 })
    expect(snap.entries[0].sets[1]).toEqual({ done: false, w: 80, r: 5, rest: 120 })
    // The hold falls back to the global rest.
    expect(snap.entries[1]).toEqual({ name: 'ex 0001', mode: 'time', sets: [{ done: false, sec: 45, rest: 90 }] })
  })

  it('passes the rest timer on: running by its end, paused by what is left, ready as none', () => {
    const S = state(session())
    expect(watchSnapshot(S, { endsAt: 5000, total: 90, left: 60 }).rest).toEqual({ endsAt: 5000, total: 90 })
    expect(watchSnapshot(S, { endsAt: 5000, total: 90, left: 42, paused: true }).rest).toEqual({ endsAt: null, total: 90, left: 42 })
    expect(watchSnapshot(S, { endsAt: 5000, total: 90, left: 0, ready: true }).rest).toBeNull()
  })

  it('reports a per-side row by side, and the unit in lb', () => {
    const A = session({ entries: [{ id: '0100', target: { side: true }, sets: [makeSideSet({ w: 10, r: 8 })] }] })
    A.entries[0].sets[0].sides.L.done = true
    const snap = watchSnapshot(state(A, { unit: 'lb' }), null)
    expect(snap.unit).toBe('lb')
    expect(snap.entries[0].sets[0].sides).toEqual({ L: true, R: false })
  })

  it('keeps `cur` inside the list', () => {
    expect(watchSnapshot(state(session({ cur: 9 })), null).cur).toBe(1)
  })
})

describe('restAfterOnWatch', () => {
  it('rests only after the last member of a superset', () => {
    const entries = [
      { id: 'a', sg: 'g', target: { restSec: 60 }, sets: [{ w: 1, r: 1 }] },
      { id: 'b', sg: 'g', target: { restSec: 90 }, sets: [{ w: 1, r: 1 }] },
    ]
    const units = supersetUnits(entries)
    expect(restAfterOnWatch(entries, units, 0, 0, 120)).toBe(0)
    expect(restAfterOnWatch(entries, units, 1, 0, 120)).toBe(90)
  })

  it('is 0 with the rest timer off and no rest of the exercise’s own', () => {
    const entries = [{ id: 'a', target: {}, sets: [{ w: 1, r: 1 }] }]
    expect(restAfterOnWatch(entries, supersetUnits(entries), 0, 0, 0)).toBe(0)
  })
})

describe('readWatchEvent', () => {
  it('accepts the three kinds and fills the defaults', () => {
    expect(readWatchEvent({ type: 'set', sid: 's1', idx: 0, i: 1, at: 5 })).toEqual({ type: 'set', sid: 's1', idx: 0, i: 1, done: true, side: null, at: 5 })
    expect(readWatchEvent({ type: 'set', sid: 's1', idx: 0, i: 1, done: false, side: 'R' })).toMatchObject({ done: false, side: 'R', at: 0 })
    expect(readWatchEvent({ type: 'rest', action: 'skip', at: 1 })).toEqual({ type: 'rest', action: 'skip', at: 1 })
    expect(readWatchEvent({ type: 'heart', sid: 's1', bpm: [120, 0, 300, 'x', 130] })).toEqual({ type: 'heart', sid: 's1', bpm: [120, 130], at: 0 })
  })

  it('drops what does not add up', () => {
    for (const ev of [null, 'x', {}, { type: 'set', sid: 's1', idx: -1, i: 0 }, { type: 'set', sid: 's1', idx: 0.5, i: 0 },
      { type: 'set', idx: 0, i: 0 }, { type: 'rest', action: 'pause' }, { type: 'heart', sid: 's1', bpm: [0] }, { type: 'nope' }]) {
      expect(readWatchEvent(ev)).toBeNull()
    }
  })

  it('calls an event fresh only within the window', () => {
    expect(isFresh({ at: 1000 }, 1000 + WATCH_FRESH_MS)).toBe(true)
    expect(isFresh({ at: 1000 }, 1001 + WATCH_FRESH_MS)).toBe(false)
    expect(isFresh({ at: 0 }, 1)).toBe(false)
  })
})

describe('ticking from the watch', () => {
  const ev = over => readWatchEvent({ type: 'set', sid: 's1', idx: 0, i: 1, at: 7000, ...over })

  it('marks the set with when it happened, once', () => {
    const S = state(session())
    expect(tickFromWatch(S, ev())).toBe(true)
    expect(S.active.entries[0].sets[1]).toMatchObject({ done: true, at: 7000 })
    // The same tick delivered again changes nothing.
    expect(tickFromWatch(S, ev())).toBe(false)
    expect(tickFromWatch(S, ev({ done: false }))).toBe(true)
    expect(S.active.entries[0].sets[1].done).toBe(false)
  })

  it('leaves another session, a set that is gone and a past workout alone', () => {
    expect(tickFromWatch(state(session()), ev({ sid: 'other' }))).toBe(false)
    expect(tickFromWatch(state(session()), ev({ i: 9 }))).toBe(false)
    expect(tickFromWatch(state(session({ editingWorkoutId: 'w' })), ev())).toBe(false)
    expect(tickFromWatch(state(null), ev())).toBe(false)
  })

  it('ticks a per-side row a side at a time, first the open one', () => {
    const S = state(session({ entries: [{ id: '0100', target: { side: true }, sets: [makeSideSet({ w: 10, r: 8 })] }] }))
    const e = readWatchEvent({ type: 'set', sid: 's1', idx: 0, i: 0, at: 1 })
    expect(watchSetTarget(S.active, e)).toEqual({ side: 'L', change: true })
    tickFromWatch(S, e)
    expect(S.active.entries[0].sets[0].sides.L.done).toBe(true)
    expect(S.active.entries[0].sets[0].done).toBe(false)
    tickFromWatch(S, e)
    expect(S.active.entries[0].sets[0].done).toBe(true)
    expect(watchSetTarget(S.active, e).change).toBe(false)
  })

  it('drops a hold’s set-aside plan when the set is ticked', () => {
    const A = session()
    A.entries[1].sets[0].planSec = 45
    const S = state(A)
    tickFromWatch(S, readWatchEvent({ type: 'set', sid: 's1', idx: 1, i: 0, at: 1 }))
    expect(S.active.entries[1].sets[0].planSec).toBeUndefined()
  })
})

describe('heart rate', () => {
  it('merges batches into one summary', () => {
    let h = mergeHeart(null, [100, 120])
    expect(h).toEqual({ n: 2, avg: 110, min: 100, max: 120 })
    h = mergeHeart(h, [140])
    expect(h).toEqual({ n: 3, avg: 120, min: 100, max: 140 })
    expect(mergeHeart(h, [])).toEqual(h)
    expect(mergeHeart(null, [10, 999])).toBeNull()
  })

  it('goes onto the running session, or the finished workout it was recorded in', () => {
    const S = state(session(), { workouts: [{ id: 'old', entries: [] }] })
    expect(addHeartSamples(S, { sid: 's1', bpm: [100] })).toBe('active')
    expect(S.active.hr).toEqual({ n: 1, avg: 100, min: 100, max: 100 })
    const w = addHeartSamples(S, { sid: 'old', bpm: [90, 110] })
    expect(w.hr).toEqual({ n: 2, avg: 100, min: 90, max: 110 })
    expect(addHeartSamples(S, { sid: 'none', bpm: [90] })).toBeNull()
  })

  it('rides into the finished workout only when there is one', () => {
    const A = session()
    A.entries[0].sets[1].done = true
    expect('hr' in buildCompletedWorkout(A, { end: 2000 })).toBe(false)
    A.hr = { n: 3, avg: 120, min: 100, max: 140 }
    expect(buildCompletedWorkout(A, { end: 2000 }).hr).toEqual({ n: 3, avg: 120, min: 100, max: 140 })
    expect(heartOf({ hr: { n: 0, avg: 1, min: 1, max: 1 } })).toBeNull()
  })

  it('isLiveSession needs a real start', () => {
    expect(isLiveSession(session({ start: 0 }))).toBe(false)
    expect(isLiveSession(session())).toBe(true)
  })
})
