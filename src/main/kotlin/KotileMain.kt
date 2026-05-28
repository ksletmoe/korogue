import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.sletmoe.krogue.kotile.MyKotileGame

/**
 * Entry point for the kotile-backed rendering path (Phase 3b-1).
 *
 * Run with:
 *   ./gradlew runKotile
 *
 * The existing [main] in [Main.kt] and the old AsciiPanel stack are untouched.
 * This is the new parallel entry point that will replace [Main.kt] in Phase 3b-2.
 */
fun main() {
    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("Krogue (kotile)")
            // 80 tiles × 10 px wide = 800 px; 40 tiles × 10 px tall = 400 px
            setWindowedMode(800, 400)
            disableAudio(true)
        }
    Lwjgl3Application(MyKotileGame(), config)
}
