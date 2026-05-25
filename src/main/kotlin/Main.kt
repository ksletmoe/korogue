import com.sletmoe.kotile.display.ascii.AsciiTileDescriptor
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.stage.Stage
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO

private val BACKGROUND = Color.web("#1d1f21")

class KotileDemo : Application() {
    override fun start(stage: Stage) {
        val window = AsciiTileWindow.create {
            widthInTiles = 80
            heightInTiles = 30
        }

        stage.title = "kotile"
        stage.scene = Scene(Group(window.node))
        stage.show()

        // Draw only after the first layout pass: showing the stage resizes the
        // canvas, and ResizableCanvas.resize() clears it, so drawing earlier
        // would be wiped.
        val snapshotPath = System.getProperty("kotile.snapshot")
        object : AnimationTimer() {
            private var frame = 0

            override fun handle(now: Long) {
                when (frame) {
                    0 -> draw(window)
                    else -> {
                        if (snapshotPath != null) {
                            val image = window.node.snapshot(null, null)
                            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", File(snapshotPath))
                            Platform.exit()
                        }
                        stop()
                    }
                }
                frame++
            }
        }.start()
    }

    private fun draw(window: AsciiTileWindow) = runBlocking {
        window.fill(AsciiTileDescriptor(' ', Color.WHITE, BACKGROUND))

        val message = "Hello, kotile!"
        message.forEachIndexed { i, character ->
            window.drawTile(2 + i, 1, AsciiTileDescriptor(character, Color.LIMEGREEN, BACKGROUND))
        }
    }
}

fun main() {
    Application.launch(KotileDemo::class.java)
}
