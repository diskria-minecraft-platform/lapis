package io.github.diskria.lapis.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.core.CoreOptions
import io.github.diskria.lapis.core.lowering.CoreLowering
import io.github.diskria.lapis.core.lowering.models.IrKMixin
import io.github.diskria.lapis.core.validator.CoreValidator
import io.github.diskria.lapis.ksp.generator.KspGenerator
import io.github.diskria.lapis.ksp.parser.KspParser
import io.github.diskria.poetesse.Poetesse
import io.github.diskria.poetesse.java.JPClassName

class Ksp(
    private val options: CoreOptions,
    private val codeGenerator: CodeGenerator,
    private val logger: KspLogger,
) : SymbolProcessor {

    private val poetesse = Poetesse {
        javaNullabilityResolver = KspJavaNullabilityResolver(
            nullableAnnotationClassName = options.nullableAnnotation?.let { JPClassName.bestGuess(it) },
            nonNullAnnotationClassName = options.nonNullAnnotation?.let { JPClassName.bestGuess(it) },
        )
    }

    private val loweredKMixins: MutableList<IrKMixin<KSFile>> = mutableListOf()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.setPhase(KspLogger.Phase.PARSING)
        val nodes = KspParser(resolver, logger).parseNodes()

        logger.setPhase(KspLogger.Phase.VALIDATION)
        val models = CoreValidator(nodes, options).validate().toList()

        if (models.isNotEmpty()) {
            logger.setPhase(KspLogger.Phase.LOWERING)
            loweredKMixins += CoreLowering(models, options, poetesse).lower()
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
        if (loweredKMixins.isEmpty()) return
        logger.setPhase(KspLogger.Phase.GENERATION)
        KspGenerator(loweredKMixins, options, poetesse, codeGenerator).generate()
    }
}
