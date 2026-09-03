package sagablind.goblin

import sagablind.loader.SagaStepProvider
import org.slf4j.LoggerFactory

class MeasurementsService extends SagaStepProvider:
  private val log = LoggerFactory.getLogger(getClass)
  def stepId = "measurements"

  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]] =
    val goblinId = args("goblinId").str
    log.debug(s"measuring goblin $goblinId")
    Thread.sleep(300)
    val result = Map("head" -> ujson.Num(58), "armLength" -> ujson.Num(42), "footSize" -> ujson.Num(38))
    log.debug(s"measurements complete: head=58 armLength=42 footSize=38")
    Right(result)

  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit] =
    log.debug("discarding measurements")
    Right(())
