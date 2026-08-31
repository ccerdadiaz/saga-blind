package sagablind.loader

import sagablind.core.{SagaId, StepDescriptor}

import java.lang.management.ManagementFactory
import java.net.URLClassLoader
import java.nio.file.{Files, Paths}
import scala.util.{Try, Using}

// ── JarLoader ───────────────────────────────────────────────────────────────
// Loads a jar and instantiates SagaStepProvider implementations.
//
// One URLClassLoader per saga instance — total class isolation.
// Trade-off: same jar loaded N times for N concurrent sagas.
//
// MEMORY WARNING: each classloader retains all loaded classes in heap.
// With hundreds of concurrent sagas using large jars, heap pressure is real.
// Monitor via JarLoader.memoryStats() or the periodic logger in SagaRuntime.

class JarLoader:

  // active classloaders — one per saga instance
  private val loaders: scala.collection.mutable.Map[SagaId, URLClassLoader] =
    scala.collection.mutable.LinkedHashMap.empty

  /** Load jar for a saga instance and instantiate all declared providers.
   *  Returns Left if the jar is not found or any class fails to instantiate. */
  def load(
    sagaId:  SagaId,
    jarPath: String,
    steps:   List[StepDescriptor],
  ): Either[String, Map[String, SagaStepProvider]] =
    if !Files.exists(Paths.get(jarPath)) then
      Left(s"Jar not found: $jarPath")
    else
      Try:
        val url    = Paths.get(jarPath).toUri.toURL
        val loader = URLClassLoader(Array(url), getClass.getClassLoader)
        loaders(sagaId) = loader
        steps.map: descriptor =>
          val instance = loader
            .loadClass(descriptor.className)
            .getDeclaredConstructor()
            .newInstance()
            .asInstanceOf[SagaStepProvider]
          descriptor.id -> instance
        .toMap
      .toEither.left.map: e =>
        s"Failed to load jar '$jarPath' for saga '${sagaId.value}': ${e.getMessage}"

  /** Release the classloader for a saga — call when saga completes or is dropped.
   *  Allows GC to reclaim the loaded classes. */
  def release(sagaId: SagaId): Unit =
    loaders.remove(sagaId).foreach(_.close())

  /** Number of active classloaders — one per running saga instance. */
  def activeCount: Int = loaders.size

  /** JVM memory and classloading stats for observability.
   *  Log periodically to detect classloader leaks or heap pressure. */
  def memoryStats(): LoaderStats =
    val rt   = Runtime.getRuntime
    val bean = ManagementFactory.getClassLoadingMXBean
    LoaderStats(
      activeLoaders        = loaders.size,
      heapUsedMb           = (rt.totalMemory - rt.freeMemory) / (1024 * 1024),
      heapTotalMb          = rt.totalMemory / (1024 * 1024),
      heapMaxMb            = rt.maxMemory / (1024 * 1024),
      loadedClassCount     = bean.getLoadedClassCount,
      totalLoadedClasses   = bean.getTotalLoadedClassCount,
      unloadedClassCount   = bean.getUnloadedClassCount,
    )

  /** Log current stats — call from a scheduled task in SagaRuntime. */
  def logStats(): Unit =
    val s = memoryStats()
    println(
      s"[JarLoader] active=${s.activeLoaders} " +
      s"heap=${s.heapUsedMb}/${s.heapTotalMb}MB (max ${s.heapMaxMb}MB) " +
      s"classes loaded=${s.loadedClassCount} unloaded=${s.unloadedClassCount}"
    )

case class LoaderStats(
  activeLoaders:      Int,
  heapUsedMb:         Long,
  heapTotalMb:        Long,
  heapMaxMb:          Long,
  loadedClassCount:   Int,
  totalLoadedClasses: Long,
  unloadedClassCount: Long,
)
