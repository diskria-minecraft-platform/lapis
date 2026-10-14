package io.github.diskria.lapis.core.validator

import io.github.diskria.lapis.core.parser.models.Node
import io.github.diskria.lapis.core.parser.models.Type

class DiagnosticReport(
    val diagnostic: Diagnostic,
    val target: Node,
)

sealed class Diagnostic(val targetStrategy: TargetStrategy, val message: String, val isWarning: Boolean = false) {

    sealed class Error(targetStrategy: TargetStrategy, message: String) :
        Diagnostic(targetStrategy, message, isWarning = false)

    class TargetTypeMismatch(useSiteDesc: String, targetStrategy: TargetStrategy, type: Type, targetType: Type) : Error(
        targetStrategy = targetStrategy,
        message = "The type '$type' of the $useSiteDesc must be the same as or a supertype of '@KMixin' target type '$targetType'.",
    )

    open class MustBePublic(targetDesc: String) : Error(
        targetStrategy = TargetStrategy.VISIBILITY_MODIFIER,
        message = "$targetDesc must be public",
    )

    object InjectionFunctionMustBePublic : MustBePublic("'@KMixin' injection function")

    object KMixinMustBePublic : MustBePublic("'@KMixin' class/interface")

    object KMixinConstructorMustBePublic : MustBePublic("'@KMixin' constructor")

    object KMixinMultipleConstructors : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "'@KMixin' class must have exactly one constructor",
    )

    object KMixinMustBeTopLevel : Error(
        targetStrategy = TargetStrategy.NAME,
        message = "'@KMixin' must be top-level",
    )

    object KMixinCannotBeSealed : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "'@KMixin' cannot be sealed",
    )

    object KMixinSideRequired : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Side argument must be specified explicitly",
    )

    object KMixinTargetRequired : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Target argument must be specified",
    )

    object KMixinTypeParametersNotSupported : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Type parameters are not supported in '@KMixin'",
    )

    object KMixinMustBeClassOrInterface : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "'@KMixin' must be a class or an interface",
    )

    object KMixinUnexpectedConstructorParameter : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Unexpected '@KMixin' constructor parameter",
    )

    class CannotBeOpen(targetDesc: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "$targetDesc cannot be open",
    )

    class CannotBeAbstract(targetDesc: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "$targetDesc cannot be abstract",
    )

    class MustBeAbstract(targetDesc: String) : Error(
        targetStrategy = TargetStrategy.NAME,
        message = "$targetDesc must be abstract",
    )

    class MustHaveExplicitType(targetDesc: String, typeDesc: String) : Error(
        targetStrategy = TargetStrategy.NAME,
        message = "$targetDesc must have explicit $typeDesc",
    )

    class CannotHaveReceiver(targetDesc: String) : Error(
        targetStrategy = TargetStrategy.RECEIVER_TYPE,
        message = "$targetDesc cannot have receiver",
    )

    class CannotHaveContextParameters(targetDesc: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "$targetDesc cannot have context parameters",
    )

    class CannotHaveTypeParameters(targetDesc: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "$targetDesc cannot have type parameters",
    )

    class CannotHaveMixinAnnotations(targetDesc: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "$targetDesc cannot have mixin annotations",
    )

    class ConflictingAnnotations(targetDesc: String, firstAnnotation: String, secondAnnotation: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "$targetDesc cannot be marked as both $firstAnnotation and $secondAnnotation",
    )

    class CompanionObjectUnsupportedAnnotation(annotation: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "$annotation functions in companion objects are not supported",
    )

    class CoroutinesNotSupported(targetDesc: String? = null) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = if (targetDesc != null) "Kotlin Coroutines are not supported in $targetDesc" else "Kotlin Coroutines are not supported",
    )

    class FailedToParseGetter(targetDesc: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Failed to parse $targetDesc getter",
    )

    class FailedToParseSetter(targetDesc: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Failed to parse $targetDesc setter",
    )

    class FailedToParseJvmName(targetDesc: String) : Error(
        targetStrategy = TargetStrategy.NAME,
        message = "Failed to parse $targetDesc JVM name",
    )

    class InvalidJavaIdentifier(targetDesc: String, name: String) : Error(
        targetStrategy = TargetStrategy.NAME,
        message = "$targetDesc '$name' must be a valid Java identifier",
    )

    class InvalidType(targetDesc: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "$targetDesc type is invalid",
    )

    object InvalidTypeDeclaration : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Type declaration has compilation errors",
    )

    object ParameterMustHaveName : Error(
        targetStrategy = TargetStrategy.NAME,
        message = "Parameter must have a name",
    )

    object StaticShadowMustBeInCompanion : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Static '@KShadow' members must be declared in companion object",
    )

    class ShadowInvalidModifiers(memberDesc: String, modifiers: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "'@KShadow' $memberDesc has invalid modifiers: $modifiers",
    )

    class ShadowMultipleVisibilityModifiers(memberDesc: String, modifiers: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "'@KShadow' $memberDesc has multiple visibility modifiers: $modifiers",
    )

    class ShadowConflictingModifiers(
        memberDesc: String,
        firstModifier: String,
        secondModifier: String,
        contextDesc: String? = null,
    ) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "'@KShadow' $memberDesc cannot be both '$firstModifier' and '$secondModifier'${if (contextDesc != null) " in $contextDesc" else ""}",
    )

    class ShadowIllegalAbstractModifiers(memberDesc: String, modifiers: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "'@KShadow' $memberDesc contains incompatible modifiers: 'ABSTRACT' cannot be combined with $modifiers",
    )

    class ShadowInterfaceExecutionType(expected: String, found: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "'@KShadow' function must specify exactly one execution type ($expected) in interface, found: $found",
    )

    object DefaultModifierOutsideInterface : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Cannot use DEFAULT modifier outside of interface",
    )

    object InjectionReceiverOnlyInInstanceContext : Error(
        targetStrategy = TargetStrategy.RECEIVER_TYPE,
        message = "Injection functions can have receiver only in instance context",
    )

    object InvalidAnnotationType : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Annotation type must be a valid class",
    )

    object InvalidAnnotationArgument : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Annotation argument is invalid",
    )

    object InvalidAnnotationArgumentValue : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Annotation argument value is invalid",
    )

    object ClassReferenceMustBeClass : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Class reference argument must resolve to a valid class",
    )

    object GenericTypeArgumentsInClassReferenceNotSupported : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Generic type arguments in class reference are not supported",
    )

    object EnumTypeMustBeClass : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Enum type must be a class",
    )

    class ReifiedTypeParameterNotSupported(name: String) : Error(
        targetStrategy = TargetStrategy.NAME,
        message = "Reified type parameter '$name' is not supported",
    )

    object ClassMustBelongToPackage : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Class declarations must belong to a package",
    )

    object ClassMustHaveQualifiedName : Error(
        targetStrategy = TargetStrategy.NAME,
        message = "Class declarations must have a fully qualified name",
    )

    class TypeParameterNotFoundInScope(name: String) : Error(
        targetStrategy = TargetStrategy.NAME,
        message = "Type parameter '$name' not found in scope",
    )

    class CyclicTypeParameterBound(name: String) : Error(
        targetStrategy = TargetStrategy.NAME,
        message = "Cyclic type parameter bound detected for '$name'",
    )

    class TargetTypeParameterNotFound(name: String, existing: String) : Error(
        targetStrategy = TargetStrategy.NAME,
        message = "Type parameter '$name' not found in target class. Existing: $existing",
    )

    class BoundsCountMismatch(name: String, expected: Int, actual: Int) : Error(
        targetStrategy = TargetStrategy.NAME,
        message = "Bounds count mismatch for '$name': expected $expected, but got $actual",
    )

    class BoundsMismatch(reason: String) : Error(
        targetStrategy = TargetStrategy.ELEMENT,
        message = "Bounds mismatch: $reason",
    )

    class Other(message: String) : Diagnostic.Error(TargetStrategy.ELEMENT, message)

    sealed class Warning(targetStrategy: TargetStrategy, message: String) :
        Diagnostic(targetStrategy, message, isWarning = true)

    class RedundantNullableTargetType(useSiteDesc: String, targetStrategy: TargetStrategy) : Diagnostic.Warning(
        targetStrategy = targetStrategy,
        message = "Redundant '?'.",
    )

    enum class TargetStrategy {
        VISIBILITY_MODIFIER,
        RETURN_TYPE,
        RECEIVER_TYPE,
        NAME,
        ELEMENT,
    }
}
