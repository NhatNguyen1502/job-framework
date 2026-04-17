package com.example

import akka.actor.typed.ActorSystem
import com.framework.LazyJobManager
import com.framework.JobProtocol._
import com.framework.{JobDefinition, SequentialStage, ParallelStage, RunnerConfig}
import scala.concurrent.duration._

object MainApp extends App {
  println("=" * 60)
  println("Starting Akka Job Framework Demo - LAZY MANAGER")
  println("=" * 60)
  
  val system: ActorSystem[JobCommand] = ActorSystem(LazyJobManager(MyRunnerFactory), "JobSystem")

  val myJob = JobDefinition(List(
    // Stage 0: Sequential - Validate inputs
    SequentialStage(
      RunnerConfig("ValidateInputs"),
      timeout = Some(5.seconds)
    ),
    
    // Stage 1: Parallel - Process two parts concurrently
    ParallelStage(
      List(
        RunnerConfig("ProcessPart1"),
        RunnerConfig("ProcessPart2")
      ),
      timeout = Some(10.seconds)
    ),
    
    // Stage 2: Sequential - Generate final report
    SequentialStage(
      RunnerConfig("GenerateReport"),
      timeout = Some(5.seconds)
    )
  ))

  println("\n📋 Job Definition:")
  println("  Stage 0: ValidateInputs (Sequential, timeout=5s)")
  println("  Stage 1: ProcessPart1 + ProcessPart2 (Parallel, timeout=10s)")
  println("  Stage 2: GenerateReport (Sequential, timeout=5s)")
  println()

  system ! StartJob(myJob)

  // Keep app running to see all logs
  Thread.sleep(3000)
  
  println("\n" + "=" * 60)
  println("Demo completed! Terminating system...")
  println("=" * 60)
  system.terminate()
}
