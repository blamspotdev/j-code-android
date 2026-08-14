package dev.jcode.vdevice;

/** Guest -> IDE notifications for one embedded session. */
interface IGuestSessionCallback {
    /** The guest's last activity finished, or the container tore the session down. */
    oneway void onGuestFinished(String reason);

    /**
     * The guest asked for permissions the device has not decided about, and the person at the
     * keyboard has to. The IDE puts them on the screen and answers with
     * IGuestSession.permissionResult under the same requestId; the guest is waiting on it, so an
     * answer that never comes is an app that never gets its callback.
     */
    oneway void onPermissionRequest(int requestId, in String[] permissions, String packageName);
}
