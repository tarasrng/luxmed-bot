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
package com.lbs.server.conversation

import akka.actor.ActorSystem
import com.lbs.bot.Bot
import com.lbs.bot.model.{Command, MessageSource}
import com.lbs.server.conversation.Login._
import com.lbs.server.conversation.base.{Conversation, Interactional}
import com.lbs.server.lang.{Localizable, Localization}
import com.lbs.server.service.{ApiService, DataService}
import com.lbs.server.util.MessageExtractors
import org.jasypt.util.text.TextEncryptor

class Login(source: MessageSource, bot: Bot, dataService: DataService, apiService: ApiService, textEncryptor: TextEncryptor,
            val localization: Localization, originator: Interactional)(val actorSystem: ActorSystem) extends Conversation[LoginData] with Localizable {

  protected var userId: UserId = _

  entryPoint(logIn, LoginData())

  private var forwardCommand: ForwardCommand = _

  def logIn: Step =
    monologue {
      case Msg(cmd: Command, LoginData(None, None)) =>
        forwardCommand = ForwardCommand(cmd)
        goto(requestUsername)
    }

  def requestUsername: Step =
    ask { _ =>
      bot.sendMessage(source, lang.provideUsername)
    } onReply {
      case Msg(MessageExtractors.OptionalTextCommand(username), _) =>
        goto(requestPassword) using LoginData(username = username)
    }

  def requestPassword: Step =
    ask { _ =>
      bot.sendMessage(source, lang.providePassword)
    } onReply {
      case Msg(MessageExtractors.OptionalTextCommand(password), loginData: LoginData) =>
        goto(processLoginInformation) using loginData.copy(password = password.map(textEncryptor.encrypt))
    }

  def processLoginInformation: Step = {
    process { case LoginData(Some(username), Some(password)) =>
      val loginResult = apiService.login(username, password)
      loginResult match {
        case Left(error) =>
          bot.sendMessage(source, error.getMessage)
          goto(requestUsername)
        case Right(loggedIn) =>
          val credentials = dataService.saveCredentials(source, username, password)
          userId = UserId(credentials.userId, credentials.accountId, source)
          apiService.addSession(credentials.accountId, loggedIn.accessToken, loggedIn.tokenType)
          bot.sendMessage(source, lang.loginAndPasswordAreOk)
          originator ! LoggedIn(forwardCommand, credentials.userId, credentials.accountId)
          end()
      }
    }
  }
}

object Login {

  case class LoginData(username: Option[String] = None, password: Option[String] = None)

  case class ForwardCommand(cmd: Command)

  case class UserId(userId: Long, accountId: Long, source: MessageSource)

  case class LoggedIn(forwardCommand: ForwardCommand, userId: Long, accountId: Long)

}