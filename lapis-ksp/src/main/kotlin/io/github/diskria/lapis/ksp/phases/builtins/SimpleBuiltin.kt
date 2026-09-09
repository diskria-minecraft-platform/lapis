package io.github.diskria.lapis.ksp.phases.builtins

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.diskria.lapis.ksp.extensions.kp.*
import io.github.diskria.lapis.ksp.phases.generator.builders.nullKotlinCodeBlock
import io.github.diskria.lapis.ksp.phases.generator.builders.toKotlinCodeBlock
import io.github.diskria.lapis.ksp.phases.lowering.IrVisibilityModifier
import io.github.diskria.lapis.ksp.phases.lowering.asIrClassName
import io.github.diskria.lapis.ksp.phases.lowering.asIrParameterizedTypeName
import io.github.diskria.lapis.ksp.phases.lowering.asIrTypeName
import io.github.diskria.lapis.ksp.phases.lowering.models.IrParameter
import io.github.diskria.lapis.ksp.phases.lowering.models.toKotlinConstructorProperty
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeVariableName
import io.github.diskria.poetesse.kotlin.KPAny
import io.github.diskria.poetesse.kotlin.KPBoolean
import io.github.diskria.poetesse.kotlin.KPModifier
import io.github.diskria.poetesse.kotlin.KPType

enum class SimpleBuiltin(override val isInternal: Boolean = false) : Builtin<KPType> {
    LocalVar {
        override fun generate(resolveBuiltin: BuiltinResolver): KPType =
            buildKotlinInterface(name) {
                addModifiers(KPModifier.SEALED)
                val localTypeVariableName = IrTypeVariableName.of("T")
                setVariableTypes(localTypeVariableName)
                addProperty(buildKotlinProperty("value", localTypeVariableName) {
                    addModifiers(KPModifier.ABSTRACT)
                    mutable(true)
                })
            }
    },
    Instanceof {
        override fun generate(resolveBuiltin: BuiltinResolver): KPType =
            buildKotlinClass(name) {
                val valueParameter = IrParameter("value", KPAny.asIrClassName())
                val operationParameter = IrParameter(
                    "operation",
                    Operation::class.asIrParameterizedTypeName(KPBoolean.asIrClassName()),
                )
                setConstructor(valueParameter, operationParameter)
                addProperties(
                    listOf(
                        valueParameter.toKotlinConstructorProperty(),
                        operationParameter.toKotlinConstructorProperty(IrVisibilityModifier.PRIVATE),
                    )
                )
                addFunction(buildKotlinFunction("invoke") {
                    addModifiers(KPModifier.OPERATOR)
                    val valueParameter = buildKotlinParameter("value", valueParameter.typeName) {
                        setDefaultValue("this.%N") { +valueParameter }
                    }
                    addParameter(valueParameter)
                    setReturnType(KPBoolean.asIrClassName())
                    setBody {
                        return_("%N.%L(%N)") { +operationParameter; +Operation<*>::call; +valueParameter }
                    }
                })
            }
    },
    CancelSignal(isInternal = true) {
        override fun generate(resolveBuiltin: BuiltinResolver): KPType =
            buildKotlinObject(name) {
                setSuperClass(
                    RuntimeException::class.asIrTypeName(),
                    constructorArguments = listOf(
                        nullKotlinCodeBlock,
                        nullKotlinCodeBlock,
                        false.toKotlinCodeBlock(),
                        false.toKotlinCodeBlock(),
                    )
                )
                addFunction(buildKotlinFunction(RuntimeException::fillInStackTrace.name) {
                    addModifiers(KPModifier.OVERRIDE)
                    setReturnType(Throwable::class.asIrTypeName())
                    setBody { return_("this") }
                })
            }
    };

    abstract override fun generate(resolveBuiltin: BuiltinResolver): KPType
}
