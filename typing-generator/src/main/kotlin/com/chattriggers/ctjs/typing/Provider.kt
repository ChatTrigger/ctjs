package com.chattriggers.ctjs.typing

import com.chattriggers.ctjs.typing.annotations.CTApi
import com.chattriggers.ctjs.typing.annotations.useClassName
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

class Provider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return Processor(environment)
    }
}

@OptIn(KspExperimental::class)
class Processor(environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private val codeGenerator = environment.codeGenerator
    private val logger = environment.logger
    private val dependentFiles = mutableSetOf<KSFile>()
    private val builder = StringBuilder()
    private var indent = 0
    private lateinit var resolver: Resolver

    private val classQueue = mutableListOf<KSClassDeclaration>()
    private val processedClasses = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        appendLine("""
            /// <reference no-default-lib="true" />
            /// <reference lib="es2015" />
            export {};

            declare global {
        """.trimIndent())
        indent++

        this.resolver = resolver

        resolver.getSymbolsWithAnnotation(CTApi::class.qualifiedName!!).forEach {
            require(it is KSClassDeclaration)
            classQueue.add(it)
        }

        while (classQueue.isNotEmpty()) {
            val clazz = classQueue.removeLast()
            logger.warn("processing ${clazz.qualifiedName?.asString()}")
            processClass(clazz)
        }

        return emptyList()
    }

    override fun finish() {
        indent--
        check(indent == 0)
        appendLine("}")

        codeGenerator
            .createNewFileByPath(Dependencies(true, *dependentFiles.toTypedArray()), "typings", "d.ts")
            .write(builder.toString().toByteArray())
    }

    private fun processClass(clazz: KSClassDeclaration) {
        clazz.containingFile?.let(dependentFiles::add)

        var className = clazz.simpleName.asString()
        var type = if (clazz.classKind == ClassKind.OBJECT) "namespace" else "class"

        clazz.getAnnotationsByType(CTApi::class).singleOrNull()?.let {
            if (!it.useClassName())
                className = it.name
            if (it.singleton)
                type = "namespace"
        }

        appendLine("export $type $className {")
        indented {
            var firstMethod = true
            for (method in clazz.getAllFunctions()) {
                if (method.simpleName.asString().let { it == "<init>" || it == "<clinit>" })
                    continue

                if (!firstMethod)
                    append('\n')
                firstMethod = false

                processMethod(type == "namespace", method)
            }
        }
        appendLine("}\n")
    }

    private fun processMethod(isInNamespace: Boolean, method: KSFunctionDeclaration) {
        if (method.docString != null)
            append(formatDocString(method.docString!!))

        appendLine(buildString {
            if (isInNamespace)
                append("function ")
            append("${method.simpleName.asString()}(")

            var firstParam = true
            for (param in method.parameters) {
                if (!firstParam)
                    append(", ")
                firstParam = false
                append(param.name?.asString() ?: "_")
                append(": ")
                val resolvedType = param.type.resolve().also {
                    submitDeclaration(it.declaration)
                }
                append(resolvedType.typeName())
            }

            append(')')

            val returnType = method.returnType?.resolve()?.also {
                submitDeclaration(it.declaration)
            }

            if (returnType != null && returnType != resolver.builtIns.unitType) {
                append(": ${returnType.typeName()}")
            }

            append(';')
        })
    }

    private fun formatDocString(str: String) = buildString {
        append("/**\n")
        str.trim().lines().forEach { append(" * $it\n") }
        append(" */")
    }.prependIndent("    ".repeat(indent)) + "\n"

    private fun submitDeclaration(declaration: KSDeclaration?) {
        if (declaration is KSClassDeclaration && declaration.qualifiedName!!.asString() !in processedClasses) {
            classQueue.add(declaration)
            processedClasses.add(declaration.qualifiedName!!.asString())
        } else if (declaration is KSTypeAlias) {
            submitDeclaration(declaration.type.resolve().declaration)
        }
    }

    private fun append(s: Any) {
        builder.append(s)
    }

    private fun appendLine(s: Any) {
        repeat(indent) { builder.append("    ") }
        builder.append(s)
        builder.append('\n')
    }

    private fun indented(block: () -> Unit) {
        indent++
        block()
        indent--
        check(indent >= 0)
    }
}
