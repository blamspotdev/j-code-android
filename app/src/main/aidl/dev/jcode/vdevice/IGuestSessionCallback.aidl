package dev.jcode.vdevice;

/** Guest -> IDE notifications for one embedded session. */
interface IGuestSessionCallback {
    /** The guest's last activity finished, or the container tore the session down. */
    oneway void onGuestFinished(String reason);
}
