package com.chattriggers.ctjs.typing

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFile

class Provider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return Processor(environment)
    }
}

class Processor(environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private val codeGenerator = environment.codeGenerator
    private val logger = environment.logger
    private val dependentFiles = mutableSetOf<KSFile>()
    private val builder = StringBuilder()
    private var indent = 0

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val nodes = Collector(resolver, logger).collect()

        appendLine(
            """
            /// <reference no-default-lib="true" />
            /// <reference lib="es2015" />
            export {};

            declare global {
        """.trimIndent())
        indent++

        nodes.forEach(::generate)

        return emptyList()
    }

    private fun generate(node: Node) {
        when (node) {
            is Node.Package -> generatePackage(node)
            is Node.Class -> generateClass(node, false)
            is Node.Enum -> generateEnum(node, false)
            is Node.TypeReference,
            is Node.Function,
            is Node.Property,
            Node.Unknown -> throw IllegalStateException()
        }
    }

    private fun generatePackage(pkg: Node.Package) {
        appendLine("namespace ${pkg.name} {")

        indented {
            pkg.children.values.forEach(::generate)
        }

        appendLine("}")
    }

    private fun generateClass(clazz: Node.Class, isNested: Boolean) {
        val partitionedChildren = partitionChildren(clazz.children)

        appendLine(buildString {
            if (clazz.isInterface)
                append("abstract ")
            append("class")

            if (!isNested)
                append(" " + clazz.name)

            if (clazz.typeParameters.isNotEmpty())
                append(clazz.typeParameters.joinToString(prefix = "<", postfix = ">"))
            append(" {")
        })

        indented {
            generateChildren(partitionedChildren)
        }
        appendLine("}")
    }

    private fun generateEnum(enum: Node.Enum, isNested: Boolean) {
        val partitionedChildren = partitionChildren(enum.children)

        if (isNested) {
            append("class {\n")
        } else {
            appendLine("class ${enum.name} {")
        }

        indented {
            for (entry in enum.entries) {
                if (entry.docString != null)
                    append(formatDocString(entry.docString))
                appendLine("static ${entry.name}: ${enum.name}")
            }

            generateChildren(partitionedChildren)
        }
        appendLine("}")

    }

    private fun generateChildren(children: PartitionedChildren) {
        // Unlike Java, JS does not allow properties and functions to have the same name,
        // so in the case that a pair does share the same name, we prefer the function
        val functionNames = children.functions.mapTo(mutableSetOf()) { it.name }

        for (property in children.properties) {
            if (property.name in functionNames)
                continue

            if (property.docString != null)
                append(formatDocString(property.docString))

            appendLine(buildString {
                if (property.static)
                    append("static ")
                append(property.name)
                append(": ")
                append(property.type.stringify())
                append(';')
            })
        }

        if (children.properties.isNotEmpty() && children.functions.isNotEmpty())
            append('\n')

        for (function in children.functions) {
            if (function.docString != null)
                append(formatDocString(function.docString))

            appendLine(buildString {
                if (function.static)
                    append("static ")

                append(function.name)

                if (function.typeParameters.isNotEmpty())
                    append(function.typeParameters.joinToString(prefix = "<", postfix = ">"))

                append("(")
                append(function.params.joinToString { "${it.first}: ${it.second.stringify()}" })
                append(")")

                if (function.returnType != null)
                    append(": " + function.returnType.stringify())

                append(';')
            })
        }

        if (children.functions.isNotEmpty() && children.other.isNotEmpty())
            append('\n')

        for (other in children.other) {
            repeat(indent) { builder.append("\t") }
            append("static ${other.name} = ")
            when (other) {
                is Node.Class -> generateClass(other, true)
                is Node.Enum -> generateEnum(other, true)
                else -> error("Unexpected node in children.other: ${other::class.simpleName}")
            }
        }
    }

    data class PartitionedChildren(
        val properties: List<Node.Property>,
        val functions: List<Node.Function>,
        val other: List<Node>,
    )

    private fun partitionChildren(children: List<Node>): PartitionedChildren {
        val properties = mutableListOf<Node.Property>()
        val functions = mutableListOf<Node.Function>()
        val other = mutableListOf<Node>()

        children.forEach {
            when (it) {
                is Node.Property -> properties.add(it)
                is Node.Function -> functions.add(it)
                else -> other.add(it)
            }
        }

        return PartitionedChildren(properties, functions, other)
    }

    private fun formatDocString(str: String) = buildString {
        append("/**\n")
        str.trim().lines().forEach { append(" * $it\n") }
        append(" */")
    }.prependIndent("\t".repeat(indent)) + "\n"

    override fun finish() {
        indent--
        check(indent == 0)
        appendLine("}")

        codeGenerator
            .createNewFileByPath(Dependencies(true, *dependentFiles.toTypedArray()), "typings", "d.ts")
            .write(builder.toString().toByteArray())
    }

    private fun append(s: Any) {
        builder.append(s)
    }

    private fun appendLine(s: Any) {
        repeat(indent) { builder.append("\t") }
        builder.append(s)
        builder.append('\n')
    }

    private fun indented(block: () -> Unit) {
        indent++
        block()
        indent--
        check(indent >= 0)
    }

    private fun Node.TypeReference.stringify(): String = buildString {
        append(qualifiedName())
        if (typeArguments.isNotEmpty())
            append(typeArguments.joinToString(prefix = "<", postfix = ">") { it.stringify() })
    }
}
