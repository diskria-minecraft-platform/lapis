package io.github.diskria.lapis.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.core.cli.CliOptions
import io.github.diskria.lapis.core.lowering.CoreLowering
import io.github.diskria.lapis.core.lowering.models.KMixinBlueprint
import io.github.diskria.lapis.core.validator.CoreValidator
import io.github.diskria.lapis.ksp.generator.KspGenerator
import io.github.diskria.lapis.ksp.logging.KspLogger
import io.github.diskria.lapis.ksp.parser.KspNode
import io.github.diskria.lapis.ksp.parser.KspParser

class Ksp(
    private val options: CliOptions,
    private val codeGenerator: CodeGenerator,
    private val logger: KspLogger,
) : SymbolProcessor {

    private val validator = CoreValidator<KSFile>(options)
    private val blueprints: MutableList<KMixinBlueprint<KSFile>> = mutableListOf()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val nodes = KspParser(resolver).parseNodes()
        val models = nodes.mapNotNull { node ->
            val (model, diagnosticReports) = validator.validate(node)
            diagnosticReports.forEach { report ->
                val targetNode = report.target as KspNode
                targetNode.reportDiagnostic(report.diagnostic, logger)
            }
            model
        }.toList()
        if (models.isNotEmpty()) {
            blueprints += CoreLowering(models, options).lower()
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
        if (blueprints.isEmpty()) return
        KspGenerator(blueprints, options, codeGenerator).generate()
    }
}
