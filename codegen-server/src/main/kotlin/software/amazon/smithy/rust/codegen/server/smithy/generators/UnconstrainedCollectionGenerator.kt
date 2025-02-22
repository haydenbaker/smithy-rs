/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators

import software.amazon.smithy.model.shapes.CollectionShape
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.makeMaybeConstrained
import software.amazon.smithy.rust.codegen.core.smithy.module
import software.amazon.smithy.rust.codegen.server.smithy.PubCrateConstraintViolationSymbolProvider
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.UnconstrainedShapeSymbolProvider
import software.amazon.smithy.rust.codegen.server.smithy.canReachConstrainedShape
import software.amazon.smithy.rust.codegen.server.smithy.isDirectlyConstrained

/**
 * Generates a Rust type for a constrained collection shape that is able to hold values for the corresponding
 * _unconstrained_ shape. This type is a [RustType.Opaque] wrapper tuple newtype holding a `Vec`. Upon request parsing,
 * server deserializers use this type to store the incoming values without enforcing the modeled constraints. Only after
 * the full request has been parsed are constraints enforced, via the `impl TryFrom<UnconstrainedSymbol> for
 * ConstrainedSymbol`.
 *
 * This type is never exposed to the user; it is always `pub(crate)`. Only the deserializers use it.
 *
 * Consult [UnconstrainedShapeSymbolProvider] for more details and for an example.
 */
class UnconstrainedCollectionGenerator(
    val codegenContext: ServerCodegenContext,
    private val unconstrainedModuleWriter: RustWriter,
    val shape: CollectionShape,
) {
    private val model = codegenContext.model
    private val symbolProvider = codegenContext.symbolProvider
    private val unconstrainedShapeSymbolProvider = codegenContext.unconstrainedShapeSymbolProvider
    private val pubCrateConstrainedShapeSymbolProvider = codegenContext.pubCrateConstrainedShapeSymbolProvider
    private val publicConstrainedTypes = codegenContext.settings.codegenConfig.publicConstrainedTypes
    private val constraintViolationSymbolProvider =
        with(codegenContext.constraintViolationSymbolProvider) {
            if (publicConstrainedTypes) {
                this
            } else {
                PubCrateConstraintViolationSymbolProvider(this)
            }
        }
    private val constrainedShapeSymbolProvider = codegenContext.constrainedShapeSymbolProvider
    private val constrainedSymbol = if (shape.isDirectlyConstrained(symbolProvider)) {
        constrainedShapeSymbolProvider.toSymbol(shape)
    } else {
        pubCrateConstrainedShapeSymbolProvider.toSymbol(shape)
    }

    fun render() {
        check(shape.canReachConstrainedShape(model, symbolProvider))

        val symbol = unconstrainedShapeSymbolProvider.toSymbol(shape)
        val name = symbol.name
        val innerShape = model.expectShape(shape.member.target)
        val innerUnconstrainedSymbol = unconstrainedShapeSymbolProvider.toSymbol(innerShape)
        val constraintViolationSymbol = constraintViolationSymbolProvider.toSymbol(shape)
        val innerConstraintViolationSymbol = constraintViolationSymbolProvider.toSymbol(innerShape)

        unconstrainedModuleWriter.withInlineModule(symbol.module()) {
            rustTemplate(
                """
                ##[derive(Debug, Clone)]
                pub(crate) struct $name(pub(crate) std::vec::Vec<#{InnerUnconstrainedSymbol}>);

                impl From<$name> for #{MaybeConstrained} {
                    fn from(value: $name) -> Self {
                        Self::Unconstrained(value)
                    }
                }

                impl #{TryFrom}<$name> for #{ConstrainedSymbol} {
                    type Error = #{ConstraintViolationSymbol};

                    fn try_from(value: $name) -> Result<Self, Self::Error> {
                        let res: Result<_, (usize, #{InnerConstraintViolationSymbol})> = value
                            .0
                            .into_iter()
                            .enumerate()
                            .map(|(idx, inner)| inner.try_into().map_err(|inner_violation| (idx, inner_violation)))
                            .collect();
                        res.map(Self)
                           .map_err(|(idx, inner_violation)| #{ConstraintViolationSymbol}::Member(idx, inner_violation))
                    }
                }
                """,
                "InnerUnconstrainedSymbol" to innerUnconstrainedSymbol,
                "InnerConstraintViolationSymbol" to innerConstraintViolationSymbol,
                "ConstrainedSymbol" to constrainedSymbol,
                "ConstraintViolationSymbol" to constraintViolationSymbol,
                "MaybeConstrained" to constrainedSymbol.makeMaybeConstrained(),
                "TryFrom" to RuntimeType.TryFrom,
            )
        }
    }
}
