package ucai.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

/**
 * Base for data classes serialized as a fixed-position JSON array of numbers,
 * e.g. `[riderId, distM, speed, groupId, rankInGroup]`. Ints are written without a decimal part.
 *
 * Decoding ignores trailing extra elements, so a later version may append fields.
 */
@OptIn(ExperimentalSerializationApi::class)
abstract class CompactArraySerializer<T>(serialName: String, private val arity: Int) : KSerializer<T> {
    final override val descriptor: SerialDescriptor =
        SerialDescriptor(serialName, ListSerializer(Double.serializer()).descriptor)

    protected abstract fun Writer.write(value: T)
    protected abstract fun Reader.read(): T

    final override fun serialize(encoder: Encoder, value: T) {
        val out = encoder.beginCollection(descriptor, arity)
        Writer(out, descriptor).write(value)
        out.endStructure(descriptor)
    }

    final override fun deserialize(decoder: Decoder): T {
        val input = decoder.beginStructure(descriptor)
        val reader = Reader(input, descriptor)
        val value = reader.read()
        reader.skipRest()
        input.endStructure(descriptor)
        return value
    }

    class Writer internal constructor(private val out: CompositeEncoder, private val d: SerialDescriptor) {
        private var index = 0
        fun int(v: Int) = out.encodeIntElement(d, index++, v)
        fun double(v: Double) = out.encodeDoubleElement(d, index++, v)
    }

    class Reader internal constructor(private val input: CompositeDecoder, private val d: SerialDescriptor) {
        fun int(): Int = input.decodeIntElement(d, next())
        fun double(): Double = input.decodeDoubleElement(d, next())

        private fun next(): Int {
            val index = input.decodeElementIndex(d)
            if (index == CompositeDecoder.DECODE_DONE) throw SerializationException("${d.serialName}: array too short")
            return index
        }

        internal fun skipRest() {
            while (true) {
                val index = input.decodeElementIndex(d)
                if (index == CompositeDecoder.DECODE_DONE) return
                input.decodeSerializableElement(d, index, JsonElement.serializer())
            }
        }
    }
}
