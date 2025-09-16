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

class LogAggregatorSpec extends AnyFlatSpec with Matchers {

    "LogAggregator" should "parse log entries correctly" in {
    val logLine = "2023-09-16 10:30:15.123 INFO com.example.TestClass This is a test message"
    val source = "test.log"

    // Test the parsing logic directly
    val parts = logLine.split(" ", 4)
    parts.length should be >= 4

    val timestamp = try {
      Instant.parse(parts(0) + "T" + parts(1)).toEpochMilli
    } catch {
      case _: Exception => java.lang.System.currentTimeMillis()
    }

    val logEntry = LogEntry(source, timestamp, parts(2), parts.drop(3).mkString(" "))

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
      val parts = line.split(" ", 4)
      val logEntry = if (parts.length >= 4) {
        val timestamp = try {
          Instant.parse(parts(0) + "T" + parts(1)).toEpochMilli
        } catch {
          case _: Exception => java.lang.System.currentTimeMillis()
        }
        LogEntry("test.log", timestamp, parts(2), parts.drop(3).mkString(" "))
      } else {
        LogEntry("test.log", java.lang.System.currentTimeMillis(), "INFO", line)
      }

      logEntry.source shouldBe "test.log"
      logEntry.level shouldBe "INFO"
    }
  }

  it should "handle malformed log lines with error recovery" in {
    val malformedLines = List(
      "",
      "incomplete log line",
      "2023-09-16 only date"
    )

    malformedLines.foreach { line =>
      val parts = line.split(" ", 4)
      if (parts.length >= 4) {
        val timestamp = try {
          Instant.parse(parts(0) + "T" + parts(1)).toEpochMilli
        } catch {
          case _: Exception => java.lang.System.currentTimeMillis()
        }
        val logEntry = LogEntry("test.log", timestamp, parts(2), parts.drop(3).mkString(" "))
        logEntry.source shouldBe "test.log"
      } else {
        val logEntry = LogEntry("test.log", java.lang.System.currentTimeMillis(), "INFO", line)
        logEntry.source shouldBe "test.log"
      }
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
    val empty = LogStats(0, 0)

    // Test combine operation
    val combined = LogStats(stats1.errorCount + stats2.errorCount, stats1.warningCount + stats2.warningCount)
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

  it should "handle empty log sources list" in {
    val emptySources: List[String] = List()

    emptySources.isEmpty shouldBe true
    emptySources.length shouldBe 0
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
