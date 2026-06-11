import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.sletmoe.korogue.demo.MyGame

fun main() {
    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("Korogue")
            // 80 tiles × 10 px wide = 800 px; 40 tiles × 10 px tall = 400 px
            setWindowedMode(800, 400)
            disableAudio(true)
        }
    Lwjgl3Application(MyGame(), config)
}
