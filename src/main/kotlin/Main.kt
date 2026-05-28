import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.sletmoe.krogue.kotile.MyGame

fun main() {
    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("Krogue")
            // 80 tiles × 10 px wide = 800 px; 40 tiles × 10 px tall = 400 px
            setWindowedMode(800, 400)
            disableAudio(true)
        }
    Lwjgl3Application(MyGame(), config)
}
