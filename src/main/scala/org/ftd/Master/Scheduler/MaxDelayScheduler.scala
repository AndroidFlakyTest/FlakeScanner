package org.ftd.Master.Scheduler

import org.ftd.Master.{MessageGroup, MessageID}

import scala.collection.immutable.{HashSet, ListMap}
import scala.collection.mutable

class MaxDelayScheduler(var uselessMsgIDs: Set[MessageID] = HashSet()) extends AdaptiveScheduler {

  var lastReleaseMap: ListMap[MessageGroup, mutable.LinkedHashSet[MessageID]] = ListMap()
  var maxDelayAchieved: Boolean = false
  var initUpdate: Boolean = false

  def update(lastRunResult: SchedulingUpdateInfo): Unit = {
    require(!initUpdate || lastRunResult.releaseMap.keys == lastReleaseMap.keys)
    if (initUpdate && lastRunResult.releaseMap.forall { case (k, v) => lastReleaseMap.get(k).head == v }) {
      maxDelayAchieved = true
    }
    initUpdate = true;
    lastReleaseMap = lastRunResult.releaseMap
    uselessMsgIDs = uselessMsgIDs ++ lastRunResult.ignoreMsgs
  }

  def next(): SchedulingInfo = {
    //require(lastReleaseMap != null, "Must call update method for at least once")
    require(hasNext)
    val newReleaseMap = lastReleaseMap.mapValues(s => (mutable.LinkedHashSet.newBuilder ++= s.toList.reverse).result()).to(ListMap)
    SchedulingInfo(newReleaseMap, uselessMsgIDs, false, false)
  }

  def hasNext: Boolean = !maxDelayAchieved
}
