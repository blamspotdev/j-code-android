package com.example.midiguest;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.ToneGenerator;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test fixture that answers one question: can a MIDI app be developed and tested inside JCode's
 * virtual-device container?
 *
 * It probes the three things such an app needs — audio out, the MIDI service, and a virtual MIDI
 * device of its own — and reports every result twice, on screen and to logcat under {@link #TAG},
 * so a single screenshot and a single logcat capture describe the whole run.
 *
 * Everything runs itself on a timer from {@code onCreate}: relaying a tap into an embedded guest is
 * a different subsystem from the one under test, and a fixture that needs one cannot distinguish
 * "audio is broken" from "the tap never arrived".
 */
public class MidiMain extends Activity {

    public static final String TAG = "MIDIFIX";

    private static final int RATE = 44100;
    private static final int TONE_HZ = 440;

    /** Long enough that `dumpsys media.audio_flinger` can be sampled while the track is live. */
    private static final int AUTO_TONE_MS = 3000;
    private static final int LONG_TONE_MS = 10000;
    private static final int BUILD_ATTEMPTS = 3;

    private static final String VIRTUAL_PRODUCT = "MIDI Fixture";

    /** The key MidiService files a virtual device's owning {@link ServiceInfo} under. It has no
     *  public constant — {@code MidiDeviceInfo.PROPERTY_SERVICE_INFO} is {@code @hide} — but the
     *  Bundle is handed over already, so reading it needs no reflection and no hidden-API waiver. */
    private static final String PROPERTY_SERVICE_INFO = "service_info";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService audio = Executors.newSingleThreadExecutor();
    private final Map<String, String> sections = new LinkedHashMap<>();

    private TextView report;
    private AudioManager audioManager;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        setContentView(buildUi());

        Log.i(TAG, "===== MIDIFIX run " + System.currentTimeMillis()
                + " pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid()
                + " package=" + getPackageName() + " =====");

        // Posted, not called: the container builds an embedded guest synchronously on its main
        // thread while the IDE process waits, and every probe here is a binder round trip.
        main.post(() -> {
            identity();
            audioProperties();
            midi();
            virtualDevice();
        });

        main.postDelayed(() -> playTone(AUTO_TONE_MS), 1200);
        main.postDelayed(this::toneGenerator, 1200 + AUTO_TONE_MS + 1200);
        main.postDelayed(() -> {
            midi();
            virtualDevice();
            Log.i(TAG, "===== auto sweep done =====");
        }, 1200 + AUTO_TONE_MS + 1200 + 2500);
    }

    @Override
    protected void onDestroy() {
        audio.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- probes

    private void identity() {
        boolean installed;
        try {
            getPackageManager().getPackageInfo(getPackageName(), 0);
            installed = true;
        } catch (Throwable t) {
            installed = false;
        }
        section("WHERE",
                "package  = " + getPackageName()
                        + "\npid/uid  = " + android.os.Process.myPid() + " / " + android.os.Process.myUid()
                        + "\nprocess  = " + processName()
                        + "\nMODEL    = " + Build.MODEL + "  DEVICE=" + Build.DEVICE
                        + "\ninstalled= " + (installed ? "yes (PackageManager knows this package)"
                                                       : "NO (not an installed package)")
                        + "\nfilesDir = " + getFilesDir());
    }

    private void audioProperties() {
        PackageManager pm = getPackageManager();
        int minMono = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int min48 = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        section("AUDIO PROPERTIES",
                "OUTPUT_SAMPLE_RATE      = " + audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                        + "\nOUTPUT_FRAMES_PER_BUFFER= " + audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
                        + "\nnativeOutputSampleRate  = " + AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
                        + "\nminBuffer 44100 mono16  = " + minMono + " bytes"
                        + "\nminBuffer 48000 mono16  = " + min48 + " bytes"
                        + "\nSTREAM_MUSIC volume     = " + volume + " / " + maxVolume
                        + "\nmode                    = " + audioManager.getMode()
                        + "\nFEATURE_AUDIO_LOW_LATENCY= " + pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
                        + "\nFEATURE_AUDIO_PRO       = " + pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)
                        + "\noutputs                 = " + outputDevices());
    }

    private String outputDevices() {
        StringBuilder out = new StringBuilder();
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (out.length() > 0) out.append(", ");
            out.append(deviceType(device.getType()));
        }
        return out.length() == 0 ? "(none)" : out.toString();
    }

    private void midi() {
        PackageManager pm = getPackageManager();
        boolean feature = pm.hasSystemFeature(PackageManager.FEATURE_MIDI);
        MidiManager manager = (MidiManager) getSystemService(MIDI_SERVICE);

        StringBuilder body = new StringBuilder()
                .append("FEATURE_MIDI = ").append(feature)
                .append("\nMidiManager  = ").append(manager == null ? "NULL" : manager.getClass().getName());

        if (manager != null) {
            MidiDeviceInfo[] devices;
            try {
                devices = manager.getDevices();
            } catch (Throwable t) {
                devices = new MidiDeviceInfo[0];
                body.append("\ngetDevices() threw ").append(t);
            }
            body.append("\ngetDevices()  = ").append(devices.length).append(" device(s)");
            for (MidiDeviceInfo info : devices) {
                body.append("\n  ").append(describe(info));
            }
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    body.append("\ngetDevicesForTransport(BYTE_STREAM) = ")
                            .append(manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).size());
                } catch (Throwable t) {
                    body.append("\ngetDevicesForTransport threw ").append(t);
                }
            }
        }
        section("MIDI", body.toString());
    }

    private String describe(MidiDeviceInfo info) {
        Bundle props = info.getProperties();
        String owner = "";
        try {
            ServiceInfo service = props.getParcelable(PROPERTY_SERVICE_INFO);
            if (service != null) owner = " from " + service.packageName;
        } catch (Throwable ignored) {
        }
        return "id=" + info.getId()
                + " type=" + midiType(info.getType())
                + " name=" + props.getString(MidiDeviceInfo.PROPERTY_NAME)
                + " manuf=" + props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
                + " product=" + props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                + " in=" + info.getInputPortCount()
                + " out=" + info.getOutputPortCount()
                + owner;
    }

    /**
     * Whether this app's own {@link FixtureMidiService} became a MIDI device.
     *
     * Two separate answers, because they fail for different reasons: the system's MIDI service only
     * knows about virtual devices whose package is installed, and {@code queryIntentServices} shows
     * whether the manifest entry was ever registered in the first place.
     */
    private void virtualDevice() {
        PackageManager pm = getPackageManager();
        StringBuilder body = new StringBuilder();

        int declared = 0;
        try {
            Intent intent = new Intent("android.media.midi.MidiDeviceService");
            intent.setPackage(getPackageName());
            for (ResolveInfo resolved : pm.queryIntentServices(intent, PackageManager.GET_META_DATA)) {
                declared++;
                body.append("\n  registered: ").append(resolved.serviceInfo.name);
            }
        } catch (Throwable t) {
            body.append("\n  queryIntentServices threw ").append(t);
        }
        body.insert(0, "manifest <service> visible to PackageManager = "
                + (declared > 0 ? declared + " ->" : "NO — the container never registered it"));

        boolean listed = false;
        MidiManager manager = (MidiManager) getSystemService(MIDI_SERVICE);
        if (manager != null) {
            try {
                for (MidiDeviceInfo info : manager.getDevices()) {
                    if (VIRTUAL_PRODUCT.equals(info.getProperties().getString(MidiDeviceInfo.PROPERTY_PRODUCT))) {
                        listed = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        body.append("\n'").append(VIRTUAL_PRODUCT).append("' listed by MidiManager = ")
                .append(listed ? "YES" : "NO");

        section("VIRTUAL MIDI DEVICE", body.toString());
    }

    // ----------------------------------------------------------------- audio

    /**
     * Writes a {@value #TONE_HZ} Hz sine through {@link AudioTrack}, and reports everything that can
     * be observed about whether it reached the mixer: the track's own state, its underrun count, the
     * output it was routed to, and {@code AudioManager.isMusicActive()} sampled mid-tone — the one
     * check here that asks AudioFlinger rather than the track object.
     *
     * The session id is printed because it is the handle that ties this run to a
     * {@code dumpsys media.audio_flinger} capture taken from outside.
     */
    private void playTone(int millis) {
        section("AUDIOTRACK", "running…");
        audio.execute(() -> {
            StringBuilder body = new StringBuilder();
            AudioTrack track = null;
            try {
                int minBuffer = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                int bufferBytes = Math.max(minBuffer, RATE / 5 * 2);

                // Retried, because a cold start can reach here before the audio HAL is ready and one
                // UnsupportedOperationException would otherwise read as "this container cannot play
                // audio". How many attempts it took is reported rather than hidden.
                int attempt = 1;
                for (; ; attempt++) {
                    try {
                        track = buildTrack(bufferBytes);
                        break;
                    } catch (RuntimeException e) {
                        if (attempt == BUILD_ATTEMPTS) throw e;
                        Log.w(TAG, "AudioTrack build attempt " + attempt + " failed", e);
                        Thread.sleep(700);
                    }
                }

                body.append("build attempts  = ").append(attempt).append(" of ").append(BUILD_ATTEMPTS)
                        .append("\ngetState()      = ").append(trackState(track.getState()))
                        .append("\nsessionId       = ").append(track.getAudioSessionId())
                        .append("  (pid ").append(android.os.Process.myPid())
                        .append(" uid ").append(android.os.Process.myUid()).append(')')
                        .append("\nbufferSize      = ").append(bufferBytes).append(" bytes / ")
                        .append(track.getBufferSizeInFrames()).append(" frames")
                        .append("\nsampleRate      = ").append(track.getSampleRate());
                if (Build.VERSION.SDK_INT >= 26) {
                    body.append("\nperformanceMode = ").append(track.getPerformanceMode());
                }
                boolean musicBefore = audioManager.isMusicActive();

                track.play();
                body.append("\nafter play()    = ").append(playState(track.getPlayState()));

                int totalFrames = RATE * millis / 1000;
                short[] chunk = new short[RATE / 10];
                int written = 0;
                int shortWrites = 0;
                boolean musicDuring = false;
                String routed = "?";
                for (int frame = 0; frame < totalFrames; frame += chunk.length) {
                    int count = Math.min(chunk.length, totalFrames - frame);
                    for (int i = 0; i < count; i++) {
                        double phase = 2 * Math.PI * TONE_HZ * (frame + i) / RATE;
                        chunk[i] = (short) (Math.sin(phase) * 0.35 * Short.MAX_VALUE);
                    }
                    int result = track.write(chunk, 0, count);
                    if (result < 0) {
                        body.append("\nwrite() failed  = ").append(result);
                        break;
                    }
                    written += result;
                    if (result < count) shortWrites++;
                    if (frame >= totalFrames / 2 && !musicDuring) {
                        musicDuring = audioManager.isMusicActive();
                        routed = routedDevice(track);
                    }
                }

                body.append("\nmid-tone state  = ").append(playState(track.getPlayState()))
                        .append("\nframes written  = ").append(written).append(" / ").append(totalFrames)
                        .append(shortWrites > 0 ? " (" + shortWrites + " short writes)" : "")
                        .append("\nunderruns       = ").append(track.getUnderrunCount())
                        .append("\nroutedDevice    = ").append(routed)
                        .append("\nisMusicActive   = ").append(musicBefore).append(" before, ")
                        .append(musicDuring).append(" during");
                track.stop();
                body.append("\nAUDIBLE TONE    = ").append(musicDuring
                        ? "AudioFlinger reported music active — the track reached the mixer"
                        : "AudioFlinger did NOT report music active — treat as unproven");
            } catch (Throwable t) {
                Log.e(TAG, "AudioTrack failed", t);
                body.append("\nEXCEPTION       = ").append(t);
            } finally {
                if (track != null) {
                    try {
                        track.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
            String text = body.toString();
            main.post(() -> section("AUDIOTRACK", text));
        });
    }

    private AudioTrack buildTrack(int bufferBytes) {
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    private String routedDevice(AudioTrack track) {
        AudioDeviceInfo device = track.getRoutedDevice();
        if (device == null) return "null (not routed)";
        return deviceType(device.getType()) + " \"" + device.getProductName() + "\"";
    }

    /** A second path to the same speaker: {@link ToneGenerator} is built in native code and mixes
     *  through a stream type rather than an app-owned {@link AudioTrack}. */
    private void toneGenerator() {
        section("TONEGENERATOR", "running…");
        audio.execute(() -> {
            StringBuilder body = new StringBuilder();
            ToneGenerator tones = null;
            try {
                tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
                boolean started = tones.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1200);
                body.append("startTone()   = ").append(started);
                Thread.sleep(600);
                body.append("\nisMusicActive = ").append(audioManager.isMusicActive()).append(" during");
                Thread.sleep(800);
                tones.stopTone();
                body.append("\nresult        = ").append(started
                        ? "constructed and started without error"
                        : "startTone returned false");
            } catch (Throwable t) {
                Log.e(TAG, "ToneGenerator failed", t);
                body.append("\nEXCEPTION     = ").append(t);
            } finally {
                if (tones != null) {
                    try {
                        tones.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
            String text = body.toString();
            main.post(() -> section("TONEGENERATOR", text));
        });
    }

    // ------------------------------------------------------------------- ui

    private ScrollView buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#12161C"));
        root.setPadding(32, 20, 32, 20);
        root.setGravity(Gravity.TOP);

        TextView title = new TextView(this);
        title.setText("MIDI Fixture");
        title.setTextColor(Color.parseColor("#80CBC4"));
        title.setTextSize(20);
        root.addView(title);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("440 Hz", () -> playTone(AUTO_TONE_MS)));
        buttons.addView(button("10 s tone", () -> playTone(LONG_TONE_MS)));
        buttons.addView(button("Beep", this::toneGenerator));
        buttons.addView(button("Re-scan", () -> {
            midi();
            virtualDevice();
        }));
        root.addView(buttons);

        report = new TextView(this);
        report.setTextColor(Color.WHITE);
        report.setTextSize(11);
        report.setTypeface(Typeface.MONOSPACE);
        root.addView(report);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#12161C"));
        scroll.addView(root);
        return scroll;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setPadding(8, 0, 8, 0);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void section(String name, String body) {
        Log.i(TAG, "--- " + name + " ---\n" + body);
        sections.put(name, body);
        StringBuilder all = new StringBuilder();
        for (Map.Entry<String, String> entry : sections.entrySet()) {
            all.append('\n').append(entry.getKey()).append('\n').append(entry.getValue()).append('\n');
        }
        report.setText(all.toString());
    }

    // -------------------------------------------------------------- decoding

    private static String processName() {
        if (Build.VERSION.SDK_INT >= 28) return android.app.Application.getProcessName();
        return "(unknown)";
    }

    private static String trackState(int state) {
        switch (state) {
            case AudioTrack.STATE_INITIALIZED: return "STATE_INITIALIZED";
            case AudioTrack.STATE_NO_STATIC_DATA: return "STATE_NO_STATIC_DATA";
            case AudioTrack.STATE_UNINITIALIZED: return "STATE_UNINITIALIZED";
            default: return "state " + state;
        }
    }

    private static String playState(int state) {
        switch (state) {
            case AudioTrack.PLAYSTATE_PLAYING: return "PLAYSTATE_PLAYING";
            case AudioTrack.PLAYSTATE_PAUSED: return "PLAYSTATE_PAUSED";
            case AudioTrack.PLAYSTATE_STOPPED: return "PLAYSTATE_STOPPED";
            default: return "playState " + state;
        }
    }

    private static String midiType(int type) {
        switch (type) {
            case MidiDeviceInfo.TYPE_USB: return "USB";
            case MidiDeviceInfo.TYPE_VIRTUAL: return "VIRTUAL";
            case MidiDeviceInfo.TYPE_BLUETOOTH: return "BLUETOOTH";
            default: return "type " + type;
        }
    }

    private static String deviceType(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "BUILTIN_SPEAKER";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "WIRED_HEADPHONES";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "WIRED_HEADSET";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "BLUETOOTH_A2DP";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "BLUETOOTH_SCO";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB_DEVICE";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB_HEADSET";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "TELEPHONY";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            default: return "audioType " + type;
        }
    }
}
