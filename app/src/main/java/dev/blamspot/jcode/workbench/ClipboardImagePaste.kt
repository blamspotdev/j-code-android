package dev.blamspot.jcode.workbench

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import java.io.ByteArrayOutputStream

/*
 * Pasting an image into an extension's web UI.
 *
 * An Android WebView never surfaces a clipboard *image* to the page: Chromium's paste path carries
 * text, so a web app listening for `paste` and reading `clipboardData.files` receives nothing no
 * matter how the user pastes. The image has to be read natively and handed to the page.
 *
 * It is delivered as a real `paste` ClipboardEvent carrying a File, rather than as a path or a
 * bespoke bridge call, because that is the event a web chat UI (OpenChamber's, driving opencode)
 * already listens for — it needs no cooperation from the extension.
 *
 * Images arrive by two different routes, and only one of them is the system clipboard:
 *
 *  - **The keyboard.** Gboard pastes an image through the Commit Content API
 *    (`InputConnection.commitContent`), never through `primaryClip` — its clipboard tab holds
 *    screenshots the system clipboard does not. A view receives those only if it advertises the MIME
 *    types in its `EditorInfo`, which is what [NoFullscreenWebView] does. This is the route that
 *    actually works on a phone.
 *  - **`ClipData`.** Ctrl+V and the panel's "Paste image" read `primaryClip`, which covers apps that
 *    do put an image URI there (Chrome's "Copy image").
 *
 * Both end up in [pasteImageUri].
 */

/** Whether the clipboard currently holds something this can paste. Cheap: no decoding. */
internal fun hasClipboardImage(context: Context): Boolean = clipboardImageUri(context) != null

private fun clipboardImageUri(context: Context): Uri? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
    val uri = clip.getItemAt(0).uri ?: return null
    val declaredImage = clip.description?.hasMimeType("image/*") == true
    val resolvedImage = runCatching { context.contentResolver.getType(uri) }
        .getOrNull()?.startsWith("image/") == true
    return uri.takeIf { declaredImage || resolvedImage }
}

/**
 * Reads the clipboard image as base64 PNG, or null when the clipboard holds no image (or it can't
 * be decoded).
 *
 * Downscaled past [MAX_EDGE_PX] because the bytes cross into JS as a base64 string through
 * `evaluateJavascript` — a full-resolution screenshot would be several MB of string, and no model
 * consuming the image needs that.
 */
private fun encodeImageAsBase64Png(context: Context, uri: Uri): String? {
    return runCatching {
        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input ?: return null)
        } ?: return null
        val scaled = bitmap.downscaledTo(MAX_EDGE_PX)
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }.onFailure {
        // An image was offered but we cannot read it — most often a content:// URI whose read grant
        // does not extend to this app. Worth a line: the paste otherwise just does nothing, with no
        // way to tell that from "the page ignored it".
        android.util.Log.w(TAG, "image could not be read: $it")
    }.getOrNull()
}

private const val TAG = "ClipboardImagePaste"

private fun Bitmap.downscaledTo(maxEdge: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return this
    val ratio = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
}

/**
 * Delivers the clipboard image to [this] page as a `paste` event. Returns false when the clipboard
 * holds no image, so the caller can fall through to normal (text) paste handling.
 */
internal fun WebView.pasteClipboardImage(): Boolean {
    val uri = clipboardImageUri(context) ?: return false
    return pasteImageUri(uri)
}

/**
 * Delivers the image at [uri] to [this] page as a `paste` event. Returns false when it can't be read.
 *
 * This is the shared tail of both routes — the keyboard's `commitContent` and the system clipboard.
 */
internal fun WebView.pasteImageUri(uri: Uri): Boolean {
    // Decoded on the calling thread on purpose: a keyboard's commitContent only holds the URI read
    // grant for the duration of that call, so the bytes have to be read before returning.
    val base64 = encodeImageAsBase64Png(context, uri) ?: return false
    // Base64 is [A-Za-z0-9+/=] only, so a plain single-quoted literal cannot break out.
    val js = pasteImageJs(base64)
    // ...but the injection itself is posted: commitContent arrives on the InputConnection's own
    // handler thread, and every WebView method has to run on the thread that created the WebView.
    if (Looper.myLooper() == Looper.getMainLooper()) evaluateJavascript(js, null) else post {
        evaluateJavascript(js, null)
    }
    return true
}

/**
 * Builds the File from base64 with `atob` rather than `fetch(dataUrl)`: these pages are served over
 * https with a Content-Security-Policy, and a `data:` fetch is exactly what `connect-src` blocks.
 *
 * Dispatched at each document's focused element with `bubbles`, so a listener on the element, the
 * document or the window all see it. Same-origin iframes are included because a webview UI commonly
 * renders its content in one, and the top document's activeElement is then just the frame.
 */
private fun pasteImageJs(base64: String): String = """
(function () {
  try {
    var bin = atob('$base64');
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    var docs = [document];
    var frames = document.querySelectorAll('iframe');
    for (var f = 0; f < frames.length; f++) {
      try { if (frames[f].contentDocument) docs.push(frames[f].contentDocument); } catch (e) {}
    }
    var delivered = 0;
    for (var d = 0; d < docs.length; d++) {
      var doc = docs[d];
      var file = new File([bytes], 'pasted-image.png', { type: 'image/png' });
      var dt = new DataTransfer();
      dt.items.add(file);
      var target = doc.activeElement || doc.body;
      if (!target) continue;
      target.dispatchEvent(new ClipboardEvent('paste', {
        clipboardData: dt, bubbles: true, cancelable: true,
      }));
      delivered++;
    }
    console.log('[jcode] pasted clipboard image into ' + delivered + ' document(s)');
  } catch (e) {
    console.error('[jcode] clipboard image paste failed: ' + e);
  }
})();
""".trimIndent()

/** Longest edge kept when pasting; larger images are downscaled to it. */
private const val MAX_EDGE_PX = 1600
