package com.framework

case class JobContext(data: Map[String, Any] = Map.empty) {
  def get[T](key: String): Option[T] = data.get(key).map(_.asInstanceOf[T])
  
  def put(key: String, value: Any): JobContext = copy(data = data + (key -> value))
  
  def merge(other: JobContext): JobContext = copy(data = data ++ other.data)
  
  def keys: Set[String] = data.keySet
}
