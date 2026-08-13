package dev.jcode.vdevice

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** How good the device says its fix is. Metres, and deliberately GPS-plausible rather than perfect. */
private const val SIMULATED_ACCURACY = 5f

/** How often a registered listener is told where the device is. */
private const val UPDATE_MS = 1_000L

/** The providers a simulated device offers. `passive` is included: apps ask for it by name. */
private val PROVIDERS = listOf(
    LocationManager.GPS_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
    LocationManager.PASSIVE_PROVIDER,
    LocationManager.FUSED_PROVIDER,
)

/**
 * The device's own GPS: a fix the user typed, reported to a guest as though a receiver had produced
 * it.
 *
 * There is no passthrough mode here and there never will be. A guest is somebody else's APK running
 * inside the IDE, under J Code's uid, and the single most valuable thing it could steal is where the
 * user is standing. So the phone's real location is not one of the choices — the device either has
 * no location hardware at all, or it has one that always says the same thing.
 *
 * ### Where the seam is, and where it is not
 *
 * `LocationManager` is final, so it cannot be subclassed the way [GuestSensorManager] subclasses
 * `SensorManager`. It is a thin client over one binder, so the obvious move is to replace that
 * binder — but the field holding it, `LocationManager.mService`, is **blocked** at `targetSdk` 33.
 * Measured on Android 13 from inside a guest: `LocationManager.class.getDeclaredFields()` answers
 * with the public `String` constants and *nothing else*, which is what a denied member looks like
 * from here. So there is no instance to patch.
 *
 * What is reachable is the step before the instance exists. `SystemServiceRegistry` builds the one
 * `LocationManager` each `ContextImpl` gets by asking `ServiceManager.getService("location")` and
 * wrapping the result with `ILocationManager.Stub.asInterface` — and `ServiceManager.sCache` is
 * consulted first, is greylisted, and is writable. A local `Binder` carrying our own
 * `ILocationManager` as its interface put there before any guest starts means every location
 * manager built in this process afterwards is a genuine, complete client object that happens to be
 * talking to us. `asInterface` hands back the local object rather than a binder proxy, so not one
 * call leaves the process.
 *
 * The handler **never delegates**. An unmodelled method answers with nothing rather than falling
 * through to the real location service, because falling through is precisely the failure this
 * guards against: one forgotten method is one route to the user's coordinates. Nothing is lost by
 * it — the honest answer for a device with no receiver *is* nothing.
 */
internal object GuestLocation {

    private const val DESCRIPTOR = "android.location.ILocationManager"

    @Volatile
    private var installed = false

    /**
     * Puts the device's own location service in front of the phone's, for this process.
     *
     * Called from [GuestRuntime.install], which is before any guest exists and therefore before
     * anything in `:guest` has asked for a `LocationManager`. False leaves guests with the phone's
     * location service — which still refuses them everything, because J Code holds no location
     * permission, so the failure is "no simulated location" rather than "the user's coordinates".
     */
    @Synchronized
    fun install(context: Context): Boolean {
        if (installed) return true
        return runCatching {
            val iface = HiddenApi.classOrNull(DESCRIPTOR) ?: return false
            val manager = HiddenApi.classOrNull("android.os.ServiceManager") ?: return false
            @Suppress("UNCHECKED_CAST")
            val cache = HiddenApi.field(manager, "sCache")?.get(null) as? MutableMap<String, IBinder>
                ?: return false
            val answers = Proxy.newProxyInstance(
                GuestLocation::class.java.classLoader,
                arrayOf(iface),
                Answers(context.applicationContext),
            ) as IInterface
            // A local binder, so `Stub.asInterface` finds the interface attached to it and returns
            // that object directly instead of building a proxy around a remote one.
            cache[Context.LOCATION_SERVICE] = Binder().apply { attachInterface(answers, DESCRIPTOR) }
            installed = true
            Log.i(TAG, "location service replaced for the virtual device")
            true
        }.onFailure {
            Log.w(TAG, "cannot replace the location service; guests get the phone's, which refuses them", it)
        }.getOrDefault(false)
    }

    /** Where the device says it is, as a fix an app can read. */
    private fun fix(context: Context): Location = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = VirtualDevicePolicy.simulatedLatitude(context)
        longitude = VirtualDevicePolicy.simulatedLongitude(context)
        accuracy = SIMULATED_ACCURACY
        time = System.currentTimeMillis()
        // Consumers reject a fix without this — it is how they tell a fresh one from a replay, and
        // AndroidX's location helpers throw on a Location that has never had it set.
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private class Answers(private val context: Context) : InvocationHandler {

        private val handler = Handler(Looper.getMainLooper())

        /** Listener transports the guest has registered, and the ticker feeding each. */
        private val feeds = HashMap<Any, Runnable>()

        /**
         * Method names already reported.
         *
         * The device answers a fixed set and returns nothing for the rest, and from the outside
         * "nothing" is indistinguishable from "this device has never heard of that call" — so each
         * name is written down the first time it is asked for, once, and the log says which of the
         * two it was. A guest that quietly gets no location is otherwise a silent failure with
         * nowhere to start looking.
         */
        private val seen = HashSet<String>()

        private val enabled: Boolean
            get() {
                val guest = GuestRuntime.activePackage() ?: return false
                return VirtualDevicePolicy.mode(context, guest, VirtualHardware.Location) !=
                    HardwareMode.Off
            }

        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            val arguments: Array<Any?> = args ?: emptyArray()
            synchronized(seen) {
                if (seen.add(method.name)) Log.i(TAG, "location: guest asked for ${method.name}")
            }
            return when (method.name) {
                "getAllProviders", "getProviders" -> if (enabled) PROVIDERS else emptyList<String>()
                "getBestProvider" -> if (enabled) LocationManager.GPS_PROVIDER else null
                "hasProvider" -> enabled && arguments.filterIsInstance<String>().firstOrNull() in PROVIDERS
                "isProviderEnabledForUser", "isProviderEnabled" -> enabled
                "isLocationEnabledForUser", "isLocationEnabled" -> enabled
                "getLastLocation" -> if (enabled) GuestLocation.fix(context) else null
                "getCurrentLocation" -> deliverOnce(arguments)
                "registerLocationListener", "requestLocationUpdates" -> feed(arguments)
                "unregisterLocationListener", "removeUpdates" -> stop(arguments)
                // A provider's properties are a hidden parcelable the container cannot build, and
                // every caller of this is written for the null a nonexistent provider returns.
                "getProviderProperties" -> null
                else -> emptyValue(method.returnType)
            }
        }

        /** `getCurrentLocation`: one fix, then done. Returns the cancellation transport, which is null. */
        private fun deliverOnce(args: Array<Any?>): Any? {
            if (!enabled) return null
            val consumer = args.firstOrNull { CALLBACK?.isInstance(it) == true } ?: return null
            handler.post { send(consumer, CALLBACK_DESCRIPTOR) { it.writeTypedObject(fix(context), 0) } }
            return null
        }

        /**
         * `requestLocationUpdates`: the same fix, again, at a steady tick.
         *
         * A simulated device does not move, so every update carries identical coordinates and only
         * the timestamps advance — which is exactly what a phone standing still on a windowsill
         * reports, and what an app watching for movement should make of it.
         */
        private fun feed(args: Array<Any?>): Any? {
            val listener = args.firstOrNull { LISTENER?.isInstance(it) == true }
            if (listener == null) {
                Log.w(
                    TAG,
                    "location: no listener to feed among " +
                        args.joinToString { it?.javaClass?.name ?: "null" },
                )
                return null
            }
            if (!enabled) return null
            synchronized(feeds) {
                if (feeds.containsKey(listener)) return null
                val tick = object : Runnable {
                    override fun run() {
                        if (!enabled) {
                            synchronized(feeds) { feeds.remove(listener) }
                            return
                        }
                        deliver(listener)
                        handler.postDelayed(this, UPDATE_MS)
                    }
                }
                feeds[listener] = tick
                handler.post(tick)
            }
            return null
        }

        private fun stop(args: Array<Any?>): Any? {
            val listener = args.firstOrNull { LISTENER?.isInstance(it) == true } ?: return null
            synchronized(feeds) { feeds.remove(listener) }?.let(handler::removeCallbacks)
            return null
        }

        /**
         * Hands one fix to a listener transport, in the shape `onLocationChanged` has taken since
         * Android 12: a list of locations, and a completion callback the caller may ignore. The
         * device's `minSdk` is later than the single-`Location` form, so there is only one shape to
         * write.
         */
        private fun deliver(listener: Any) {
            send(listener, LISTENER_DESCRIPTOR) { parcel ->
                parcel.writeTypedList(listOf(fix(context)))
                // The completion callback the listener may be told to fire. Nothing here is waiting
                // to hear that the delivery landed, and the parameter is `@nullable`.
                parcel.writeStrongBinder(null)
            }
        }
    }

    /**
     * Calls the guest's callback binder by transaction rather than by method.
     *
     * There is no method to call. Everything `ILocationListener` and `ILocationCallback` declare is
     * blocked to reflection — measured on Android 13, `ILocationListener.class.getMethods()` offers
     * exactly one member, `asBinder`, inherited from the public `IInterface`. So the interface
     * cannot be invoked through, its `Stub` cannot be reached around, and the transport's own class
     * is hidden too, with its members filtered out of the same list.
     *
     * What remains is what a binder is *for*. `IBinder.transact` is public API, so is `Parcel`, and
     * the transport is a local `Binder` — the call goes straight into its `onTransact`, which
     * unmarshals and invokes the method itself. The parcel has to be written exactly as the
     * generated stub reads it: the interface token, then each argument in declaration order.
     *
     * [FIRST_CALL] is [IBinder.FIRST_CALL_TRANSACTION], and both interfaces declare the method used
     * here first. A platform that reorders them would break this, and say so: `onTransact` answers
     * false for a code it does not know, which is logged rather than swallowed.
     */
    private fun send(callback: Any, descriptor: String, arguments: (Parcel) -> Unit) {
        val binder = callback as? IBinder ?: return
        val parcel = Parcel.obtain()
        runCatching {
            parcel.writeInterfaceToken(descriptor)
            arguments(parcel)
            if (!binder.transact(FIRST_CALL, parcel, null, IBinder.FLAG_ONEWAY)) {
                Log.w(TAG, "location: $descriptor refused transaction $FIRST_CALL")
            }
        }.onFailure {
            Log.w(TAG, "cannot hand the device's fix to ${callback.javaClass.name}", it)
        }
        parcel.recycle()
    }

    private const val FIRST_CALL = IBinder.FIRST_CALL_TRANSACTION
    private const val LISTENER_DESCRIPTOR = "android.location.ILocationListener"
    private const val CALLBACK_DESCRIPTOR = "android.location.ILocationCallback"

    /**
     * The two callback interfaces a guest hands over, resolved once.
     *
     * Both the object *and* the method have to be found through the interface rather than through
     * the object's own class, and that is not a style choice. The transport `LocationManager` passes
     * in is `LocationManager$LocationListenerTransport`, a hidden nested class — and reflection over
     * a hidden class hands back a *filtered* member list, so `transport.javaClass.methods` does not
     * contain `onLocationChanged` at all. Measured: the registration arrived, the transport was in
     * the arguments, and looking it up by what it could be told found nothing.
     *
     * `Class.isInstance` asks about the object rather than about its members, and a `Method` taken
     * off the interface dispatches to the implementation the same way a call through the interface
     * would. Neither goes near the filtered list.
     */
    private val LISTENER = HiddenApi.classOrNull("android.location.ILocationListener")
    private val CALLBACK = HiddenApi.classOrNull("android.location.ILocationCallback")
}
