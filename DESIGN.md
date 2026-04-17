# Job Management Framework Architecture (Akka Typed)

## Overview
The framework is designed to orchestrate complex jobs composed of multiple tasks (JobRunners). It supports sequential and parallel execution of these tasks while enforcing a standardized lifecycle for each one.

## Core Components

### 1. JobManager
The **JobManager** is the central orchestrator. Its responsibilities include:
- Receiving a `JobDefinition` which defines the workflow stages.
- Coordinating the execution of **Stages** (Sequential or Parallel).
- Managing the **JobContext**, a shared data store that travels through the stages.
- Handling failures and deciding whether to fail-fast or continue.

### 2. JobRunner
A **JobRunner** represents a single task within a stage. Every JobRunner must follow a 4-step lifecycle:
1.  **Validate**: Ensures all preconditions and input data are valid before starting.
2.  **Initialize**: Sets up necessary resources, connections, or specific context.
3.  **Run**: Executes the core business logic of the task.
4.  **Finalize**: Performs cleanup, logging, or post-execution reporting.

Each JobRunner is an Akka Typed Actor, ensuring isolation and type-safe messaging.

### 3. JobContext
The **JobContext** is a thread-safe container passed between stages. 
- Individual `JobRunners` can read from it and contribute new results to it.
- The `JobManager` merges context updates from multiple parallel runners into the main context.

### 4. Execution Flow
- **Sequential Stage**: The `JobManager` starts one `JobRunner`, waits for it to finish, and then proceeds to the next stage.
- **Parallel Stage**: The `JobManager` starts multiple `JobRunners` simultaneously. It waits for all runners to complete (successfully or with failure) before merging the results and moving to the next stage.

## Error Handling
The framework supports configurable error strategies:
- **Fail-Fast**: If any `JobRunner` fails, the entire Job is marked as failed, and subsequent stages are cancelled.
- **Continue-on-Failure**: The failure is logged/recorded in the context, but the Job proceeds to the next stage.

## Usage Example
Users define their jobs by implementing the `JobRunner` abstract class and defining a `JobDefinition` list:
```scala
val definition = JobDefinition(List(
  SequentialStage(RunnerConfig("ValidateInputs")),
  ParallelStage(List(
    RunnerConfig("ProcessPart1"),
    RunnerConfig("ProcessPart2")
  )),
  SequentialStage(RunnerConfig("GenerateReport"))
))
```
