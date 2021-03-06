/*
 * MIT License
 *
 * Copyright (c) 2019-2020 JetBrains s.r.o.
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
package org.jetbrains.projector.client.web

import org.jetbrains.projector.client.common.misc.Logger
import org.jetbrains.projector.client.common.DrawEvent
import org.jetbrains.projector.client.common.SingleRenderingSurfaceProcessor.Companion.shrinkByPaintEvents
import org.jetbrains.projector.client.web.component.MarkdownPanelManager
import org.jetbrains.projector.client.common.misc.ImageCacher
import org.jetbrains.projector.client.web.misc.PingStatistics
import org.jetbrains.projector.client.web.speculative.Typing
import org.jetbrains.projector.client.web.state.ProjectorUI
import org.jetbrains.projector.client.web.window.OnScreenMessenger
import org.jetbrains.projector.client.web.window.WindowDataEventsProcessor
import org.jetbrains.projector.common.misc.Do
import org.jetbrains.projector.common.protocol.toClient.*
import kotlin.browser.window

class ServerEventsProcessor(private val windowDataEventsProcessor: WindowDataEventsProcessor) {

  @OptIn(ExperimentalStdlibApi::class)
  fun process(commands: ToClientMessageType, pingStatistics: PingStatistics, typing: Typing, markdownPanelManager: MarkdownPanelManager) {
    val drawCommandsEvents = mutableListOf<ServerDrawCommandsEvent>()

    commands.forEach { command ->
      Do exhaustive when (command) {
        is ServerWindowSetChangedEvent -> {
          windowDataEventsProcessor.process(command)
          markdownPanelManager.updatePlacements()
        }

        is ServerDrawCommandsEvent -> drawCommandsEvents.add(command)

        is ServerImageDataReplyEvent -> ImageCacher.putImageData(
          command.imageId,
          command.imageData,
          windowDataEventsProcessor::redrawWindows
        )

        is ServerCaretInfoChangedEvent -> typing.changeCaretInfo(command.data)

        is ServerClipboardEvent -> handleServerClipboardChange(command)

        is ServerPingReplyEvent -> pingStatistics.onPingReply(command)

        is ServerMarkdownEvent -> when (command) {
          is ServerMarkdownEvent.ServerMarkdownShowEvent -> markdownPanelManager.show(command.panelId, command.show)
          is ServerMarkdownEvent.ServerMarkdownResizeEvent -> markdownPanelManager.resize(command.panelId, command.size)
          is ServerMarkdownEvent.ServerMarkdownMoveEvent -> markdownPanelManager.move(command.panelId, command.point)
          is ServerMarkdownEvent.ServerMarkdownDisposeEvent -> markdownPanelManager.dispose(command.panelId)
          is ServerMarkdownEvent.ServerMarkdownPlaceToWindowEvent -> markdownPanelManager.placeToWindow(command.panelId, command.windowId)
          is ServerMarkdownEvent.ServerMarkdownSetHtmlEvent -> markdownPanelManager.setHtml(command.panelId, command.html)
          is ServerMarkdownEvent.ServerMarkdownSetCssEvent -> markdownPanelManager.setCss(command.panelId, command.css)
          is ServerMarkdownEvent.ServerMarkdownScrollEvent -> markdownPanelManager.scroll(command.panelId, command.scrollOffset)
          is ServerMarkdownEvent.ServerMarkdownBrowseUriEvent -> browseUri(command.link)
        }

        is ServerWindowColorsEvent -> {
          ProjectorUI.setColors(command.colors)
          // todo: should WindowManager.lookAndFeelChanged() be called here?
          OnScreenMessenger.lookAndFeelChanged()
        }
      }
    }

    // todo: determine the moment better
    if (drawCommandsEvents.any { it.drawEvents.any { drawEvent -> drawEvent is ServerDrawStringEvent } }) {
      typing.removeSpeculativeImage()
    }

    drawCommandsEvents.forEach { event ->
      Do exhaustive when (val target = event.target) {
        is ServerDrawCommandsEvent.Target.Onscreen -> windowDataEventsProcessor.draw(target.windowId, event.drawEvents)

        is ServerDrawCommandsEvent.Target.Offscreen -> {
          val offscreenProcessor = ImageCacher.getOffscreenProcessor(target)

          // todo: don't create this deque every time
          val drawEvents = ArrayDeque<DrawEvent>().apply { addAll(event.drawEvents.shrinkByPaintEvents()) }

          offscreenProcessor.process(drawEvents)

          windowDataEventsProcessor.redrawWindows()
        }
      }
    }
  }

  fun onResized() {
    windowDataEventsProcessor.onResized()
  }

  private fun handleServerClipboardChange(event: ServerClipboardEvent) {
    window.navigator.clipboard.writeText(event.stringContent)
      .catch { logger.error { "Error writing clipboard: $it" } }
  }

  private fun browseUri(link: String) {
    val popUpWindow = window.open(link, "_blank")

    if (popUpWindow != null) {
      popUpWindow.focus()  // browser has allowed it to be opened
    }
    else {
      window.alert("To open $link, please allow popups for this website")  // browser has blocked it
    }
  }

  companion object {

    private val logger = Logger(ServerEventsProcessor::class.simpleName!!)
  }
}
