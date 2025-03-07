// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.intellij.platform.diagnostic.telemetry.impl

import com.intellij.platform.diagnostic.telemetry.OpenTelemetryUtils.csvHeadersLines
import java.io.File
import java.util.zip.GZIPOutputStream

internal class LinesStorage(writeToFile: File) {
  private var bufferedWriter = GZIPOutputStream(writeToFile.outputStream(), true).bufferedWriter()
  private val lines = ArrayList<String>()

  fun updateDestFile(newFilePath: File) {
    closeBufferedWriter()
    bufferedWriter = GZIPOutputStream(newFilePath.outputStream(), true).bufferedWriter()
  }

  fun getLines(): List<String> {
    synchronized(lines) {
      return lines.toList()
    }
  }

  fun clearStorage() {
    synchronized(lines) {
      lines.clear()
    }
  }

  fun closeBufferedWriter() {
    bufferedWriter.flush()
    bufferedWriter.close()
  }

  fun appendLine(line: String) {
    synchronized(lines) {
      lines.add(line)
    }
  }

  fun appendHeaderLines() {
    synchronized(lines) {
      lines.addAll(csvHeadersLines())
    }
  }

  fun dump() {
    bufferedWriter.let { out ->
      synchronized(lines) {
        lines.forEach { out.appendLine(it) }
        lines.clear()
      }
      out.flush()
    }
  }
}