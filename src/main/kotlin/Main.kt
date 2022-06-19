import com.sletmoe.krogue.Game
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val game = Game("Krogue", 60)
    game.run()
}
