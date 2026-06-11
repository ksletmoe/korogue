package com.sletmoe.korogue.registry

import com.sletmoe.korogue.ecs.Component
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlin.reflect.KClass

/**
 * Knows how to (de)serialize the registered [Component] types. Builds the kotlinx
 * [SerializersModule] the CBOR codec uses for polymorphic component encoding, and answers
 * which components are persistable — a component whose concrete type isn't registered
 * (e.g. transient intents) is simply skipped by the codec (ADR-0009).
 */
class ComponentRegistry internal constructor(
    private val serializers: Map<KClass<out Component>, KSerializer<out Component>>,
) {
    val serializersModule: SerializersModule =
        SerializersModule {
            polymorphic(Component::class) {
                serializers.forEach { (klass, ser) ->
                    @Suppress("UNCHECKED_CAST")
                    subclass(klass as KClass<Component>, ser as KSerializer<Component>)
                }
            }
        }

    /** True if [component]'s concrete type is registered — i.e. it should be persisted. */
    fun isRegistered(component: Component): Boolean = component::class in serializers
}
