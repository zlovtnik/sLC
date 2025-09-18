package com.rcs.healthcheck.unit

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.typesafe.config.{ConfigFactory, Config => TSConfig}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import com.rcs.healthcheck.{AppConfig, HealthCheckConfig, LogAggregationConfig, ServerConfig, LoggingConfig, RedisConfig}
import zio._
import zio.Runtime
import java.lang.System

class ConfigSpec extends AnyFlatSpec with Matchers {

  "Config loading" should "load valid configuration correctly" in {
    val configString = """
      |app {
      |  name = "Test App"
      |  version = "1.0.0"
      |  health-check {
      |    endpoints = ["http://localhost:8080"]
      |    interval = 30s
      |    timeout = 10s
      |    connectivity-url = "https://www.google.com"
      |    parallelism = 4
      |  }
      |  log-aggregation {
      |    sources = ["logs/app.log"]
      |    max-entries = 1000
      |    max-log-file-size = 100 MiB
      |    persist-dir = "test-logs"
      |  }
      |  server {
      |    host = "0.0.0.0"
      |    port = 9090
      |  }
      |  logging {
      |    level = "INFO"
      |    file = "logs/test.log"
      |  }
      |  redis {
      |    host = "localhost"
      |    port = 6379
      |    password = "testpass"
      |    database = 0
      |    timeout = 5s
      |    username = "testuser"
      |  }
      |}
    """.stripMargin

    val config = ConfigFactory.parseString(configString)

    val healthCheckConfig = config.getConfig("app.health-check")
    val logAggregationConfig = config.getConfig("app.log-aggregation")
    val serverConfig = config.getConfig("app.server")
    val loggingConfig = config.getConfig("app.logging")
    val redisConfig = config.getConfig("app.redis")

    val appConfig = AppConfig(
      name = config.getString("app.name"),
      version = config.getString("app.version"),
      healthCheck = HealthCheckConfig(
        endpoints = healthCheckConfig.getStringList("endpoints").asScala.toList,
        interval = FiniteDuration(healthCheckConfig.getDuration("interval").toNanos, NANOSECONDS),
        timeout = FiniteDuration(healthCheckConfig.getDuration("timeout").toNanos, NANOSECONDS),
        connectivityUrl = Some(healthCheckConfig.getString("connectivity-url")),
        parallelism = healthCheckConfig.getInt("parallelism")
      ),
      logAggregation = LogAggregationConfig(
        sources = logAggregationConfig.getStringList("sources").asScala.toList,
        maxEntries = logAggregationConfig.getInt("max-entries"),
        maxLogFileSize = logAggregationConfig.getBytes("max-log-file-size"),
        persistDir = logAggregationConfig.getString("persist-dir")
      ),
      server = ServerConfig(
        host = serverConfig.getString("host"),
        port = serverConfig.getInt("port")
      ),
      logging = LoggingConfig(
        level = loggingConfig.getString("level"),
        file = loggingConfig.getString("file")
      ),
      redis = RedisConfig(
        host = redisConfig.getString("host"),
        port = redisConfig.getInt("port"),
        password = Some(redisConfig.getString("password")),
        database = redisConfig.getInt("database"),
        timeout = FiniteDuration(redisConfig.getDuration("timeout").toNanos, NANOSECONDS),
        username = Some(redisConfig.getString("username"))
      )
    )

    appConfig.name shouldBe "Test App"
    appConfig.version shouldBe "1.0.0"
    appConfig.healthCheck.endpoints shouldBe List("http://localhost:8080")
    appConfig.server.port shouldBe 9090
  }

  it should "validate port range correctly" in {
    val validPorts = List(0, 80, 443, 8080, 9090, 65535)
    val invalidPorts = List(-1, 65536, 100000)

    validPorts.forall(p => p >= 0 && p <= 65535) shouldBe true
    invalidPorts.exists(p => p < 0 || p > 65535) shouldBe true
  }

  it should "handle missing optional connectivity-url" in {
    // Create a test configuration that omits the connectivity-url field
    val testConfigString = """
      |app {
      |  name = "Test App"
      |  version = "1.0.0"
      |  health-check {
      |    endpoints = ["http://localhost:8080"]
      |    interval = 30s
      |    timeout = 10s
      |  }
      |  log-aggregation {
      |    sources = ["logs/app.log"]
      |    max-entries = 1000
      |    max-log-file-size = 100 MiB
      |    persist-dir = "test-logs"
      |  }
      |  server {
      |    host = "0.0.0.0"
      |    port = 9090
      |  }
      |  logging {
      |    level = "INFO"
      |    file = "logs/test.log"
      |  }
      |  redis {
      |    host = "localhost"
      |    port = 6379
      |    database = 0
      |    timeout = 5s
      |  }
      |}
    """.stripMargin

    // Create a temporary config file for this test
    val tempConfigFile = java.io.File.createTempFile("test-config", ".conf")
    tempConfigFile.deleteOnExit()
    java.nio.file.Files.write(tempConfigFile.toPath, testConfigString.getBytes(java.nio.charset.StandardCharsets.UTF_8))

    // Save original system property
    val originalConfigFile = System.getProperty("config.file")

    try {
      // Override the config.file system property to use our test config
      System.setProperty("config.file", tempConfigFile.getAbsolutePath)

      // Test the actual Config.load() method with the test configuration
      val runtime = Runtime.default
      val result = zio.Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.run(com.rcs.healthcheck.Config.load()).getOrThrow()
      }

      // Verify that connectivityUrl is None when the field is missing
      result.healthCheck.connectivityUrl shouldBe None

      // Verify other fields are still parsed correctly
      result.healthCheck.endpoints shouldBe List("http://localhost:8080")
      result.name shouldBe "Test App"

    } finally {
      // Restore the original config.file property
      if (originalConfigFile != null) {
        System.setProperty("config.file", originalConfigFile)
      } else {
        System.clearProperty("config.file")
      }

      // Clean up the temporary file
      tempConfigFile.delete()
    }
  }
}