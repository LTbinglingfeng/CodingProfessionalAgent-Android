package dev.cpa.android.core.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Outline icon set drawn for CPA on a 24-unit grid. Shapes follow the iOS SF Symbols used by the
 * iOS client (meaning and silhouette), but every path is authored here; no Apple assets are copied.
 */
object CpaIcons {
    private fun icon(name: String, vararg paths: String, stroke: Float = 1.8f, filled: Boolean = false): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            if (filled) builder.addPath(addPathNodes(d), fill = SolidColor(Color.Black))
            else builder.addPath(addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        return builder.build()
    }
    private const val FOLDER = "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"
    private const val PENCIL = "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z M15 5l4 4"
    private const val FILE = "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z M14 2v4a2 2 0 0 0 2 2h4"
    private const val PIN = "M12 17v5 M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"
    private const val MAIL = "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M22 7l-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"

    val Menu by lazy { icon("menu", "M4 8.5h16 M4 15.5h10", stroke = 2f) }
    val Search by lazy { icon("search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z M20 20l-4.2-4.2", stroke = 2f) }
    val Compose by lazy { icon("compose", "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
        "M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.873a2 2 0 0 1 .506-.852z") }
    val Folder by lazy { icon("folder", FOLDER, stroke = 1.6f) }
    val FolderPlus by lazy { icon("folderPlus", FOLDER, "M12 10v6 M9 13h6", stroke = 1.6f) }
    val FolderMinus by lazy { icon("folderMinus", FOLDER, "M9 13h6", stroke = 1.6f) }
    val Chats by lazy { icon("chats", "M14 9a2 2 0 0 1-2 2H6l-4 4V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2z", "M18 9h2a2 2 0 0 1 2 2v11l-4-4h-6a2 2 0 0 1-2-2v-1", stroke = 1.6f) }
    val Ellipsis by lazy { icon("ellipsis", "M5 12h.01 M12 12h.01 M19 12h.01", stroke = 3.2f) }
    val ChevronLeft by lazy { icon("chevronLeft", "M15 19l-7-7 7-7", stroke = 2.2f) }
    val ChevronRight by lazy { icon("chevronRight", "M9 18l6-6-6-6", stroke = 2.2f) }
    val ChevronDown by lazy { icon("chevronDown", "M6 9l6 6 6-6", stroke = 2.2f) }
    val ChevronUp by lazy { icon("chevronUp", "M18 15l-6-6-6 6", stroke = 2.2f) }
    val ChevronUpDown by lazy { icon("chevronUpDown", "M7 15l5 5 5-5 M7 9l5-5 5 5", stroke = 2f) }
    val Close by lazy { icon("close", "M18 6L6 18 M6 6l12 12", stroke = 2.2f) }
    val Plus by lazy { icon("plus", "M5 12h14 M12 5v14", stroke = 1.9f) }
    val ArrowUp by lazy { icon("arrowUp", "M12 19V5 M5 12l7-7 7 7", stroke = 2.4f) }
    val ArrowDown by lazy { icon("arrowDown", "M12 5v14 M19 12l-7 7-7-7", stroke = 2f) }
    val ArrowRight by lazy { icon("arrowRight", "M5 12h14 M12 5l7 7-7 7", stroke = 1.8f) }
    val StopFill by lazy { icon("stop", "M9 6h6a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H9a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3z", filled = true) }
    val PlayFill by lazy { icon("play", "M8 4.8v14.4a1 1 0 0 0 1.53.85l11.2-7.2a1 1 0 0 0 0-1.7L9.53 3.95A1 1 0 0 0 8 4.8z", filled = true) }
    val Copy by lazy { icon("copy", "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z", "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2", stroke = 1.6f) }
    val Branch by lazy { icon("branch", "M12 21v-8 M12 13L6.5 5 M12 13l5.5-8 M6.5 5h4 M6.5 5v4 M17.5 5h-4 M17.5 5v4", stroke = 1.8f) }
    val Pin by lazy { icon("pin", PIN, stroke = 1.6f) }
    val PinOff by lazy { icon("pinOff", PIN, "M3 3l18 18", stroke = 1.6f) }
    val Pencil by lazy { icon("pencil", PENCIL, stroke = 1.6f) }
    val PencilLine by lazy { icon("pencilLine", PENCIL, "M13 21h8", stroke = 1.6f) }
    val Archive by lazy { icon("archive", "M3 3h18a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8 M10 12h4", stroke = 1.6f) }
    val Mail by lazy { icon("mail", MAIL, stroke = 1.6f) }
    val MailBadge by lazy { icon("mailBadge", "M13 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-7 M22 7l-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7", "M19 2.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z", stroke = 1.6f) }
    val MailOpen by lazy { icon("mailOpen", "M21.2 8.4c.5.38.8.97.8 1.6v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V10a2 2 0 0 1 .8-1.6l8-6a2 2 0 0 1 2.4 0l8 6Z", "M22 10l-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 10", stroke = 1.6f) }
    val Hash by lazy { icon("hash", "M4 9h16 M4 15h16 M10 3L8 21 M16 3l-2 18", stroke = 1.6f) }
    val Link by lazy { icon("link", "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71", stroke = 1.6f) }
    val MessagePlus by lazy { icon("messagePlus", "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z", "M12 7v6 M9 10h6", stroke = 1.6f) }
    val Gear by lazy { icon("gear", "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", stroke = 1.6f) }
    val Camera by lazy { icon("camera", "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z", "M12 10a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", stroke = 1.7f) }
    val Photo by lazy { icon("photo", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M9 7a2 2 0 1 0 0 4a2 2 0 1 0 0-4z M21 15l-3.086-3.086a2 2 0 0 0-2.828 0L6 21", stroke = 1.7f) }
    val Paperclip by lazy { icon("paperclip", "M16 6l-8.414 8.586a2 2 0 0 0 2.828 2.828l8.414-8.586a4 4 0 0 0-5.656-5.656l-8.415 8.585a6 6 0 1 0 8.486 8.486L20.5 13", stroke = 1.7f) }
    val Display by lazy { icon("display", "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M8 21h8 M12 17v4", stroke = 1.6f) }
    val Laptop by lazy { icon("laptop", "M5 5h14a1 1 0 0 1 1 1v10H4V6a1 1 0 0 1 1-1z", "M2 19h20", stroke = 1.6f) }
    val Terminal by lazy { icon("terminal", "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M6 9l3 3-3 3 M11 15h5", stroke = 1.6f) }
    val Code by lazy { icon("code", "M8 7l-5 5 5 5 M16 7l5 5-5 5 M14 4l-4 16", stroke = 1.6f) }
    val Check by lazy { icon("check", "M20 6L9 17l-5-5", stroke = 2.2f) }
    val Warning by lazy { icon("warning", "M21.73 18l-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4 M12 17h.01", stroke = 1.7f) }
    val Bolt by lazy { icon("bolt", "M13 2L3 14h9l-1 8 10-12h-9l1-8z", filled = true) }
    val BoltOutline by lazy { icon("boltOutline", "M13 2L3 14h9l-1 8 10-12h-9l1-8z", stroke = 1.6f) }
    val Cpu by lazy { icon("cpu", "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M9 9h6v6H9z M15 2v2 M15 20v2 M2 15h2 M2 9h2 M20 15h2 M20 9h2 M9 2v2 M9 20v2", stroke = 1.6f) }
    val Shrink by lazy { icon("shrink", "M14 10l7-7 M14 10h5 M14 10V5 M10 14l-7 7 M10 14H5 M10 14v5", stroke = 2f) }
    val Expand by lazy { icon("expand", "M21 3l-7 7 M21 3h-5 M21 3v5 M3 21l7-7 M3 21h5 M3 21v-5", stroke = 2f) }
    val DocText by lazy { icon("docText", FILE, "M16 13H8 M16 17H8 M10 9H8", stroke = 1.6f) }
    val DocPlus by lazy { icon("docPlus", FILE, "M12 12v6 M9 15h6", stroke = 1.6f) }
    val Wrench by lazy { icon("wrench", "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z", stroke = 1.6f) }
    val QuestionCircle by lazy { icon("questionCircle", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3 M12 17h.01", stroke = 1.6f) }
    val QuestionBubble by lazy { icon("questionBubble", "M7.9 20A9 9 0 1 0 4 16.1L2 22Z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3 M12 17h.01", stroke = 1.7f) }
    val Sparkles by lazy { icon("sparkles", "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z", "M20 3v4 M22 5h-4", stroke = 1.6f) }
    val Sparkle by lazy { icon("sparkle", "M12 2l2.3 7.7L22 12l-7.7 2.3L12 22l-2.3-7.7L2 12l7.7-2.3z", filled = true) }
    val Cube by lazy { icon("cube", "M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z", "M3.3 7L12 12l8.7-5 M12 22V12", stroke = 2f) }
    val House by lazy { icon("house", "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8", "M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", stroke = 1.6f) }
    val Drive by lazy { icon("drive", "M22 12H2 M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z", "M6 16h.01 M10 16h.01", stroke = 1.6f) }
    val Refresh by lazy { icon("refresh", "M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8", "M21 3v5h-5", stroke = 1.8f) }
    val Eye by lazy { icon("eye", "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0", "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", stroke = 1.6f) }
    val Trash by lazy { icon("trash", "M3 6h18 M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6 M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2", stroke = 1.6f) }
    val OpenExternal by lazy { icon("openExternal", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M9 15l6-6 M10 9h5v5", stroke = 1.7f) }
    val TurnUpLeft by lazy { icon("turnUpLeft", "M9 14L4 9l5-5", "M20 20v-7a4 4 0 0 0-4-4H4", stroke = 1.7f) }
    val ArrowCircleRight by lazy { icon("arrowCircleRight", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M8 12h8 M12 8l4 4-4 4", stroke = 1.6f) }
    val Share by lazy { icon("share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8 M16 6l-4-4-4 4 M12 2v13", stroke = 1.7f) }
    val Table by lazy { icon("table", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M3 9h18 M3 15h18 M9 3v18", stroke = 2f) }
    val Presentation by lazy { icon("presentation", "M2 3h20 M21 3v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V3 M7 21l5-5 5 5", stroke = 2f) }
    val Film by lazy { icon("film", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M7 3v18 M17 3v18 M3 12h18", stroke = 2f) }
    val Waveform by lazy { icon("waveform", "M2 10v3 M6 6v11 M10 3v18 M14 8v7 M18 5v13 M22 10v3", stroke = 2f) }
    val Note by lazy { icon("note", FILE, stroke = 2f) }
    val Undo by lazy { icon("undo", "M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11", stroke = 1.7f) }
}

/** Tinted CPA icon (defaults to the current content color). */
@Composable
fun CpaIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Color.Unspecified) {
    if (tint == Color.Unspecified) Icon(icon, contentDescription, modifier.size(size))
    else Icon(icon, contentDescription, modifier.size(size), tint = tint)
}
