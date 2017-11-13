package karino2.livejournal.com.mpd2issue

import android.content.*
import android.widget.Toast


/**
 * Created by _ on 2017/11/13.
 */
class CopyIdReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val idString = intent.getStringExtra("issueid")
        // ClipData clip = ClipData.newRawUri("result", Uri.parse(uriString));
        val clip = ClipData.newPlainText("result", idString)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(context, "Copied: " + idString, Toast.LENGTH_SHORT).show()
    }

}