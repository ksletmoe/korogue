package com.sletmoe.krogue

import kotlin.random.Random

class World(private val tiles: TileGrid, val playableCharacter: Character, nonPlayableCharacters: List<Character>) {
    companion object {
        fun builder(width: Int, height: Int, random: Random): Builder = Builder(width, height, random)
    }

    val width: Int
        get() = tiles.width
    val height: Int
        get() = tiles.height
    private val _npcs = nonPlayableCharacters.toMutableList()
    val npcs: List<Character>
        get() = _npcs.toList()

    fun addNpc(npc: Character) {
        _npcs.add(npc)
    }

    fun tileAt(x: Int, y: Int): Tile = tiles[x, y]
    fun nonPlayableCharacterAt(x: Int, y: Int): Character? = npcs.firstOrNull { it.x == x && it.y == y }
    fun isBlocked(x: Int, y: Int): Boolean = tiles[x, y].isBlocked || nonPlayableCharacterAt(x, y) != null

    class Builder(
        private val width: Int,
        private val height: Int,
        private val random: Random = Random.Default
    ) {
        private val tiles = TileGrid(width, height)
        private lateinit var playableCharacter: Character
        private val npcs: MutableList<Character> = mutableListOf()

        fun fill(tile: Tile): Builder {
            (0..tiles.lastColumnIndex).forEach { column ->
                (0..tiles.lastRowIndex).forEach { row ->
                    tiles[column, row] = tile
                }
            }

            return this
        }

        fun withTile(x: Int, y: Int, tile: Tile): Builder {
            tiles[x, y] = tile
            return this
        }

        fun withPlayableCharacter(character: Character): Builder {
            playableCharacter = character
            return this
        }

        fun withNonPlayableCharacter(character: Character): Builder {
            npcs.add(character)
            return this
        }

        fun withNonPlayableCharacters(characters: List<Character>): Builder {
            npcs.addAll(characters)
            return this
        }

        fun withRandomWalkCave(startX: Int, startY: Int, length: Int, groundTile: Tile): Builder {
            var direction: Int
            var x = startX
            var y = startY

            repeat(length) {
                direction = random.nextInt(4)
                if (direction == 0 && x + 1 < tiles.lastColumnIndex) {
                    x += 1
                } else if (direction == 1 && x - 1 > 0) {
                    x -= 1
                } else if (direction == 2 && y + 1 < tiles.lastRowIndex) {
                    y += 1
                } else if (direction == 3 && y - 1 > 0) {
                    y -= 1
                }
                tiles[x, y] = groundTile
            }

            return this
        }

        fun build(): World {
            return World(tiles, playableCharacter, npcs)
        }
    }
}
