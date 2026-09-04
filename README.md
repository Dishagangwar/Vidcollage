# VidCollage

Finds every unique person in a portrait video and builds one shareable collage per clip, with each
person shown exactly once alongside how many times they appeared. Everything runs on device: face
detection, face embeddings, clustering, shot selection and rendering. There is no backend and no
network call anywhere in the app.

## At a glance

| | |
|---|---|
| **Embedding model** | FaceNet (Inception-ResNet-v1), `app/src/main/assets/facenet.tflite` |
| **Model input / output** | 160 × 160 × 3 float RGB → 128-D, L2-normalised |
| **Model size** | 23,705,216 bytes (23.7 MB), bundled in the APK, memory-mapped at runtime |
| **Face detection** | ML Kit `face-detection` 16.1.7, bundled — no Google Play services needed |
| **Similarity metric** | Cosine similarity of unit-length embeddings (a plain dot product) |
| **Identity threshold** | **`MERGE_SIMILARITY = 0.55`** — two clusters are the same person above this |
| **Tracking threshold** | `MIN_SIMILARITY = 0.45` — a detection may continue an existing appearance above this |
| **Language / UI** | Kotlin, XML views, Material 3, `minSdk 26`, `targetSdk 37` |

Both thresholds are justified by measurement in [Choosing the similarity threshold](#choosing-the-similarity-threshold).

## Build and setup

### Prerequisites

| | |
|---|---|
| **JDK** | 25 — pinned in `gradle/gradle-daemon-jvm.properties`. Gradle downloads a matching toolchain automatically if you do not have one. |
| **Android SDK** | Platform **API 37** and platform-tools. Install via Android Studio's SDK Manager or `sdkmanager "platforms;android-37" "platform-tools"`. |
| **Gradle / AGP** | Gradle 9.5 (via the committed wrapper — do not install it yourself) and AGP 9.3.2. |
| **Device** | Any device or emulator on **API 26+**. Both face models ship inside the APK, so no Play services and no network are required. |

### Point the build at your SDK

`local.properties` is gitignored, so create it after cloning:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS
echo "sdk.dir=$HOME/Android/Sdk"        > local.properties   # Linux
```

Setting the `ANDROID_HOME` environment variable instead works just as well.

### Build a debug APK

```bash
./gradlew :app:assembleDebug
```

The APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Build and install in one step

```bash
./gradlew :app:installDebug          # builds, installs on the connected device
adb shell am start -n com.example.vidcollage/.MainActivity
```

Or install an APK you already have:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No signing configuration is needed — the debug build uses the standard debug keystore, which Gradle
creates on first use.

### Using the app

Tap **Choose videos**, pick up to five clips, and each one gets its own card: the people it found,
their appearance counts, the collage, and **Save** / **Share**. Tap any face for a sheet showing
every moment that person is on screen and why their shot was picked; tap the collage for a
full-screen view. Saving writes to `Pictures/VidCollage/`; sharing goes through the standard Android
share sheet via a `FileProvider`.

## How it works

The whole pipeline lives in [`pipeline/`](app/src/main/java/com/example/vidcollage/pipeline) and runs
on `Dispatchers.Default` from [`CollageViewModel`](app/src/main/java/com/example/vidcollage/CollageViewModel.kt).
The UI only ever observes a `StateFlow<ProcessingState>`, so the main thread never touches a frame.

### 1. Sample frames — `FrameSampler`

`MediaMetadataRetriever.getScaledFrameAtTime` every **200 ms**, decoded straight to a display-oriented
bitmap no larger than **960 px** on the long side. One frame is in memory at a time.

200 ms is fast enough that a one-second appearance still yields five samples, and 960 px was chosen by
measurement: dropping from 1280 px cut ML Kit's per-frame cost from ~350 ms to ~120 ms on the test
device without changing any of the counts, and a 960 px frame still leaves plenty of pixels for a
generous collage crop.

### 2. Detect faces — ML Kit

`PERFORMANCE_MODE_ACCURATE` with landmarks and classification on, `minFaceSize = 0.09`. Two things
happen on top of the raw results:

- **Duplicate suppression.** ML Kit sometimes reports one face as a nest of two or three overlapping
  boxes. Left alone those become *simultaneous* appearances, which the clusterer is then forbidden to
  merge (see below), and one person turns into three — this actually happened on the test clip. Boxes
  overlapping more than 60% of the smaller one are collapsed to the outermost.
- **A blur gate.** A whip-pan smears every face in the frame, and the brief says those frames count
  for nobody. Faces whose Laplacian variance falls below `BLUR_FLOOR` are dropped before they can
  start or extend an appearance.

### 3. Embed — FaceNet via TensorFlow Lite

Each face is cropped with the eyes levelled (rotation taken from the eye landmarks, falling back to
ML Kit's roll angle), padded slightly past the detection box, and resized to the model's input size.
The crop is standardised per image ("prewhitening", the preprocessing FaceNet was trained with), and
the resulting vector is L2-normalised so similarity is a plain dot product.

**Model:** `app/src/main/assets/facenet.tflite` — Inception-ResNet-v1 FaceNet, **160×160×3 float
input, 128-D output**, 23.7 MB, taken from
[shubham0204/FaceRecognition_With_FaceNet_Android](https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android)
(Apache-2.0), itself a TFLite conversion of the Keras port of the original FaceNet weights.

The interpreter runs with 4 threads, and the asset is memory-mapped rather than copied onto the heap
(which is why `noCompress += "tflite"` is set in `app/build.gradle.kts` — a compressed asset cannot be
mapped). The input size and embedding length are read off the interpreter at runtime rather than
hardcoded, so swapping in a different embedding network is a matter of replacing the asset.

### 4. Group into appearances — `AppearanceTracker`

An *appearance* is one continuous visible segment. ML Kit only issues tracking ids in stream mode, and
we deliberately decode sampled frames instead of a live stream, so the association is done here: a
detection continues an appearance when it looks like the same face (cosine similarity ≥
`MIN_SIMILARITY` against the appearance's running centroid) in roughly the same place (box overlap and
proximity), and the segment has not been silent for longer than `MAX_GAP_MS`. Matching is greedy on a
combined affinity score — 0.55 similarity, 0.25 overlap, 0.20 proximity — one detection per appearance
per frame. Segments seen in only one frame are discarded as flicker.

The 700 ms gap tolerance is what lets a blink, a brief occlusion, or a blurred pan sit *inside* one
appearance rather than splitting it in two.

### 5. Cluster into people — `FaceClusterer`

Average-linkage agglomerative clustering over appearance centroids, merging while the best pair is
above `MERGE_SIMILARITY`. Two deliberate departures from the textbook version:

- **Clustering runs on appearances, not on individual faces.** A centroid averaged over a whole
  segment is far steadier than any single frame, so one bad detection cannot split a person.
- **Appearances that share a moment get a cannot-link constraint.** Two faces visible in the same
  frame are by definition two different people, so those clusters are never merged no matter how
  similar they look. This is what makes the "A and B share the frame" case come out as one appearance
  each rather than one person.

### 6. Pick a representative shot — `FaceQuality`

Every detection is scored, and each appearance keeps only its best frame (as a bitmap already cropped
for the collage, so at most one crop per appearance is ever held). The weights:

| Component | Weight | Source |
|---|---|---|
| Frontality | 0.30 | ML Kit head Euler angles — yaw 0.5, pitch 0.3, roll 0.2 |
| Sharpness | 0.25 | Variance of the Laplacian over the centre of the aligned crop |
| Eyes open | 0.20 | Lower of ML Kit's two eye-open probabilities |
| Smiling | 0.15 | ML Kit smiling probability |
| Face size | 0.10 | Face height as a fraction of the frame |

Two multiplicative penalties on top: **×0.55** when the face box runs into the frame border (cut-off
face) and **×0.80** when another face is close enough to crowd into the tile. Both are penalties
rather than hard rejections, so a person who is *only* ever clipped or *only* ever in a two-shot still
gets a tile. This breakdown is what the per-person bottom sheet renders as bars.

Because sharpness is measured on a fixed-size aligned crop, the number is comparable between a face
filling the frame and one far away — which is what makes it usable both for scoring and as the blur
gate in step 2.

### 7. Crop and render — `FaceCrops`, `CollageRenderer`

Tiles are cut at **2.2×** the detection box, never tighter than 1.35×, so they keep hair, chin and
background instead of being a low-resolution crop of the bounding box. When another face is nearby the
crop pulls back only far enough to keep it out of the shot.

The collage is a 1080×1920 story card: gradient ground, header with the clip name and the totals,
then a grid whose column count and tile aspect ratio are chosen to fill the card rather than leave the
top and bottom empty. Each tile carries a scrim, the person's label, their appearance count, and a
count badge.

## Tuning constants

Every threshold lives in a named constant next to the code that uses it. The ones that matter:

| Constant | Value | Where |
|---|---|---|
| `SAMPLE_INTERVAL_MS` | 200 | `FrameSampler` |
| `MAX_LONG_SIDE` | 960 | `FrameSampler` |
| `MIN_FACE_SIZE` | 0.09 | `VideoProcessor` |
| `BLUR_FLOOR` | 80 | `VideoProcessor` |
| `MAX_BOX_CONTAINMENT` | 0.6 | `VideoProcessor` |
| `MAX_GAP_MS` | 700 | `AppearanceTracker` |
| `MIN_FRAMES` | 2 | `AppearanceTracker` |
| `MIN_SIMILARITY` | 0.45 | `AppearanceTracker` |
| `MIN_AFFINITY` | 0.45 | `AppearanceTracker` |
| **`MERGE_SIMILARITY`** | **0.55** | **`FaceClusterer`** |

### Choosing the similarity threshold

`MERGE_SIMILARITY = 0.55` is the one number that decides whether two appearances are the same person,
so it was picked from a measurement rather than guessed, and the measurement is kept as an instrumented
test (`EmbeddingSeparationTest`) so it fails if the model or the preprocessing ever drifts.

The test embeds two different photographs of each of three people and reports the full similarity
matrix. Reproduce it with:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.vidcollage.pipeline.EmbeddingSeparationTest
adb logcat -d -s EmbeddingSeparation:I
```

Latest run on a Pixel 8a emulator:

| Pair type | Similarities | Range |
|---|---|---|
| Same person | 0.682, 0.772, 0.871 | **0.68 – 0.87** |
| Different people | −0.149 … 0.291 (12 pairs) | **≤ 0.29** |

0.55 sits inside a gap running from 0.29 to 0.68: it clears the hardest different-person pair by 0.26
and sits 0.13 below the weakest same-person pair. These are photographs of the same person taken years
apart, which is a far harder case than two frames from a single clip — within one video the
same-person similarities are higher still, so the practical margin is wider than the table suggests.

`MIN_SIMILARITY = 0.45` is deliberately looser than `MERGE_SIMILARITY`: the tracker also has box
overlap and proximity to lean on, and being slightly generous there is safe because clustering runs
afterwards and can still merge two fragments of one person.

`BLUR_FLOOR = 80` was calibrated the same way. `BlurGateTest` compares each crisp portrait against a
40 px horizontal smear of itself, roughly one frame of a fast pan. Crisp faces measure **1123–2332**;
the smeared versions measure **34–75**. 80 rejects the smear with room to spare while leaving crisp
faces untouched.

## Tests

```bash
./gradlew :app:testDebugUnitTest          # 21 JVM tests
./gradlew :app:connectedDebugAndroidTest  # 4 instrumented tests, needs a device or emulator
```

**JVM** — the tracker's temporal logic (continuous segments, gap splitting, blink tolerance, two
people in one frame, flicker rejection), the clusterer (merging, separation, the cannot-link
constraint, ordering), the quality scoring, and the embedding vector maths.

**Instrumented** — the two calibration tests above and a collage rendering test that checks every
group size the grid has a case for. A fourth, `VideoPipelineEndToEndTest`, runs the entire pipeline
over a real clip and **skips unless you supply one**:

```bash
adb push your_clip.mp4 /sdcard/Android/data/com.example.vidcollage/files/testclip.mp4
```

It was developed against a 28-second portrait clip with known ground truth — three people, four
appearances each, two of them sharing the frame at 10.1–11.7 s — and the pipeline reproduces all
three counts and all twelve time windows exactly.

## Performance

Per frame on a Pixel 8a emulator (arm64, Android 37): decode ~400 ms, detection ~120 ms, embedding
~35 ms. Decode dominates and barely moves with resolution, because `MediaMetadataRetriever` re-seeks
and re-decodes from the preceding keyframe for every sample. On real hardware, where that seek is
cheap, the same clip runs several times faster.

The obvious next optimisation is to replace the retriever with a `MediaExtractor` + `MediaCodec`
pass that decodes the stream once sequentially and keeps every *n*th frame. That is the right fix and
would cut the dominant cost, but it is a decoder rewrite with real surface-format risk, and the brief
asks to prioritise a working end-to-end flow — so it is written down here rather than half-built.

## APK size

The debug APK is ~80 MB, and all of it is models and native code:

| Contents | Size |
|---|---|
| `assets/facenet.tflite` (stored uncompressed so it can be mapped) | 23.7 MB |
| ML Kit bundled face detector, native libs × 4 ABIs | ~33 MB |
| TensorFlow Lite native libs × 4 ABIs | ~15 MB |
| Everything else (dex, resources) | ~16 MB |

Nothing is downloaded at runtime, which is the trade being made. A release build with ABI splits or an
App Bundle drops the per-device download to roughly a third, since only one ABI ships to each device.

## Known limitations

- Faces that ML Kit cannot detect at all — extreme profiles, very small faces, heavy occlusion — are
  invisible to everything downstream.
- `MERGE_SIMILARITY` is a single global threshold. Identical twins, or one person under dramatically
  different lighting across a clip, will land on the wrong side of it.
- Appearance counting is quantised to the 200 ms sampling grid: a segment shorter than ~400 ms can
  fall below the two-frame minimum and be dropped as flicker.
- Results live in memory only; nothing is persisted across process death.
