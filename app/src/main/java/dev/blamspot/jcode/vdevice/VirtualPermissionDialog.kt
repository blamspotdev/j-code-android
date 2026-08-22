package dev.blamspot.jcode.vdevice

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.blamspot.jcode.R

/**
 * The device asking on an app's behalf — what a phone puts up when an app calls
 * `requestPermissions`, at the same moment and for the same reason.
 *
 * ### Why it is drawn here rather than composed by the IDE
 *
 * It used to be a Compose `AlertDialog` in the tab: the request travelled out of `:guest` over the
 * binder, the IDE put it on the screen, and the answer came back. It looked right and it was in the
 * wrong window. Everything that makes the device inspectable stops at the container's edge —
 * `screencap` draws the container, `uiautomator dump` walks it, `input tap` dispatches into it — so
 * a dialog composed *over* the tab was a modal an agent could see in a photograph of the phone and
 * could not answer on the device. An app blocked on a permission it could not be granted is an app
 * that cannot be driven at all, which is the one failure this whole surface exists to prevent.
 *
 * As a child of [EmbeddedGuest]'s container it is device content, and all three follow for free —
 * the same reasoning [VirtualStatusBar] is built on, and the same reasoning that made the device's
 * file picker a real app rather than a drawn screen.
 *
 * ### Modal, and modal on purpose
 *
 * There is no dismiss. The guest is blocked on the answer, and a dialog that could be swiped away
 * would leave an app waiting on a callback that never comes. The scrim takes every touch that is not
 * on a button, so nothing underneath can be pressed while the question is open, and [EmbeddedGuest]
 * routes Back here and drops it.
 *
 * One dialog for the whole request rather than one per permission. The platform asks in sequence;
 * this asks once, because an app that wants the camera and the microphone together is asking one
 * question — "may I do the thing I am for" — and answering it three times is the part of the phone
 * experience nobody was hoping to reproduce.
 */
@SuppressLint("ViewConstructor")
internal class VirtualPermissionDialog(
    context: Context,
    packageName: String,
    permissions: List<String>,
    private val onAnswer: (Boolean) -> Unit,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()

    init {
        setBackgroundColor(SCRIM)
        isClickable = true
        isFocusable = false
        // The app under this must not receive focus changes it cannot act on, and nothing in here
        // needs any: both answers are a tap.
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        contentDescription = "Permission request"
        addView(card(packageName, permissions), LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply {
            marginStart = dp(24f)
            marginEnd = dp(24f)
        })
    }

    private fun card(packageName: String, permissions: List<String>): View {
        val label = VirtualDeviceApps.apk(context, packageName)
            ?.let { VirtualDevice.inspect(context, it.absolutePath).getOrNull()?.label }
            ?: packageName
        val wanted = permissions.map { VirtualDevicePolicy.phrase(context, it) }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, dp(24f))
            setPadding(dp(24f), dp(26f), dp(24f), dp(14f))
        }

        card.addView(shield(), LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // "to <phrase>?", not "to use the <phrase>?" — the platform's labels are verb phrases, so
        // CAMERA's reads "take pictures and videos" and the old wording asked whether to allow an
        // app "to use the take pictures and videos".
        val question = if (wanted.size == 1) {
            "Allow $label to ${wanted.first().replaceFirstChar { it.lowercase() }}?"
        } else {
            "Allow $label to use these?"
        }
        card.addView(
            text(question, 17f, FOREGROUND).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(16f), 0, 0)
                setLineSpacing(dp(3f).toFloat(), 1f)
                contentDescription = question
            },
            wrap(),
        )

        if (wanted.size > 1) {
            wanted.forEach { asked ->
                card.addView(
                    text("•  $asked", 14f, MUTED).apply { setPadding(0, dp(8f), 0, 0) },
                    wrap(),
                )
            }
        }

        card.addView(
            text(
                "This is ${VirtualIdentity.MODEL}'s hardware, not the phone's.",
                12f,
                MUTED,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14f), 0, dp(6f))
            },
            wrap(),
        )

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, 0)
        }
        buttons.addView(button(R.id.vdevice_permission_deny, "Deny", ACCENT, false), grow())
        buttons.addView(button(R.id.vdevice_permission_allow, "Allow", ACCENT, true), grow())
        card.addView(buttons, wrap())
        return card
    }

    /**
     * A shield rather than the requesting app's icon.
     *
     * The icon belongs on a dialog *from* the app; this one is the device speaking about the app,
     * and putting the app's own mark on it invites the reading that the app is the one asking to be
     * trusted with itself.
     */
    private fun shield(): View = ImageView(context).apply {
        setImageResource(R.drawable.ic_vdevice_permission)
        imageTintList = ColorStateList.valueOf(ACCENT)
        val inset = dp(10f)
        setPadding(inset, inset, inset, inset)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(CHIP)
        }
        contentDescription = "Permission"
    }

    private fun button(id: Int, label: String, colour: Int, filled: Boolean): TextView =
        text(label, 14f, if (filled) SURFACE else colour).apply {
            setId(id)
            gravity = Gravity.CENTER
            setPadding(0, dp(13f), 0, dp(13f))
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                rounded(if (filled) colour else Color.TRANSPARENT, dp(12f)),
                rounded(Color.WHITE, dp(12f)),
            )
            isClickable = true
            contentDescription = label
            setOnClickListener { onAnswer(filled) }
        }

    /**
     * Everything that is not a button.
     *
     * Consumed rather than ignored: a `FrameLayout` with nothing clickable under the finger would
     * pass the touch on to the app behind it, so a person could keep using an app that is, as far as
     * it knows, waiting for an answer.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    private fun text(value: String, size: Float, colour: Int) = TextView(context).apply {
        text = value
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    }

    private fun rounded(colour: Int, radius: Int) = GradientDrawable().apply {
        setColor(colour)
        cornerRadius = radius.toFloat()
    }

    private fun wrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun grow() = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
    ).apply {
        marginStart = dp(4f)
        marginEnd = dp(4f)
    }

    private companion object {
        val SCRIM = VirtualPalette.SCRIM
        const val SURFACE = VirtualPalette.SURFACE
        const val CHIP = VirtualPalette.CHIP
        const val FOREGROUND = VirtualPalette.TEXT
        const val MUTED = VirtualPalette.MUTED
        const val ACCENT = VirtualPalette.ACCENT
    }
}
