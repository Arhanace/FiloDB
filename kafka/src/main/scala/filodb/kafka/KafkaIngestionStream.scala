package filodb.kafka

import scala.concurrent.blocking

import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Task
import monix.kafka.{CommittableMessage, KafkaConsumerConfig, KafkaConsumerObservable}
import monix.reactive.Observable
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition

import filodb.coordinator.{IngestionStream, IngestionStreamFactory}
import filodb.core.binaryrecord2.RecordContainer
import filodb.core.memstore.SomeData
import filodb.core.metadata.Schemas

/**
  * KafkaIngestionStream creates an IngestionStream of RecordContainers by consuming from a single partition
  * in Apache Kafka.  Each message in Kafka is expected to be a RecordContainer's bytes.
  *
  * @param config  the Typesafe source config to use, see sourceconfig in `docs/ingestion.md`
  * @param schemas the Schemas to support reading from
  * @param shard   the shard / partition
  */
class KafkaIngestionStream(config: Config,
                           schemas: Schemas,
                           shard: Int,
                           offset: Option[Long]) extends IngestionStream with StrictLogging {

  private val _sc = new SourceConfig(config, shard)
  import _sc._

  protected def sc: SourceConfig = _sc

  private val tp = new TopicPartition(IngestionTopic, shard)

  logger.info(s"Creating consumer assigned to topic ${tp.topic} partition ${tp.partition} offset $offset")
  protected lazy val consumer = createKCO(sc, tp, offset)

  private[filodb] def createKCO(sourceConfig: SourceConfig,
                topicPartition: TopicPartition,
                offset: Option[Long]): KafkaConsumerObservable[Long, Any, CommittableMessage[Long, Any]] = {

    val consumer = createConsumer(sourceConfig, topicPartition, offset)
    val cfg = KafkaConsumerConfig(sourceConfig.asConfig)
    require(!cfg.enableAutoCommit, "'enable.auto.commit' must be false.")

    KafkaConsumerObservable.manualCommit(cfg, consumer)
  }

  private[filodb] def createConsumer(sourceConfig: SourceConfig,
                                     topicPartition: TopicPartition,
                                     offset: Option[Long]): Task[KafkaConsumer[Long, Any]] = {
    import collection.JavaConverters._

    Task {
      val props = sourceConfig.asProps
      if (sourceConfig.LogConfig) logger.info(s"Consumer properties: $props")

      blocking {
        val consumer = new KafkaConsumer(props)
        consumer.assign(List(topicPartition).asJava)
        offset.foreach { off => consumer.seek(topicPartition, off) }
        consumer.asInstanceOf[KafkaConsumer[Long, Any]]
      }
    }
  }

  /**
   * Returns a reactive Observable stream of RecordContainers from Kafka.
   * NOTE: the scheduler used makes a huge difference.
   * The IO scheduler allows all the Kafka partition inits to happen at beginning,
   *   & allows lots of simultaneous streams to stream efficiently.
   * The global scheduler allows parallel stream init, multiple streams to consume in parallel, but REALLY slowly
   * The computation() sched seems to behave like a round robin: it seems to take turns pulling from only
   *   one or a few partitions at a time; probably doesn't work when you have lots of streams
   */
  override def get: Observable[SomeData] =
    consumer.map { msg =>
      SomeData(msg.record.value.asInstanceOf[RecordContainer], msg.record.offset)
    }

  override def teardown(): Unit = {
    logger.info(s"Shutting down stream $tp")
    // consumer does callback to close but confirm
   }
}

/** The no-arg constructor `IngestionFactory` for the kafka ingestion stream.
  * INTERNAL API.
  */
class KafkaIngestionStreamFactory extends IngestionStreamFactory {

  /**
    * Returns an IngestionStream that can be subscribed to for a given shard,
    * or in this case, a single partition (1 shard => 1 Kafka partition) of a given Kafka topic.
    */
  override def create(config: Config, schemas: Schemas, shard: Int, offset: Option[Long]): IngestionStream = {
    new KafkaIngestionStream(config, schemas, shard, offset)
  }
}
