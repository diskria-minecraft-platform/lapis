package io.github.diskria.lapis.ksp.extensions.ks

import com.google.devtools.ksp.processing.KSBuiltIns
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.extensions.requireQualifiedName
import io.github.diskria.lapis.ksp.phases.parser.helpers.AnnotationArgumentValue
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

inline fun <reified A : Annotation> KSAnnotation.isType(): Boolean {
    val qualifiedName = A::class.requireQualifiedName()
    return qualifiedName.endsWith(shortName.asString()) &&
        annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
}

inline fun <reified A : Annotation> KSAnnotation.findArgumentValue(
    property: KProperty1<A, *>,
    explicit: Boolean = false,
): AnnotationArgumentValue? =
    (if (explicit) arguments.filter { it.isExplicit } else arguments)
        .find { it.name?.asString() == property.name }
        ?.value
        ?.let { AnnotationArgumentValue(it, keepDefault = explicit) }

inline fun <reified A : Annotation> KSAnnotation.getArgumentValue(
    property: KProperty1<A, String>,
    explicit: Boolean = false,
): String? =
    findArgumentValue(property, explicit)?.asString()

inline fun <reified A : Annotation> KSAnnotation.getArgumentValue(
    property: KProperty1<A, KClass<*>>,
    builtIns: KSBuiltIns,
    explicit: Boolean = false,
): KSType? =
    findArgumentValue(property, explicit)?.asClassType(builtIns)

inline fun <reified A : Annotation, reified E : Enum<E>> KSAnnotation.getArgumentValue(
    property: KProperty1<A, E>,
    explicit: Boolean = false,
): E? =
    findArgumentValue(property, explicit)?.asEnum()

inline fun <reified A : Annotation> KSAnnotation.getArrayArgumentValue(
    property: KProperty1<A, *>,
    explicit: Boolean = false,
): Iterable<AnnotationArgumentValue>? =
    findArgumentValue(property, explicit)?.asArray()

@JvmName("getEnumArrayArgumentValue")
inline fun <reified A : Annotation, reified E : Enum<E>> KSAnnotation.getArgumentValue(
    property: KProperty1<A, Array<out E>>,
    explicit: Boolean = false,
): List<E>? =
    getArrayArgumentValue(property, explicit)?.mapNotNull { it.asEnum() }
