package io.github.diskria.lapis.kcp.fir.generator

import io.github.diskria.lapis.core.extensions.lapisLog
import io.github.diskria.lapis.kcp.fir.parser.parser
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.name.CallableId

data object LapisParsedKey : GeneratedDeclarationKey()

class FirGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {

    private var isResolving = false

    @OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class)
    override fun getTopLevelCallableIds(): Set<CallableId> {
        if (isResolving) return emptySet()
        isResolving = true
        return try {
            session.parser.getExtensionCallableIds().also { lapisLog("getTopLevelCallableIds() = $it") }
        } finally {
            isResolving = false
        }
    }

    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?,
    ): List<FirNamedFunctionSymbol> {
        if (context != null) return emptyList()
        return emptyList()
//        val functionSymbol = FirNamedFunctionSymbol(callableId)
//        val returnType = session.builtinTypes.unitType
//        buildNamedFunction {
//            moduleData = session.moduleData
//            origin = FirDeclarationOrigin.Plugin(LapisParsedKey)
//            name = ...
//            symbol = functionSymbol
//            status = FirResolvedDeclarationStatusImpl(Visibilities.Public, Modality.FINAL, EffectiveVisibility.Public)
//            receiverParameter = buildReceiverParameter {
//                moduleData = session.moduleData
//                origin = FirDeclarationOrigin.Plugin(LapisParsedKey)
//                typeRef = buildResolvedTypeRef {
//                    coneType = TARGET_CLASS_ID.constructClassLikeType()
//                }
//                containingDeclarationSymbol = functionSymbol
//                symbol = FirReceiverParameterSymbol()
//            }
//            returnTypeRef = returnType
//            isLocal = false
//            resolvePhase = FirResolvePhase.BODY_RESOLVE
//            body = buildEmptyExpressionBlock().also {
//                it.replaceConeTypeOrNull(returnType.coneType)
//            }
//        }.apply {
//            lapisExtensionData = printlnTextOnCall
//        }
//        return listOf(functionSymbol)
    }

}
