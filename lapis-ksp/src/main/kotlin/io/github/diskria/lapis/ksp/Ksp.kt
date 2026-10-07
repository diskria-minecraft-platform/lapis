package io.github.diskria.lapis.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.core.cli.CliOptions
import io.github.diskria.lapis.core.lowering.CoreLowering
import io.github.diskria.lapis.core.lowering.models.IrKMixin
import io.github.diskria.lapis.core.validator.CoreValidator
import io.github.diskria.lapis.ksp.generator.KspGenerator
import io.github.diskria.lapis.ksp.logging.KspLogger
import io.github.diskria.lapis.ksp.parser.KspParser
import io.github.diskria.poetesse.Poetesse
import io.github.diskria.poetesse.java.JPAnnotation
import io.github.diskria.poetesse.java.JPClassName
import io.github.diskria.poetesse.java.JPTypeName

class Ksp(
    private val options: CliOptions,
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
        val nodes = KspParser(resolver, logger).parseNodes()
        val models = CoreValidator(nodes, options).validate().toList()
        if (models.isNotEmpty()) {
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
        KspGenerator(loweredKMixins, options, poetesse, codeGenerator).generate()
    }
}

class KspJavaNullabilityResolver(
    private val nullableAnnotationClassName: JPClassName?,
    private val nonNullAnnotationClassName: JPClassName?,
) : Poetesse.JavaNullabilityResolver {

    override fun setNullable(typeName: JPTypeName, nullable: Boolean): JPTypeName {
        if (nullableAnnotationClassName == null && nonNullAnnotationClassName == null) return typeName
        val targetAnnotation = if (nullable) nullableAnnotationClassName else nonNullAnnotationClassName
        val cleanAnnotations = typeName.annotations().filterNot {
            it.type() == nullableAnnotationClassName || it.type() == nonNullAnnotationClassName
        }
        val finalAnnotations = if (targetAnnotation != null) {
            cleanAnnotations + JPAnnotation.builder(targetAnnotation).build()
        } else {
            cleanAnnotations
        }
        return typeName.withoutAnnotations().annotated(finalAnnotations)
    }
}
