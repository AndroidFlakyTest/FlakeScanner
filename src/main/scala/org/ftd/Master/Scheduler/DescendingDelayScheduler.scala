package org.ftd.Master.Scheduler

import org.apache.logging.log4j.LogManager
import org.ftd.Master.{MessageGroup, MessageID}

import scala.collection.immutable.{HashSet, ListMap}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class DescendingDelayScheduler(val maxDelayReleaseMap: ListMap[MessageGroup, mutable.LinkedHashSet[MessageID]], val msgGroupOrder: ListBuffer[MessageGroup], var uselessMsgIDs: Set[MessageID] = HashSet(), var unknownMsgIDs: Set[MessageID] = HashSet(), passedBefore: Boolean, failedBefore: Boolean) extends AdaptiveScheduler {

  private val logger = LogManager.getLogger()

  val baseReleaseMap: ListMap[MessageGroup, mutable.LinkedHashSet[MessageID]] = (if (!failedBefore) maxDelayReleaseMap else {
    msgGroupOrder.iterator.foldLeft(ListMap[MessageGroup, mutable.LinkedHashSet[MessageID]]())(
      (map, thisGrp) => map.updated(thisGrp, maxDelayReleaseMap.getOrElse(thisGrp, mutable.LinkedHashSet[MessageID]()))
    )
  }).updated(null, (uselessMsgIDs | unknownMsgIDs).to(mutable.LinkedHashSet))

  private val attempts: Iterator[ListMap[MessageGroup, mutable.LinkedHashSet[MessageID]]] = baseReleaseMap.toList.zipWithIndex.drop(1).iterator.flatMap {
    case ((group, msgsIDInGrp), i) =>
      msgsIDInGrp.iterator.flatMap(msgID => {
        logger.debug(s"xx: ${msgID}")
        val orderIdx = msgGroupOrder.indexOf(msgID.group)
        ( (i - 1).until(orderIdx).by(-1) ).iterator.map(targetMsgGroupIdx=>{
          val targetMsgGroup = msgGroupOrder(targetMsgGroupIdx)
          maxDelayReleaseMap.updated(group, baseReleaseMap.get(group).head.clone.subtractOne(msgID))
            .updated(targetMsgGroup, mutable.LinkedHashSet[MessageID](baseReleaseMap.get(targetMsgGroup).head.toList.appended(msgID): _*))
        })
      })

    }

  def next(): SchedulingInfo = {
    SchedulingInfo(attempts.next(), uselessMsgIDs, strictRelease = true, ignoreUnknonMsgs = true)
  }

  def hasNext: Boolean = attempts.hasNext

  override def update(lastRunResult: SchedulingUpdateInfo): Unit = {
    uselessMsgIDs = uselessMsgIDs ++ lastRunResult.ignoreMsgs
  }

}
