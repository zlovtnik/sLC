# Health Check and Log Aggregator

A Scala application built with ZIO and Cats integration that monitors application health and aggregates logs from multiple sources.

## Features

- **Health Monitoring**: Periodically checks health endpoints of configured services
- **Log Aggregation**: Collects and processes logs from multiple file sources
- **ZIO Integration**: Built with ZIO for functional effects and concurrency
- **Cats Integration**: Uses Cats for functional programming abstractions
- **Configuration**: External configuration via Typesafe Config
- **HTTP Client**: Uses STTP for HTTP requests

## Project Structure

```
src/
├── main/
│   ├── resources/
│   │   └── application.conf    # Configuration file
│   └── scala/
│       └── com/example/healthcheck/
│           ├── Config.scala    # Configuration loading
│           ├── HealthCheckService.scala  # Health monitoring service
│           ├── LogAggregator.scala       # Log aggregation service
│           └── Main.scala     # Application entry point
└── test/
    └── scala/                 # Test files
```

## Configuration

The application is configured via `src/main/resources/application.conf`:

```hocon
app {
  name = "Health Check and Log Aggregator"
  version = "0.1.0"

  health-check {
    endpoints = [
      "http://localhost:8080",
      "http://localhost:8081"
    ]
    interval = 30s
    timeout = 10s
  }

  log-aggregation {
    sources = [
      "logs/application.log",
      "logs/error.log"
    ]
    max-entries = 1000
  }

  server {
    host = "0.0.0.0"
    port = 9090
  }

  logging {
    level = "INFO"
    file = "logs/health-check-app.log"
  }
}
```

## Dependencies

- **ZIO**: Functional effects and concurrency
- **Cats**: Functional programming abstractions
- **STTP**: HTTP client
- **Circe**: JSON processing
- **Typesafe Config**: Configuration management
- **Logback**: Logging framework

## Building and Running

### Prerequisites

- Java 11 or higher
- SBT (Scala Build Tool)

### Build

```bash
sbt compile
```

### Run

```bash
sbt run
```

### Test

```bash
sbt test
```

## Usage

The application will:

1. Load configuration from `application.conf`
2. Perform initial health checks on configured endpoints
3. Aggregate logs from configured sources
4. Set up periodic health monitoring
5. Log results and continue running

## Architecture

- **HealthCheckService**: Handles HTTP health checks using STTP
- **LogAggregator**: Processes log files and streams log entries
- **Config**: Loads and provides application configuration
- **Main**: Orchestrates services and manages application lifecycle

## Extending

- Add new health check types by extending `HealthCheckService`
- Add new log sources by extending `LogAggregator`
- Modify configuration by updating `application.conf`
- Add new services by creating new ZIO layers