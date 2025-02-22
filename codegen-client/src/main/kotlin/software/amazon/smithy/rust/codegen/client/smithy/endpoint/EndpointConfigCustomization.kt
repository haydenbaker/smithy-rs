/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.endpoint

import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.generators.config.ConfigCustomization
import software.amazon.smithy.rust.codegen.client.smithy.generators.config.ServiceConfig
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable

/**
 * Customization which injects an Endpoints 2.0 Endpoint Resolver into the service config struct
 */
internal class EndpointConfigCustomization(
    codegenContext: ClientCodegenContext,
    private val typesGenerator: EndpointTypesGenerator,
) :
    ConfigCustomization() {
    private val runtimeConfig = codegenContext.runtimeConfig
    private val moduleUseName = codegenContext.moduleUseName()
    private val types = Types(runtimeConfig)

    override fun section(section: ServiceConfig): Writable {
        return writable {
            val resolverTrait = "#{SmithyResolver}<#{Params}>"
            val codegenScope = arrayOf(
                "SmithyResolver" to types.resolveEndpoint,
                "Params" to typesGenerator.paramsStruct(),
                "DefaultResolver" to typesGenerator.defaultResolver(),
            )
            when (section) {
                is ServiceConfig.ConfigStruct -> rustTemplate(
                    "pub (crate) endpoint_resolver: std::sync::Arc<dyn $resolverTrait>,",
                    *codegenScope,
                )

                is ServiceConfig.ConfigImpl ->
                    rustTemplate(
                        """
                        /// Returns the endpoint resolver.
                        pub fn endpoint_resolver(&self) -> std::sync::Arc<dyn $resolverTrait> {
                            self.endpoint_resolver.clone()
                        }
                        """,
                        *codegenScope,
                    )

                is ServiceConfig.BuilderStruct ->
                    rustTemplate(
                        "endpoint_resolver: Option<std::sync::Arc<dyn $resolverTrait>>,",
                        *codegenScope,
                    )

                ServiceConfig.BuilderImpl ->
                    rustTemplate(
                        """
                        /// Sets the endpoint resolver to use when making requests.
                        ///
                        /// When unset, the client will used a generated endpoint resolver based on the endpoint resolution
                        /// rules for `$moduleUseName`.
                        ///
                        /// ## Examples
                        /// ```no_run
                        /// use aws_smithy_http::endpoint;
                        /// use $moduleUseName::endpoint::{Params as EndpointParams, DefaultResolver};
                        /// /// Endpoint resolver which adds a prefix to the generated endpoint
                        /// struct PrefixResolver {
                        ///     base_resolver: DefaultResolver,
                        ///     prefix: String
                        /// }
                        /// impl endpoint::ResolveEndpoint<EndpointParams> for PrefixResolver {
                        ///   fn resolve_endpoint(&self, params: &EndpointParams) -> endpoint::Result {
                        ///        self.base_resolver
                        ///              .resolve_endpoint(params)
                        ///              .map(|ep|{
                        ///                   let url = ep.url().to_string();
                        ///                   ep.into_builder().url(format!("{}.{}", &self.prefix, url)).build()
                        ///               })
                        ///   }
                        /// }
                        /// let prefix_resolver = PrefixResolver {
                        ///     base_resolver: DefaultResolver::new(),
                        ///     prefix: "subdomain".to_string()
                        /// };
                        /// let config = $moduleUseName::Config::builder().endpoint_resolver(prefix_resolver);
                        /// ```
                        pub fn endpoint_resolver(mut self, endpoint_resolver: impl $resolverTrait + 'static) -> Self {
                            self.endpoint_resolver = Some(std::sync::Arc::new(endpoint_resolver) as _);
                            self
                        }

                        /// Sets the endpoint resolver to use when making requests.
                        ///
                        /// When unset, the client will used a generated endpoint resolver based on the endpoint resolution
                        /// rules for `$moduleUseName`.
                        pub fn set_endpoint_resolver(&mut self, endpoint_resolver: Option<std::sync::Arc<dyn $resolverTrait>>) -> &mut Self {
                            self.endpoint_resolver = endpoint_resolver;
                            self
                        }
                        """,
                        *codegenScope,
                    )

                ServiceConfig.BuilderBuild -> {
                    rustTemplate(
                        """
                        endpoint_resolver: self.endpoint_resolver.unwrap_or_else(||
                            std::sync::Arc::new(#{DefaultResolver}::new())
                        ),
                        """,
                        *codegenScope,
                    )
                }

                else -> emptySection
            }
        }
    }
}
