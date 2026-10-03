package io.github.diskria.lapis.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import io.github.diskria.lapis.ksp.phases.generator.Generator
import io.github.diskria.lapis.ksp.phases.lowering.Lowering
import io.github.diskria.lapis.ksp.phases.lowering.models.KMixinFir
import io.github.diskria.lapis.ksp.phases.parser.SymbolParser
import io.github.diskria.lapis.ksp.phases.validator.FrontendValidator
import io.github.diskria.poetesse.Poetesse
import io.github.diskria.poetesse.java.JPClassName

class LapisSymbolProcessor(
    private val options: KspOptions,
    private val codeGenerator: CodeGenerator,
    private val logger: KspLogger,
) : SymbolProcessor {

    private val poetesse = Poetesse {
        javaNullabilityResolver = KspJavaNullabilityResolver(
            nullableAnnotationClassName = options.nullableAnnotation?.let { JPClassName.bestGuess(it) },
            nonNullAnnotationClassName = options.nonNullAnnotation?.let { JPClassName.bestGuess(it) },
        )
    }

    private val validator = FrontendValidator(options, logger)
    private val firs: MutableList<KMixinFir> = mutableListOf()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.setPhase(KspLogger.Phase.PARSING)
        val nodes = SymbolParser(resolver, logger).parseNodes()

        logger.setPhase(KspLogger.Phase.VALIDATION)
        val models = validator.validate(nodes).toList()

        if (models.isNotEmpty()) {
            logger.setPhase(KspLogger.Phase.LOWERING)
            firs += Lowering(models, options, poetesse).lower()
        }

        return emptyList()
    }

    override fun finish() {
        generate()
    }

    override fun onError() {
        generate()
    }

    private fun generate() {
        if (firs.isEmpty()) return
        logger.setPhase(KspLogger.Phase.GENERATION)
        Generator(firs, options, poetesse, codeGenerator).generate()
    }
}
