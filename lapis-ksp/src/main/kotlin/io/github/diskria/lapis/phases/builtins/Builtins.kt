package io.github.diskria.lapis.phases.builtins

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.diskria.lapis.Lapis
import io.github.diskria.lapis.extensions.common.lapisError
import io.github.diskria.lapis.extensions.kp.buildKotlinFile
import io.github.diskria.lapis.extensions.kp.buildKotlinObject
import io.github.diskria.lapis.phases.generator.models.GenDescriptorWrapperImplResult
import io.github.diskria.lapis.phases.lowering.models.IrDescriptorWrapperImpl
import io.github.diskria.lapis.phases.lowering.types.IrClassName
import io.github.diskria.lapis.phases.lowering.types.IrParameterizedTypeName
import io.github.diskria.poetesse.kotlin.KPType
import io.github.diskria.poetesse.kotlin.KPTypeAlias

class Builtins(packageName: String, private val codeGenerator: CodeGenerator) {

    var isExternalGenerated: Boolean = false
        private set

    private val externalClassName: IrClassName = IrClassName.of(packageName, Lapis.NAME)
    private val internalClassName: IrClassName = IrClassName.of(packageName, Lapis.NAME + "Internal")
    private val requestedInternalBuiltins: MutableMap<String, Builtin<*>> = mutableMapOf()

    private var isInternalGenerated: Boolean = false

    fun generateExternal() {
        if (isExternalGenerated) {
            lapisError("External builtins already generated")
        }
        buildKotlinFile(externalClassName) {
            val externalBuiltins = Builtin.entries.filter { !it.isInternal }.map { it.generate(::get) }
            externalBuiltins.filterIsInstance<KPTypeAlias>().forEach {
                addTypeAlias(it)
            }
            addType(buildKotlinObject(externalClassName.simpleName) {
                addTypes(externalBuiltins.filterIsInstance<KPType>())
            })
        }.writeTo(codeGenerator, Dependencies.ALL_FILES)
        isExternalGenerated = true
    }

    fun generateInternal() {
        if (isInternalGenerated) {
            lapisError("Internal builtins already generated")
        }
        if (requestedInternalBuiltins.isEmpty()) {
            return
        }
        buildKotlinFile(internalClassName) {
            val builtins = requestedInternalBuiltins.values.map { it.generate(::get) }
            builtins.filterIsInstance<KPTypeAlias>().forEach(::addTypeAlias)
            addType(buildKotlinObject(internalClassName.simpleName) {
                addTypes(builtins.filterIsInstance<KPType>())
            })
        }.writeTo(codeGenerator, Dependencies.ALL_FILES)
        isInternalGenerated = true
    }

    operator fun get(builtin: Builtin<*>): IrClassName {
        if (builtin.isInternal) {
            requestedInternalBuiltins.getOrPut(builtin.name) { builtin }
        }
        return (if (builtin.isInternal) internalClassName else externalClassName).nested(builtin.name)
    }

    fun <T : IrDescriptorWrapperImpl<T>> generateDescriptorWrapperImpl(
        impl: T,
        superClassTypeName: IrParameterizedTypeName,
    ): GenDescriptorWrapperImplResult =
        impl.wrapperBuiltin.generateImpl(impl, superClassTypeName, ::get)
}
