package com.rcs.healthcheck

import zio._
import zio.stream._
import java.io.{IOException, PrintWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.io.Source
import cats.Monoid
import cats.implicits._

case class LogEntry(source: String, timestamp: Long, level: String, message: String)

final case class LogStats(errorCount: Long, warningCount: Long)

trait LogPersistence {
  def persist(logs: Chunk[LogEntry]): Task[Unit]
}

trait LogAggregator {
  def aggregateLogs(sources: List[String]): ZIO[Any, Throwable, List[LogEntry]]
  def streamLogs(sources: List[String]): ZStream[Any, Throwable, LogEntry]
  def aggregateStats(sources: List[String]): ZIO[Any, Throwable, LogStats]
}

object LogAggregator {
  // Define how to combine two LogStats instances
  implicit val statsMonoid: Monoid[LogStats] = new Monoid[LogStats] {
    def empty: LogStats = LogStats(0L, 0L)
    def combine(x: LogStats, y: LogStats): LogStats =
      LogStats(x.errorCount + y.errorCount, x.warningCount + y.warningCount)
  }

  // Valid log levels for parsing log entries
  private val VALID_LOG_LEVELS = Set("DEBUG", "INFO", "WARN", "WARNING", "ERROR")

  // Helper function to parse timestamp from log line
  private def parseTimestamp(timestampStr: String): Long = {
    try {
      // Try parsing as ISO instant first
      Instant.parse(timestampStr).toEpochMilli
    } catch {
      case _: Exception =>
        try {
          // Try parsing with common log format: yyyy-MM-dd HH:mm:ss.SSS
          val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
          java.time.LocalDateTime.parse(timestampStr, formatter)
            .toInstant(java.time.ZoneOffset.UTC)
            .toEpochMilli
        } catch {
          case _: Exception =>
            // Fallback to current time
            java.lang.System.currentTimeMillis()
        }
    }
  }

  // Helper function to parse a single log line
  def parseLogLine(line: String, source: String): LogEntry = {
    val parts = line.split(" ", 4)
    if (parts.length >= 4) {
      val timestampStr = s"${parts(0)} ${parts(1)}"
      val timestamp = parseTimestamp(timestampStr)
      val level = parts(2)
      val message = parts(3)
      LogEntry(source, timestamp, level, message)
    } else if (parts.length == 3) {
      // Handle 3-part lines like "2023-09-16 INFO message"
      // But only if the middle part is a valid log level
      if (VALID_LOG_LEVELS.contains(parts(1).toUpperCase)) {
        val timestamp = parseTimestamp(parts(0))
        val level = parts(1).toUpperCase
        val message = parts(2)
        LogEntry(source, timestamp, level, message)
      } else {
        // Not a valid log level, treat as malformed
        LogEntry(source, java.lang.System.currentTimeMillis(), "INFO", line)
      }
    } else {
      LogEntry(source, java.lang.System.currentTimeMillis(), "INFO", line)
    }
  }

  // Helper function to rotate log file if it meets or exceeds the size threshold
  private def rotateIfNeeded(logFile: Path, maxSize: Long): Task[Unit] =
    ZIO.whenZIO {
      ZIO.attemptBlockingIO(Files.exists(logFile)).zipWithPar(
        ZIO.attemptBlockingIO(if (Files.exists(logFile)) Files.size(logFile) else 0L)
      )((exists, size) => exists && size >= maxSize)
    } {
      for {
        ts <- ZIO.clockWith(_.currentDateTime.map(_.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))))
        archive = logFile.resolveSibling(s"${logFile.getFileName.toString}.archive.$ts")
        _  <- ZIO.attemptBlockingIO(Files.move(logFile, archive, StandardCopyOption.ATOMIC_MOVE))
        _  <- ZIO.logInfo(s"Rotated log file to $archive")
      } yield ()
    }.unit

  val logPersistenceLive: ZLayer[AppConfig, Nothing, LogPersistence] =
    ZLayer.fromZIO {
      for {
        config <- ZIO.service[AppConfig]
        sem    <- zio.Semaphore.make(1)
      } yield new LogPersistence {
        override def persist(logs: Chunk[LogEntry]): Task[Unit] =
          sem.withPermit {
            for {
              date    <- ZIO.clockWith(_.currentDateTime.map(_.toLocalDate.toString))
              dir      = Paths.get(config.logAggregation.persistDir)
              _       <- ZIO.attemptBlockingIO(Files.createDirectories(dir)).ignore
              logFile  = dir.resolve(s"persisted-logs-$date.log")
              _       <- rotateIfNeeded(logFile, config.logAggregation.maxLogFileSize)
              _       <- ZIO.scoped {
                           ZIO.fromAutoCloseable {
                             ZIO.attempt(
                               new PrintWriter(
                                 Files.newBufferedWriter(
                                   logFile,
                                   StandardCharsets.UTF_8,
                                   StandardOpenOption.CREATE,
                                   StandardOpenOption.APPEND
                                 )
                               )
                             )
                           }.flatMap { writer =>
                             ZIO.attempt {
                               logs.foreach { log =>
                                 writer.println(s"${log.timestamp} ${log.level} ${log.source} ${log.message}")
                               }
                             }
                           }
                         }
            } yield ()
          }.catchAll(e => ZIO.logWarning(s"Failed to persist logs: ${e.getMessage}"))
      }
    }
  def live: ZLayer[Any, Nothing, LogAggregator] =
    ZLayer.succeed {
      new LogAggregator {
        override def aggregateLogs(sources: List[String]): ZIO[Any, Throwable, List[LogEntry]] = {
          ZIO.foreach(sources)(source => collectLogsFromSource(source)).map(_.flatten)
        }

        override def streamLogs(sources: List[String]): ZStream[Any, Throwable, LogEntry] = {
          ZStream.fromIterable(sources).flatMap(source => streamLogsFromSource(source))
        }

        private def collectLogsFromSource(source: String): ZIO[Any, Throwable, List[LogEntry]] = {
          ZIO.scoped {
            ZIO.fromAutoCloseable(ZIO.attempt(Source.fromFile(source)))
              .flatMap { sourceFile =>
                ZIO.attempt(sourceFile.getLines().map(parseLogLine(_, source)).toList)
              }
          }.catchAll {
            case e: IOException => ZIO.succeed(List(LogEntry(source, java.lang.System.currentTimeMillis(), "ERROR", s"Failed to read: ${e.getMessage}")))
            case e => ZIO.fail(e)
          }
        }

        override def aggregateStats(sources: List[String]): ZIO[Any, Throwable, LogStats] = {
          streamLogs(sources)
            .mapChunks { chunk =>
              val acc = chunk.foldLeft(Monoid[LogStats].empty) { (s, entry) =>
                entry.level.toUpperCase match {
                  case "ERROR"               => s.copy(errorCount = s.errorCount + 1)
                  case "WARN" | "WARNING"    => s.copy(warningCount = s.warningCount + 1)
                  case _                     => s
                }
              }
              Chunk.single(acc)
            }
            .runFold(Monoid[LogStats].empty)(_ |+| _)
        }

        private def streamLogsFromSource(source: String): ZStream[Any, Throwable, LogEntry] = {
          val path = Paths.get(source)
          ZStream
            .fromFile(path.toFile)
            .via(ZPipeline.utf8Decode)
            .via(ZPipeline.splitLines)
            .map { line =>
              LogAggregator.parseLogLine(line, source)
            }
            .catchAll(e => ZStream.succeed(LogEntry(source, java.lang.System.currentTimeMillis(), "ERROR", s"Failed to stream log file: ${e.getMessage}")))
        }
      }
    }

  def aggregateLogs(sources: List[String]): ZIO[LogAggregator, Throwable, List[LogEntry]] =
    ZIO.serviceWithZIO(_.aggregateLogs(sources))

  def streamLogs(sources: List[String]): ZStream[LogAggregator, Throwable, LogEntry] =
    ZStream.serviceWithStream(_.streamLogs(sources))

  def aggregateStats(sources: List[String]): ZIO[LogAggregator, Throwable, LogStats] =
    ZIO.serviceWithZIO(_.aggregateStats(sources))
}