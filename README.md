# SORA Studio

**A professional, fully offline music production DAW for Android — written in 100% Kotlin.**
No C++, no C, no JNI, no NDK, no account, no cloud, no subscription.

Create beats, compose melodies, record vocals, arrange multitrack projects, mix with real DSP
effects, automate parameters, master and export finished audio — entirely on your device.

---

## Highlights

| Area | What's inside |
| --- | --- |
| **Audio engine** | Custom pull-based engine on `AudioTrack` (float PCM, low-latency mode) + `AudioRecord`. One renderer drives both real-time playback and offline exports, so what you hear is what you export. Sample-accurate MIDI/drum scheduling inside each block, loop playback, metronome, live audition, global reverb/delay send buses, master chain + safety limiter. |
| **Arrangement** | Multitrack timeline (audio / vocal / instrument / drum / sampler tracks), clip move/trim/split/duplicate, fades, clip gain & pitch, mute/solo/lock, track colours, markers, loop region, snap-to-grid, zoom, undo/redo for every edit. |
| **Piano roll** | Draw/move/resize notes, velocity lane, scale highlighting, quantize, humanize, transpose, chord stamping, arpeggiator and chord-progression generators, clip-scoped playhead. |
| **Drum machine** | 16/32-step sequencer, 9 lanes, per-step velocity/probability/pitch, swing, 4 generated kits (Studio, 808, Lo-Fi, Electro), custom lane samples, groove presets, pattern library (Room). |
| **Instruments** | 21 synthesized presets (piano, EP, organ, basses, **808 with glide**, leads, pads, plucks, bells, strings, guitars) — PolyBLEP oscillators, FM, additive harmonics, ADSR, per-voice filter. Sampler with root-note pitching. Playable multi-touch keyboard, MIDI capture-to-clip, USB/BLE MIDI input (Android MIDI API). |
| **Recording** | PCM recording with live waveform/level, input gain, optional monitoring, count-in, punch-in at playhead, loop-take slicing, take history with preview & placement. |
| **Vocal studio** | Guided chain (EQ → gate → de-esser → compressor → saturation), presets, **live auto tune**, offline **pitch-correction render** (key/scale/strength/speed), noise-reduction render, pitch detection readout. |
| **Effects (23)** | 3-band / parametric / 8-band EQ, HP/LP filters, compressor, limiter, gate, de-esser, Freeverb reverb, delay + ping-pong, chorus, flanger, phaser, tremolo, auto-pan, distortion, saturation, bit crusher, stereo width (M/S + bass mono), pitch shift, live auto tune — all real Kotlin DSP with presets, wet/dry, reordering and automation. |
| **Mixer** | Per-track fader/pan/meters (peak + RMS), mute/solo/mono, two sends, master strip with limiter toggle. Every control changes the actual audio. |
| **Automation** | Lanes for volume, pan, sends, synth cutoff/resonance and **any effect parameter**, linear/smooth/step curves, touch-write recording at the playhead. |
| **Audio editor** | Waveform editing: trim, split workflow, reverse, fades, gain, normalize, silence trim, offline pitch shift, time stretch, pitch correction — always non-destructive to the original file. |
| **Export** | Real offline rendering: WAV 16/24-bit & 32-bit float, 44.1/48/96 kHz, stereo/mono, normalize, **stems**, single-track renders, M4A (AAC via MediaCodec), MP3/FLAC when the device ships an encoder (clearly reported otherwise), Standard MIDI File export/import. |
| **Projects** | Versioned JSON format (`.soraproj`), transactional saves (temp → backup → rename), autosave, crash recovery, templates, favourites, duplicate/delete, Room index. |

All bundled sounds are **synthesized in code** (drum one-shots and instrument presets are generated
at runtime), so there are no third-party sample licenses to worry about and the APK stays small.

---

## Architecture

Clean, modular, Hilt-injected. The audio engine is fully independent of the UI;
feature screens are state-driven composables fed by a single `ProjectSession`
(the live document) and a `PlaybackController` (transport/audition).

```
:app                      Application, MainActivity, navigation, DI, MainViewModel
:core:common              Music theory, tempo/dB/pan math (pure JVM)
:core:dsp                 Buffers, biquads, 23 effects, synths, drum synth, pitch tools, WAV codec (pure JVM)
:core:midi                Notes, scheduler, quantize/humanize/arpeggiator/chords, SMF read/write (pure JVM)
:core:project             Versioned project model, edit ops, templates, session + undo (pure JVM)
:core:database            Room: project index, presets, patterns, recordings, favourites
:core:storage             Transactional project files, sample library, DataStore settings
:core:audio               AudioTrack engine, AudioRecord recorder, MediaCodec decode/encode, renderer, MIDI devices
:core:designsystem        SORA theme + studio controls (knobs, faders, meters, waveform, keyboard)
:feature:home             Home, projects, new-project dialog
:feature:studio           Studio shell: transport, tempo/loop/metronome, undo/redo, tabs
:feature:arrangement      Multitrack timeline
:feature:pianoroll        Piano roll
:feature:drums            Step sequencer
:feature:instruments      Presets, synth editor, keyboard, MIDI capture
:feature:recording        Recording studio + audio editor
:feature:vocal            Vocal chain, auto tune, tuned-take rendering
:feature:mixer            Mixer
:feature:effects          Insert-effect editor
:feature:automation       Automation lanes
:feature:sounds           Offline sound library
:feature:export           Export & mastering
:feature:settings         Settings (DataStore)
```

**Key versions** (stable, mutually compatible): Kotlin 1.9.24 · AGP 8.5.2 · Gradle 8.7 ·
Compose BOM 2024.09.00 · Hilt 2.51.1 · Room 2.6.1 · DataStore 1.1.1 · Coroutines 1.8.1 ·
kotlinx.serialization 1.7.1 · compile/target SDK 34 · min SDK 26.

### Audio rendering model

`ProjectRenderer.render()` is a pure function of *(project, position, live events)*:

1. Per block, each track renders its clips: MIDI notes and drum steps are scheduled at
   exact frame offsets; audio clips are copied sample-accurately with fades/gain.
2. Insert FX → volume/pan (constant-power) → meter → master sum + send buses.
3. Send reverb/delay → master FX → safety limiter → float PCM out.

Offline export reuses the exact same function at the chosen sample rate.

---

## Build & install

**Requirements:** Android Studio (Koala or newer) with JDK 17 and Android SDK 34.

```bash
git clone https://github.com/surafel5509-del/Sora-Studio.git
cd Sora-Studio
./gradlew assembleDebug          # build debug APK
./gradlew installDebug           # install on a connected device/emulator
./gradlew test                   # unit tests (DSP, MIDI, project, music theory)
```

In Android Studio: *File → Open*, select the project folder, let Gradle sync, press **Run ▶**.

**CI:** every push runs unit tests + debug APK build on GitHub Actions
(`.github/workflows/android.yml`) and uploads the APK as the
`sora-studio-debug-apk` artifact. A manual *workflow_dispatch* run additionally
executes the Compose UI tests on an emulator.

---

## Permissions & privacy

- `RECORD_AUDIO` — only when you press Record (requested at runtime with an explanation).
- No `INTERNET` permission — the app cannot phone home, by design.
- Files live in app-specific storage; sharing uses `FileProvider` + the Storage Access Framework.

## Honest limitations

- **Monitoring latency** while recording depends on the phone's audio path (typically 100–300 ms
  without a low-latency path). Use wired headphones; the app always requests the low-latency
  performance mode when available.
- **MP3/FLAC export** require a device encoder (most phones ship AAC only). The Export screen
  detects this and tells you, instead of faking success. WAV and M4A always work.
- **Live auto tune** is a light, low-latency correction for monitoring; the Vocal Studio's
  *Render Tuned Take* does a fuller offline correction. Neither claims to be a studio tuning suite.
- Sampler-track note timing inside a block is accurate to within one audio block (≤512 frames);
  instrument tracks are sample-accurate.
- Time stretching uses OLA/WSOLA-style processing — musical results within roughly 0.5×–2×.

## License

Project code: MIT (see repository). All bundled sounds are generated by the app itself
(synthesized drums/presets) and are royalty-free for any use.
