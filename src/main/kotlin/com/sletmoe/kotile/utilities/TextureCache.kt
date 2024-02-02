package com.sletmoe.kotile.utilities

import com.sksamuel.aedile.core.caffeineBuilder
import javafx.scene.image.Image

data class CacheConfig(val maxSizeBytes: Int = DEFAULT_MAX_SIZE_BYTES) {
    companion object {
        private const val DEFAULT_MAX_SIZE_BYTES = 67_108_864  // 64 MB
    }
}

typealias TextureLoader = () -> Image

interface TextureCache {
    suspend fun getTexture(cacheKey: Int, textureLoader: TextureLoader): Image
}

class ConcreteTextureCache(config: CacheConfig) : TextureCache {
    private val cache = caffeineBuilder<Int, Image> {
        this.maximumWeight = config.maxSizeBytes.toLong()
        this.weigher = { _, image -> image.width.toInt() * image.height.toInt() * image.elementSizeBytes() }
    }.build()

    override suspend fun getTexture(cacheKey: Int, textureLoader: TextureLoader): Image {
        return cache.get(cacheKey) { textureLoader() }
    }
}

class NullTextureCache : TextureCache {
    override suspend fun getTexture(cacheKey: Int, textureLoader: TextureLoader): Image = textureLoader()
}
