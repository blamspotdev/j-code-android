package dev.blamspot.jcode.vdevice

import android.content.Context
import kotlin.random.Random

/** One network the device's Wi-Fi can see. [level] is 0–4, the scale `WifiManager` reports on. */
internal data class WifiNetwork(val ssid: String, val level: Int, val secured: Boolean)

/** One thing the device's Bluetooth can see. [kind] is what it is, in the word a person would use. */
internal data class NearbyDevice(val name: String, val kind: String, val paired: Boolean)

/**
 * What the device's radios have around them.
 *
 * A simulated radio with nothing in range is a switch and a label. Turning Wi-Fi on gave a device
 * that was *on the network* and could not say what it was on, which reads as a screen that has not
 * been finished — there is nowhere for "which network am I on" to come from, no list to look at, and
 * nothing that changes when the switch does.
 *
 * So the device has surroundings: a handful of networks with names, signal strengths and locks, and
 * a few Bluetooth things in range. They are **generated once and kept**, in the policy file, which
 * puts them in the volatile tree — a new set of neighbours every time JCode starts, the same set for
 * as long as that device lives, and a `Scan again` that draws new ones on purpose. Generating them
 * on every read would be a list that reshuffled while somebody looked at it.
 *
 * ### What an app on the device sees
 *
 * **Nothing, and that is not this file's doing.** `WifiManager` could not be stood in for — measured,
 * and written up in the spec — so a guest's scan goes to the phone's manager, and JCode holds no
 * location permission, which is what `getScanResults` requires: it answers with an empty list, and
 * `getConnectionInfo().getSSID()` answers `<unknown ssid>`. A guest therefore learns neither these
 * names nor the real ones, which is the right end state for the real ones. These exist for the
 * device's own screens — its Settings app and the bench — the same way the camera's scene does.
 */
internal object VirtualRadios {

    fun wifi(context: Context): List<WifiNetwork> {
        VirtualDevicePolicy.radioState(context, WIFI_SCAN)?.let { stored ->
            decodeWifi(stored).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return generateWifi().also { store(context, WIFI_SCAN, encodeWifi(it)) }
    }

    /** The network the device is on, or null when it is on none of them. */
    fun connected(context: Context): WifiNetwork? {
        val networks = wifi(context)
        val chosen = VirtualDevicePolicy.radioState(context, WIFI_SSID)
        return networks.firstOrNull { it.ssid == chosen } ?: networks.firstOrNull()
    }

    fun connect(context: Context, ssid: String) {
        store(context, WIFI_SSID, ssid)
    }

    fun bluetooth(context: Context): List<NearbyDevice> {
        VirtualDevicePolicy.radioState(context, BLUETOOTH_DEVICES)?.let { stored ->
            decodeDevices(stored).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return generateDevices().also { store(context, BLUETOOTH_DEVICES, encodeDevices(it)) }
    }

    fun setPaired(context: Context, name: String, paired: Boolean) {
        val updated = bluetooth(context).map { if (it.name == name) it.copy(paired = paired) else it }
        store(context, BLUETOOTH_DEVICES, encodeDevices(updated))
    }

    /**
     * New neighbours.
     *
     * Wi-Fi keeps whichever network was chosen only if it is still in range — which it usually is
     * not, and being dropped off the network by a scan is exactly what happens when a device is
     * carried somewhere else.
     */
    fun rescanWifi(context: Context) {
        val networks = generateWifi()
        store(context, WIFI_SCAN, encodeWifi(networks))
        val chosen = VirtualDevicePolicy.radioState(context, WIFI_SSID)
        if (networks.none { it.ssid == chosen }) store(context, WIFI_SSID, networks.first().ssid)
    }

    /** New neighbours, keeping whatever was paired: a pairing outlives being out of range. */
    fun rescanBluetooth(context: Context) {
        val paired = bluetooth(context).filter { it.paired }
        val found = generateDevices().filterNot { device -> paired.any { it.name == device.name } }
        store(context, BLUETOOTH_DEVICES, encodeDevices(paired + found))
    }

    // --- generating ------------------------------------------------------------------------------

    /**
     * A believable street's worth of Wi-Fi: mostly locked, one open, strongest first.
     *
     * The names are the shapes real ones have — a router's default with its hex tail, a place, a
     * household — without being anybody's. A generated SSID that read like a real address would be
     * a worse kind of realism.
     */
    private fun generateWifi(): List<WifiNetwork> = buildList {
        val names = ROUTER_NAMES.shuffled().take(2).map { "$it${hex(4)}" } +
            PLACE_NAMES.shuffled().take(Random.nextInt(3, 5))
        names.shuffled().forEachIndexed { index, ssid ->
            add(
                WifiNetwork(
                    ssid = ssid,
                    // Descending with a little jitter, so the list is ordered the way a scan is
                    // without every device having the same shape of signal.
                    level = (4 - index / 2 - Random.nextInt(0, 2)).coerceIn(0, 4),
                    // One open network, because "which of these is not secured" is a thing somebody
                    // building against a captive portal wants to see.
                    secured = index != names.lastIndex,
                ),
            )
        }
        sortByDescending { it.level }
    }

    private fun generateDevices(): List<NearbyDevice> = DEVICE_KINDS
        .shuffled()
        .take(Random.nextInt(3, 5))
        .map { (name, kind) -> NearbyDevice(name = "$name ${hex(2)}", kind = kind, paired = false) }

    private fun hex(length: Int): String =
        (1..length).map { HEX[Random.nextInt(HEX.length)] }.joinToString("")

    // --- storage ---------------------------------------------------------------------------------

    private fun store(context: Context, key: String, value: String) {
        VirtualDevicePolicy.setRadioState(context, key, value)
    }

    // Field-and-record separators rather than JSON: this is a properties file, the values are
    // generated here, and `clean` keeps a separator from ever reaching one.
    private fun encodeWifi(networks: List<WifiNetwork>): String = networks.joinToString(RECORD.toString()) {
        "${clean(it.ssid)}$FIELD${it.level}$FIELD${it.secured}"
    }

    private fun decodeWifi(stored: String): List<WifiNetwork> = stored.split(RECORD).mapNotNull {
        val parts = it.split(FIELD)
        if (parts.size != 3) return@mapNotNull null
        WifiNetwork(
            ssid = parts[0],
            level = parts[1].toIntOrNull()?.coerceIn(0, 4) ?: return@mapNotNull null,
            secured = parts[2].toBooleanStrictOrNull() ?: return@mapNotNull null,
        )
    }

    private fun encodeDevices(devices: List<NearbyDevice>): String = devices.joinToString(RECORD.toString()) {
        "${clean(it.name)}$FIELD${clean(it.kind)}$FIELD${it.paired}"
    }

    private fun decodeDevices(stored: String): List<NearbyDevice> = stored.split(RECORD).mapNotNull {
        val parts = it.split(FIELD)
        if (parts.size != 3) return@mapNotNull null
        NearbyDevice(
            name = parts[0],
            kind = parts[1],
            paired = parts[2].toBooleanStrictOrNull() ?: return@mapNotNull null,
        )
    }

    private fun clean(value: String): String = value.filterNot { it == FIELD || it == RECORD }

    private const val FIELD = '|'
    private const val RECORD = ';'
    private const val HEX = "0123456789ABCDEF"

    private const val WIFI_SCAN = "wifi/scan"
    private const val WIFI_SSID = "wifi/ssid"
    private const val BLUETOOTH_DEVICES = "bluetooth/devices"

    private val ROUTER_NAMES = listOf("TP-Link_", "ASUS_", "NETGEAR-", "Linksys_", "ZTE_", "Fibre-")

    private val PLACE_NAMES = listOf(
        "Cafe Guest",
        "Library Wi-Fi",
        "Coworking 5G",
        "Upstairs",
        "Back Office",
        "Studio",
        "Workshop",
        "Guest Network",
        "Meeting Room",
        "Rooftop",
    )

    private val DEVICE_KINDS = listOf(
        "Wireless Earbuds" to "Audio",
        "Desk Speaker" to "Audio",
        "Fitness Band" to "Wearable",
        "Smart Watch" to "Wearable",
        "Folding Keyboard" to "Input",
        "Travel Mouse" to "Input",
        "Car Audio" to "Audio",
        "Game Controller" to "Input",
    )
}

/** How strong a signal reads as, in the words a phone uses rather than a number nobody converts. */
internal fun signalLabel(level: Int): String = when (level) {
    4 -> "Excellent"
    3 -> "Good"
    2 -> "Fair"
    1 -> "Weak"
    else -> "Very weak"
}
