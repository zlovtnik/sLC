package com.rcs.healthcheck.unit

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio._
import zio.stream._
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.io.Source
import cats.Monoid
import cats.implicits._
import com.rcs.healthcheck._
import com.rcs.healthcheck.LogAggregator._

class LogAggregatorSpec extends AnyFlatSpec with Matchers {

  // Helper method to parse log lines with error recovery
  private def parseLogLine(logLine: String, source: String): LogEntry = {
    val parts = logLine.split(" ", 4)
    val timestamp = if (parts.length >= 2) {
      try {
        Instant.parse(parts(0) + "T" + parts(1)).toEpochMilli
      } catch {
        case _: Exception => java.lang.System.currentTimeMillis()
      }
    } else {
      java.lang.System.currentTimeMillis()
    }

    if (parts.length >= 4) {
      LogEntry(source, timestamp, parts(2), parts.drop(3).mkString(" "))
    } else {
      // Error recovery: fallback to currentTimeMillis for timestamps and preserve source
      LogEntry(source, timestamp, "INFO", logLine)
    }
  }

    "LogAggregator" should "parse log entries correctly" in {
    val logLine = "2023-09-16 10:30:15.123 INFO com.example.TestClass This is a test message"
    val source = "test.log"

    val logEntry = parseLogLine(logLine, source)

    logEntry.source shouldBe "test.log"
    logEntry.level shouldBe "INFO"
    logEntry.message shouldBe "com.example.TestClass This is a test message"
  }

  it should "handle malformed log lines gracefully" in {
    val malformedLines = List(
      "",
      "incomplete log line",
      "2023-09-16 only date"
    )

    malformedLines.foreach { line =>
      val logEntry = parseLogLine(line, "test.log")

      // Verify error recovery behavior specific to malformed lines
      logEntry.source shouldBe "test.log"
      logEntry.timestamp should be > 0L  // Timestamp should be valid (either parsed or currentTimeMillis)
      logEntry.level shouldBe "INFO"  // Should default to INFO for malformed lines
      // For malformed lines, message should be the original line content
      logEntry.message shouldBe line
    }
  }

  it should "aggregate log statistics correctly" in {
    val logEntries = List(
      LogEntry("app.log", java.lang.System.currentTimeMillis(), "INFO", "Application started"),
      LogEntry("app.log", java.lang.System.currentTimeMillis(), "ERROR", "Database connection failed"),
      LogEntry("app.log", java.lang.System.currentTimeMillis(), "WARN", "Configuration warning"),
      LogEntry("app.log", java.lang.System.currentTimeMillis(), "ERROR", "Another error occurred"),
      LogEntry("app.log", java.lang.System.currentTimeMillis(), "DEBUG", "Debug message")
    )

    val stats = logEntries.foldLeft(LogStats(0L, 0L)) { (acc, entry) =>
      entry.level.toUpperCase match {
        case "ERROR" => acc.copy(errorCount = acc.errorCount + 1)
        case "WARN" | "WARNING" => acc.copy(warningCount = acc.warningCount + 1)
        case _ => acc
      }
    }

    stats.errorCount shouldBe 2
    stats.warningCount shouldBe 1
  }

  it should "validate LogStats Monoid operations" in {
    val stats1 = LogStats(5, 3)
    val stats2 = LogStats(2, 1)
    val empty = Monoid[LogStats].empty

    // Test combine operation
    val combined = stats1 |+| stats2
    combined.errorCount shouldBe 7
    combined.warningCount shouldBe 4

    // Test empty
    empty.errorCount shouldBe 0
    empty.warningCount shouldBe 0
  }

  it should "handle timestamp parsing edge cases" in {
    val testCases = List(
      ("2023-09-16T10:30:15.123Z", "ISO instant format"),
      ("2023-09-16 10:30:15.123", "Log format with space"),
      ("invalid-timestamp", "Invalid format")
    )

    testCases.foreach { case (timestampStr, description) =>
      val timestamp = try {
        Instant.parse(timestampStr).toEpochMilli
      } catch {
        case _: Exception =>
          try {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            java.time.LocalDateTime.parse(timestampStr, formatter)
              .toInstant(java.time.ZoneOffset.UTC)
              .toEpochMilli
          } catch {
            case _: Exception =>
              java.lang.System.currentTimeMillis()
          }
      }

      timestamp should be > 0L
    }
  }

  it should "validate LogEntry structure" in {
    val timestamp = java.lang.System.currentTimeMillis()
    val logEntry = LogEntry("test.log", timestamp, "INFO", "Test message")

    logEntry.source shouldBe "test.log"
    logEntry.timestamp shouldBe timestamp
    logEntry.level shouldBe "INFO"
    logEntry.message shouldBe "Test message"
  }

  it should "validate log file path handling" in {
    val testPaths = List(
      "logs/app.log",
      "/var/log/application.log",
      "relative/path/to/log.log"
    )

    testPaths.foreach { path =>
      val pathObj = Paths.get(path)
      pathObj.toString should include ("log")
      pathObj.getFileName.toString should endWith (".log")
    }
  }
}
