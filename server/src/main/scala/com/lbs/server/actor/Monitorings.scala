/**
 * MIT License
 *
 * Copyright (c) 2018 Yevhen Zadyra
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.lbs.server.actor

import akka.actor.{ActorRef, PoisonPill, Props}
import com.lbs.bot._
import com.lbs.bot.model.{Button, Command}
import com.lbs.server.actor.Chat.Init
import com.lbs.server.actor.Login.UserId
import com.lbs.server.actor.Monitorings.{AwaitDecision, AwaitPage, RequestData, Tags}
import com.lbs.server.lang.{Localizable, Localization}
import com.lbs.server.repository.model.Monitoring
import com.lbs.server.service.MonitoringService

class Monitorings(val userId: UserId, bot: Bot, monitoringService: MonitoringService, val localization: Localization, monitoringsPagerActorFactory: (UserId, ActorRef) => ActorRef) extends SafeFSM[FSMState, Monitoring] with Localizable {

  private val monitoringsPager = monitoringsPagerActorFactory(userId, self)

  startWith(RequestData, null)

  whenSafe(RequestData) {
    case Event(Next, _) =>
      val monitorings = monitoringService.getActiveMonitorings(userId.userId)
      monitoringsPager ! Right[Throwable, Seq[Monitoring]](monitorings)
      goto(AwaitPage)
  }

  whenSafe(AwaitPage) {
    case Event(cmd: Command, _) =>
      monitoringsPager ! cmd
      stay()
    case Event(Pager.NoItemsFound, _) =>
      bot.sendMessage(userId.source, lang.noActiveMonitorings)
      goto(RequestData)
    case Event(monitoring: Monitoring, _) =>
      bot.sendMessage(userId.source, lang.deactivateMonitoring(monitoring), inlineKeyboard =
        createInlineKeyboard(Seq(Button(lang.no, Tags.No), Button(lang.yes, Tags.Yes))))
      goto(AwaitDecision) using monitoring
  }

  whenSafe(AwaitDecision) {
    case Event(Command(_, _, Some(Tags.No)), _) =>
      bot.sendMessage(userId.source, lang.monitoringWasNotDeactivated)
      goto(RequestData)
    case Event(Command(_, _, Some(Tags.Yes)), monitoring: Monitoring) =>
      monitoringService.deactivateMonitoring(monitoring.recordId)
      bot.sendMessage(userId.source, lang.deactivated)
      goto(RequestData)
  }

  whenUnhandledSafe {
    case Event(Init, _) =>
      invokeNext()
      monitoringsPager ! Init
      goto(RequestData)
  }

  initialize()

  override def postStop(): Unit = {
    monitoringsPager ! PoisonPill
    super.postStop()
  }
}

object Monitorings {
  def props(userId: UserId, bot: Bot, monitoringService: MonitoringService, localization: Localization, monitoringsPagerActorFactory: (UserId, ActorRef) => ActorRef): Props =
    Props(classOf[Monitorings], userId, bot, monitoringService, localization, monitoringsPagerActorFactory)

  object RequestData extends FSMState

  object AwaitPage extends FSMState

  object AwaitDecision extends FSMState

  object Tags {
    val Yes = "yes"
    val No = "no"
  }

}










