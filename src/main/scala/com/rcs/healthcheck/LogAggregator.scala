package com.rcs.healthcheck

import zio._
import zio.stream._
import java.nio.file.{Path, Paths}
import java.io.IOException
import scala.io.Source
import cats.Monoid
import cats.implicits._
import java.io.{FileWriter, PrintWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardOpenOption}
import java.time.Instant
import java.time.format.DateTimeFormatter

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

  val logPersistenceLive: ZLayer[Any, Nothing, LogPersistence] =
    ZLayer.succeed {
      new LogPersistence {
        override def persist(logs: Chunk[LogEntry]): Task[Unit] = {
          for {
            date     <- ZIO.clockWith(_.currentDateTime.map(_.toLocalDate.toString))
            dir       = Paths.get("persisted-logs")
            _        <- ZIO.attempt(Files.createDirectories(dir)).ignore
            logFile   = dir.resolve(s"persisted-logs-$date.log")
            _        <- ZIO.scoped {
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
                            ZIO.foreachDiscard(logs) { log =>
                              ZIO.attempt(writer.println(s"${log.timestamp} [${log.level}] ${log.source}: ${log.message}"))
                            }
                          }
                        }.catchAll(e => ZIO.logWarning(s"Failed to persist logs to file '${logFile.toString}': ${e.getMessage}"))
          } yield ()
        }
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
          ZIO.attempt {
            val path = Paths.get(source)
            val sourceFile = Source.fromFile(path.toFile)
            try {
              sourceFile.getLines().map { line =>
                // Simple log parsing - in real app, use proper log parsing
                val parts = line.split(" ", 4)
                if (parts.length >= 4) {
                  val timestamp = parseTimestamp(parts(0))
                  LogEntry(source, timestamp, parts(1), parts.drop(3).mkString(" "))
                } else {
                  LogEntry(source, java.lang.System.currentTimeMillis(), "INFO", line)
                }
              }.toList
            } finally {
              sourceFile.close()
            }
          }.catchAll {
            case e: IOException => ZIO.succeed(List(LogEntry(source, java.lang.System.currentTimeMillis(), "ERROR", s"Failed to read log file: ${e.getMessage}")))
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
              val parts = line.split(" ", 4)
              if (parts.length >= 4) {
                val timestamp = parseTimestamp(parts(0))
                LogEntry(source, timestamp, parts(1), parts.drop(3).mkString(" "))
              } else
                LogEntry(source, java.lang.System.currentTimeMillis(), "INFO", line)
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