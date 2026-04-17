# Job Manager Comparison

## Overview

This framework now provides **two different job management approaches**:

### 1. **LazyJobManager** - On-Demand Creation
### 2. **EagerJobManager** - Pre-Allocation

---

## LazyJobManager (Dynamic Creation)

### Architecture:
```
StartJob → Stage 0 needs → Create Runner0 → Execute → Destroy
         → Stage 1 needs → Create Runner1 → Execute → Destroy
         → ...
```

### Characteristics:
- ✅ **Lazy creation**: Runners created only when their stage starts
- ✅ **Auto cleanup**: Runners destroyed after completion
- ✅ **Memory efficient**: Low memory footprint
- ✅ **Flexible**: Can create different runners based on previous stage results
- ⚠️ **Creation overhead**: Spawn/stop overhead per stage
- ⚠️ **No reuse**: Cannot reuse runners across stages

### Best For:
- Jobs with many stages
- Dynamic workflows (next stage depends on previous results)
- Long-running jobs with limited resources
- One-off executions

### Usage:
```scala
val lazySystem = ActorSystem(
  LazyJobManager(MyRunnerFactory), 
  "LazyJobSystem"
)
lazySystem ! StartJob(jobDefinition)
```

---

## EagerJobManager (Pre-Allocation)

### Architecture:
```
StartJob → Pre-allocate ALL runners → Trigger Stage 0 → Trigger Stage 1 → ...
                                    ↓ (all in idle state)
           [Runner0, Runner1, Runner2, Runner3...]
```

### Characteristics:
- ✅ **Pre-allocation**: All runners created upfront at job start
- ✅ **No creation overhead**: Runners stay alive, just triggered
- ✅ **Clear hierarchy**: Supervisor has all children from start
- ✅ **Potential reuse**: Runners could be reused (not implemented yet)
- ⚠️ **Higher memory**: All runners exist simultaneously
- ⚠️ **Fixed set**: Must know all runners at job start
- ⚠️ **Stateful runners**: Requires StatefulJobRunner base class

### Best For:
- Jobs with fixed, known runner set
- Performance-critical applications
- Jobs that rerun frequently
- Microservices-style architectures

### Usage:
```scala
val eagerSystem = ActorSystem(
  EagerJobManager(MyStatefulRunnerFactory), 
  "EagerJobSystem"
)
eagerSystem ! StartJob(jobDefinition)
```

---

## Key Differences

| Aspect | LazyJobManager | EagerJobManager |
|--------|----------------|-----------------|
| **Runner Creation** | On-demand per stage | All upfront |
| **Runner Lifecycle** | Create → Run → Stop | Create → Idle → Run → Idle |
| **Memory Usage** | Low (only active runners) | High (all runners always) |
| **Performance** | Spawn overhead per stage | No overhead after init |
| **Flexibility** | Dynamic workflows | Fixed workflow |
| **Base Class** | `JobRunner` | `StatefulJobRunner` |
| **Factory** | `RunnerFactory` | `StatefulRunnerFactory` |
| **Cleanup** | Auto per stage | Manual at job end |

---

## Implementation Details

### LazyJobManager Flow:
```
1. StartJob received
2. executeStage(0) called
3. Factory.create() → spawn runner → StartRunner
4. Runner executes lifecycle → stops
5. JobRunnerResponse received
6. executeStage(1) called
7. Repeat until all stages done
```

### EagerJobManager Flow:
```
1. StartJob received
2. Extract all unique runners from ALL stages
3. Pre-allocate all runners (factory.createStateful())
4. executeStage(0) called
5. Send StartRunner to already-existing runner
6. Runner executes → returns to idle (doesn't stop!)
7. JobRunnerResponse received
8. executeStage(1) called → reuse existing runner
9. After all stages: stop all runners
```

---

## Code Examples

### Lazy Runner (one-shot):
```scala
class MyRunner(name: String) extends JobRunner(name) {
  protected def validate(ctx: JobContext): Future[Boolean] = ???
  protected def initialize(ctx: JobContext): Future[JobContext] = ???
  protected def run(ctx: JobContext): Future[JobContext] = ???
  protected def finalize(ctx: JobContext, success: Boolean): Future[Unit] = ???
}

object MyFactory extends RunnerFactory {
  def create(name: String, props: Map[String, Any]): JobRunner = 
    new MyRunner(name)
}
```

### Stateful Runner (reusable):
```scala
class MyStatefulRunner(name: String) extends StatefulJobRunner(name) {
  protected def validate(ctx: JobContext): Future[Boolean] = ???
  protected def initialize(ctx: JobContext): Future[JobContext] = ???
  protected def run(ctx: JobContext): Future[JobContext] = ???
  protected def finalize(ctx: JobContext, success: Boolean): Future[Unit] = ???
}

object MyStatefulFactory extends StatefulRunnerFactory {
  def createStateful(name: String, props: Map[String, Any]): StatefulJobRunner = 
    new MyStatefulRunner(name)
}
```

---

## Execution Output Comparison

### LazyJobManager:
```
[LazyJobManager] Starting Job Execution
[LazyJobManager] Executing Sequential Stage: ValidateInputs
Runner ValidateInputs: Starting lifecycle
Runner ValidateInputs: Completed
[LazyJobManager] Executing Parallel Stage with 2 runners
Runner ProcessPart1: Starting lifecycle
Runner ProcessPart2: Starting lifecycle
...
```

### EagerJobManager:
```
[EagerJobManager] Starting Job Execution - Pre-allocating runners
[EagerJobManager] Pre-allocating runner: ValidateInputs
[EagerJobManager] Pre-allocating runner: ProcessPart1
[EagerJobManager] Pre-allocating runner: ProcessPart2
[EagerJobManager] Pre-allocating runner: GenerateReport
[EagerJobManager] Pre-allocated 4 runners
[EagerJobManager] Triggering Sequential Stage: ValidateInputs
[StatefulRunner:ValidateInputs] Received execution trigger
[StatefulRunner:ValidateInputs] Execution completed, returning to idle
...
```

---

## When to Use Which?

### Use LazyJobManager when:
- ✅ You have dynamic workflows
- ✅ Next stage depends on previous results
- ✅ Memory is constrained
- ✅ Runner set is not known upfront
- ✅ Simple one-off jobs

### Use EagerJobManager when:
- ✅ You have fixed, predictable workflows
- ✅ Performance is critical
- ✅ You want clear supervision hierarchy
- ✅ Memory is not a concern
- ✅ Jobs run frequently with same structure
- ✅ Future runner reuse is desired

---

## Future Enhancements

### For LazyJobManager:
- Runner pooling (reuse destroyed runners)
- Conditional stage execution
- Dynamic stage insertion

### For EagerJobManager:
- True runner reuse across multiple job executions
- Runner warm-up/cool-down phases
- Resource reservation and allocation
- Circuit breaker integration

---

## Conclusion

Both managers are production-ready and solve different problems:

- **LazyJobManager**: Flexibility and efficiency
- **EagerJobManager**: Performance and predictability

Choose based on your specific requirements!
