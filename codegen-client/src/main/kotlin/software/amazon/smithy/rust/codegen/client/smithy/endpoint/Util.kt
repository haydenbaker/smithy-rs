/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.endpoint

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.rulesengine.language.syntax.Identifier
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter
import software.amazon.smithy.rulesengine.language.syntax.parameters.ParameterType
import software.amazon.smithy.rulesengine.traits.ContextParamTrait
import software.amazon.smithy.rust.codegen.client.smithy.endpoint.generators.EndpointsStdLib
import software.amazon.smithy.rust.codegen.client.smithy.endpoint.generators.FunctionRegistry
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.InlineDependency
import software.amazon.smithy.rust.codegen.core.rustlang.RustDependency
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustReservedWords
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.core.smithy.makeOptional
import software.amazon.smithy.rust.codegen.core.smithy.rustType
import software.amazon.smithy.rust.codegen.core.util.letIf
import software.amazon.smithy.rust.codegen.core.util.toSnakeCase

data class Context(val functionRegistry: FunctionRegistry, val runtimeConfig: RuntimeConfig)

/**
 * Utility function to convert an [Identifier] into a valid Rust identifier (snake case)
 */
fun Identifier.rustName(): String {
    return this.toString().stringToRustName()
}

/**
 * Endpoints standard library file
 */
internal fun endpointsLib(name: String, vararg additionalDependency: RustDependency) = InlineDependency.forRustFile(
    RustModule.pubCrate(
        name,
        parent = EndpointsStdLib,
    ),
    "/inlineable/src/endpoint_lib/$name.rs",
    *additionalDependency,
)

class Types(runtimeConfig: RuntimeConfig) {
    private val smithyTypesEndpointModule = CargoDependency.smithyTypes(runtimeConfig).toType().member("endpoint")
    val smithyHttpEndpointModule = CargoDependency.smithyHttp(runtimeConfig).toType().member("endpoint")
    val resolveEndpoint = smithyHttpEndpointModule.member("ResolveEndpoint")
    val smithyEndpoint = smithyTypesEndpointModule.member("Endpoint")
    val resolveEndpointError = smithyHttpEndpointModule.member("ResolveEndpointError")
}

private fun String.stringToRustName(): String = RustReservedWords.escapeIfNeeded(this.toSnakeCase())

/**
 * Returns the memberName() for a given [Parameter]
 */
fun Parameter.memberName(): String {
    return name.rustName()
}

fun ContextParamTrait.memberName(): String = this.name.stringToRustName()

/**
 * Returns the symbol for a given parameter. This enables [RustWriter] to generate the correct [RustType].
 */
fun Parameter.symbol(): Symbol {
    val rustType = when (this.type) {
        ParameterType.STRING -> RustType.String
        ParameterType.BOOLEAN -> RustType.Bool
        else -> TODO("unexpected type: ${this.type}")
    }
    // Parameter return types are always optional
    return Symbol.builder().rustType(rustType).build().letIf(!this.isRequired) { it.makeOptional() }
}
