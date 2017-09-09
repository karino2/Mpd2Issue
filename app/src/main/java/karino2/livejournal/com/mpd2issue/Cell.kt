package karino2.livejournal.com.mpd2issue

/**
 * Created by _ on 2017/09/09.
 */

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonWriter

import java.io.IOException

/**
 * Created by _ on 2017/05/24.
 */

data class Cell(
        @SerializedName("cell_type")
        var _cellType: String,
        @SerializedName("source")
                var _source: JsonElement,
        var executionCount: Int? = null,
        var metadata: JsonElement? = null,
        // always 1 element.
        var outputs: MutableList<Output>? = null) {

    var source: String
            get() = jsonElementToString(_source)
            set(newContent) { _source = JsonPrimitive(newContent) }


    data class Output(var name: String = "",
                      // (name, _text) or _data
                      var outputType: String? = null,
                      @SerializedName("_text")
                      var _text: JsonElement? = null,
                      @SerializedName("_data")
                      var _data: Map<String, JsonElement>? = null,

                      var executionCount: Int? = null
    ) {


        val isImage: Boolean
            get() {
                if (_data == null)
                    return false
                for (key in _data!!.keys) {
                    if (key.startsWith("image/png") || key.startsWith("image/jpeg")) {
                        return true
                    }
                }
                return false
            }

        fun setData(newData: JsonObject) {
            val dataType = object : TypeToken<Map<String, JsonElement>>() {

            }.type
            _data = s_gson.fromJson<Map<String, JsonElement>>(newData, dataType)
        }

        val imageAsBase64: String?
            get() {
                for (key in _data!!.keys) {
                    if (key.startsWith("image/png") || key.startsWith("image/jpeg")) {
                        return jsonElementToString(_data!![key])
                    }
                }
                return null
            }

        val text : String
            get() = if (_data == null) jsonElementToString(_text) else jsonElementToString(_data!!["_text/plain"])

        fun appendResult(newcontents: String) {
            // in this case, output is cleared at first and _text must be JsonArray.
            val array = _text as JsonArray?
            array!!.add(newcontents)
        }

    }

    fun clearOutput() {
        outputs!!.clear()
        val newoutput = Output()
        newoutput.outputType = "stream"
        newoutput.name = "stdout"
        newoutput._text = JsonArray()
        outputs!!.add(newoutput)
    }

    internal val output: Output?
        get() = if (outputs!!.isEmpty()) null else outputs!![0]

    enum class CellType {
        UNINITIALIZE,
        CODE,
        MARKDOWN
    }

    var cellType: CellType
        get() {
            return if ("code" == _cellType) {
                CellType.CODE
            } else if ("markdown" == _cellType) {
                CellType.MARKDOWN
            } else {
                CellType.UNINITIALIZE
            }
        }
        set(newType) {
            when (newType) {
                Cell.CellType.CODE -> _cellType = "code"
                Cell.CellType.MARKDOWN -> _cellType = "markdown"
                else -> throw IllegalArgumentException("Unknown cell type: " + newType)
            }
        }

    @Throws(IOException::class)
    fun toJson(gson: Gson, writer: JsonWriter) {
        if (CellType.MARKDOWN == cellType) {
            toJsonMarkdownCell(gson, writer)
        } else {
            toJsonCodeCell(gson, writer)
        }
    }

    internal val execCountForSave: Int?
        get() {
            if (executionCount == null)
                return null
            return if (executionCount === EXEC_COUNT_RUNNING) null else executionCount
        }

    @Throws(IOException::class)
    private fun toJsonCodeCell(gson: Gson, writer: JsonWriter) {
        writer.beginObject()
        writer.name("cell_type").value("code")
                .name("execution_count").value(execCountForSave)


        writeMetadata(gson, writer)

        writer.name("source")
                .value(source)

        writer.name("outputs")
                .beginArray()
                .jsonValue(gson.toJson(output))
                .endArray()

        writer.endObject()

    }

    @Throws(IOException::class)
    private fun toJsonMarkdownCell(gson: Gson, writer: JsonWriter) {
        //         {"metadata":{"collapsed":true},"cell_type":"markdown","source":"## Markdown cell\n\nHere is the test of markdown.\nNext line."},
        writer.beginObject()
                .name("cell_type").value("markdown")

        writeMetadata(gson, writer)

        writer.name("source").value(source)


        writer.endObject()

    }

    @Throws(IOException::class)
    private fun writeMetadata(gson: Gson, writer: JsonWriter) {
        writer.name("metadata")

        if (metadata == null) {
            writer.beginObject().endObject()
        } else {
            writer.jsonValue(gson.toJson(metadata))
        }
    }

    companion object {
        val EXEC_COUNT_RUNNING = -1

        internal var s_gson = Gson()


        internal fun jsonElementToString(obj: JsonElement?): String {
            if (obj == null)
                return ""
            if (obj.isJsonArray) {
                val sources = s_gson.fromJson(obj, List::class.java)
                return mergeAll(sources as List<String>)
            }
            return obj.asString

        }


        internal fun mergeAll(texts: List<String>): String {
            val buf = StringBuilder()
            for (source in texts) {
                buf.append(source)
                // should I handle return code here?
            }
            return buf.toString()
        }
    }


}
