package com.example

import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

@Keep
class ShellUserService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String?): String {
        if (command.isNullOrBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "Perintah kosong")
            }.toString()
        }

        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (stdoutBuilder.length < 16384) {
                            stdoutBuilder.append(line).append('\n')
                        }
                    }
                }
            }

            val stderrThread = Thread {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (stderrBuilder.length < 8192) {
                            stderrBuilder.append(line).append('\n')
                        }
                    }
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            stdoutThread.join(1000)
            stderrThread.join(1000)

            val exitCode = if (finished) process.exitValue() else -1
            JSONObject().apply {
                put("ok", finished && exitCode == 0)
                put("exitCode", exitCode)
                put("stdout", stdoutBuilder.toString().trim())
                put("stderr", stderrBuilder.toString().trim())
            }.toString()
        } catch (t: Throwable) {
            JSONObject().apply {
                put("ok", false)
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", t.message ?: "Gagal mengeksekusi perintah shell")
            }.toString()
        }
    }

    override fun execBatch(commandsJson: String?): String {
        if (commandsJson.isNullOrBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("total", 0)
                put("succeeded", 0)
                put("failed", 0)
                put("results", JSONArray())
            }.toString()
        }

        return try {
            val inputArray = JSONArray(commandsJson)
            val commands = ArrayList<String>(inputArray.length())
            for (i in 0 until inputArray.length()) {
                val cmd = inputArray.optString(i, "").trim()
                if (cmd.isNotEmpty() && !cmd.startsWith("#")) {
                    commands.add(cmd)
                }
            }

            if (commands.isEmpty()) {
                return JSONObject().apply {
                    put("ok", true)
                    put("total", 0)
                    put("succeeded", 0)
                    put("failed", 0)
                    put("results", JSONArray())
                }.toString()
            }

            val process = ProcessBuilder("sh")
                .redirectErrorStream(true)
                .start()

            val markerPrefix = "__XP_BATCH_DONE_"
            val writerThread = Thread {
                OutputStreamWriter(process.outputStream).use { writer ->
                    commands.forEachIndexed { idx, cmd ->
                        writer.write("{\n")
                        writer.write(cmd)
                        writer.write("\n} 2>&1\n")
                        writer.write("echo \"${markerPrefix}${idx}_\$?__\"\n")
                    }
                    writer.write("exit 0\n")
                    writer.flush()
                }
            }
            writerThread.start()

            val resultsArray = JSONArray()
            val perCommandOutput = StringBuilder()
            var succeeded = 0
            var failed = 0
            val seenIndices = HashSet<Int>()

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (currentLine.startsWith(markerPrefix) && currentLine.endsWith("__")) {
                        val payload = currentLine.removePrefix(markerPrefix).removeSuffix("__")
                        val splitIdx = payload.lastIndexOf('_')
                        if (splitIdx > 0) {
                            val cmdIndex = payload.substring(0, splitIdx).toIntOrNull()
                            val exitCode = payload.substring(splitIdx + 1).toIntOrNull() ?: -1
                            if (cmdIndex != null && cmdIndex in commands.indices) {
                                seenIndices.add(cmdIndex)
                                if (exitCode == 0) succeeded++ else failed++
                                resultsArray.put(
                                    JSONObject().apply {
                                        put("command", commands[cmdIndex])
                                        put("exitCode", exitCode)
                                        put("ok", exitCode == 0)
                                        put("output", perCommandOutput.toString().trim().take(512))
                                    }
                                )
                                perCommandOutput.setLength(0)
                            }
                        }
                    } else if (perCommandOutput.length < 2048) {
                        perCommandOutput.append(currentLine).append('\n')
                    }
                }
            }

            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            writerThread.join(1000)

            for (i in commands.indices) {
                if (!seenIndices.contains(i)) {
                    failed++
                    resultsArray.put(
                        JSONObject().apply {
                            put("command", commands[i])
                            put("exitCode", -1)
                            put("ok", false)
                            put("output", "Timeout atau proses terhenti")
                        }
                    )
                }
            }

            JSONObject().apply {
                put("ok", failed == 0)
                put("total", commands.size)
                put("succeeded", succeeded)
                put("failed", failed)
                put("results", resultsArray)
            }.toString()
        } catch (t: Throwable) {
            JSONObject().apply {
                put("ok", false)
                put("total", 0)
                put("succeeded", 0)
                put("failed", 1)
                put("error", t.message ?: "Batch execution error")
                put("results", JSONArray())
            }.toString()
        }
    }
}
