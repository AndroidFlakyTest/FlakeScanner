package org.ftd.Master.Scheduler

import org.ftd.Master.{MessageGroup, MessageID}

import scala.collection.immutable.ListMap
import scala.collection.mutable

case class SchedulingUpdateInfo(testStatus: Boolean, releaseMap: ListMap[MessageGroup, mutable.LinkedHashSet[MessageID]], ignoreMsgs: Set[MessageID])
case class SchedulingInfo(releaseMap: ListMap[MessageGroup, mutable.LinkedHashSet[MessageID]], ignoreMsgs: Set[MessageID], strictRelease: Boolean, ignoreUnknonMsgs: Boolean)

abstract class Scheduler() extends Iterator[SchedulingInfo] {
    /**
     * Whether the scheduler has attempted all possibilities
     */
    def hasNext: Boolean
}