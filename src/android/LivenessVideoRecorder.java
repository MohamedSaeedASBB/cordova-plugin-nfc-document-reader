package com.nfcdocumentreader;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Records the liveness session with CameraX, as a third use case bound beside the preview and the
 * frame analysis.
 *
 * TRIMMING
 * Most of a session is the customer reading a prompt and deciding to move. That footage is the bulk
 * of the file and carries none of the evidence, so the recording is paused between challenges.
 * CameraX stitches the timeline across a pause itself, which is the one part of this that is easier
 * here than on iOS.
 *
 * WHAT THIS CANNOT DO THAT THE iOS SIDE CAN
 * {@code Recorder} in camera-video 1.3.x does not expose the codec, so Android records H.264 where
 * iOS records HEVC. At the same visual quality that is roughly twice the bytes. Resolution and
 * bitrate are controlled here, which recovers most of the difference; the rest is a library
 * limitation and is reported in the payload as the codec, so a backend can see which it received.
 */
class LivenessVideoRecorder {

    private static final String TAG = "LivenessVideo";
    /** Long enough for the muxer to finalise a short clip, short enough not to hang a screen. */
    private static final long FINALISE_TIMEOUT_SECONDS = 8;

    static class Options {
        /** SD is 480p. HD would be four times the pixels for footage of one face. */
        Quality quality = Quality.SD;
        int bitrate = 900_000;
        boolean trimToChallenges = true;
    }

    static class Output {
        final File file;
        final long bytes;
        final long durationMs;
        final String codec;
        final boolean trimmed;

        Output(File file, long bytes, long durationMs, String codec, boolean trimmed) {
            this.file = file;
            this.bytes = bytes;
            this.durationMs = durationMs;
            this.codec = codec;
            this.trimmed = trimmed;
        }
    }

    private final Options options;
    private final Executor mainExecutor;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private File file;

    private boolean active = false;
    private boolean finalised = false;
    private long durationNanos = 0L;
    private final CountDownLatch finaliseLatch = new CountDownLatch(1);

    LivenessVideoRecorder(Context context, Options options) {
        this.options = options;
        this.mainExecutor = ContextCompat.getMainExecutor(context);
    }

    /** The use case to bind alongside the preview and the analyser. */
    VideoCapture<Recorder> useCase() {
        if (videoCapture == null) {
            Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(options.quality))
                    .setTargetVideoEncodingBitRate(options.bitrate)
                    .build();
            videoCapture = VideoCapture.withOutput(recorder);
        }
        return videoCapture;
    }

    /**
     * Starts recording to a temporary file. Audio is never requested: a liveness check has nothing
     * to learn from it, and recording a customer's voice is a consent question nobody asked.
     */
    @SuppressLint("MissingPermission")
    void start(Context context) {
        if (recording != null || videoCapture == null) return;
        try {
            file = File.createTempFile("liveness-", ".mp4", context.getCacheDir());
            FileOutputOptions outputOptions = new FileOutputOptions.Builder(file).build();

            recording = videoCapture.getOutput()
                    .prepareRecording(context, outputOptions)
                    .start(mainExecutor, this::onRecordEvent);

            // Trimming means the useful windows are opened by resume(); start paused so the
            // reading time before the first challenge is not in the file.
            if (options.trimToChallenges) {
                recording.pause();
                active = false;
            } else {
                active = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not start recording: " + e.getClass().getSimpleName());
            recording = null;
        }
    }

    /** Opens a challenge window. */
    void resume() {
        if (recording == null || !options.trimToChallenges || active) return;
        try {
            recording.resume();
            active = true;
        } catch (Exception e) {
            Log.w(TAG, "Resume failed: " + e.getClass().getSimpleName());
        }
    }

    /** Closes a challenge window. */
    void pause() {
        if (recording == null || !options.trimToChallenges || !active) return;
        try {
            recording.pause();
            active = false;
        } catch (Exception e) {
            Log.w(TAG, "Pause failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Stops and waits for the muxer to finalise the file.
     *
     * Blocking is deliberate: this is called from the background executor that builds the payload,
     * and the file is not readable until the finalise event arrives. A video that fails to write
     * returns null and the liveness result goes out without it — a recording problem must never
     * fail a check that passed.
     */
    Output stopAndAwait() {
        if (recording == null) return null;
        try {
            recording.stop();
            if (!finaliseLatch.await(FINALISE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Recording did not finalise in time");
                return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Stop failed: " + e.getClass().getSimpleName());
            return null;
        } finally {
            recording = null;
        }

        if (!finalised || file == null || !file.exists() || file.length() == 0) return null;
        return new Output(file, file.length(),
                TimeUnit.NANOSECONDS.toMillis(durationNanos),
                "h264", options.trimToChallenges);
    }

    /**
     * Removes the file. The payload carries the bytes, so the copy in the cache is temporary — and
     * it is video of a customer's face, which should not outlive the call that produced it.
     */
    static void discard(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete the recording");
        }
    }

    private void onRecordEvent(@NonNull VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
            finalised = !finalize.hasError();
            if (!finalised) {
                Log.w(TAG, "Recording finalised with error code " + finalize.getError());
            }
            durationNanos = finalize.getRecordingStats().getRecordedDurationNanos();
            finaliseLatch.countDown();
        } else if (event instanceof VideoRecordEvent.Status) {
            durationNanos = event.getRecordingStats().getRecordedDurationNanos();
        }
    }
}
