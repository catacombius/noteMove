# NoteMove

An Android sketchpad in the spirit of **Ableton Note** and **Ableton Move**: make beats and ideas on the
phone, then open them in **Ableton Live** as a real Live Set (`.als`).

It's built for the **Samsung Galaxy Z Fold** family and works on any Android 10+ phone or tablet.

> NoteMove is an independent project. It isn't affiliated with or endorsed by Ableton. It doesn't use
> Ableton's sounds or code; every instrument is synthesised by the app.

## Features

| | Note / Move | NoteMove |
|---|---|---|
| Session View | clips × scenes, scene launch | Tracks × scenes (up to 16 × 32), launch quantization, stop buttons, duplicate / delete clips & scenes |
| Pads | 4×4 drums, in-key melodic pads (Move: 4×8) | Multitouch 4×4 drum pads; isomorphic in-key or chromatic melodic layout; 4×8 Move layout on large screens with 16 velocity pads for the selected drum |
| Step sequencing | 16 step buttons, pages | Move-style 16-step strip (drum pad or held note), full drum step grid, piano roll with velocity and length editing |
| Recording | count-in, overdub, quantize, metronome | Count-in, overdub into looping clips, record quantize, metronome, latency compensation |
| Capture | Move's Capture MIDI | **Capture** turns what you just played into a clip, aligned to the bar grid |
| Note repeat | ✓ | 1/4 … 1/32 and triplets, recorded into the clip |
| Sounds | Drum Rack kits, Drift, Melodic Sampler | 5 synthesised drum kits (19 drum voices); 2-oscillator synth with filter/envelopes/LFO/glide and 8 presets; chromatic sampler |
| Sampling | mic sampling into pads / sampler | Record from the mic or import any audio file into a drum pad or sampler track; auto-trim and normalise |
| Mixer & FX | volume, pan, sends | Volume, pan, mute/solo, meters; per-track filter and drive; shared tempo-synced delay and reverb; master limiter |
| Undo | ✓ | Undo / redo |
| To Live | Ableton Cloud / Move Manager | **Export a zipped Live project** or just the `.als` via the share sheet or save to a folder; **open `.als` files** from Live |
| Controllers | — | USB / Bluetooth MIDI controllers (keyboards, pad controllers, Move or Push in MIDI mode) play the selected track |

### What goes into the Live export

```
<Set> Project/
  <Set>.als                         Live Set: one MIDI track per track, clips in Session View,
                                    scenes laid out in Arrangement View, tempo, key/scale, names, colours, mix
  Samples/Rendered/<track>/…wav     every clip rendered with the phone's sounds as a seamless loop
  Samples/Imported/…wav             samples you recorded or imported
  <Set> Mixdown.wav                 the scenes played in order
  MIDI/…mid                         plain MIDI files (whole song + every clip)
  <Set>.notemove                    the phone project (open it again in NoteMove)
```

Drum tracks use Drum Rack note numbers (pad 1 = C1), so any Drum Rack you drop on the track plays the
beat. On melodic tracks, drop Drift, Wavetable, Simpler or any other instrument. To keep the exact sound
from the phone, drag the rendered loops onto audio tracks.

The `.als` writer follows the Live 11 schema, which Live 12 also opens. The test suite parses each generated
set, checks that every automation id is unique, and round-trips it through the importer. The set has also
been read by an independent `.als` parser.

## Galaxy Z Fold layouts

The app changes its layout as the phone folds, without restarting, so audio keeps playing:

- **Cover screen (folded):** a Note-style layout with one panel at a time (Session, Play, Edit, Sound,
  Mix), a track switcher and a 16-step strip under the pads.
- **Inner screen (unfolded):** the editor or session on one side and a Move-style 4×8 pad surface with
  16 step buttons on the other. In portrait the two are stacked.
- **Tabletop (half-folded, hinge horizontal):** clips and editor on the upper half, pads on the lower half
  lying flat on the table. The split follows the hinge.
- **Book (half-folded, hinge vertical):** editor on the left page, pads on the right page.

## Building

- **Android Studio:** open the folder, let Gradle sync, then run the `app` configuration.
- **Command line:** `./gradlew :app:assembleDebug`. The APK is written to `app/build/outputs/apk/debug/`.
- **CI:** every push runs `.github/workflows/android.yml`, which runs the core tests and attaches debug and
  release APKs to the workflow run as the `NoteMove-apk` artifact. The release APK is signed with the
  debug key so it installs directly; set up your own signing before you publish it.

Requirements: JDK 17, Android SDK 35. The minimum supported Android version is 10 (API 29).

## Project layout

- `core/` is pure Kotlin with no Android code, unit-tested on the JVM. It contains:
  - `model/`: projects, tracks, clips, scales, kits and presets, plus clip operations (quantize, capture…)
  - `dsp/`: oscillators, filters, envelopes, synth, drum voices, sampler, delay, reverb, limiter
  - `engine/AudioEngine.kt`: the real-time sequencer and mixer used for playback and offline rendering
  - `export/`: Live Set exporter and importer, MIDI writer, WAV I/O, offline renderer, project packager
  - `resources/als/`: the Live Set XML fragments. They are regenerated with `tools/make_als_templates.py`.
- `app/` is the Android app. It contains:
  - `audio/`: low-latency `AudioTrack` output, mic recorder, audio decoder
  - `midi/`: MIDI controller input
  - `data/`: project and sample storage
  - `export/`: sharing, saving and import
  - `ui/`: Compose UI and the fold-aware layout (`DeviceLayout.kt`)

Run the tests with `./gradlew :core:test`.
