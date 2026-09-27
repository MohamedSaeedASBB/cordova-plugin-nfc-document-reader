# Video capture for liveness — feasibility

Written in answer to requirements 1 and 2:
*"دراسة مدى توفر إمكانية إرسال مقطع فيديو لعملية التحقق عوضاً عن الصور الثابتة"* — feasibility of sending a
video clip for verification instead of static images — and *"تقييم إمكانية ضغط الفيديو لتقليص الحجم الإجمالي"*
— evaluating compression to reduce the total size.

**Recommendation: technically straightforward, and worth doing — but not as a replacement for the stills,
and not before the questions in §5 are answered.** A video is more evidence, not better evidence, and it
enlarges what the bank has to hold.

## 1. Feasible? Yes, on both platforms, with no new dependency

| | Android | iOS |
|---|---|---|
| Recorder | `androidx.camera:camera-video` (CameraX `VideoCapture`) | `AVCaptureMovieFileOutput` |
| Already a dependency? | No — one extra CameraX artifact | **Yes** — AVFoundation is already in use |
| Codec | H.264 or HEVC, hardware-encoded | H.264 or HEVC, hardware-encoded |

Both platforms can record while the existing frame analysis runs, so the liveness state machine, the
prompts and the challenge evaluation would not change at all. The video becomes an additional artefact
recorded alongside the decision, not the thing the decision is made from.

Estimated effort: about 3–4 days per platform including the payload plumbing and the retention questions,
assuming §5 is settled first.

## 2. What it would cost in payload size

Measured from two real payloads this plugin produced:

| Payload | Total | Liveness images within it |
|---|---|---|
| `djalal-idv.json` | 985 KB | portrait 86 KB + 2 challenge frames at 79 and 77 KB = **242 KB** |
| `touati_livenessCheck.json` | 632 KB | portrait 46 KB, no challenge frames retained = **46 KB** |

A liveness session runs roughly 10–20 seconds. Hardware H.264 at 720p:

| Setting | Bitrate | 15-second clip | Against 242 KB of stills |
|---|---|---|---|
| 720p, 30fps, high quality | ~4 Mbps | ~7.5 MB | **31× larger** |
| 720p, 24fps, moderate | ~1.5 Mbps | ~2.8 MB | 12× larger |
| 480p, 15fps, aggressive | ~600 kbps | ~1.1 MB | 4.6× larger |
| 480p, 15fps, HEVC | ~350 kbps | ~650 KB | 2.7× larger |

These are estimates from standard encoder bitrates, not measurements — no video path exists yet to
measure. They are the right order of magnitude for planning.

**Base64 adds 33% on top**, as it does for the stills today, because the payload is JSON. A video large
enough to be worth having should be uploaded as binary multipart rather than embedded — which is a change
on the backend as well as the app.

## 3. Compression: what actually helps

In order of effect:

1. **HEVC over H.264** — roughly half the size at the same visual quality. Both platforms encode it in
   hardware. Cost: the backend needs to decode HEVC, and some server-side tooling still does not.
2. **Resolution before frame rate.** Liveness needs a face large enough to see eyes and mouth move; 480p
   is sufficient for that and is a quarter the pixels of 720p. Dropping frame rate below ~15fps starts to
   lose the blink, which is exactly the event that matters.
3. **Trim to the challenge windows.** The plugin already knows precisely when each challenge started and
   finished — that is what `challengeFrames` is built from. Recording only those windows, rather than the
   whole session, cuts most of the footage while keeping every moment that carries evidence. This is the
   single biggest saving available and it is specific to this design.
4. **Do not send a video at all when the check passed cleanly.** Send it only on failure, or on a sample
   of sessions for audit. Most of the cost is paid for sessions nobody will ever watch.

A sensible target: **480p HEVC, 15fps, trimmed to challenge windows ≈ 200–400 KB per session**, which is
comparable to what the challenge stills already cost.

## 4. What a video buys, honestly

It buys **auditability**: a human reviewer, or a later dispute, can see what happened rather than reading
a verdict. For a regulated onboarding that is a real benefit and is probably the actual motivation behind
the request.

It does **not** buy better presentation-attack detection by itself. The current check is defeated by a
replayed video, an injected camera feed or a 3D mask, and recording the session does not change that — the
same attack is simply recorded. Detecting those needs different work: texture and moiré analysis, depth
where the hardware has it, and challenge timing that a replay cannot anticipate. A video makes
*after-the-fact* review possible, which is worth having, but it should not be presented to a risk
committee as a stronger control than it is.

## 5. Questions to settle before building it

These are policy, not engineering, and they are why this is a recommendation rather than a change already
made:

1. **Retention.** A video of a customer's face is biometric personal data. How long is it kept, where, and
   who can view it? The stills already raise this; a video raises it further.
2. **Consent.** Does the existing onboarding consent cover recording and storing video, or does the
   wording need to change?
3. **Upload path.** Multipart binary rather than base64 in JSON, which is a backend change.
4. **Failure behaviour.** A 2 MB upload on a weak branch connection will sometimes fail. Does a failed
   video upload fail the onboarding, or is the video best-effort while the stills remain authoritative?
5. **Always, or on exception?** §3.4 — this decides most of the cost.

## 6. Recommended shape, if it goes ahead

- Record **in addition to** the stills, never instead of them. The stills are what the face match runs on
  and what the backend compares to DG2; that path should not depend on a video decoder.
- 480p, 15fps, HEVC, trimmed to the challenge windows.
- Returned as a file path or a binary handle, not base64 in the JSON payload.
- Off by default, enabled per-session by the app — so the decision to record is the bank's, taken
  deliberately, and visible in the call.
