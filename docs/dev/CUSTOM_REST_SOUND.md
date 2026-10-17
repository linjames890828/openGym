# Proposal: your own sound for the end of a rest

**Status: proposal, nothing implemented.** A map of how this would fit the code as it stands, for
whoever picks it up. See [`CLAUDE.md`](../../CLAUDE.md) for the general architecture.

## What it adds

Settings → Timer alerts → Sound today offers six built-in end tones (`chime`, `classic`, `bell`,
`beep`, `whistle`, `soft`, #306). This adds a seventh row, **Your own sound…**: pick an audio
file from the device (a voice saying "next set!", a gong, a clip of a song) and the end of every
rest, hold and "switch sides" plays it instead of a built-in tone.

Nothing else changes: the 3-2-1 countdown beeps stay as they are, "Play a sound" still turns all
of it off, and vibration and the screen flash are untouched.

## How the end tone is played today

- **The table.** `lib/rest-sounds.js` `REST_SOUNDS` holds the six tones as data (notes, peak,
  hold, timbre). `restSoundOf(S)` reads the choice from `S.restSound` (and the legacy
  `S.classicChime`) and falls back to `chime` for anything it does not know.
- **On screen, in a browser or on iPhone.** `store/useUI.js` `endTone` → `lib/sound.js`
  `chime(enabled, kind)`, Web Audio oscillators. There are no audio files in the app at all.
- **On screen, Android app.** `endTone` → `lib/rest-alert.js` `toneNative(kind)` →
  `RestAlertPlugin.tone` → `RestAlert.playNow`, which renders the same tone in
  `RestTone.java` (PCM16 mono, 22050 Hz) and plays it with `AudioTrack`, ducking music around it
  (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`). Falls back to the page's `chime` if that fails.
- **Locked / in the background, Android app.** `buildRestAlert` puts `tone: kind` into the
  alarm; `RestAlert.fire` → `playSound(ctx, toneOf(intent))`, the same native path.
  `RestTone.render` plays the chime for a name it does not know.
- **Locked, iPhone / browser.** Server web push (`api/push-messages.js`, `public/sw.js`) with the
  system's own notification sound. No app tone plays there today, built-in or not.
- **Watch.** `frontend/android/wear` only vibrates.

## The design

### 1. The file is re-encoded on the device into one fixed format

The picked file is decoded with Web Audio `decodeAudioData` (mp3, m4a/AAC and wav decode in every
target browser and WebView), then written back as **WAV, PCM16 mono, 22050 Hz**: the exact
format `RestAlert.playClip` already plays. This is the same idea as photos, which are re-encoded
on the device before they are stored (`media-limits.js` `imageMB`).

While it is decoded the file is also:

- **trimmed**: leading silence cut (below about −50 dBFS), then capped at **5 s**. An end tone
  that runs into the next set is a bug, not a feature. The editor shows the cut and lets you
  pick where the 5 s start, the way a video's poster frame is picked.
- **peak-normalised** to the level of the built-in `chime` (`CHIME_PEAK`), so a quiet recording
  is not lost under music and a loud one does not jump out. That is the complaint #306's louder
  chime answered (Discord: "Rest Timer Sound Notification too Quiet").

The result is at most 5 × 22050 × 2 bytes ≈ **215 KB**, whatever was picked.

What this buys:

- one stored type, so no codec question on any platform, now or later;
- Android plays it with **no new player code**: the WAV's samples go straight into the existing
  `playClip`, so ducking, the alarm stream, the silent-tail logic and the
  "wait until it was heard" loop all come for free;
- a size small enough that it never needs the metered-connection rules
  (`BG_UPLOAD_METERED_MAX_MB`);
- **no new dependency** on either side: the WAV writer is about 30 lines next to `lib/sound.js`.

### 2. It is stored like a custom exercise's photo

The media pipeline built for custom-exercise photos and videos already does everything this needs:
`lib/media-store.js` (IndexedDB in a browser, `Library/opengym-media/` on a phone),
`lib/media-sync.js` (upload, sha256-checked download to other devices, offline), the server's
`api/media.js` (type sniffing, caps, quota, garbage collection) and `lib/backup-media.js` (zip
backups). The sound becomes one more file in it:

```js
S.restSound = 'custom'
S.restSoundFile = { hash, mime: 'audio/wav', size, sec, name }   // a MediaRef; name is shown, never trusted
```

Changes needed:

| Where | What |
|---|---|
| `lib/media-refs.js` `MEDIA_MIMES` + `api/media.js` `MEDIA_TYPES` | add `audio/wav` (`kind: 'audio'`, `ext: 'wav'`) |
| `lib/media-sniff.js` + the server's sniffer | recognise `RIFF....WAVE`, and accept only PCM16 mono 22050 Hz, no other chunks after `data` |
| `lib/media-refs.js` `stateMediaRefs` + `api/media.js` `referencedHashes` | also walk `S.restSoundFile`, or the server's GC deletes it; extend the shared fixture `api/test/fixtures/media-refs.json` so both sides keep agreeing |
| `lib/media-limits.js` + `api/media.js` `mediaLimits` | `soundSec: 5`, `soundKB: 256` (an `.env` override like the other `MEDIA_*` caps) |
| `lib/backup-media.js` | nothing beyond the above: it follows `referencedFiles` |

Because the ref lives in the synced state, every signed-in device (and a paired phone) gets the
same sound, and a backup with media brings it back.

### 3. Playing it

- **`lib/rest-sounds.js`.** `custom` is not a row of `REST_SOUNDS` (it has no notes, and
  `rest-sounds.test.js` checks that table against `RestTone.java` number for number).
  `restSoundOf(S)` returns `'custom'` only when `S.restSound === 'custom'` and
  `S.restSoundFile` passes `normalizeMediaRef`; otherwise it falls through to the existing rules.
- **Page (`lib/sound.js`).** A new `playFile(hash)` reads the WAV from `mediaStore`, decodes it
  once (cached `AudioBuffer`) and plays it through the same `AudioContext`, `wake()` and
  `sleepAfter`, so the iPhone "Play even on silent" and unlock handling apply unchanged.
  `chime(enabled, 'custom')` calls it; **if the file is missing** (not synced to this device
  yet, store blocked in a private window) it plays the `chime` instead. A rest never ends in
  silence because of this feature.
- **Android, on screen.** `toneNative('custom', path)` passes the file's path in the phone's
  media folder (`<files dir>/opengym-media/<hash>.wav`, from `media-store-fs.js`).
  `RestAlert.playNow` reads the samples (the header is fixed, so this is an offset and a
  length check) and calls `playClip`. A file that is missing or does not pass the check →
  `RestTone.render("chime")`.
- **Android, locked.** `buildRestAlert` adds `toneFile: path` next to `tone: 'custom'`;
  `RestAlert.fire` does the same as above.
- **iPhone / browser, locked.** Unchanged: the system notification sound, as for every built-in
  tone today. The editor's hint says so ("Plays while the app is open").
- **Watch.** Unchanged: it vibrates.

### 4. Older apps and older APKs see a chime

- An app version from before this, on another device, reads `restSound: 'custom'`:
  `restSoundOf` does not know it → `chime`.
- An APK from before this is sent `tone: 'custom'`: `RestTone.render` does not know it → chime.
  It never reads `toneFile`.
- A server from before this refuses the upload (unknown type). The file stays on the device as
  `rejected`, exactly as an oversized photo does, and plays there; other devices play the chime
  until the server is updated.

No migration, and nothing an older version could break.

### 5. Settings UI

In `RestSoundSheet` (`views/Settings.jsx`), under the six built-in tones:

- **Your own sound…** opens the file picker (`accept="audio/*"`). After decoding: a waveform
  strip with the 5 s window to drag, a ▶ preview, and **Use this sound**.
- Once one is set, the row shows its name and length ("next-set.m4a · 2.4 s") with ▶,
  **Replace** and **Remove**. Remove goes back to `chime` and drops the ref; the media GC
  takes the file.
- Tapping a built-in tone keeps the custom file in the state, so switching back costs nothing.
- Refusals, in plain words: "Couldn't read this file" (decode failed), "This file has no
  sound" (silent after trimming).

New locale keys go into every pack in `frontend/src/locales/` as usual.

## Tests

Following CONTRIBUTING.md, the logic is pure helpers with tests beside them:

- `lib/wav.test.js`: the encoder (header bytes, mono downmix, resample, 5 s cap, silence trim,
  peak normalisation) and the parser/validator the sniffer uses.
- `lib/rest-sounds.test.js`: `restSoundOf` with `custom` (valid ref, broken ref, missing ref,
  together with legacy `classicChime`).
- `lib/media-refs.test.js` + `api` tests: `restSoundFile` is referenced (shared fixture), so GC
  and sync keep it.
- `store/useUI.native-rest.test.js`: `custom` goes native with its path, and falls back to the
  chime when the file is missing.
- `lib/rest-alert.test.js`: `buildRestAlert` carries `toneFile` for `custom` only.
- `views/Settings.sound.test.jsx`: pick, preview, remove; a built-in pick keeps the file.
- Java: a `RestToneTest` case that a well-formed WAV plays its samples and a bad one plays the
  chime.

## Not in scope

- Customising the 3-2-1 countdown beeps (`useUI.js` `beep(snd, 660, 0.1)`): a separate, smaller
  change.
- A volume slider.
- A custom sound when the iPhone is locked. That needs a native iOS rest alarm (a local
  notification whose sound file lives in `Library/Sounds`), which the app does not have for any
  tone yet. The 22050 Hz PCM WAV stored here is a format iOS notification sounds accept, so this
  design does not block it.
- Sound on the watch.

## Open questions

1. **5 s cap**: long enough for a voice line, short enough not to run into the set. Should a
   self-hoster be able to raise it, or is a fixed cap simpler?
2. **One sound or one per routine**: start with one per profile; a per-routine sound could later
   reuse the same ref shape.
3. **Server without media storage**: when `config.media` is off, keep the file on the device only
   (like local mode) or hide the row? Keeping it local seems right: the setting is about this
   phone's alarm anyway.
