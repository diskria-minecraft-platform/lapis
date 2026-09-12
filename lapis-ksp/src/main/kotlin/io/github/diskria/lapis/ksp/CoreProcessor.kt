package io.github.diskria.lapis.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import io.github.diskria.lapis.ksp.common.KSBaseTypes
import io.github.diskria.lapis.ksp.logging.Logger
import io.github.diskria.lapis.ksp.phases.LapisPhase
import io.github.diskria.lapis.ksp.phases.bootstrap.Options
import io.github.diskria.lapis.ksp.phases.generator.Generator
import io.github.diskria.lapis.ksp.phases.lowering.Lowering
import io.github.diskria.lapis.ksp.phases.lowering.models.IrPatch
import io.github.diskria.lapis.ksp.phases.parser.SymbolParser
import io.github.diskria.lapis.ksp.phases.validator.FrontendValidator
import java.util.*

class CoreProcessor(
    private val options: Options,
    private val codeGenerator: CodeGenerator,
    private val logger: Logger,
) : SymbolProcessor {

    private val lowering: Lowering = Lowering(options, logger)
    private val patches: SortedMap<String, IrPatch> = sortedMapOf()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val baseTypes = KSBaseTypes(resolver.builtIns)
        val parser = SymbolParser(resolver, baseTypes, logger)
        logger.setPhase(LapisPhase.PARSING)
        val parserResult = parser.parse()

        logger.setPhase(LapisPhase.VALIDATION)
        val validatorResult = FrontendValidator(logger, options).validate(parserResult)

        logger.setPhase(LapisPhase.TRANSFORMATION)
        val irResult = lowering.lower(validatorResult)
        irResult.patches.forEach { patches[it.className.qualifiedName] = it }

        return emptyList()
    }

    override fun finish() {
        generate()
    }

    override fun onError() {
        generate()
    }

    private fun generate() {
        logger.setPhase(LapisPhase.GENERATION)
        Generator(options, codeGenerator, logger).generate(patches.values.toList())
    }
}
