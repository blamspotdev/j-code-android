package com.example.midiguest;

import android.media.midi.MidiDeviceService;
import android.media.midi.MidiDeviceStatus;
import android.media.midi.MidiReceiver;
import android.util.Log;

/**
 * A virtual MIDI device, the kind a real MIDI app publishes so other apps can play into it.
 *
 * Nothing here is interesting; its whole job is to be <em>declared</em>. The system's MIDI service
 * finds virtual devices by scanning installed packages for this service's meta-data, so whether
 * {@code MidiManager} ever lists "JCode MIDI Fixture" answers whether a container that does not
 * install the guest can host one at all.
 */
public class FixtureMidiService extends MidiDeviceService {

    @Override
    public MidiReceiver[] onGetInputPortReceivers() {
        return new MidiReceiver[] {
            new MidiReceiver() {
                @Override
                public void onSend(byte[] msg, int offset, int count, long timestamp) {
                    Log.i(MidiMain.TAG, "virtual device received " + count + " MIDI bytes");
                }
            }
        };
    }

    @Override
    public void onDeviceStatusChanged(MidiDeviceStatus status) {
        Log.i(MidiMain.TAG, "virtual device status: " + status);
    }
}
