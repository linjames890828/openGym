import { afterEach, describe, expect, it, vi } from 'vitest'
import { applyWatchEvent, bindWatchToggle, _resetWatchSync } from './watch-sync.js'

// The two stores as watch-sync sees them: getState(), and update() cloning the state as the real
// store does.
function stores(S) {
  const box = { S }
  const ui = { stopRest: vi.fn(), addRest: vi.fn() }
  return {
    box,
    ui,
    store: { getState: () => ({ S: box.S, update: mut => { const next = structuredClone(box.S); mut(next); box.S = next } }) },
    uiStore: { getState: () => ui },
  }
}
const live = () => ({
  unit: 'kg', restSec: 90, workouts: [{ id: 'done-1', entries: [] }],
  active: { id: 's1', start: 1, d: '2026-10-15', cur: 0, entries: [{ id: '0025', target: {}, sets: [{ w: 80, r: 5 }] }] },
})
const NOW = 1_000_000

afterEach(() => _resetWatchSync())

describe('applyWatchEvent', () => {
  it('sends a fresh tick through the workout screen’s toggle', () => {
    const s = stores(live())
    const toggle = vi.fn()
    bindWatchToggle(toggle)
    expect(applyWatchEvent({ type: 'set', sid: 's1', idx: 0, i: 0, at: NOW - 1000 }, { store: s.store, ui: s.uiStore }, NOW)).toBe(true)
    expect(toggle).toHaveBeenCalledWith(0, 0, undefined)
    expect(s.box.S.active.entries[0].sets[0].done).toBeUndefined()
  })

  it('logs a stale tick, or one with no screen up, quietly', () => {
    const s = stores(live())
    const toggle = vi.fn()
    const unbind = bindWatchToggle(toggle)
    applyWatchEvent({ type: 'set', sid: 's1', idx: 0, i: 0, at: NOW - 60_000 }, { store: s.store, ui: s.uiStore }, NOW)
    expect(toggle).not.toHaveBeenCalled()
    expect(s.box.S.active.entries[0].sets[0]).toMatchObject({ done: true, at: NOW - 60_000 })
    unbind()
    const t = stores(live())
    applyWatchEvent({ type: 'set', sid: 's1', idx: 0, i: 0, at: NOW }, { store: t.store, ui: t.uiStore }, NOW)
    expect(t.box.S.active.entries[0].sets[0].done).toBe(true)
  })

  it('does not toggle a set that is already the way the watch wants it', () => {
    const S = live()
    S.active.entries[0].sets[0].done = true
    const s = stores(S)
    const toggle = vi.fn()
    bindWatchToggle(toggle)
    expect(applyWatchEvent({ type: 'set', sid: 's1', idx: 0, i: 0, at: NOW }, { store: s.store, ui: s.uiStore }, NOW)).toBe(false)
    expect(toggle).not.toHaveBeenCalled()
  })

  it('skips or lengthens the rest only while the press is fresh', () => {
    const s = stores(live())
    applyWatchEvent({ type: 'rest', action: 'skip', at: NOW }, { store: s.store, ui: s.uiStore }, NOW)
    applyWatchEvent({ type: 'rest', action: 'add', at: NOW }, { store: s.store, ui: s.uiStore }, NOW)
    applyWatchEvent({ type: 'rest', action: 'skip', at: NOW - 60_000 }, { store: s.store, ui: s.uiStore }, NOW)
    expect(s.ui.stopRest).toHaveBeenCalledTimes(1)
    expect(s.ui.addRest).toHaveBeenCalledWith(15)
  })

  it('adds heart rate to the session, and stamps a finished workout it lands on late', () => {
    const s = stores(live())
    applyWatchEvent({ type: 'heart', sid: 's1', bpm: [120, 140], at: 1 }, { store: s.store, ui: s.uiStore }, NOW)
    expect(s.box.S.active.hr).toEqual({ n: 2, avg: 130, min: 120, max: 140 })
    applyWatchEvent({ type: 'heart', sid: 'done-1', bpm: [100], at: 1 }, { store: s.store, ui: s.uiStore }, NOW)
    expect(s.box.S.workouts[0]).toMatchObject({ hr: { n: 1, avg: 100 }, _ts: NOW })
  })

  it('ignores junk', () => {
    const s = stores(live())
    expect(applyWatchEvent({ type: 'set', sid: 's1', idx: 'x' }, { store: s.store, ui: s.uiStore }, NOW)).toBe(false)
    expect(applyWatchEvent(null, { store: s.store, ui: s.uiStore }, NOW)).toBe(false)
  })
})
