package dev.blamspot.jcode.vdevice;

/**
 * Guest -> IDE notifications for one embedded session.
 *
 * Deliberately short. Anything the device can draw on its own screen belongs there rather than
 * here: the permission prompt used to come out over this interface for the IDE to compose, and a
 * dialog composed over the tab is one an agent can photograph and cannot tap. What is left is what
 * the IDE genuinely has to know — that there is no longer an app to show.
 */
interface IGuestSessionCallback {
    /** The guest's last activity finished, or the container tore the session down. */
    oneway void onGuestFinished(String reason);
}
