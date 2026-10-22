package org.multipath.outproxy

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

object I2pdConf {
    fun enableSam(conf: String): String? {
        val lines = conf.replace("\r\n", "\n").split("\n").toMutableList()
        var section = ""
        var samHeader = -1
        var samEnd = lines.size
        for ((i, raw) in lines.withIndex()) {
            val t = raw.trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                if (section == "sam") { samEnd = i; break }
                section = t.substring(1, t.length - 1).trim().lowercase()
                if (section == "sam") samHeader = i
                continue
            }
            if (section != "sam" || t.startsWith("#")) continue
            val kv = t.split("=", limit = 2)
            if (kv.size == 2 && kv[0].trim().equals("enabled", ignoreCase = true)) {
                if (kv[1].trim().equals("true", ignoreCase = true)) return null
                lines[i] = "enabled = true"
                return lines.joinToString("\n")
            }
        }
        if (samHeader >= 0) {
            lines.add(samEnd, "enabled = true")
            return lines.joinToString("\n")
        }
        val tail = if (lines.lastOrNull()?.isBlank() == false) listOf("") else emptyList()
        return (lines + tail + listOf("[sam]", "enabled = true", "")).joinToString("\n")
    }

    fun looksLikeConf(conf: String) =
        Regex("^\\s*\\[(http|ntcp2|ssu2|sam|reseed|httpproxy)\\]", RegexOption.MULTILINE).containsMatchIn(conf)

    fun probeSam(address: String): String? {
        val (host, port) = Settings.parseSamAddress(address) ?: return null
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 700)
                s.soTimeout = 2000
                val w = OutputStreamWriter(s.getOutputStream())
                w.write("HELLO VERSION MIN=3.1 MAX=3.3\n")
                w.flush()
                val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: return null
                if (!line.contains("RESULT=OK")) return null
                Regex("VERSION=(\\S+)").find(line)?.groupValues?.get(1) ?: "?"
            }
        } catch (_: Exception) {
            null
        }
    }
}
