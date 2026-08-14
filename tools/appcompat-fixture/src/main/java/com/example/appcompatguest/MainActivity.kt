package com.example.appcompatguest

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView

/**
 * An AppCompat guest, for the one thing the framework-themed fixtures cannot answer.
 *
 * `AppCompatDelegate` refuses to inflate anything until it can read `windowActionBar` off the
 * activity's *own* theme — the check behind "You need to use a Theme.AppCompat theme (or
 * descendant) with this activity." That attribute is defined in the guest's resource table, so the
 * check passes only if the container has the activity themed against the guest's resources rather
 * than JCode's, and it fails for a theme that merely *looks* applied.
 *
 * Everything on screen is a plain view with an id and a label, which is also what makes this the
 * fixture to point `uiautomator dump` at: every node here has a resource-id to find it by.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Read back through the delegate's own gate: if this resolves, so did createSubDecor.
        val attrs = theme.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.colorAccent))
        val accent = attrs.getColor(0, 0)
        attrs.recycle()

        findViewById<AppCompatTextView>(R.id.theme_readout).text =
            "colorAccent = #${Integer.toHexString(accent).uppercase()}"

        findViewById<Button>(R.id.tap_button).let { button ->
            var taps = 0
            button.setOnClickListener { button.text = "Tapped ${++taps}" }
        }
        findViewById<Button>(R.id.dialog_button).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("AppCompat dialog")
                .setMessage("Inflated by AppCompatDelegate, hosted by the container.")
                .setPositiveButton("Close", null)
                .show()
        }
        findViewById<Button>(R.id.second_button).setOnClickListener {
            startActivity(android.content.Intent(this, SecondActivity::class.java))
        }
        // The two things a driver needs the device's log for, on demand.
        findViewById<Button>(R.id.print_button).setOnClickListener {
            println("appcompat-fixture: println reached the device log")
            System.err.println("appcompat-fixture: and so did stderr")
            runCatching { error("a caught failure") }
                .onFailure { it.printStackTrace() }
        }
        findViewById<Button>(R.id.crash_button).setOnClickListener {
            throw IllegalStateException("appcompat-fixture crashed on purpose")
        }
    }

}

/** A second AppCompat screen, so intra-guest navigation is themed too. */
class SecondActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(
                    TextView(this@SecondActivity).apply {
                        id = R.id.second_readout
                        text = "Second AppCompat activity"
                        textSize = 20f
                    },
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }
}
