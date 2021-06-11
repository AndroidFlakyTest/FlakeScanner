package org.ftd.Master

import com.sun.jdi.ThreadReference
import org.apache.logging.log4j.LogManager

import scala.collection.mutable

class MessageStore {

  private val logger = LogManager.getLogger()

  val map = new mutable.LinkedHashMap[MessageGroup, mutable.LinkedHashMap[MessageID, mutable.LinkedHashSet[Message]]]()

  def createMessage(msg: Message): Message = {
    createMessage(msg.ID, msg.runtimeInfo)
  }

  def createMessage(ID: MessageID, runtimeInfo: Message_RuntimeInfo): Message = {
    val msgSet = map.getOrElseUpdate(ID.group, new mutable.LinkedHashMap()).getOrElseUpdate(ID, new mutable.LinkedHashSet[Message]())
    val occurrence = msgSet.size
    val msg = Message(ID, runtimeInfo, null, occurrence)
    assert(msgSet.add(msg), "Repeated message detected!")

    logger.debug(s"Adding message: ${msg}")

    msg
  }

  def getSuspendedMessages: Iterable[Message] = {
    //noinspection SpellCheckingInspection
    map.values.flatMap(IDmap=>IDmap.values.flatMap(msgSet => msgSet.filter(msg => msg.isSuspended)))
  }

  override def toString: String = {
    var str = ""

    for ((group, groupMap) <- map) {
      str += s"Group: ${group}\n"
      for ((id, msgSet) <- groupMap) {
        str += s"ID: ${id}\n"
        for (msg <- msgSet) {
          str += s"msg: ${msg}\n"
        }
        str += '\n'
      }
      str += '\n'
    }

    str
  }
}
