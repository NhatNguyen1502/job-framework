package com.example

import akka.actor.typed.ActorSystem
import com.framework.{LazyJobManager, EagerJobManager}
import com.framework.JobProtocol._
import com.framework.{JobDefinition, SequentialStage, ParallelStage, RunnerConfig}
import scala.concurrent.duration._

/**
 * Demo comparing LazyJobManager vs EagerJobManager
 */
object ComparisonDemo extends App {
  
  val jobDefinition = JobDefinition(List(
    // Stage 0: Sequential validation
    SequentialStage(
      RunnerConfig("ValidateInputs"),
      timeout = Some(5.seconds)
    ),
    
    // Stage 1: Parallel processing
    ParallelStage(
      List(
        RunnerConfig("ProcessPart1"),
        RunnerConfig("ProcessPart2")
      ),
      timeout = Some(10.seconds)
    ),
    
    // Stage 2: Sequential report generation  
    SequentialStage(
      RunnerConfig("GenerateReport"),
      timeout = Some(5.seconds)
    )
  ))

  println("=" * 80)
  println("LAZY JOB MANAGER - Creates runners on-demand")
  println("=" * 80)
  
  val lazySystem: ActorSystem[JobCommand] = 
    ActorSystem(LazyJobManager(MyRunnerFactory), "LazyJobSystem")
  
  lazySystem ! StartJob(jobDefinition)
  Thread.sleep(3000)
  lazySystem.terminate()
  
  println("\n\n")
  Thread.sleep(1000)
  
  println("=" * 80)
  println("EAGER JOB MANAGER - Pre-allocates all runners")
  println("=" * 80)
  
  val eagerSystem: ActorSystem[JobCommand] = 
    ActorSystem(EagerJobManager(MyStatefulRunnerFactory), "EagerJobSystem")
  
  eagerSystem ! StartJob(jobDefinition)
  Thread.sleep(3000)
  eagerSystem.terminate()
  
  Thread.sleep(500)
  println("\n" + "=" * 80)
  println("Demo completed!")
  println("=" * 80)
}
