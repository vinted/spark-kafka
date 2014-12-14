package com.tresata.spark.kafka

import java.io.{ File, IOException }
import java.nio.ByteBuffer
import java.net.InetSocketAddress
import java.util.{ Properties, UUID }

import kafka.admin.TopicCommand
import kafka.common.TopicAndPartition
import kafka.producer.{ KeyedMessage, ProducerConfig, Producer }
import kafka.utils.ZKStringSerializer
import kafka.serializer.{ StringDecoder, StringEncoder }
import kafka.server.{ KafkaConfig, KafkaServer }

import org.I0Itec.zkclient.ZkClient

import org.apache.zookeeper.server.ZooKeeperServer
import org.apache.zookeeper.server.NIOServerCnxnFactory

import org.apache.spark.{ SparkConf, SparkContext }

import org.scalatest.{ FunSpec, BeforeAndAfterAll }

class KafkaRDDSpec extends FunSpec with BeforeAndAfterAll {
  import KafkaTestUtils._

  val zkConnect = "localhost:2181"
  val zkConnectionTimeout = 6000
  val zkSessionTimeout = 6000

  val brokerPort = 9092
  val brokerProps = getBrokerConfig(brokerPort, zkConnect)
  val brokerConf = new KafkaConfig(brokerProps)

  val sparkConf = new SparkConf(false)
    .setMaster("local")
    .setAppName("test")
    .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")

  protected var zookeeper: EmbeddedZookeeper = _
  protected var zkClient: ZkClient = _
  protected var server: KafkaServer = _
  protected var producer: Producer[String, String] = _
  protected var sc: SparkContext = _

  override def beforeAll {
    // zookeeper server startup
    zookeeper = new EmbeddedZookeeper(zkConnect)
    zkClient = new ZkClient(zkConnect, zkSessionTimeout, zkConnectionTimeout, ZKStringSerializer)

    // kafka broker startup
    server = new KafkaServer(brokerConf)
    server.startup()
    Thread.sleep(2000)

    // spark
    sc = new SparkContext(sparkConf)
  }

  override def afterAll {
    sc.stop()

    producer.close()
    server.shutdown()
    brokerConf.logDirs.foreach { f => deleteRecursively(new File(f)) }

    zkClient.close()
    zookeeper.shutdown()
  }

  describe("KafkaRDD") {
    it("should post messages to kafka") {
      val topic = "topic1"
      createTopic(topic)

      val rdd = sc.parallelize(List(("1", "a"), ("2", "b")))
      KafkaRDD.writeWithKeysToKafka(rdd, topic, new ProducerConfig(getProducerConfig(brokerConf.hostName + ":" + brokerConf.port)))
      Thread.sleep(1000)
    }

    it("should post messages to kafka without keys") {
      val topic = "topic1"

      val rdd = sc.parallelize(List("b", "c", "c", "c"))
      KafkaRDD.writeToKafka(rdd, topic, new ProducerConfig(getProducerConfig(brokerConf.hostName + ":" + brokerConf.port)))
      Thread.sleep(1000)
    }

    it("should collect the correct messages without provided offsets") {
      val topic = "topic1"

      val rdd = new KafkaRDD(sc, SimpleConsumerConfig(getConsumerConfig(zkConnect)), topic)
      assert(rdd.collect.map{ pom => (pom.partition, pom.offset, byteBufferToString(pom.message.key), byteBufferToString(pom.message.payload)) }.toList ===
        List((0, 0L, "1", "a"), (0, 1L, "2", "b"), (0, 2L, null, "b"), (0, 3L, null, "c"), (0, 4L, null, "c"), (0, 5L, null, "c")))
      assert(rdd.nextOffsets === Map(0 -> 6L))
    }

    it("should collect the correct messages with provided offsets") {
      val topic = "topic1"
      val sent = Map("e" -> 2)
      produceAndSendMessage(topic, sent)

      val rdd = new KafkaRDD(sc, SimpleConsumerConfig(getConsumerConfig(zkConnect)), topic, Map(0 -> 6L))
      assert(rdd.collect.map{ pom => (pom.partition, pom.offset, byteBufferToString(pom.message.payload)) }.toList ===
        List((0, 6L, "e"), (0, 7L, "e")))
      assert(rdd.nextOffsets === Map(0 -> 8L))
    }

    it("should collect the correct messages if more than fetchSize") {
      val topic = "topic1"

      val send = Map("a" -> 101)
      produceAndSendMessage(topic, send)
      val rdd = new KafkaRDD(sc, SimpleConsumerConfig(getConsumerConfig(zkConnect)), topic, Map(0 -> 8L))
      val l = rdd.collect.map{ pom => (pom.partition, pom.offset, byteBufferToString(pom.message.payload)) }.toList
      assert(l.size == 101 && l.forall(_._3 == "a") && l.last == (0, 108L, "a"))
      assert(rdd.nextOffsets === Map(0 -> 109L))
    }
  }

  private def byteBufferToString(bb: ByteBuffer): String = {
    if (bb == null) null
    else {
      val b = new Array[Byte](bb.remaining)
      bb.get(b, 0, b.length)
      new String(b, "UTF8")
    }
  }

  private def createTestMessage(topic: String, sent: Map[String, Int])
    : Seq[KeyedMessage[String, String]] = {
    val messages = for ((s, freq) <- sent; i <- 0 until freq) yield {
      new KeyedMessage[String, String](topic, s)
    }
    messages.toSeq
  }

  def createTopic(topic: String) {
    TopicCommand.createTopic(zkClient, new TopicCommand.TopicCommandOptions(Array("--topic", topic, "--partitions", "1", "--replication-factor", "1")))
    // wait until metadata is propagated
    waitUntilMetadataIsPropagated(Seq(server), topic, 0, 1000)
  }

  def produceAndSendMessage(topic: String, sent: Map[String, Int]) {
    val brokerAddr = brokerConf.hostName + ":" + brokerConf.port
    producer = new Producer[String, String](new ProducerConfig(getProducerConfig(brokerAddr)))
    producer.send(createTestMessage(topic, sent): _*)
  }
}

object KafkaTestUtils {
  def getBrokerConfig(port: Int, zkConnect: String): Properties = {
    val props = new Properties()
    props.put("broker.id", "0")
    props.put("host.name", "localhost")
    props.put("port", port.toString)
    props.put("log.dir", createTempDir().getAbsolutePath)
    props.put("zookeeper.connect", zkConnect)
    props.put("log.flush.interval.messages", "1")
    props.put("replica.socket.timeout.ms", "1500")
    props
  }

  def getProducerConfig(brokerList: String): Properties = {
    val props = new Properties()
    props.put("metadata.broker.list", brokerList)
    props.put("serializer.class", classOf[StringEncoder].getName)
    props
  }

  def getConsumerConfig(zkConnect: String): Properties = {
    val props = new Properties()
    props.put("zookeeper.connect", zkConnect)
    props.put("fetch.message.max.bytes", "100")
    props
  }

  def waitUntilTrue(condition: () => Boolean, waitTime: Long): Boolean = {
    val startTime = System.currentTimeMillis()
    while (true) {
      if (condition())
        return true
      if (System.currentTimeMillis() > startTime + waitTime)
        return false
      Thread.sleep(waitTime.min(100L))
    }
    // Should never go to here
    throw new RuntimeException("unexpected error")
  }

  def waitUntilMetadataIsPropagated(servers: Seq[KafkaServer], topic: String, partition: Int, timeout: Long) {
    assert(waitUntilTrue({ () =>
      servers.foldLeft(true){ (state, server) =>
        val topicMetaData = server.apis.metadataCache.getTopicMetadata(Set(topic))
        state && (topicMetaData.size > 0) && (topicMetaData.head.partitionsMetadata.size > 0)
      }
    }, timeout), s"Partition [$topic, $partition] metadata not propagated after timeout")
  }

  def deleteRecursively(file: File) {
    if (file != null) {
      if ((file.isDirectory) && !isSymlink(file)) {
        for (child <- listFilesSafely(file)) {
          deleteRecursively(child)
        }
      }
      if (!file.delete()) {
        // Delete can also fail if the file simply did not exist
        if (file.exists()) {
          throw new IOException("Failed to delete: " + file.getAbsolutePath)
        }
      }
    }
  }

  def isSymlink(file: File): Boolean = {
    if (file == null) throw new NullPointerException("File must not be null")
    val fileInCanonicalDir = if (file.getParent() == null) {
      file
    } else {
      new File(file.getParentFile().getCanonicalFile(), file.getName())
    }

    if (fileInCanonicalDir.getCanonicalFile().equals(fileInCanonicalDir.getAbsoluteFile())) {
      return false
    } else {
      return true
    }
  }

  private def listFilesSafely(file: File): Seq[File] = {
    val files = file.listFiles()
    if (files == null) {
      throw new IOException("Failed to list files for dir: " + file)
    }
    files
  }

  def createTempDir(root: String = System.getProperty("java.io.tmpdir")): File = {
    var attempts = 0
    val maxAttempts = 10
    var dir: File = null
    while (dir == null) {
      attempts += 1
      if (attempts > maxAttempts) {
        throw new IOException("Failed to create a temp directory (under " + root + ") after " +
          maxAttempts + " attempts!")
      }
      try {
        dir = new File(root, "spark-" + UUID.randomUUID.toString)
        if (dir.exists() || !dir.mkdirs()) {
          dir = null
        }
      } catch { case e: IOException => ; }
    }
    dir
  }

  class EmbeddedZookeeper(val zkConnect: String) {
    val snapshotDir = createTempDir()
    val logDir = createTempDir()

    val zookeeper = new ZooKeeperServer(snapshotDir, logDir, 500)
    val (ip, port) = {
      val splits = zkConnect.split(":")
      (splits(0), splits(1).toInt)
    }
    val factory = new NIOServerCnxnFactory()
    factory.configure(new InetSocketAddress(ip, port), 16)
    factory.startup(zookeeper)

    def shutdown() {
      factory.shutdown()
      deleteRecursively(snapshotDir)
      deleteRecursively(logDir)
    }
  }
}
