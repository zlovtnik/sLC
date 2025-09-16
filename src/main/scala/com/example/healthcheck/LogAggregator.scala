package com.example.healthcheck

import zio._
import zio.stream._
import java.nio.file.{Path, Paths}
import java.io.IOException
import scala.io.Source

case class LogEntry(source: String, timestamp: Long, level: String, message: String)

trait LogAggregator {
  def aggregateLogs(sources: List[String]): ZIO[Any, Throwable, List[LogEntry]]
  def streamLogs(sources: List[String]): ZStream[Any, Throwable, LogEntry]
}

object LogAggregator {
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

        private def streamLogsFromSource(source: String): ZStream[Any, Throwable, LogEntry] = {
          ZStream.fromZIO(collectLogsFromSource(source)).flatMap(entries => ZStream.fromIterable(entries))
        }
      }
    }

  def aggregateLogs(sources: List[String]): ZIO[LogAggregator, Throwable, List[LogEntry]] =
    ZIO.serviceWithZIO(_.aggregateLogs(sources))

  def streamLogs(sources: List[String]): ZStream[LogAggregator, Throwable, LogEntry] =
    ZStream.serviceWithStream(_.streamLogs(sources))
}