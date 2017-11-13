package karino2.livejournal.com.mpd2issue

import android.content.SharedPreferences
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.widget.EditText
import java.io.File
import android.widget.Toast
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonWriter
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import okhttp3.*
import java.io.StringWriter
import android.app.NotificationManager
import android.app.PendingIntent
import android.R.id.edit
import android.content.Context
import android.content.Intent
import android.support.v7.app.NotificationCompat
import java.io.InputStream


fun String?.baseName() : String? {
    if(this == null)
        return null
    val pos = this.lastIndexOf(".")
    return if(pos == -1) null else this.substring(0, pos)
}


class UploaderActivity : AppCompatActivity() {

    var fileUri : Uri? = null

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

            fileUri = Uri.parse(uristr)
        }

        (findViewById(R.id.editTextUrl) as EditText).setText(prefs.getString("last_url", "https://github.com/"));

    }

    var duringPost = false

    fun showMessage(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()


    val prefs : SharedPreferences by lazy { LoginActivity.getAppPreferences(this) }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_uploader, menu);
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_item_post).setEnabled(!duringPost)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.menu_item_post -> {
                showMessage("posting...")
                duringPost = true
                invalidateOptionsMenu()
                postToIssue()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        fileUri?.let{ outState.putString("FILE_PATH", fileUri!!.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val filepath = savedInstanceState.getString("FILE_PATH", null)
        filepath?.let{ fileUri = Uri.parse(filepath) }
    }

    fun Uri.toName() = File(this.path).name

    /*
    fun openInputStream(uri : Uri) : InputStream {
        val pfd = contentResolver.openFileDescriptor(uri, "r")
        pfd.fileDescriptor
    }
    */

    private fun postToIssue() {
        val issueUrlStr = findETText(R.id.editTextUrl)
        prefs.edit()
                .putString("last_url", issueUrlStr)
                .commit()

        val issueUrl = Uri.parse(issueUrlStr)
        val pathSegs = issueUrl.pathSegments




        if(fileUri == null) {
            showMessage("No file selected.")
            return
        }

        if((pathSegs.size < 3) or ((pathSegs[pathSegs.size-1] != "issues") and (pathSegs[pathSegs.size-2] != "issues"))) {
            showMessage("Invalid issue url: $issueUrlStr")
            return
        }

        val note = readNote()

        if(pathSegs.last() != "issues") {
            try {
                val issueNum = Integer.parseInt(pathSegs.last())
                val owner = pathSegs[pathSegs.size-4]!!
                val repoName = pathSegs[pathSegs.size-3]!!
                updateIssue(owner, repoName, issueNum, note.cells!!)

                return
            } catch(e : NumberFormatException ) {
                showMessage("Invalid URL.")
                return
            }
        }
        val owner = pathSegs[pathSegs.size-3]
        val repoName = pathSegs[pathSegs.size-2]


        val issueNoCand = tryGetSpecifiedIssueId(note)
        if(issueNoCand == -1) {
            val apiUri = "https://api.github.com/repos/${owner}/${repoName}/issues"

            postToIssueInternal(fileUri!!.toName().baseName() ?: "dummy", apiUri, note)
        } else {
            val cells = note.cells!!
            val fromSecond = cells.slice(1..cells.size-1)
            updateIssue(owner, repoName, issueNoCand, fromSecond)
        }


    }

    private fun tryGetSpecifiedIssueId(note: Note) : Int {
        note.cells?.let {
            if(note.cells.size < 1)
                return -1
            val first = note.cells[0]
            if(first.cellType != Cell.CellType.MARKDOWN)
                return -1
            val content = first.source
            val issueIdPat = """IssueId: *(\d+)""".toRegex()
            val res = issueIdPat.matchEntire(content)
            res?.let {
                return res.groupValues[1].toInt()
            }
            return -1

        }
        return -1
    }


    private fun updateIssue(owner: String, repoName: String, issueNum: Int, cells: List<Cell>) {
        val apiUri = "https://api.github.com/repos/${owner}/${repoName}/issues/${issueNum}"

        updateIssue(fileUri!!.toName().baseName() ?: "dummy", apiUri, cells)
    }

    fun readNote() : Note {
        val inputStream = contentResolver.openInputStream(fileUri)
        try {
            val note = Note.fromJson(inputStream)
            return note!!
        } finally {
            inputStream.close()
        }

    }


    data class IssueResult(val comments : Int)

    enum class UpdateStatus {
        NORMAL, FULL
    }

    private fun updateIssue(bookname: String, issueApiUrl: String, cells: List<Cell>) {

        val client = OkHttpClient()


        val request = requestWithTokenBuilder()
                .url(issueApiUrl)
                .get()
                .build()


        val gson = Note.gson

        Single.fromCallable { client.newCall(request).execute() }
                .map {resp->
                    val issueResult = gson.fromJson(resp.body()!!.string(), IssueResult::class.java)
                    issueResult.comments
                }
                .map {
                    val postedNum = it+1
                    val cellNum = cells.size
                    if (cellNum <= postedNum) {
                        Pair(UpdateStatus.FULL, null)
                    } else {
                        val from = postedNum
                        // currently, only support ADD NEW CELL.
                        // do not check time stamp.
                        // But I guess this is OK for most of real situation.
                        val resp = postRest(client, issueApiUrl, cells, from)
                        Pair(UpdateStatus.NORMAL, resp)
                    }
                }
                .subscribeOn(Schedulers.single())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    (state, resps) ->
                    when(state) {
                        UpdateStatus.FULL-> {
                            enablePost()
                            showMessage("Already up to date.")
                        }
                        UpdateStatus.NORMAL-> {
                            val webUrl = apiUrlToWebUrl(issueApiUrl)
                            showUrlNotification(webUrl)
                            enablePost()
                            showMessage("update done.")
                            finish()
                        }
                    }

                }
    }



    fun postMarkdownCell(client: OkHttpClient, commentUrl: String, cell : Cell) : Response {
        return postOneComment(client, cell.source, commentUrl)

    }

    private fun postOneComment(client: OkHttpClient, bodyStr: String, commentUrl: String): Response {
        val reqBody = requestBodyBuilder {
            beginObject()
                    .name("body").value(bodyStr)
                    .endObject()
        }

        val request = requestWithTokenBuilder()
                .url(commentUrl)
                .post(reqBody)
                .build()
        return client.newCall(request).execute()
    }


    data class ImgurData(val link: String)
    data class ImgurResult(val data: ImgurData)

    data class NullableResponse(val response : Response?)



    fun postImageCell(client: OkHttpClient, issueUrl: String, cell : Cell, index: Int) : NullableResponse {
        if(cell.output == null) {
            Log.d("Mpd2Issue", "Null output of image cell. Invalid. skip")
            return NullableResponse(null)
        }

        val image = cell.output!!.base64Image
        if(image == null) {
            Log.d("Mpd2Issue", "Image cell does not have supported image format. Invalid. skip")
            return NullableResponse(null)
        }

        val imagebytes = Base64.decode(image.content, Base64.DEFAULT)

        val issueId = Uri.parse(issueUrl).lastPathSegment

        val imageExt = if(image.key.startsWith("image/png")) "png" else "jpg"
        val imageFileName = "${issueId}_${index}.${imageExt}"

        val imgurRequestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("title", "Mpd2Issue images")
                .addFormDataPart("image", imageFileName,
                        RequestBody.create(MediaType.parse(image.key), imagebytes))
                .build()


        val imgurRequest = requestWithTokenBuilder()
                .url("https://api.imgur.com/3/image")
                .header("Authorization", "Client-ID ${getString(R.string.imgur_client_id)}")
                .post(imgurRequestBody)
                .build()

        val imgurResp = client.newCall(imgurRequest).execute()

        val gson = Note.gson
        val imgurRespBody = imgurResp.body()!!.string()
        val imgurResult = gson.fromJson(imgurRespBody, ImgurResult::class.java)

        val md = StringBuilder()
                .append("![](")
                .append(imgurResult.data.link)
                .append(")")
                .toString()

        return NullableResponse(postOneComment(client, md, issueUrl+"/comments"))

    }

    fun postCell(client: OkHttpClient, issueUrl: String, cell: Cell, index: Int) : NullableResponse {
        if(cell.cellType == Cell.CellType.CODE) {
            return postImageCell(client, issueUrl, cell, index)
        }
        return  NullableResponse(postMarkdownCell(client, issueUrl+"/comments", cell))
    }

    fun postRest(client: OkHttpClient, issueUrl: String, cells: List<Cell>,  from: Int = 1) : List<Response> {
        return cells.slice(from until cells.size)
                .filter { cell -> (cell.cellType == Cell.CellType.MARKDOWN) or (cell.cellType == Cell.CellType.CODE) }
                .mapIndexed {
                    index, cell -> postCell(client, issueUrl, cell, index)
                }
                .filter { resp -> resp.response != null }
                .map { resp->resp.response!!}
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

    fun enablePost() {
        duringPost = false
        invalidateOptionsMenu()
    }

    private fun postToIssueInternal(fname : String, apiUri: String, note: Note?) {
        if((note == null) or (note!!.cells == null) or (note.cells!!.isEmpty())) {
            showMessage("Null note. wrong ipynb format.")
            enablePost()
            return
        }
        if (note.cells[0].cellType != Cell.CellType.MARKDOWN) {
            showMessage("Initial cell is not markdown. NYI.")
            enablePost()
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
                .map { Pair(it, postRest(client, it, note.cells!!)) }
                .subscribeOn(Schedulers.single())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    (issueUrl, resps) ->

                    val webUrl = apiUrlToWebUrl(issueUrl)
                    showUrlNotification(webUrl)
                    enablePost()
                    showMessage("done.")
                    finish()
                }

    }

    fun apiUrlToWebUrl(issueApiUrl : String) : String {
        // https://api.github.com/repos/karino2/karino2.github.io/issues/13 -> https://github.com/karino2/karino2.github.io/issues/13
        val uri = Uri.parse(issueApiUrl)
        val segs = uri.pathSegments
        val ownerStart = segs.size - 4
        val afterOwner = segs.slice(ownerStart until segs.size).joinToString("/")
        return "https://github.com/$afterOwner"
    }

    fun webUrlToIssueId(webUrl: String) : String {
        val uri = Uri.parse(webUrl)
        val segs = uri.pathSegments
        return segs[segs.size-1]
    }

    val NOTIFICATION_ID = 1

    private fun showUrlNotification(url: String) {
        val builder = NotificationCompat.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("GistIpynb")
                .setContentText(url)


        val issueId = webUrlToIssueId(url)
        val intentCopy = Intent(this, CopyIdReceiver::class.java)
        intentCopy.putExtra("issueid", "IssueId:$issueId")
        val piCopy = PendingIntent.getBroadcast(this, 1,intentCopy,  PendingIntent.FLAG_UPDATE_CURRENT)

        builder.addAction(android.R.drawable.ic_menu_edit, "Id", piCopy);


        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(url)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        builder.setContentIntent(pendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, builder.build())


    }

    private fun findETText(id: Int) = (findViewById(id) as EditText).text.toString()
}
