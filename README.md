# Akka Job Framework

A flexible and type-safe job orchestration framework built with Akka Typed for Scala. This framework allows you to define complex workflows composed of sequential and parallel tasks with standardized lifecycle management.

## Features

- **Standardized Lifecycle**: Every job runner follows a 4-step lifecycle (Validate → Initialize → Run → Finalize)
- **Sequential & Parallel Execution**: Mix sequential and parallel stages in your workflows
- **Type-Safe Messaging**: Built on Akka Typed for compile-time safety
- **Shared Context**: Thread-safe context management across job stages
- **Flexible Error Handling**: Support for fail-fast and continue-on-failure strategies
- **Actor Isolation**: Each job runner runs as an isolated Akka actor

## Architecture

The framework consists of four core components:

1. **JobManager**: Central orchestrator that coordinates workflow execution
2. **JobRunner**: Individual task actors that follow the standardized lifecycle
3. **JobContext**: Thread-safe shared data store passed between stages
4. **JobDefinition**: Declarative workflow definition with stages

See [DESIGN.md](DESIGN.md) for detailed architecture documentation.

## Getting Started

### Prerequisites

- Scala 2.13.12
- SBT 1.x
- Akka 2.8.5

### Installation

Clone the repository:
```bash
git clone <repository-url>
cd job-framework
```

Build the project:
```bash
sbt compile
```

### Usage Example

Define your job runners by extending the `JobRunner` class:

```scala
class ValidateInputsRunner extends JobRunner {
  override def validate(context: JobContext): Unit = {
    // Validate preconditions
  }
  
  override def initialize(context: JobContext): Unit = {
    // Initialize resources
  }
  
  override def run(context: JobContext): JobContext = {
    // Execute business logic
    context.updated("validation", "passed")
  }
  
  override def finalize(context: JobContext): Unit = {
    // Cleanup and logging
  }
}
```

Define your workflow:

```scala
val definition = JobDefinition(List(
  SequentialStage(RunnerConfig("ValidateInputs")),
  ParallelStage(List(
    RunnerConfig("ProcessPart1"),
    RunnerConfig("ProcessPart2")
  )),
  SequentialStage(RunnerConfig("GenerateReport"))
))

// Start the job
jobManager ! StartJob(definition)
```

## Project Structure

```
job-framework/
├── src/main/scala/com/
│   ├── example/          # Example implementations
│   └── framework/        # Core framework code
├── build.sbt             # SBT build configuration
├── DESIGN.md             # Architecture documentation
└── MANAGER_COMPARISON.md # Design comparisons
```

## Error Handling

The framework supports two error strategies:

- **Fail-Fast**: Job stops immediately on first failure
- **Continue-on-Failure**: Failures are logged, job continues

## Documentation

- [DESIGN.md](DESIGN.md) - Detailed architecture and design decisions
- [MANAGER_COMPARISON.md](MANAGER_COMPARISON.md) - Framework comparison analysis

## Development

Run the project:
```bash
sbt run
```
