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
import org.jsoup.Jsoup
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

    data class AssetJson(val uploadUrl: String,
                         val header : JsonElement,
                         val asset: JsonElement,
                         val form: JsonObject,
                         val assetUploadUrl: String)

    data class ImagePutResult(val href: String)

    data class NullableResponse(val response : Response?)



    fun postImageCell(client: OkHttpClient, issueWithToken: IssueWithUploadToken, cell : Cell, index: Int) : NullableResponse {
        if(cell.output == null) {
            Log.d("Mpd2Issue", "Null output of image cell. Invalid. skip")
            return NullableResponse(null)
        }

        val image = cell.output!!.base64Image
        if(image == null) {
            Log.d("Mpd2Issue", "Image cell does not have supported image format. Invalid. skip")
            return NullableResponse(null)
        }

        val issueUrl = issueWithToken.url
        val imagebytes = Base64.decode(image.content, Base64.DEFAULT)

        val issueId = Uri.parse(issueUrl).lastPathSegment

        val imageExt = if(image.key.startsWith("image/png")) "png" else "jpg"
        val imageFileName = "${issueId}_${index}.${imageExt}"

        val firstForm = FormBody.Builder()
                .add("name", imageFileName)
                .add("size", imagebytes.size.toString())
                .add("content_type", image.key)
                .add("authenticity_token", issueWithToken.authentityToken)
                .build()

        // need to add authenticity_token here.



        val firstRequest = requestWithTokenBuilder()
                .url("https://github.com/upload/policies/assets")
                .post(firstForm)
                .build()

        val firstResp = client.newCall(firstRequest).execute()

        val gson = Note.gson
        val firstRespBody = firstResp.body()!!.string()
        val assetResp = gson.fromJson(firstRespBody, AssetJson::class.java)

        val secondBuilder = MultipartBody.Builder()
        for(entry in assetResp.form.entrySet()) {
            secondBuilder.addFormDataPart(entry.key, entry.value.asString)
        }

        val imageBody = RequestBody.create(MediaType.parse(image.key), imagebytes)
        val secondForm = secondBuilder.addFormDataPart("file", imageFileName,imageBody)
        val secondRequest = requestWithTokenBuilder()
                .url(assetResp.uploadUrl)
                .post(firstForm)
                .build()
        val secondResp = client.newCall(secondRequest).execute()

        val thirdRequest = requestWithTokenBuilder()
                .url(assetResp.assetUploadUrl)
                .post(FormBody.Builder().build())
                .build()
        val thirdResp = client.newCall(thirdRequest).execute()
        val putResult = gson.fromJson(thirdResp.body()!!.string(), ImagePutResult::class.java)

        val md = StringBuilder()
                .append("![](")
                .append(putResult.href)
                .append(")")
                .toString()

        return NullableResponse(postOneComment(client, md, issueUrl+"/comments"))

    }

    fun postCell(client: OkHttpClient, issueWithToken: IssueWithUploadToken, cell: Cell, index: Int) : NullableResponse {
        if(cell.cellType == Cell.CellType.CODE) {
            return postImageCell(client, issueWithToken, cell, index)
        }
        return  NullableResponse(postMarkdownCell(client, issueWithToken.url+"/comments", cell))
    }

    fun postRest(client: OkHttpClient, issueWithToken: IssueWithUploadToken, note: Note) : List<Response> {
        return note.cells!!.slice(1 until note.cells.size)
                .filter { cell -> (cell.cellType == Cell.CellType.MARKDOWN) or (cell.cellType == Cell.CellType.CODE) }
                .mapIndexed {
                    index, cell -> postCell(client, issueWithToken, cell, index)
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

    data class IssueWithUploadToken(val url: String, val authentityToken: String)

    fun scrapeAuthenticityToken(client: OkHttpClient, issueUrl: String) : IssueWithUploadToken {

        // https://api.github.com/repos/karino2/karino2.github.io/issues/11
        // -> https://github.com/karino2/karino2.github.io/issues/11
        val segs = Uri.parse(issueUrl).pathSegments
        // remove repos
        val relative = segs.slice(1 until segs.size).joinToString("/")

        val url = StringBuilder()
                .append("https://github.com/")
                .append(relative)
                .toString()

        val request = requestWithTokenBuilder()
                .url(url)
                .get()
                .build()

        val resp = client.newCall(request).execute()

        val bodyStr = resp.body()!!.string()
        val selected = Jsoup.parse(bodyStr).select("div[data-upload-policy-authenticity-token]")
        val div = selected.first()
        val uploadToken = div.attr("data-upload-policy-authenticity-token")


        // val uploadToken = Jsoup.parse(resp.body()!!.string()).select("div[data-upload-policy-authenticity-token]").first().attr("data-upload-policy-authenticity-token")
        return IssueWithUploadToken(issueUrl, uploadToken)
    }

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
                    resp -> scrapeAuthenticityToken(client, resp.header("Location")!!)
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
