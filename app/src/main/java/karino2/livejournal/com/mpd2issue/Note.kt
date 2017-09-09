package karino2.livejournal.com.mpd2issue

/**
 * Created by _ on 2017/09/09.
 */


import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Date

/**
 * Created by _ on 2017/05/24.
 */

data class Note(    var name: String? = null,
                    var path: String? = null,
                    var lastModified: Date? = null,
                    var created: Date? = null,
                    var content: Content? = null,
                    var format: String? = null,
                    var mimetype: String? = null,
                    var writable: Boolean = false,
                    var type: String? = null
) {


    class Content {
        var cells: List<Cell>? = null

    }

    companion object {

        fun fromJson(buf: String): Note {
            val gson = gson
            return gson.fromJson(buf, Note::class.java)
        }

        val gson : Gson by lazy {
            GsonBuilder()
                    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                    .create()

        }

        @Throws(IOException::class)
        fun fromJson(inputStream: InputStream): Note {
            // JsonReader reader = new JsonReader(new InputStreamReader(inputStream, "UTF-8"));
            val json = readAll(inputStream)
            return fromJson(json)
        }

        @Throws(IOException::class)
        private fun readAll(inputStream: InputStream): String {
            val br = BufferedReader(InputStreamReader(inputStream))
            val buf = StringBuffer()

            var line: String?
            line = br.readLine()
            while (line != null) {
                buf.append(line)
                line = br.readLine()
            }
            br.close()
            return buf.toString()
        }
    }
}
