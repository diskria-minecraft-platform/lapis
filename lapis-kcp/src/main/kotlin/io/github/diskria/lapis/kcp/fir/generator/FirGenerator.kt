package io.github.diskria.lapis.kcp.fir.generator

import io.github.diskria.lapis.core.cli.CliOptions
import io.github.diskria.lapis.core.extensions.lapisFatal
import io.github.diskria.lapis.core.parser.models.AnnotationsContainer
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.fakeElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.extensions.*
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.plugin.createTopLevelFunction
import org.jetbrains.kotlin.fir.plugin.createTopLevelProperty
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.getContainingClassSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

class FirGenerator(session: FirSession, private val options: CliOptions) : FirDeclarationGenerationExtension(session) {

    data object GeneratedExtensionKey : GeneratedDeclarationKey()

    private val kMixinFqn = FqName(AnnotationsContainer.KMIXIN_FQCN)
    private val kMixinClassId = ClassId.topLevel(kMixinFqn)

    private val extensionFqn = FqName(AnnotationsContainer.EXTENSION_FQCN)
    private val extensionLookupPredicate = LookupPredicate.create {
        annotated(extensionFqn) and parentAnnotated(kMixinFqn)
    }
    private val extensionPredicate = DeclarationPredicate.create {
        annotated(extensionFqn) and parentAnnotated(kMixinFqn)
    }

    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(extensionLookupPredicate, extensionPredicate)
    }

    @OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class)
    override fun getTopLevelCallableIds(): Set<CallableId> =
        runCatching {
            findExtensions().mapTo(mutableSetOf()) { (callable, _, packageFqName) ->
                CallableId(packageFqName, callable.name)
            }
        }.getOrElse {
            lapisFatal("[FIR-GENERATOR:getTopLevelCallableIds]", it)
            emptySet()
        }

    @OptIn(SymbolInternals::class, ExperimentalTopLevelDeclarationsGenerationApi::class)
    override fun generateProperties(
        callableId: CallableId,
        context: MemberGenerationContext?,
    ): List<FirPropertySymbol> =
        if (context != null) emptyList()
        else runCatching {
            findExtensions().mapNotNull { (callable, targetClassId, packageFqName) ->
                if (callable !is FirPropertySymbol) return@mapNotNull null
                if (callable.name != callableId.callableName) return@mapNotNull null
                if (packageFqName != callableId.packageName) return@mapNotNull null
                val originalProperty = callable.fir
                originalProperty.lazyResolveToPhase(FirResolvePhase.STATUS)
                val receiverType = targetClassId.constructClassLikeType(typeArguments = emptyArray() /* TODO */)
                val virtualProperty = createTopLevelProperty(
                    key = GeneratedExtensionKey,
                    callableId = callableId,
                    returnType = originalProperty.returnTypeRef.coneTypeOrUnit,
                    isVal = originalProperty.isVal,
                    hasBackingField = false,
                    containingFileName = targetClassId.extensionsFileName,
                ) {
                    source = originalProperty.source?.fakeElement(KtFakeSourceElementKind.PropertyTypeFromGetterReturnType)
                    originalProperty.contextParameters.forEach { contextParameter ->
                        contextParameter.returnTypeRef.coneTypeOrNull?.let { contextReceiver(it) }
                    }
                    extensionReceiverType(receiverType)
                    originalProperty.typeParameters.forEach { typeParameter ->
                        typeParameter(typeParameter.name, typeParameter.variance) {
                            typeParameter.bounds.forEach { bound -> bound.coneTypeOrNull?.let { bound(it) } }
                        }
                    }
                    status { isExternal = true } // TODO IR
                }
                virtualProperty.symbol
            }
        }.getOrElse {
            lapisFatal("[FIR-GENERATOR:generateProperties]", it)
            emptyList()
        }

    @OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class, SymbolInternals::class)
    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?,
    ): List<FirNamedFunctionSymbol> =
        if (context != null) emptyList()
        else runCatching {
            findExtensions().mapNotNull { (callable, targetClassId, packageFqName) ->
                if (callable !is FirNamedFunctionSymbol) return@mapNotNull null
                if (callable.name != callableId.callableName) return@mapNotNull null
                if (packageFqName != callableId.packageName) return@mapNotNull null
                val originalFunction = callable.fir
                originalFunction.lazyResolveToPhase(FirResolvePhase.STATUS)
                val receiverType = targetClassId.constructClassLikeType(typeArguments = emptyArray() /* TODO */)
                val virtualFunction = createTopLevelFunction(
                    key = GeneratedExtensionKey,
                    callableId = callableId,
                    returnType = originalFunction.returnTypeRef.coneTypeOrUnit,
                    containingFileName = targetClassId.extensionsFileName,
                ) {
                    source = originalFunction.source?.fakeElement(KtFakeSourceElementKind.FromUseSiteTarget)
                    originalFunction.contextParameters.forEach { contextParameter ->
                        contextParameter.returnTypeRef.coneTypeOrNull?.let { contextReceiver(it) }
                    }
                    originalFunction.typeParameters.forEach { typeParameter ->
                        typeParameter(typeParameter.name, typeParameter.variance) {
                            typeParameter.bounds.forEach { bound -> bound.coneTypeOrNull?.let { bound(it) } }
                        }
                    }
                    extensionReceiverType(receiverType)
                    originalFunction.valueParameters.forEach { valueParameter ->
                        valueParameter(
                            name = valueParameter.name,
                            type = valueParameter.returnTypeRef.coneTypeOrUnit,
                            isVararg = valueParameter.isVararg,
                            isCrossinline = valueParameter.isCrossinline,
                            isNoinline = valueParameter.isNoinline,
                        )
                    }
                    status { isExternal = true } // TODO: IR
                }
                virtualFunction.symbol
            }
        }.getOrElse {
            lapisFatal("[FIR-GENERATOR:generateFunctions]", it)
            emptyList()
        }

    private val FirTypeRef.coneTypeOrUnit: ConeKotlinType
        get() = coneTypeOrNull ?: session.builtinTypes.unitType.coneType

    private val ClassId.extensionsFileName: String
        get() = "__${shortClassName}__Extensions"

    private data class Extension(
        val callable: FirCallableSymbol<*>,
        val targetClassId: ClassId,
        val packageFqName: FqName,
    )

    private fun findExtensions(): List<Extension> =
        session.predicateBasedProvider.getSymbolsByPredicate(extensionLookupPredicate).mapNotNull { symbol ->
            val callableSymbol = symbol as? FirCallableSymbol<*> ?: return@mapNotNull null
            if (!callableSymbol.matches(extensionPredicate)) return@mapNotNull null
            val classSymbol = callableSymbol.getContainingClassSymbol() ?: return@mapNotNull null
            val kMixinAnnotation = classSymbol.getAnnotationByClassId(kMixinClassId, session) ?: return@mapNotNull null
            val targetTypeProjection = kMixinAnnotation.typeArguments.singleOrNull() as? FirTypeProjectionWithVariance
                ?: return@mapNotNull null
            val targetClassId = targetTypeProjection.typeRef.coneTypeOrNull?.fullyExpandedType(session)?.classId
                ?: return@mapNotNull null
            Extension(callableSymbol, targetClassId, classSymbol.classId.packageFqName)
        }

    private fun FirBasedSymbol<*>.matches(predicate: DeclarationPredicate): Boolean =
        session.predicateBasedProvider.matches(predicate, this)
}

/* TODO
// 1. Вызываем ваш графовый решатель (тот самый алгоритм на деке)
val solverResult = TypeParameterSolver.solve(
    usedNamesInSignature = collectAllUsedTypeParameters(originalFunction.symbol),
    localTypeParamNames = originalFunction.typeParameters.map { it.name.asString() }.toSet(),
    enclosingParams = mixinClassSymbol.typeParameterSymbols.mapIndexed { index, param ->
        EnclosingTypeParamInfo(index, param.name.asString(), param.resolvedBounds.collectNames())
    }
)

// 2. Создаем новые FirTypeParameter для нашей top-level функции:
val newTopLevelTypeParameters = mutableListOf<FirTypeParameter>()
val substitutionMap = mutableMapOf<FirTypeParameterSymbol, ConeKotlinType>()

for (mixinParamSymbol in mixinClassSymbol.typeParameterSymbols) {
    val nameStr = mixinParamSymbol.name.asString()

    if (nameStr in solverResult.usedEnclosingParamNames) {
        // Создаем новый параметр для top-level функции
        val newParam = buildTypeParameter {
            this.moduleData = session.moduleData
            this.origin = Key.origin
            this.resolvePhase = FirResolvePhase.BODY_RESOLVE
            this.name = mixinParamSymbol.name
            this.symbol = FirTypeParameterSymbol()
            this.containingDeclarationSymbol = newTopLevelFunctionSymbol
            this.variance = mixinParamSymbol.variance
            this.isReified = false
        }
        newTopLevelTypeParameters.add(newParam)

        // Регистрируем замену: старый дженерик миксина -> новый дженерик функции!
        substitutionMap[mixinParamSymbol] = ConeTypeParameterTypeImpl(
            newParam.symbol.toLookupTag(),
            isMarkedNullable = false
        )
    }
}

// 3. Создаем стандартный компиляторный ConeSubstitutor:
val substitutor = substitutorByMap(substitutionMap, session)

// 4. КОМПИЛЯТОР САМ ПЕРЕПИСЫВАЕТ ТИПЫ ЧЕРЕЗ СУБСТИТУТОР!

// А) Обновляем границы (bounds) у новых параметров:
for (newParam in newTopLevelTypeParameters) {
    val originalParam = mixinClassSymbol.typeParameterSymbols.first { it.name == newParam.name }
    newParam.replaceBounds(
        originalParam.resolvedBounds.map { bound ->
            bound.coneType.let { substitutor.substituteOrSelf(it) }.toFirResolvedTypeRef()
        }
    )
}

// Б) Ресивер функции: используемые параметры подставляются, а лишние становятся ConeStarProjection!
val receiverTypeArguments = mixinClassSymbol.typeParameterSymbols.map { param ->
    val replacement = substitutionMap[param]
    if (replacement != null) {
        replacement // ConeKotlinType (новый дженерик функции)
    } else {
        ConeStarProjection // ЗВЕЗДОЧКА для неиспользуемого дженерика!
    }
}.toTypedArray()

val finalReceiverType = targetClassId.constructClassLikeType(receiverTypeArguments, isNullable = false)

// В) Применяем субститутор к типам аргументов и возвращаемому значению:
// (Если функция внутри миксина возвращала T, теперь она возвращает T от top-level функции!)
val finalReturnType = substitutor.substituteOrSelf(originalFunction.returnTypeRef.coneType)
 */

/* TODO
val extraTypeParameters = collectExtraTypeParametersFromMixin(...)
val extraTypeParameters = listOf<>()
for (extraParam in extraTypeParameters) {
    typeParameters.add(
        buildTypeParameter {
            this.moduleData = session.moduleData
            this.origin = Key.origin
            this.resolvePhase = FirResolvePhase.BODY_RESOLVE
            this.name = extraParam.name
            this.symbol = FirTypeParameterSymbol()
            this.containingDeclarationSymbol = this@copyFirFunctionWithResolvePhase.symbol
            this.variance = extraParam.variance
            this.isReified = false
            this.bounds.addAll(extraParam.bounds)
        }
    )
}
*/
