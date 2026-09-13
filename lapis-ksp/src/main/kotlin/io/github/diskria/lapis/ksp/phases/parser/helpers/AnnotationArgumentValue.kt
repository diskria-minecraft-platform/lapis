package io.github.diskria.lapis.ksp.phases.parser.helpers

import com.google.devtools.ksp.processing.KSBuiltIns
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.extensions.castOrNull
import io.github.diskria.lapis.ksp.extensions.ks.name
import kotlin.enums.enumEntries

class AnnotationArgumentValue(
    val rawValue: Any,
    private val keepDefault: Boolean = false,
) {
    fun asString(): String? =
        rawValue.castOrNull<String>()?.filterDefault { it.isEmpty() }

    fun asClassType(builtIns: KSBuiltIns): KSType? =
        rawValue.castOrNull<KSType>()?.filterDefault { it == builtIns.unitType }

    inline fun <reified E : Enum<E>> asEnum(default: E? = null): E? {
        val entryName = rawValue.castOrNull<KSClassDeclaration>()?.name?.filterDefault { it == default?.name }
        return enumEntries<E>().find { it.name == entryName }
    }

    fun asArray(): Iterable<AnnotationArgumentValue>? {
        val items = when (rawValue) {
            is Iterable<*> -> rawValue
            is Array<*> -> rawValue.asIterable()
            else -> null
        }
        return items
            ?.filterNotNull()
            ?.map { AnnotationArgumentValue(it, keepDefault) }
            ?.filterDefault { it.isEmpty() }
    }

    fun <T> T.filterDefault(isDefault: (T) -> Boolean): T? =
        if (keepDefault) this
        else takeUnless { isDefault(it) }
}
