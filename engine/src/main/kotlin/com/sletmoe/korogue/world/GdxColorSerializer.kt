package com.sletmoe.korogue.world

import com.badlogic.gdx.graphics.Color
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes a libGDX [Color] as a packed RGBA8888 [Int] — compact and lossless for the
 * 8-bit colours tiles use. Lets [Tile] be `@Serializable` despite holding a non-serializable
 * GDX type (4f).
 */
object GdxColorSerializer : KSerializer<Color> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("GdxColor", PrimitiveKind.INT)

    override fun serialize(
        encoder: Encoder,
        value: Color,
    ) = encoder.encodeInt(Color.rgba8888(value))

    override fun deserialize(decoder: Decoder): Color = Color().also { Color.rgba8888ToColor(it, decoder.decodeInt()) }
}
