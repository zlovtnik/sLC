package com.rcs.healthcheck

import zio._
import sttp.client3.httpclient.zio.HttpClientZioBackend
import cats.data.Validated
import com.typesafe.scalalogging.LazyLogging

object Main extends ZIOAppDefault with LazyLogging {

  private def logHealthCheckResult(result: CheckResult, prefix: String = ""): ZIO[Any, Nothing, Unit] = result match {
    case Validated.Valid(()) => ZIO.succeed(logger.info(s"All ${prefix}health checks passed!"))
    case Validated.Invalid(errors) => ZIO.succeed(logger.warn(s"${prefix.capitalize}health check failures: ${errors.toList.map(_.message).mkString(", ")}"))
  }

  val configLayer: ZLayer[Any, Throwable, AppConfig] = com.rcs.healthcheck.Config.live
  val appLayer: ZLayer[Any, Throwable, HealthCheckService with LogAggregator with LogPersistence with AppConfig] =
    HttpClientZioBackend.layer() ++ configLayer >>> HealthCheckService.live ++ LogAggregator.live ++ LogAggregator.logPersistenceLive ++ configLayer

  val program: ZIO[HealthCheckService with LogAggregator with LogPersistence with AppConfig, Throwable, Unit] = for {
    config <- ZIO.service[AppConfig]
    _ <- ZIO.succeed(logger.info(s"Starting ${config.name} v${config.version}"))

    // Health checks
    _ <- ZIO.succeed(logger.info("Performing initial health checks..."))
    checks = List(DatabaseCheck, InternetCheck) ++ config.healthCheck.endpoints.map(EndpointCheck)
    result <- HealthCheckService.checkAllHealths(checks)
    _ <- logHealthCheckResult(result)

    // Log aggregation
    _ <- ZIO.succeed(logger.info("Aggregating logs..."))
    stats <- LogAggregator.aggregateStats(config.logAggregation.sources)
    _ <- ZIO.succeed(logger.info(s"Log stats: ${stats.errorCount} errors, ${stats.warningCount} warnings"))
    processingPipeline = LogAggregator.streamLogs(config.logAggregation.sources)
      .groupedWithin(config.logAggregation.maxEntries, zio.Duration.fromSeconds(1))
      .mapZIO(chunk => ZIO.serviceWithZIO[LogPersistence](_.persist(chunk)))
    _ <- processingPipeline.runDrain.catchAll(e => ZIO.succeed(logger.error(s"Log processing failed: $e"))).forkDaemon

    // Schedule periodic checks
    _ <- ZIO.succeed(logger.info(s"Setting up periodic health checks every ${config.healthCheck.interval}..."))
    fiber <- (for {
      _ <- ZIO.succeed(logger.info("Running periodic health check..."))
      periodicResult <- HealthCheckService.checkAllHealths(checks)
      _ <- logHealthCheckResult(periodicResult, "periodic ")
    } yield ())
      .repeat(Schedule.spaced(java.time.Duration.ofMillis(config.healthCheck.interval.toMillis)))
      .fork

    _ <- ZIO.succeed(logger.info("Application started successfully. Press Ctrl+C to stop."))
    _ <- ZIO.never
  } yield ()

  override def run: ZIO[Any, Throwable, Unit] = {
    program.provideLayer(appLayer)
  }
}