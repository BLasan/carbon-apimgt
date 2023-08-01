/*
 *   Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.carbon.apimgt.impl.definitions;

import io.swagger.models.*;
import io.swagger.models.auth.*;
import io.swagger.parser.SwaggerParser;
import org.apache.commons.io.IOUtils;
import org.apache.synapse.unittest.testcase.data.classes.AssertNotNull;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wso2.carbon.apimgt.api.APIDefinitionValidationResponse;
import org.wso2.carbon.apimgt.api.ExceptionCodes;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Scope;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.carbon.apimgt.impl.APIConstants;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OAS2ParserTest extends OASTestBase {
    private OAS2Parser oas2Parser = new OAS2Parser();

    @Test
    public void testGetURITemplates() throws Exception {
        String relativePath = "definitions" + File.separator + "oas2" + File.separator + "oas2_scopes.json";
        String oas2Scope = IOUtils.toString(getClass().getClassLoader().getResourceAsStream(relativePath), "UTF-8");
        testGetURITemplates(oas2Parser, oas2Scope);
    }

    @Test
    public void testGetScopes() throws Exception {
        String relativePath = "definitions" + File.separator + "oas2" + File.separator + "oas2_scopes.json";
        String oas2Scope = IOUtils.toString(getClass().getClassLoader().getResourceAsStream(relativePath), "UTF-8");
        testGetScopes(oas2Parser, oas2Scope);
    }

    @Test
    public void testGenerateAPIDefinition() throws Exception {
        testGenerateAPIDefinition(oas2Parser);
    }

    @Test
    public void testUpdateAPIDefinition() throws Exception {
        String relativePath = "definitions" + File.separator + "oas2" + File.separator + "oas2Resources.json";
        String oas2Resources = IOUtils.toString(getClass().getClassLoader().getResourceAsStream(relativePath), "UTF-8");
        OASParserEvaluator evaluator = (definition -> {
            SwaggerParser swaggerParser = new SwaggerParser();
            Swagger swagger = swaggerParser.parse(definition);
            Assert.assertNotNull(swagger);
            Assert.assertEquals(1, swagger.getPaths().size());
            Assert.assertFalse(swagger.getPaths().containsKey("/noresource/{resid}"));
        });
        testGenerateAPIDefinition2(oas2Parser, oas2Resources, evaluator);
    }

    @Test
    public void testUpdateAPIDefinitionWithExtensions() throws Exception {
        String relativePath = "definitions" + File.separator + "oas2" + File.separator + "oas2Resources.json";
        String oas2Resources = IOUtils.toString(getClass().getClassLoader().getResourceAsStream(relativePath), "UTF-8");
        SwaggerParser swaggerParser = new SwaggerParser();

        // check remove vendor extensions
        String definition = testGenerateAPIDefinitionWithExtension(oas2Parser, oas2Resources);
        Swagger swaggerObj = swaggerParser.parse(definition);
        boolean isExtensionNotFound =
                swaggerObj.getVendorExtensions() == null || swaggerObj.getVendorExtensions().isEmpty();
        Assert.assertTrue(isExtensionNotFound);
        Assert.assertEquals(2, swaggerObj.getPaths().size());

        Iterator<Map.Entry<String, Path>> itr = swaggerObj.getPaths().entrySet().iterator();
        while (itr.hasNext()) {
            Map.Entry<String, Path> pathEntry = itr.next();
            Path path = pathEntry.getValue();
            for (Map.Entry<HttpMethod, Operation> operationEntry : path.getOperationMap().entrySet()) {
                Operation operation = operationEntry.getValue();
                Assert.assertFalse(operation.getVendorExtensions().containsKey(APIConstants.SWAGGER_X_SCOPE));
            }
        }

        // check updated scopes in security definition
        Operation itemGet = swaggerObj.getPath("/items").getGet();
        Assert.assertTrue(itemGet.getSecurity().get(0).get("default").contains("newScope"));

        // check available scopes in security definition
        OAuth2Definition oAuth2Definition = (OAuth2Definition) swaggerObj.getSecurityDefinitions().get("default");
        Assert.assertTrue(oAuth2Definition.getScopes().containsKey("newScope"));
        Assert.assertEquals("newScopeDescription", oAuth2Definition.getScopes().get("newScope"));

        Assert.assertTrue(oAuth2Definition.getVendorExtensions().containsKey(APIConstants.SWAGGER_X_SCOPES_BINDINGS));
        Map<String, String> scopeBinding = (Map<String, String>) oAuth2Definition.getVendorExtensions()
                .get(APIConstants.SWAGGER_X_SCOPES_BINDINGS);
        Assert.assertTrue(scopeBinding.containsKey("newScope"));
        Assert.assertEquals("admin", scopeBinding.get("newScope"));
    }

    @Test
    public void testGetURITemplatesOfOpenAPI20Spec() throws Exception {
        String relativePath = "definitions" + File.separator + "oas2" + File.separator + "oas2_uri_template.json";
        String swagger = IOUtils.toString(getClass().getClassLoader().getResourceAsStream(relativePath), "UTF-8");
        Set<URITemplate> uriTemplates = new LinkedHashSet<>();
        uriTemplates.add(getUriTemplate("POST", "Application User", "/*"));
        uriTemplates.add(getUriTemplate("GET", "Application", "/*"));
        uriTemplates.add(getUriTemplate("PUT", "None", "/*"));
        uriTemplates.add(getUriTemplate("DELETE", "Any", "/*"));
        uriTemplates.add(getUriTemplate("GET", "Application & Application User", "/abc"));
        Set<URITemplate> uriTemplateSet = oas2Parser.getURITemplates(swagger);
        Assert.assertEquals(uriTemplateSet, uriTemplates);
    }

    @Test
    public void testRemoveResponsesObjectFromOpenAPI20Spec() throws Exception {
        String relativePathSwagger1 = "definitions" + File.separator + "oas2" + File.separator +
                "oas2_uri_template.json";
        String relativePathSwagger2 = "definitions" + File.separator + "oas2" + File.separator +
                "oas2_uri_template_with_responsesObject.json";
        String swaggerWithoutResponsesObject = IOUtils.toString(getClass().getClassLoader().
                getResourceAsStream(relativePathSwagger1), "UTF-8");
        String swaggerWithResponsesObject = IOUtils.toString(getClass().getClassLoader().
                getResourceAsStream(relativePathSwagger2), "UTF-8");
        Swagger swagger = oas2Parser.getSwagger(swaggerWithResponsesObject);
        Assert.assertEquals(oas2Parser.removeResponsesObject(swagger,swaggerWithoutResponsesObject),
                oas2Parser.removeResponsesObject(swagger,swaggerWithResponsesObject));
    }
    @Test
    public void testSwaggerValidatorWithValidationLevel2() throws Exception {
        String faultySwagger = IOUtils.toString(
                getClass().getClassLoader().getResourceAsStream("definitions" + File.separator + "oas2"
                        + File.separator + "oas_util_test_faulty_swagger.json"), String.valueOf(StandardCharsets.UTF_8));
        APIDefinitionValidationResponse response = OASParserUtil.validateAPIDefinition(faultySwagger, true);
        Assert.assertFalse(response.isValid());
        Assert.assertEquals(3, response.getErrorItems().size());
        Assert.assertEquals(ExceptionCodes.OPENAPI_PARSE_EXCEPTION.getErrorCode(),
                response.getErrorItems().get(0).getErrorCode());
        Assert.assertEquals(ExceptionCodes.INVALID_OAS2_FOUND.getErrorCode(),
                response.getErrorItems().get(1).getErrorCode());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testGetOASDefinitionForStore() throws Exception {
        // Testing API with all 3 oauth, basic and api key security with scopes.
        String swagger = IOUtils.toString(
                getClass().getClassLoader().getResourceAsStream("definitions" + File.separator + "oas2"
                        + File.separator + "oas2AllSecurity.json"), String.valueOf(StandardCharsets.UTF_8));
        APIIdentifier apiIdentifier = new APIIdentifier("admin", "SwaggerPetstore", "1.0.0");
        Map<String, String> hostWithSchemes = new HashMap<>();
        hostWithSchemes.put(APIConstants.HTTPS_PROTOCOL, "https://localhost");
        API api = new API(apiIdentifier);
        api.setApiSecurity("oauth_basic_auth_api_key_mandatory,api_key,basic_auth,oauth2");
        api.setTransports("https");
        api.setContext("/petstore");
        api.setScopes(getAPITestScopes());
        String swaggerForStore = oas2Parser.getOASDefinitionForStore(api, swagger, hostWithSchemes);
        SwaggerParser swaggerParser = new SwaggerParser();
        Swagger swaggerObjForStore = swaggerParser.parse(swaggerForStore);
        Assert.assertNotNull(swaggerObjForStore.getSecurityDefinitions());
        Map<String, SecuritySchemeDefinition> securitySchemeDefinitionMap = swaggerObjForStore.getSecurityDefinitions();
        // Test basic auth security definition.
        Assert.assertNotNull(securitySchemeDefinitionMap.get(APIConstants.API_SECURITY_BASIC_AUTH));
        BasicAuthDefinition basicAuthDefinition = (BasicAuthDefinition)
                securitySchemeDefinitionMap.get(APIConstants.API_SECURITY_BASIC_AUTH);
        Assert.assertTrue(basicAuthDefinition.getVendorExtensions()
                .containsKey(APIConstants.SWAGGER_X_BASIC_AUTH_SCOPES));
        Assert.assertTrue(basicAuthDefinition.getVendorExtensions()
                .containsKey(APIConstants.SWAGGER_X_SCOPES_BINDINGS));
        testAPIScopes((Map<String, String>) basicAuthDefinition.getVendorExtensions()
                        .get(APIConstants.SWAGGER_X_BASIC_AUTH_SCOPES),
                (Map<String, String>) basicAuthDefinition.getVendorExtensions()
                        .get(APIConstants.SWAGGER_X_SCOPES_BINDINGS));
        // Test OAuth security definition.
        Assert.assertNotNull(securitySchemeDefinitionMap.get(APIConstants.SWAGGER_APIM_DEFAULT_SECURITY));
        Assert.assertEquals(securitySchemeDefinitionMap.get(APIConstants.SWAGGER_APIM_DEFAULT_SECURITY).getType(),
                APIConstants.DEFAULT_API_SECURITY_OAUTH2);
        Assert.assertTrue(securitySchemeDefinitionMap.get(APIConstants.SWAGGER_APIM_DEFAULT_SECURITY)
                .getVendorExtensions().containsKey(APIConstants.SWAGGER_X_SCOPES_BINDINGS));
        OAuth2Definition oAuth2Definition = ((OAuth2Definition) securitySchemeDefinitionMap
                .get(APIConstants.SWAGGER_APIM_DEFAULT_SECURITY));
        testAPIScopes(oAuth2Definition.getScopes(), (Map<String, String>) oAuth2Definition.getVendorExtensions()
                .get(APIConstants.SWAGGER_X_SCOPES_BINDINGS));
        // Test API Key security definition.
        Assert.assertNotNull(securitySchemeDefinitionMap.get(APIConstants.API_SECURITY_API_KEY));
        ApiKeyAuthDefinition apiKeyAuthDefinition = (ApiKeyAuthDefinition) securitySchemeDefinitionMap
                .get(APIConstants.API_SECURITY_API_KEY);
        Assert.assertEquals(apiKeyAuthDefinition.getIn(), In.HEADER);
        Assert.assertEquals(apiKeyAuthDefinition.getName(), APIConstants.API_KEY_AUTH_TYPE);
        // Test Swagger Security.
        List<SecurityRequirement> securityRequirements = swaggerObjForStore.getSecurity();
        Assert.assertNotNull(securityRequirements);
        Assert.assertEquals(securityRequirements.size(), 3);
        SecurityRequirement defaultSec = new SecurityRequirement();
        defaultSec.setRequirements(APIConstants.SWAGGER_APIM_DEFAULT_SECURITY, new ArrayList<>());
        SecurityRequirement basicSec = new SecurityRequirement();
        basicSec.setRequirements(APIConstants.API_SECURITY_BASIC_AUTH, new ArrayList<>());
        SecurityRequirement apiKeySec = new SecurityRequirement();
        apiKeySec.setRequirements(APIConstants.API_SECURITY_API_KEY, new ArrayList<>());
        Assert.assertArrayEquals(securityRequirements.toArray(),
                new SecurityRequirement[]{defaultSec, basicSec, apiKeySec});
        // Test operation level security.
        for (Map.Entry<String, Path> pathEntry : swaggerObjForStore.getPaths().entrySet()) {
            for (Operation operation : pathEntry.getValue().getOperations()) {
                List<Map<String, List<String>>> opSecurity = operation.getSecurity();
                Assert.assertFalse(opSecurity.isEmpty());
                if ("post".equals(operation.getOperationId())) {
                    Map<String, List<String>> defaultOpSec = new HashMap<>();
                    defaultOpSec.put(APIConstants.SWAGGER_APIM_DEFAULT_SECURITY, new ArrayList<String>() {{
                        add("PetLocalScope");
                        add("GlobalScope");
                    }});
                    Map<String, List<String>> basicOpSec = new HashMap<>();
                    basicOpSec.put(APIConstants.API_SECURITY_BASIC_AUTH, new ArrayList<>());
                    Map<String, List<String>> apiKeyOpSec = new HashMap<>();
                    apiKeyOpSec.put(APIConstants.API_SECURITY_API_KEY, new ArrayList<>());
                    List<Map<String, List<String>>> expectedSec = new ArrayList<Map<String, List<String>>>() {{
                        add(defaultOpSec);
                        add(basicOpSec);
                        add(apiKeyOpSec);
                    }};
                    Assert.assertArrayEquals(opSecurity.toArray(), expectedSec.toArray());
                    Assert.assertTrue(operation.getVendorExtensions()
                            .containsKey(APIConstants.SWAGGER_X_BASIC_AUTH_RESOURCE_SCOPES));
                    List<String> basicOpScopes = (ArrayList<String>) operation.getVendorExtensions()
                            .get(APIConstants.SWAGGER_X_BASIC_AUTH_RESOURCE_SCOPES);
                    Assert.assertArrayEquals(basicOpScopes.toArray(), new String[]{"PetLocalScope", "GlobalScope"});
                }
                if ("put".equals(operation.getOperationId())) {
                    Map<String, List<String>> defaultOpSec = new HashMap<>();
                    defaultOpSec.put(APIConstants.SWAGGER_APIM_DEFAULT_SECURITY, new ArrayList<String>() {{
                        add("PetLocalScope");
                    }});
                    Map<String, List<String>> basicOpSec = new HashMap<>();
                    basicOpSec.put(APIConstants.API_SECURITY_BASIC_AUTH, new ArrayList<>());
                    Map<String, List<String>> apiKeyOpSec = new HashMap<>();
                    apiKeyOpSec.put(APIConstants.API_SECURITY_API_KEY, new ArrayList<>());
                    List<Map<String, List<String>>> expectedSec = new ArrayList<Map<String, List<String>>>() {{
                        add(defaultOpSec);
                        add(basicOpSec);
                        add(apiKeyOpSec);
                    }};
                    Assert.assertArrayEquals(opSecurity.toArray(), expectedSec.toArray());
                    Assert.assertTrue(operation.getVendorExtensions()
                            .containsKey(APIConstants.SWAGGER_X_BASIC_AUTH_RESOURCE_SCOPES));
                    List<String> basicOpScopes = (ArrayList<String>) operation.getVendorExtensions()
                            .get(APIConstants.SWAGGER_X_BASIC_AUTH_RESOURCE_SCOPES);
                    Assert.assertArrayEquals(basicOpScopes.toArray(), new String[]{"PetLocalScope"});
                }
            }
        }
    }

    private Set<Scope> getAPITestScopes() {
        Scope petLocalScope = new Scope();
        petLocalScope.setKey("PetLocalScope");
        petLocalScope.setName("PetLocalScope");
        petLocalScope.setRoles("admin");
        petLocalScope.setDescription("admin");
        Scope globalScope = new Scope();
        globalScope.setName("GlobalScope");
        globalScope.setKey("GlobalScope");
        globalScope.setDescription("desc");
        globalScope.setRoles("");
        Set<Scope> apiScopes = new HashSet<>();
        apiScopes.add(globalScope);
        apiScopes.add(petLocalScope);
        return apiScopes;
    }

    private void testAPIScopes(Map<String, String> swaggerScopes, Map<String, String> swaggerScopeToRoleBindings) {
        Assert.assertTrue(swaggerScopes.containsKey("PetLocalScope"));
        Assert.assertEquals(swaggerScopes.get("PetLocalScope"), "admin");
        Assert.assertTrue(swaggerScopes.containsKey("GlobalScope"));
        Assert.assertEquals(swaggerScopes.get("GlobalScope"), "desc");
        Assert.assertTrue(swaggerScopeToRoleBindings.containsKey("PetLocalScope"));
        Assert.assertEquals(swaggerScopeToRoleBindings.get("PetLocalScope"), "admin");
        Assert.assertTrue(swaggerScopeToRoleBindings.containsKey("GlobalScope"));
        Assert.assertEquals(swaggerScopeToRoleBindings.get("GlobalScope"), "");
    }
}