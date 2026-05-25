import com.sletmoe.kotile.display.ascii.AsciiTileDescriptor
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
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

        runBlocking {
            window.fill(AsciiTileDescriptor(' ', Color.WHITE, BACKGROUND))

            val message = "Hello, kotile!"
            message.forEachIndexed { i, character ->
                window.drawTile(2 + i, 1, AsciiTileDescriptor(character, Color.LIMEGREEN, BACKGROUND))
            }
        }

        stage.title = "kotile"
        stage.scene = Scene(Group(window.node))

        val snapshotPath = System.getProperty("kotile.snapshot")
        if (snapshotPath != null) {
            val image = stage.scene.snapshot(null)
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", File(snapshotPath))
            Platform.exit()
        } else {
            stage.show()
        }
    }
}

fun main() {
    Application.launch(KotileDemo::class.java)
}
