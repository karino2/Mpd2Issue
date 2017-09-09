package karino2.livejournal.com.mpd2issue

import android.content.SharedPreferences
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.net.Uri
import android.widget.EditText
import java.io.File
import android.widget.Toast
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import okhttp3.OkHttpClient
import okhttp3.Request


class UploaderActivity : AppCompatActivity() {

    var file : File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uploader)

        intent?.let {
            val uristr = intent.getStringExtra("uri_arg")
            if (uristr == null) {
                showMessage("no uri arg. why?")
                finish()
                return
            }
            // Basically, uri is written in prefs. So we do not need arg_uri.
            // I use it just because of checking.so I do not store it.

            val uri = Uri.parse(uristr)
            file = File(uri.getPath())

        }

        (findViewById(R.id.editTextUrl) as EditText).setText(prefs.getString("last_url", "https://github.com/"));

    }

    fun showMessage(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()


    val prefs : SharedPreferences by lazy { LoginActivity.getAppPreferences(this) }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_uploader, menu);
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.menu_item_post -> {
                postToIssue()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        file?.let{ outState.putString("FILE_PATH", file.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val filepath = savedInstanceState.getString("FILE_PATH", null)
        filepath?.let{ file = File(filepath)}
    }

    private fun postToIssue() {
        val issueUrlStr = findETText(R.id.editTextUrl)
        prefs.edit()
                .putString("last_url", issueUrlStr)
                .commit()

        val issueUrl = Uri.parse(issueUrlStr)
        val pathSegs = issueUrl.pathSegments




        if(file == null) {
            showMessage("No file selected.")
            return
        }

        if((pathSegs.size < 3) or ((pathSegs[pathSegs.size-1] != "issues") and (pathSegs[pathSegs.size-2] != "issues"))) {
            showMessage("Invalid issue url: $issueUrlStr")
            return
        }

        if(pathSegs.last() != "issues") {
            showMessage("NYI: update existing issue")
            return
        }
        val owner = pathSegs[pathSegs.size-3]
        val repoName = pathSegs[pathSegs.size-2]

        val apiUri = "https://api.github.com/repos/${owner}/${repoName}/issues"

        val inputStream = file!!.inputStream()
        try {
            val note = Note.fromJson(inputStream)
            postToIssueInternal(apiUri, note)


        } finally {
            inputStream.close()
        }


    }

    private fun postToIssueInternal(apiUri: String, note: Note) {
        // get for debug first.
        val client = OkHttpClient()
        val request = Request.Builder().url(apiUri).build()

        Single.fromCallable { client.newCall(request).execute() }
                .map { resp -> resp.body()!!.string()}
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    body ->
                    showMessage(body)
                }

    }

    private fun findETText(id: Int) = (findViewById(id) as EditText).text.toString()
}
