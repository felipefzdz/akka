/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.net.InetAddress
import java.util.concurrent.Executors
import scala.collection.AbstractIterator
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor._
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit._
import com.typesafe.config.ConfigFactory
import io.aeron.Aeron
import io.aeron.driver.MediaDriver
import akka.stream.KillSwitches
import java.io.File
import io.aeron.CncFileDescriptor

object AeronStreamMaxThroughputSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  val barrierTimeout = 5.minutes

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString(s"""
       # for serious measurements you should increase the totalMessagesFactor (20)
       akka.test.AeronStreamMaxThroughputSpec.totalMessagesFactor = 1.0
       akka {
         loglevel = ERROR
         testconductor.barrier-timeout = ${barrierTimeout.toSeconds}s
         actor {
           provider = "akka.remote.RemoteActorRefProvider"
           serialize-creators = false
           serialize-messages = false
         }
         remote.artery.enabled = off
       }
       """)))

  def aeronPort(roleName: RoleName): Int =
    roleName match {
      case `first`  ⇒ 20511 // TODO yeah, we should have support for dynamic port assignment
      case `second` ⇒ 20512
    }

  final case class TestSettings(
    testName: String,
    totalMessages: Long,
    payloadSize: Int)

  def iterate(start: Long, end: Long): Iterator[Long] = new AbstractIterator[Long] {
    private[this] var first = true
    private[this] var acc = start
    def hasNext: Boolean = acc < end
    def next(): Long = {
      if (!hasNext) throw new NoSuchElementException("next on empty iterator")
      if (first) first = false
      else acc += 1

      acc
    }
  }

}

class AeronStreamMaxThroughputSpecMultiJvmNode1 extends AeronStreamMaxThroughputSpec
class AeronStreamMaxThroughputSpecMultiJvmNode2 extends AeronStreamMaxThroughputSpec

abstract class AeronStreamMaxThroughputSpec
  extends MultiNodeSpec(AeronStreamMaxThroughputSpec)
  with STMultiNodeSpec with ImplicitSender {

  import AeronStreamMaxThroughputSpec._

  val totalMessagesFactor = system.settings.config.getDouble("akka.test.AeronStreamMaxThroughputSpec.totalMessagesFactor")

  var plot = PlotResult()

  val driver = MediaDriver.launchEmbedded()

  val stats =
    new AeronStat(AeronStat.mapCounters(new File(driver.aeronDirectoryName, CncFileDescriptor.CNC_FILE)))

  val aeron = {
    val ctx = new Aeron.Context
    ctx.aeronDirectoryName(driver.aeronDirectoryName)
    Aeron.connect(ctx)
  }

  val taskRunner = {
    val r = new TaskRunner(system.asInstanceOf[ExtendedActorSystem])
    r.start()
    r
  }

  lazy implicit val mat = ActorMaterializer()(system)
  import system.dispatcher

  def adjustedTotalMessages(n: Long): Long = (n * totalMessagesFactor).toLong

  override def initialParticipants = roles.size

  def channel(roleName: RoleName) = {
    val a = node(roleName).address
    s"aeron:udp?endpoint=${a.host.get}:${aeronPort(roleName)}"
  }

  lazy val reporterExecutor = Executors.newFixedThreadPool(1)
  def reporter(name: String): RateReporter = {
    val r = new RateReporter(SECONDS.toNanos(1), new RateReporter.Reporter {
      override def onReport(messagesPerSec: Double, bytesPerSec: Double, totalMessages: Long, totalBytes: Long): Unit = {
        println(name + ": %.03g msgs/sec, %.03g bytes/sec, totals %d messages %d MB".format(
          messagesPerSec, bytesPerSec, totalMessages, totalBytes / (1024 * 1024)))
      }
    })
    reporterExecutor.execute(r)
    r
  }

  override def afterAll(): Unit = {
    reporterExecutor.shutdown()
    taskRunner.stop()
    aeron.close()
    runOn(second) {
      println(plot.csv(system.name))
    }
    super.afterAll()
  }

  def printTotal(testName: String, total: Long, startTime: Long, payloadSize: Long): Unit = {
    val d = (System.nanoTime - startTime).nanos.toMillis
    val throughput = 1000.0 * total / d
    println(f"=== AeronStreamMaxThroughput $testName: " +
      f"${throughput}%.03g msg/s, ${throughput * payloadSize}%.03g bytes/s, " +
      s"payload size $payloadSize, " +
      s"$d ms to deliver $total messages")
    plot = plot.add(testName, throughput * payloadSize / 1024 / 1024)
  }

  def printStats(side: String): Unit = {
    println(side + " stats:")
    stats.print(System.out)
  }

  val scenarios = List(
    TestSettings(
      testName = "size-100",
      totalMessages = adjustedTotalMessages(1000000),
      payloadSize = 100),
    TestSettings(
      testName = "size-1k",
      totalMessages = adjustedTotalMessages(100000),
      payloadSize = 1000),
    TestSettings(
      testName = "size-10k",
      totalMessages = adjustedTotalMessages(10000),
      payloadSize = 10000))

  def test(testSettings: TestSettings): Unit = {
    import testSettings._
    val receiverName = testName + "-rcv"

    runOn(second) {
      val rep = reporter(testName)
      var t0 = System.nanoTime()
      var count = 0L
      val done = TestLatch(1)
      val killSwitch = KillSwitches.shared(testName)
      Source.fromGraph(new AeronSource(channel(second), aeron, taskRunner))
        .via(killSwitch.flow)
        .runForeach { bytes ⇒
          rep.onMessage(1, bytes.length)
          count += 1
          if (count == 1) {
            t0 = System.nanoTime()
          } else if (count == totalMessages) {
            printTotal(testName, totalMessages, t0, payloadSize)
            done.countDown()
            killSwitch.shutdown()
          }
        }.onFailure {
          case e ⇒
            e.printStackTrace
        }

      enterBarrier(receiverName + "-started")
      Await.ready(done, barrierTimeout)
      rep.halt()
      printStats("receiver")
      enterBarrier(testName + "-done")
    }

    runOn(first) {
      enterBarrier(receiverName + "-started")

      val payload = ("0" * payloadSize).getBytes("utf-8")
      val t0 = System.nanoTime()
      Source.fromIterator(() ⇒ iterate(1, totalMessages))
        .map { n ⇒ payload }
        .runWith(new AeronSink(channel(second), aeron, taskRunner))

      printStats("sender")
      enterBarrier(testName + "-done")

    }

    enterBarrier("after-" + testName)
  }

  "Max throughput of Aeron Streams" must {

    for (s ← scenarios) {
      s"be great for ${s.testName}, payloadSize = ${s.payloadSize}" in test(s)
    }

  }
}