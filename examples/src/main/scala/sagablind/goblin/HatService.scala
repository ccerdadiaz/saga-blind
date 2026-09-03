package sagablind.goblin

import sagablind.loader.SagaStepProvider
import org.slf4j.LoggerFactory

class HatService extends SagaStepProvider:
  private val log = LoggerFactory.getLogger(getClass)
  def stepId = "getHat"

  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]] =
    val goblinId = args("goblinId").str
    val head     = args("head").num.toInt
    log.debug(s"fitting helmet size $head for goblin $goblinId")
    Thread.sleep(600)
    val serial = s"H-${scala.util.Random.nextInt(9000) + 1000}"
    log.debug(s"helmet $serial assigned to goblin $goblinId")
    Right(Map("hatSerialNumber" -> ujson.Str(serial)))

  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit] =
    val serial = args.get("hatSerialNumber").map(_.str).getOrElse("unknown")
    log.debug(s"returning helmet $serial — sending scaffolding team")
    Thread.sleep(800)
    Right(())
