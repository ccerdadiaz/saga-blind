package sagablind.loader

import sagablind.core.{SagaId, StepDescriptor}
import sagablind.{SagaLogger, BoundLogger}

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
//
// TODO good-first-issue idiomatic-scala: replace release() with
// scala.util.Using — URLClassLoader implements AutoCloseable

class JarLoader(logger: SagaLogger = SagaLogger.noOp):
  private val log = logger.forComponent("loader")

  private val loaders: scala.collection.mutable.Map[SagaId, URLClassLoader] =
    scala.collection.mutable.LinkedHashMap.empty

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

  def release(sagaId: SagaId): Unit =
    loaders.remove(sagaId).foreach(_.close())

  def activeCount: Int = loaders.size

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

  def logStats(): Unit =
    val s = memoryStats()
    log.info(
      s"active=${s.activeLoaders} " +
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
