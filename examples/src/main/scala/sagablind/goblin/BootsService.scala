package sagablind.goblin

import sagablind.loader.SagaStepProvider
import org.slf4j.LoggerFactory

class BootsService extends SagaStepProvider:
  private val log = LoggerFactory.getLogger(getClass)
  def stepId = "boots"

  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]] =
    val footSize = args("footSize").num.toInt
    log.debug(s"crafting boots for foot size $footSize")
    Thread.sleep(400)
    val bootId = s"B-${scala.util.Random.nextInt(9000) + 1000}"
    log.debug(s"crafted boots $bootId size EU-$footSize")
    Right(Map("bootId" -> ujson.Str(bootId), "bootSize" -> ujson.Str(s"EU-$footSize")))

  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit] =
    val bootId = args.get("bootId").map(_.str).getOrElse("unknown")
    log.debug(s"returning boots $bootId to stock")
    Right(())
