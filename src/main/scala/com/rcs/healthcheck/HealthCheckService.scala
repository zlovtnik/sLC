package com.rcs.healthcheck

import zio._
import sttp.client3._
import cats.implicits._
import redis.clients.jedis.Jedis

// Define a sealed trait for our health checks
sealed trait HealthCheck
case object DatabaseCheck extends HealthCheck
case object InternetCheck extends HealthCheck
case class EndpointCheck(endpoint: String) extends HealthCheck

trait HealthCheckService {
  def runCheck(check: HealthCheck): ZIO[Any, Nothing, CheckResult]
  def checkAllHealths(checks: List[HealthCheck]): ZIO[Any, Nothing, CheckResult]
}

object HealthCheckService {
  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[HealthCheckService])

  def live: ZLayer[SttpBackend[Task, Any] with AppConfig, Throwable, HealthCheckService] =
    ZLayer.fromZIO {
      (for {
        backend <- ZIO.service[SttpBackend[Task, Any]]
        config <- ZIO.service[AppConfig]
        connectivityUrl <- ZIO.fromOption(config.healthCheck.connectivityUrl)
          .orElseFail(new RuntimeException("Missing healthCheck.connectivityUrl in application configuration. Please set this in your application.conf or environment variables."))
        // Store Redis configuration for creating connections per health check
        redisConf = config.redis
      } yield new HealthCheckService {
        private val redisTimeoutDuration = zio.Duration.fromNanos(redisConf.timeout.toNanos)
        private val healthCheckTimeoutDuration = zio.Duration.fromNanos(config.healthCheck.timeout.toNanos)

        private def toMillisTimeout(duration: zio.Duration): Int = {
          math.min(duration.toMillis, Int.MaxValue.toLong).toInt
        }

        override def runCheck(check: HealthCheck): ZIO[Any, Nothing, CheckResult] = check match {
          case DatabaseCheck =>
            // Redis connectivity health check using Jedis
            ZIO.scoped {
              val millis = toMillisTimeout(redisTimeoutDuration)
              val username = redisConf.username.getOrElse("default")
              for {
                jedis <- ZIO.fromAutoCloseable(
                  ZIO.attempt(new Jedis(redisConf.host, redisConf.port, millis, millis))
                )
                _ <- ZIO.foreachDiscard(redisConf.password) { pwd =>
                  ZIO.attempt {
                    try jedis.auth(username, pwd)    // Redis >= 6 (ACL)
                    catch {
                      case _: redis.clients.jedis.exceptions.JedisDataException =>
                        jedis.auth(pwd)               // Redis < 6 fallback
                    }
                  }
                }
                pong <- ZIO.attemptBlocking(jedis.ping())
              } yield pong == "PONG"
            }.foldZIO(
              error => {
                val errorMsg = error match {
                  case _: java.net.UnknownHostException =>
                    s"Redis host '${redisConf.host}' not found. Please check your Redis configuration."
                  case _: java.net.ConnectException =>
                    s"Cannot connect to Redis at ${redisConf.host}:${redisConf.port}. Is Redis server running?"
                  case _: redis.clients.jedis.exceptions.JedisConnectionException =>
                    s"Redis connection failed: ${error.getMessage}"
                  case e: redis.clients.jedis.exceptions.JedisDataException
                      if Option(e.getMessage).exists(_.contains("NOAUTH")) =>
                    "Redis authentication required. Please configure a password."
                  case e: redis.clients.jedis.exceptions.JedisDataException
                      if Option(e.getMessage).exists(_.toUpperCase.contains("WRONGPASS")) =>
                    "Redis authentication failed (wrong password)."
                  case _: redis.clients.jedis.exceptions.JedisAccessControlException =>
                    "Redis ACL denied the requested operation."
                  case _ =>
                    s"Redis health check failed: ${error.getMessage}"
                }
                ZIO.succeed(DatabaseError(errorMsg).invalidNel[Unit])
              },
              success => if (success) ZIO.succeed(().validNel[HealthError])
                         else ZIO.succeed(DatabaseError("Redis ping failed - no PONG response").invalidNel[Unit])
            ).timeout(healthCheckTimeoutDuration)
              .map(_.getOrElse(DatabaseError(s"Redis health check timed out after ${healthCheckTimeoutDuration.toMillis}ms").invalidNel[Unit]))
              .asInstanceOf[ZIO[Any, Nothing, CheckResult]]
          case InternetCheck =>
            // Check internet connectivity by pinging configured URL
            val request = basicRequest.get(uri"$connectivityUrl").response(asString)
            val check = backend.send(request).foldZIO(
              err => ZIO.succeed(ConnectivityError(s"Internet connectivity check failed: ${err.getMessage}").invalidNel[Unit]),
              resp =>
                if (resp.code.isSuccess) ZIO.succeed(().validNel[HealthError])
                else ZIO.succeed(ConnectivityError(s"Connectivity check returned ${resp.code}").invalidNel[Unit])
            )
            check.timeout(healthCheckTimeoutDuration)
              .map(_.getOrElse(ConnectivityError(s"Internet connectivity check timed out after ${healthCheckTimeoutDuration.toMillis}ms").invalidNel[Unit]))
          case EndpointCheck(endpoint) =>
            val base    = uri"$endpoint"
            val request = basicRequest
              .get(base.addPath("health"))
              .acceptEncoding("identity")
              .header("Accept", "text/plain, */*;q=0.1")
              .response(asString)
            val check = backend.send(request).foldZIO(
              _ => ZIO.succeed(EndpointError(s"Endpoint $endpoint is unhealthy").invalidNel[Unit]),
              response =>
                if (response.code.isSuccess) ZIO.succeed(().validNel[HealthError])
                else ZIO.succeed(EndpointError(s"Endpoint $endpoint returned error: ${response.code}").invalidNel[Unit])
            )
            // Race with timeout
            check.timeout(healthCheckTimeoutDuration)
              .map(_.getOrElse(EndpointError(s"Endpoint $endpoint timed out").invalidNel[Unit]))
        }

        override def checkAllHealths(checks: List[HealthCheck]): ZIO[Any, Nothing, CheckResult] = {
          ZIO.foreach(checks)(runCheck)
            .map(_.combineAll)
        }
      }).tapError { error =>
        ZIO.succeed(logger.error("Failed to provision HealthCheckService", error))
      }
    }

  def runHealthCheck(check: HealthCheck): ZIO[HealthCheckService, Nothing, CheckResult] =
    ZIO.serviceWithZIO[HealthCheckService](_.runCheck(check))

  def checkAllHealthChecks(checks: List[HealthCheck]): ZIO[HealthCheckService, Nothing, CheckResult] =
    ZIO.serviceWithZIO[HealthCheckService](_.checkAllHealths(checks))
}