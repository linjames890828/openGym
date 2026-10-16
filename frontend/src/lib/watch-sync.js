// The Wear OS watch app on the Android phone (docs/MOBILE.md "Wear OS watch"). This file owns the
// native plugin (WatchPlugin.java); what crosses it is decided in lib/watch-snapshot.js.
//
// The phone publishes the running workout whenever it changes, and the watch shows it. The watch
// sends back what was done on it: a set ticked, the rest skipped or lengthened, heart-rate samples.
// Those arrive in the native side first, which keeps them in a queue until the page takes them
// (`drain`), so a tick made while the page was frozen in a pocket is not lost: it is taken when the
// phone wakes, and logged quietly because the moment for its rest has passed.
//
// Nothing goes anywhere until a watch with openGym on it is paired: the native side checks for one
// before every publish. The web build never gets here (MOBILE is a build-time flag).
import { MOBILE, isAndroid } from './mobile.js'
import { watchSnapshot, readWatchEvent, isFresh, watchSetTarget, tickFromWatch, addHeartSamples } from './watch-snapshot.js'
import { exerciseNameFor } from './i18n-core.js'
import { EXIDX } from './exercises.js'
import { stampWorkout } from './sync-merge.js'

// Wrapped in an object, as in rest-alert.js: a Capacitor plugin proxy answers `then`, so a
// promise resolved with the bare proxy never settles.
let pluginP = null
const plugin = () => pluginP || (pluginP = (async () => {
  if (!(await isAndroid())) return null
  const { registerPlugin } = await import('@capacitor/core')
  return { Watch: registerPlugin('Watch') }
})().catch(() => null))

const nameOf = e => (EXIDX[e.id] ? exerciseNameFor(EXIDX[e.id]) : (e.n || e.id))

// The workout screen's own toggle, while that screen is up (views/Workout.jsx binds it): a fresh
// tick from the watch then does everything a tap on the phone does — the rest, the next exercise,
// the "workout complete" sheet.
let toggleHandler = null
/** Hands the workout screen's toggle(idx, i, side) to the watch. Returns the unbind. */
export function bindWatchToggle(fn) {
  toggleHandler = fn
  return () => { if (toggleHandler === fn) toggleHandler = null }
}

/** Applies one event from the watch. Exported for the tests; `stores` as initWatchSync takes them. */
export function applyWatchEvent(raw, { store, ui }, now = Date.now()) {
  const ev = readWatchEvent(raw)
  if (!ev) return false
  if (ev.type === 'heart') {
    let changed = null
    store.getState().update(S => {
      changed = addHeartSamples(S, ev)
      // A finished workout that gained its last batch is an edit like any other: stamped, so a
      // sync carries it to the other devices.
      if (changed && changed !== 'active') stampWorkout(changed, now)
    })
    return !!changed
  }
  if (ev.type === 'rest') {
    if (!isFresh(ev, now)) return false
    if (ev.action === 'skip') ui.getState().stopRest()
    else ui.getState().addRest(15)
    return true
  }
  const target = watchSetTarget(store.getState().S.active, ev)
  if (!target || !target.change) return false
  if (toggleHandler && isFresh(ev, now)) { toggleHandler(ev.idx, ev.i, target.side || undefined); return true }
  let changed = false
  store.getState().update(S => { changed = tickFromWatch(S, ev) }, true)
  return changed
}

let started = false
// undefined until the first publish, so a launch with no workout clears whatever an earlier run
// left on the watch (a crash mid-workout, a workout discarded while the app was killed).
let lastSent
let sending = Promise.resolve()

function publish(stores) {
  const snap = watchSnapshot(stores.store.getState().S, stores.ui.getState().timer, { nameOf })
  const json = snap ? JSON.stringify(snap) : null
  // The rest timer ticks every second and most store writes do not touch the workout: only a
  // snapshot that differs goes out.
  if (json === lastSent) return
  lastSent = json
  sending = sending.then(async () => {
    const p = await plugin()
    if (!p) return
    if (json) await p.Watch.publish({ json })
    else await p.Watch.clear()
  }).catch(() => { lastSent = undefined })
}

async function drain(stores) {
  const p = await plugin()
  if (!p) return
  const { events } = await p.Watch.drain().catch(() => ({ events: [] }))
  for (const ev of Array.isArray(events) ? events : []) {
    try { applyWatchEvent(ev, stores) } catch { /* one bad event must not hold up the rest */ }
  }
}

/**
 * Starts the link: publishes on every change of the workout or the rest timer, and takes what the
 * watch sent when the plugin says there is something, when the app comes back to the front, and
 * once at launch. `store` and `ui` are the two Zustand stores, passed in so this file imports
 * neither.
 */
export function initWatchSync(stores) {
  if (!MOBILE || started) return
  started = true
  plugin().then(p => {
    if (!p) return
    stores.store.subscribe((s, prev) => { if (s.S.active !== prev.S.active || s.S.unit !== prev.S.unit) publish(stores) })
    stores.ui.subscribe((s, prev) => { if (s.timer !== prev.timer) publish(stores) })
    p.Watch.addListener('watch', () => { drain(stores).catch(() => {}) })
    import('@capacitor/app').then(({ App }) => {
      App.addListener('appStateChange', ({ isActive }) => { if (isActive) drain(stores).catch(() => {}) })
    }).catch(() => {})
    publish(stores)
    drain(stores).catch(() => {})
  }).catch(() => {})
}

/** For the tests: forget the module's state. */
export function _resetWatchSync() { started = false; lastSent = undefined; toggleHandler = null; pluginP = null }
