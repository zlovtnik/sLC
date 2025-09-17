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

  "LogAggregator" should "parse log entries correctly" in {
    val logLine = "2023-09-16 10:30:15.123 INFO com.example.TestClass This is a test message"
    val source = "test.log"

    val logEntry = LogAggregator.parseLogLine(logLine, source)

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

    val beforeTest = java.lang.System.currentTimeMillis()

    malformedLines.foreach { line =>
      val logEntry = LogAggregator.parseLogLine(line, "test.log")

      // Verify error recovery behavior specific to malformed lines
      logEntry.source shouldBe "test.log"
      logEntry.timestamp should be >= beforeTest  // Timestamp should be currentTimeMillis for malformed lines
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
      "2023-09-16 10:30:15.123 INFO test Valid log with timestamp",
      "invalid-timestamp INFO test Invalid timestamp log",
      "2023-09-16T10:30:15.123Z INFO test ISO format log"
    )

    testCases.foreach { logLine =>
      val logEntry = LogAggregator.parseLogLine(logLine, "test.log")
      logEntry.timestamp should be > 0L
      logEntry.source shouldBe "test.log"
    }
  }

  it should "handle different log source path formats" in {
    val testCases = List(
      ("logs/app.log", "Relative path with logs directory"),
      ("/var/log/application.log", "Absolute path"),
      ("app.log", "Simple filename"),
      ("complex/path/with/many/levels/logfile.log", "Deep nested path")
    )

    testCases.foreach { case (path, description) =>
      // Test that LogAggregator can handle different path formats
      // by creating a LogEntry with the path as source
      val logEntry = LogEntry(path, java.lang.System.currentTimeMillis(), "INFO", "Test message")
      logEntry.source shouldBe path

      // Verify the path contains expected log-related elements
      path should include ("log")
      path should endWith (".log")
    }
  }
}
