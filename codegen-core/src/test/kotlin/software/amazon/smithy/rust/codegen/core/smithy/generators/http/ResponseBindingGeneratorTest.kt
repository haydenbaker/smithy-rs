/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.generators.http

import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpLocation
import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpTraitHttpBindingResolver
import software.amazon.smithy.rust.codegen.core.smithy.protocols.ProtocolContentTypes
import software.amazon.smithy.rust.codegen.core.smithy.protocols.RestJson
import software.amazon.smithy.rust.codegen.core.smithy.transformers.OperationNormalizer
import software.amazon.smithy.rust.codegen.core.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.core.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.core.testutil.renderWithModelBuilder
import software.amazon.smithy.rust.codegen.core.testutil.testCodegenContext
import software.amazon.smithy.rust.codegen.core.testutil.unitTest
import software.amazon.smithy.rust.codegen.core.util.outputShape

class ResponseBindingGeneratorTest {
    private val baseModel = """
        namespace smithy.example

        @idempotent
        @http(method: "PUT", uri: "/", code: 200)
        operation PutObject {
            output: PutObjectResponse
        }

        list Extras {
            member: Integer
        }

        list Dates {
            member: Timestamp
        }

        @mediaType("video/quicktime")
        string Video

        structure PutObjectResponse {
            // Sent in the X-Dates header
            @httpHeader("X-Dates")
            dateHeaderList: Dates,

            @httpHeader("X-Ints")
            intList: Extras,

            @httpHeader("X-MediaType")
            mediaType: Video,

            // Sent in the body
            data: Blob,

            // Sent in the body
            additional: String,
        }
    """.asSmithyModel()
    private val model = OperationNormalizer.transform(baseModel)
    private val operationShape = model.expectShape(ShapeId.from("smithy.example#PutObject"), OperationShape::class.java)
    private val codegenContext: CodegenContext = testCodegenContext(model)
    private val symbolProvider = codegenContext.symbolProvider

    private fun RustWriter.renderOperation() {
        operationShape.outputShape(model).renderWithModelBuilder(model, symbolProvider, this)
        rustBlock("impl PutObjectOutput") {
            val bindings = HttpTraitHttpBindingResolver(model, ProtocolContentTypes.consistent("dont-care"))
                .responseBindings(operationShape)
                .filter { it.location == HttpLocation.HEADER }
            bindings.forEach { binding ->
                val runtimeType = ResponseBindingGenerator(
                    RestJson(codegenContext),
                    codegenContext,
                    operationShape,
                ).generateDeserializeHeaderFn(binding)
                // little hack to force these functions to be generated
                rust("// use #T;", runtimeType)
            }
        }
    }

    @Test
    fun deserializeHeadersIntoOutputShape() {
        val testProject = TestWorkspace.testProject(symbolProvider)
        testProject.withModule(RustModule.public("output")) {
            renderOperation()
            unitTest(
                "http_header_deser",
                """
                use crate::http_serde;
                let resp = http::Response::builder()
                    .header("X-Ints", "1,2,3")
                    .header("X-Ints", "4,5,6")
                    .header("X-MediaType", "c21pdGh5LXJz")
                    .header("X-Dates", "Mon, 16 Dec 2019 23:48:18 GMT")
                    .header("X-Dates", "Mon, 16 Dec 2019 23:48:18 GMT,Tue, 17 Dec 2019 23:48:18 GMT")
                    .body(()).expect("valid request");
                assert_eq!(http_serde::deser_header_put_object_put_object_output_int_list(resp.headers()).unwrap(), Some(vec![1,2,3,4,5,6]));
                assert_eq!(http_serde::deser_header_put_object_put_object_output_media_type(resp.headers()).expect("valid").unwrap(), "smithy-rs");
                assert_eq!(http_serde::deser_header_put_object_put_object_output_date_header_list(resp.headers()).unwrap().unwrap().len(), 3);
                """,
            )
        }
        testProject.compileAndTest()
    }
}
