package io.github.diskria.lapis.kcp.fir.parser

import io.github.diskria.lapis.core.parser.models.*
import io.github.diskria.lapis.core.utils.Variance
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.builtins.functions.isSuspendOrKSuspendFunction
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaredMemberScope
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.utils.*
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.java.findJvmNameValue
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.scopes.collectAllFunctions
import org.jetbrains.kotlin.fir.scopes.collectAllProperties
import org.jetbrains.kotlin.fir.scopes.getDeclaredConstructors
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.ConstantValueKind

class FirParser(
    private val context: CheckerContext,
    private val reporter: DiagnosticReporter,
) {
    private val session: FirSession = context.session

    fun parseNode(declaration: FirRegularClass): KMixinNode<FirFile>? = when {
        declaration.hasAnnotation(ROOT_ANNOTATION_ID, session) -> declaration.parseAsRoot()
        else -> null
    }

    private fun FirRegularClass.parseAsRoot(): KMixinNode<FirFile> {
        val declaredMemberScope = context(context) { symbol.declaredMemberScope() }
        return KMixinNode(
            origin = session.firProvider.getFirClassifierContainerFile(symbol),
            name = name.toName(),
            type = defaultType().parse(),
            isClass = classKind == ClassKind.CLASS,
            isInterface = classKind == ClassKind.INTERFACE,
            isOpen = isOpen,
            isAbstract = isAbstract,
            isSealed = isSealed,
            isTopLevel = true,
            isPublic = visibility == Visibilities.Public,
            typeParameters = typeParameters.map { it.symbol.parse() },
            companionObject = null,
            constructors = declaredMemberScope.getDeclaredConstructors().map { it.parse() },
            properties = declaredMemberScope.collectAllProperties().map { it.parse() },
            functions = declaredMemberScope.collectAllFunctions().map { it.parse() },
            annotations = annotations.parse(),
            node = toNode(),
        )
    }

    private fun FirConstructorSymbol.parse() = KMixinNode.Constructor(
        isPublic = true,
        parameters = valueParameterSymbols.map { it.parseAsConstructorParameter() },
        node = toNode(),
    )

    @OptIn(SymbolInternals::class)
    private fun FirValueParameterSymbol.parseAsConstructorParameter() = KMixinNode.Constructor.Parameter(
        name = name.toName(),
        type = resolvedReturnType.parse(),
        annotations = annotations.parse(),
        node = toNode(),
    )

    @OptIn(SymbolInternals::class)
    private fun FirVariableSymbol<*>.parse() = KMixinNode.Property(
        name = name.toName(),
        type = resolvedReturnType.parse(),
        isPublic = visibility == Visibilities.Public,
        isOpen = isOpen,
        isAbstract = isAbstract,
        hasExtensionReceiver = isExtension,
        contextParameters = contextParameterSymbols.map { it.parseAsContextParameter() },
        typeParameters = typeParameterSymbols.map { it.parse() },
        annotations = annotations.parse(),
        getter = fir.getter?.let {
            KMixinNode.Property.Getter(
                jvmName = it.findJvmNameValue() ?: JvmAbi.getterName(name.toName()),
                annotations = it.annotations.parse(),
            )
        },
        setter = null,
        node = toNode(),
    )

    @OptIn(SymbolInternals::class)
    private fun FirNamedFunctionSymbol.parse() = KMixinNode.Function(
        name = name.toName(),
        jvmName = name.toName(),
        parameters = valueParameterSymbols.map { it.parseAsFunctionParameter() },
        contextParameters = contextParameterSymbols.map { it.parseAsContextParameter() },
        returnType = resolvedReturnType.parse(),
        isPublic = visibility == Visibilities.Public,
        isOpen = isOpen,
        isAbstract = isAbstract,
        extensionReceiverType = resolvedReceiverType?.parse(),
        annotations = annotations.parse(),
        typeParameters = typeParameterSymbols.map { it.parse() },
        isSuspending = isSuspend,
        node = toNode(),
    )

    @OptIn(SymbolInternals::class)
    private fun FirValueParameterSymbol.parseAsFunctionParameter() = KMixinNode.Function.Parameter(
        name = name.toName(),
        type = resolvedReturnType.parse(),
        annotations = annotations.parse(),
        node = toNode(),
    )

    @OptIn(SymbolInternals::class)
    private fun FirValueParameterSymbol.parseAsContextParameter() = ContextParameterNode(
        name = name.toName(),
        type = resolvedReturnType.parse(),
        annotations = annotations.parse(),
        node = toNode(),
    )

    private fun List<FirAnnotation>.parse(): AnnotationsContainer =
        AnnotationsContainer.of(map { it.parse() })

    private fun FirAnnotation.parse(): AnnotationNode {
        val coneType = annotationTypeRef.coneType
        val constructorSymbol = coneType.toRegularClassSymbol(session)?.primaryConstructorIfAny(session)
        return parseAnnotation(
            coneType = coneType,
            constructorSymbol = constructorSymbol,
            explicitMapping = argumentMapping.mapping,
            node = toNode(),
        )
    }

    private fun parseAnnotation(
        coneType: ConeKotlinType,
        constructorSymbol: FirConstructorSymbol?,
        explicitMapping: Map<Name, FirExpression>,
        node: FirNode,
    ): AnnotationNode {
        val arguments = explicitMapping.map { (name, expression) ->
            val valueExpression = if (expression is FirNamedArgumentExpression) expression.expression else expression
            valueExpression.parseAsAnnotationArgument(name.toName())
        }
        return AnnotationNode(
            type = coneType.parse(),
            arguments = arguments,
            node = node,
        )
    }

    private fun FirExpression.parseAsAnnotationArgument(name: String): AnnotationNode.Argument {
        fun FirExpression.extractArrayElementsOrNull(): List<FirExpression>? = when (this) {
            is FirWrappedArgumentExpression -> expression.extractArrayElementsOrNull() ?: listOf(this)
            is FirVarargArgumentsExpression -> arguments.singleOrNull()?.extractArrayElementsOrNull() ?: arguments
            is FirCollectionLiteral -> arguments
            else -> null
        }

        val arrayElements = extractArrayElementsOrNull()
        if (arrayElements != null) {
            return AnnotationNode.ArrayArgument(
                name = name,
                elements = arrayElements.map { it.parseAsAnnotationArgumentValue() },
                node = toNode(),
            )
        }
        return AnnotationNode.ScalarArgument(
            name = name,
            value = parseAsAnnotationArgumentValue(),
            node = toNode(),
        )
    }


    private fun FirExpression.parseAsAnnotationArgumentValue(): AnnotationNode.Argument.Value = when (this) {
        is FirLiteralExpression -> {
            val rawValue = value
            when (kind) {
                ConstantValueKind.Boolean -> AnnotationNode.Argument.BooleanValue(rawValue as Boolean)
                ConstantValueKind.Byte -> AnnotationNode.Argument.ByteValue((rawValue as Number).toByte())
                ConstantValueKind.Short -> AnnotationNode.Argument.ShortValue((rawValue as Number).toShort())
                ConstantValueKind.Int -> AnnotationNode.Argument.IntValue((rawValue as Number).toInt())
                ConstantValueKind.Long -> AnnotationNode.Argument.LongValue((rawValue as Number).toLong())
                ConstantValueKind.Char -> AnnotationNode.Argument.CharValue(rawValue as Char)
                ConstantValueKind.Float -> AnnotationNode.Argument.FloatValue((rawValue as Number).toFloat())
                ConstantValueKind.Double -> AnnotationNode.Argument.DoubleValue((rawValue as Number).toDouble())
                ConstantValueKind.String -> AnnotationNode.Argument.StringValue(rawValue as String)
                else -> AnnotationNode.Argument.InvalidValue
            }
        }

        is FirGetClassCall -> AnnotationNode.Argument.TypeValue(argument.resolvedType.parse())

        is FirPropertyAccessExpression -> {
            val reference = calleeReference as? FirResolvedNamedReference
                ?: return AnnotationNode.Argument.InvalidValue

            when (val resolvedSymbol = reference.resolvedSymbol) {
                is FirEnumEntrySymbol -> {
                    AnnotationNode.Argument.EnumValue(
                        type = resolvedType.parse(),
                        name = resolvedSymbol.callableId.callableName.asString()
                    )
                }

                else -> AnnotationNode.Argument.InvalidValue
            }
        }

        is FirEnumEntryDeserializedAccessExpression -> AnnotationNode.Argument.EnumValue(
            type = enumClassId.toLookupTag().constructClassType().parse(),
            name = enumEntryName.asString(),
        )

        is FirAnnotation -> AnnotationNode.Argument.AnnotationValue(parse())
        is FirFunctionCall -> {
            val reference = calleeReference as? FirResolvedNamedReference ?: return AnnotationNode.Argument.InvalidValue
            when (val resolvedSymbol = reference.resolvedSymbol) {
                is FirConstructorSymbol -> {
                    val constructorMapping = resolvedArgumentMapping
                        ?.entries
                        ?.associate { (expression, parameter) -> parameter.name to expression }
                        .orEmpty()

                    val annotationNode = parseAnnotation(
                        coneType = resolvedType,
                        constructorSymbol = resolvedSymbol,
                        explicitMapping = constructorMapping,
                        node = toNode(),
                    )

                    AnnotationNode.Argument.AnnotationValue(annotationNode)
                }

                is FirEnumEntrySymbol -> {
                    AnnotationNode.Argument.EnumValue(
                        type = resolvedType.parse(),
                        name = resolvedSymbol.callableId.callableName.asString()
                    )
                }

                else -> AnnotationNode.Argument.InvalidValue
            }
        }

        else -> AnnotationNode.Argument.InvalidValue
    }

    private fun ConeKotlinType.parse(): ParsedType {
        if (this is ConeErrorType) return ParsedInvalidType
        val symbol = toSymbol(session)
        if (symbol is FirTypeParameterSymbol) {
            return ParsedTypeParameterType(symbol.name.toName(), toType())
        }
        val actualType = if (symbol is FirTypeAliasSymbol) {
            val expanded = fullyExpandedType(session)
            if (expanded === this) return ParsedInvalidType
            val parsedExpanded = expanded.parse() as? ParsedClassType ?: return ParsedInvalidType
            parsedExpanded.actualOrThis
        } else null
        val firTypeArguments = typeArguments
        val parsedTypeArguments = firTypeArguments.map { firTypeArgument ->
            when (firTypeArgument) {
                is ConeStarProjection -> ParsedClassType.StarProjectionArgument
                is ConeKotlinTypeProjection -> {
                    val type = firTypeArgument.type.parse() as? ParsedValidType ?: return ParsedInvalidType
                    val variance = when (firTypeArgument.variance) {
                        FirVariance.IN_VARIANCE -> Variance.CONTRAVARIANT
                        FirVariance.OUT_VARIANCE -> Variance.COVARIANT
                        FirVariance.INVARIANT -> Variance.INVARIANT
                    }
                    ParsedClassType.TypeProjectionArgument(type, variance)
                }
            }
        }
        val functionTypeKind = functionTypeKind(session, expandTypeAliases = false)
        val isFunctionalType = functionTypeKind != null
        val functionalType = if (isFunctionalType) {
            parseFunctionalType(firTypeArguments.asList(), functionTypeKind.isSuspendOrKSuspendFunction)
                ?: return ParsedInvalidType
        } else null
        val classId = classId
        return ParsedClassType(
            packageName = classId?.packageFqName?.toName().orEmpty(),
            qualifiedName = classId?.asSingleFqName()?.toName(),
            typeArguments = parsedTypeArguments,
            actualType = actualType,
            functionalType = functionalType,
            type = toType(),
        )
    }

    private fun ConeKotlinType.parseFunctionalType(
        firTypeArguments: List<ConeTypeProjection>,
        isSuspending: Boolean,
    ) = ParsedClassType.FunctionalType.parse(
        typeArguments = firTypeArguments,
        nothingType = session.builtinTypes.nothingType.coneType as ConeKotlinType,
        nullableAnyType = session.builtinTypes.nullableAnyType.coneType as ConeKotlinType,
        isSuspending = isSuspending,
        getEffectiveType = { argument, fallbackType ->
            when (argument) {
                is ConeStarProjection -> fallbackType
                is ConeKotlinTypeProjection -> argument.type
            }
        },
        parseType = { type -> type.parse() },
        getContextParametersCount = { contextParameterNumberForFunctionType },
        isExtension = { isExtensionFunctionType(session) },
        getParameterName = { _, type -> type.valueParameterName(session)?.toName() },
    )

    @OptIn(SymbolInternals::class)
    private fun FirTypeParameterSymbol.parse() = TypeParameterNode(
        name = name.toName(),
        bounds = resolvedBounds.map { it.coneType.parse() }.toList(),
        isReified = isReified,
        annotations = annotations.parse(),
        node = toNode(),
    )

    private fun KtSourceElement?.toNode(): FirNode = FirNode(this, reporter, context)
    private fun FirElement?.toNode(): FirNode = this?.source.toNode()

    @OptIn(SymbolInternals::class)
    private fun FirBasedSymbol<*>?.toNode(): FirNode = this?.fir.toNode()

    private fun ConeKotlinType.toType(): FirType = FirType(this, session)
    private fun Name.toName(): String = asString()
    private fun FqName.toName(): String = asString()

    private typealias FirVariance = org.jetbrains.kotlin.types.Variance

    companion object {
        private val ROOT_ANNOTATION_ID = ClassId.topLevel(FqName(AnnotationsContainer.ROOT_FQCN))
    }
}
