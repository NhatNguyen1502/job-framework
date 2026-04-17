package com.framework

import akka.actor.typed.ActorRef

/**
 * Protocol messages for Job-level communication
 */
object JobProtocol {
  
  sealed trait JobCommand
  
  case class StartJob(definition: JobDefinition) extends JobCommand
  
  case class JobRunnerResponse(runnerName: String, result: JobResult) extends JobCommand

  // Internal timeout signal
  private[framework] case class StageTimeout(stageIndex: Int) extends JobCommand

  sealed trait JobResult
  
  case class JobSucceeded(updatedContext: JobContext) extends JobResult
  
  case class JobFailed(reason: String) extends JobResult
}
