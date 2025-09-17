package com.rcs.healthcheck

import zio._
import sttp.client3.httpclient.zio.HttpClientZioBackend
import cats.data.Validated
import com.typesafe.scalalogging.LazyLogging
import org.slf4j.MDC
import java.util.UUID

object Main extends ZIOAppDefault with LazyLogging {

  private def withCorrelationId[R, E, A](operation: String)(zio: ZIO[R, E, A]): ZIO[R, E, A] = {
    val correlationId = UUID.randomUUID().toString
    ZIO.acquireReleaseWith {
      ZIO.succeed {
        MDC.put("correlationId", correlationId)
        MDC.put("operation", operation)
        MDC.put("timestamp", java.time.Instant.now().toString)
      }
    } { _ =>
      ZIO.succeed {
        MDC.remove("correlationId")
        MDC.remove("operation")
        MDC.remove("timestamp")
      }
    } { _ => zio }
  }

  private def logHealthCheckResult(result: CheckResult, prefix: String = ""): ZIO[Any, Nothing, Unit] = result match {
    case Validated.Valid(()) =>
      ZIO.succeed {
        MDC.put("healthCheckStatus", "success")
        MDC.put("checkType", if (prefix.isEmpty) "initial" else "periodic")
        logger.info(s"All ${prefix}health checks passed!")
        MDC.remove("healthCheckStatus")
        MDC.remove("checkType")
      }
    case Validated.Invalid(errors) =>
      val logMessage = s"${prefix.capitalize}health check failures: ${errors.toList.map(_.message).mkString(", ")}"
      ZIO.succeed {
        MDC.put("healthCheckStatus", "failure")
        MDC.put("checkType", if (prefix.isEmpty) "initial" else "periodic")
        MDC.put("errorCount", errors.size.toString)
        if (prefix.isEmpty) logger.error(logMessage) else logger.warn(logMessage)
        MDC.remove("healthCheckStatus")
        MDC.remove("checkType")
        MDC.remove("errorCount")
      }
  }

  private def logIndividualHealthChecks(checks: List[HealthCheck], prefix: String = ""): ZIO[HealthCheckService, Nothing, Unit] = {
    ZIO.foreach(checks) { check =>
      HealthCheckService.runCheck(check).map { result =>
        val checkName = check match {
          case DatabaseCheck => "Redis Database"
          case InternetCheck => "Internet Connectivity"
          case EndpointCheck(endpoint) => s"Endpoint $endpoint"
        }
        val status = result match {
          case Validated.Valid(_) => "✅ PASSED"
          case Validated.Invalid(_) => "❌ FAILED"
        }
        logger.info(s"${prefix}${checkName}: $status")
      }
    }.unit
  }

  val configLayer: ZLayer[Any, Throwable, AppConfig] = com.rcs.healthcheck.Config.live
  val appLayer: ZLayer[Any, Throwable, HealthCheckService with LogAggregator with LogPersistence with AppConfig] =
    HttpClientZioBackend.layer() ++ configLayer >>> HealthCheckService.live ++ LogAggregator.live ++ LogAggregator.logPersistenceLive ++ configLayer

  val program: ZIO[HealthCheckService with LogAggregator with LogPersistence with AppConfig, Throwable, Unit] = for {
    config <- ZIO.service[AppConfig]
    _ <- ZIO.succeed(logger.info(s"Starting ${config.name} v${config.version}"))

    // Health checks with correlation
    _ <- withCorrelationId("initial-health-check") {
      for {
        _ <- ZIO.succeed(logger.info("Performing initial health checks..."))
        checks = List(DatabaseCheck, InternetCheck) ++ config.healthCheck.endpoints.map(EndpointCheck)
        result <- HealthCheckService.checkAllHealths(checks)
        _ <- logIndividualHealthChecks(checks)
        _ <- logHealthCheckResult(result)
      } yield ()
    }

    // Log aggregation with correlation
    _ <- withCorrelationId("log-aggregation") {
      for {
        _ <- ZIO.succeed(logger.info("Aggregating logs..."))
        stats <- LogAggregator.aggregateStats(config.logAggregation.sources)
        _ <- ZIO.succeed {
          MDC.put("errorCount", stats.errorCount.toString)
          MDC.put("warningCount", stats.warningCount.toString)
          logger.info(s"Log stats: ${stats.errorCount} errors, ${stats.warningCount} warnings")
          MDC.remove("errorCount")
          MDC.remove("warningCount")
        }
        processingPipeline = LogAggregator.streamLogs(config.logAggregation.sources)
          .groupedWithin(config.logAggregation.maxEntries, zio.Duration.fromSeconds(1))
          .mapZIO(chunk => ZIO.serviceWithZIO[LogPersistence](_.persist(chunk)))
        _ <- processingPipeline.runDrain.catchAll(e => ZIO.succeed {
          MDC.put("error", e.getMessage)
          logger.error(s"Log processing failed: $e")
          MDC.remove("error")
        }).forkDaemon
      } yield ()
    }

    // Schedule periodic checks with correlation
    _ <- ZIO.succeed(logger.info(s"Setting up periodic health checks every ${config.healthCheck.interval}..."))
    fiber <- (withCorrelationId("periodic-health-check") {
      for {
        _ <- ZIO.succeed(logger.info("Running periodic health check..."))
        checks = List(DatabaseCheck, InternetCheck) ++ config.healthCheck.endpoints.map(EndpointCheck)
        periodicResult <- HealthCheckService.checkAllHealths(checks)
        _ <- logIndividualHealthChecks(checks, "[PERIODIC] ")
        _ <- logHealthCheckResult(periodicResult, "periodic ")
      } yield ()
    })
      .repeat(Schedule.spaced(java.time.Duration.ofMillis(config.healthCheck.interval.toMillis)))
      .fork

    _ <- ZIO.succeed(logger.info("Application started successfully. Press Ctrl+C to stop."))
    _ <- ZIO.never
  } yield ()

  override def run: ZIO[Any, Throwable, Unit] = {
    program.provideLayer(appLayer)
  }
}