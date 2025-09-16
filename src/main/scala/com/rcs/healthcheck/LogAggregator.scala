package com.rcs.healthcheck

import zio._
import zio.stream._
import java.nio.file.{Path, Paths}
import java.io.IOException
import scala.io.Source
import cats.Monoid
import cats.implicits._
import cats.effect.{IO, Resource}
import zio.interop.catz._
import java.io.{FileWriter, PrintWriter}

case class LogEntry(source: String, timestamp: Long, level: String, message: String)

case class LogStats(errorCount: Int, warningCount: Int)

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
    def empty: LogStats = LogStats(0, 0)
    def combine(x: LogStats, y: LogStats): LogStats =
      LogStats(x.errorCount + y.errorCount, x.warningCount + y.warningCount)
  }

  val logPersistenceLive: ZLayer[Any, Nothing, LogPersistence] =
    ZLayer.succeed {
      new LogPersistence {
        override def persist(logs: Chunk[LogEntry]): Task[Unit] = {
          val persistEffect = for {
            timestamp <- ZIO.clock.flatMap(_.currentDateTime.map(_.toLocalDate.toString))
            logFile = s"persisted-logs-$timestamp.log"
            _ <- ZIO.attempt {
              val writer = new PrintWriter(new FileWriter(logFile, true))
              try {
                logs.foreach { log =>
                  writer.println(s"${log.timestamp} [${log.level}] ${log.source}: ${log.message}")
                }
              } finally {
                writer.close()
              }
            }.catchAll { error =>
              ZIO.logWarning(s"Failed to persist logs to file: ${error.getMessage}")
            }
          } yield ()
          persistEffect
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
              sourceFile.getLines().zipWithIndex.map { case (line, index) =>
                // Simple log parsing - in real app, use proper log parsing
                val parts = line.split(" ", 4)
                if (parts.length >= 4) {
                  LogEntry(source, java.lang.System.currentTimeMillis(), parts(1), parts.drop(3).mkString(" "))
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
          streamLogs(sources).map { entry =>
            entry.level.toUpperCase match {
              case "ERROR" => LogStats(1, 0)
              case "WARN" | "WARNING" => LogStats(0, 1)
              case _ => Monoid[LogStats].empty
            }
          }.runFold(Monoid[LogStats].empty)(_ |+| _)
        }

        private def streamLogsFromSource(source: String): ZStream[Any, Throwable, LogEntry] = {
          ZStream.fromZIO(collectLogsFromSource(source)).flatMap(entries => ZStream.fromIterable(entries))
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