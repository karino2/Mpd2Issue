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
import com.google.gson.stream.JsonWriter
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import okhttp3.*
import java.io.StringWriter

fun String?.baseName() : String? {
    if(this == null)
        return null
    val pos = this.lastIndexOf(".")
    return if(pos == -1) null else this.substring(0, pos)
}


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
            postToIssueInternal(file!!.name.baseName() ?: "dummy",  apiUri, note)


        } finally {
            inputStream.close()
        }


    }

    fun postRest(client: OkHttpClient, issueUrl: String, note: Note) : List<Response> {
        return note.cells!!.slice(1 until note.cells.size)
                .filter { it.cellType == Cell.CellType.MARKDOWN } // now image is not supported yet.
                .map {
                    cell ->
                    val reqBody = requestBodyBuilder {
                        beginObject()
                            .name("body").value(cell.source)
                            .endObject()
                    }

                    val request = requestWithTokenBuilder()
                            .url(issueUrl+"/comments")
                            .post(reqBody)
                            .build()
                    client.newCall(request).execute()
                }
    }

    val jsonMedia by lazy {MediaType.parse("application/json")}

    fun requestBodyBuilder(builder: JsonWriter.()->Unit) : RequestBody {
        val sw = StringWriter()
        val jw = JsonWriter(sw)
        jw.builder()
        val jsonstr = sw.toString()
        return RequestBody.create(jsonMedia, jsonstr)
    }

    val token : String by lazy { prefs.getString("access_token", "") }

    fun requestWithTokenBuilder() = Request.Builder().addHeader("Authorization", "token $token")


    private fun postToIssueInternal(fname : String, apiUri: String, note: Note?) {
        if((note == null) or (note!!.cells == null) or (note.cells!!.isEmpty())) {
            showMessage("Null note. wrong ipynb format.")
            return
        }
        if (note.cells[0].cellType != Cell.CellType.MARKDOWN) {
            showMessage("Initial cell is not markdown. NYI.")
            return
        }

        val client = OkHttpClient()

        val body = note.cells[0].source

        val reqBody = requestBodyBuilder {
            beginObject()
                .name("title").value(fname)
                .name("body").value(body)
                .endObject()

        }

        val request = requestWithTokenBuilder()
                .url(apiUri)
                .post(reqBody)
                .build()

        Single.fromCallable { client.newCall(request).execute() }
                .map {
                    resp -> resp.header("Location")!!
                }
                .map { postRest(client, it, note) }
                .subscribeOn(Schedulers.single())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    resps ->
                    showMessage("resps size: ${resps.size}")
                }

    }

    private fun findETText(id: Int) = (findViewById(id) as EditText).text.toString()
}
