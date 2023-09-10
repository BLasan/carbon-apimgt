/*
 * Copyright (c) 2022 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.tracing.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.tracing.internal.ServiceReferenceHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Class for getting Otlp tracer from reading configuration file.
 */
public class OTLPTelemetry implements APIMOpenTelemetry {

    private static final String NAME = "otlp";
    private static final String OTEL_RESOURCE_ATTRIBUTE_CONFIG_KEYS_PREFIX =
            "OpenTelemetry.RemoteTracer.ResourceAttributes.";
    private static final String OTEL_RESOURCE_ATTRIBUTES_ENV_VAR_NAME = "OTEL_RESOURCE_ATTRIBUTES";
    private static final Log log = LogFactory.getLog(OTLPTelemetry.class);
    private static final APIManagerConfiguration configuration =
            ServiceReferenceHolder.getInstance().getAPIManagerConfiguration();
    private SdkTracerProvider sdkTracerProvider;
    private OpenTelemetry openTelemetry;

    @Override
    public void init(String serviceName) {

        String headerProperty = getHeaderKeyProperty();
        String endPointURL = configuration.getFirstProperty(TelemetryConstants.OTLP_CONFIG_URL) != null ?
                configuration.getFirstProperty(TelemetryConstants.OTLP_CONFIG_URL) : null;
        String headerKey = headerProperty.substring(TelemetryConstants.OPENTELEMETRY_PROPERTIES_PREFIX.length());

        String headerValue = configuration.getFirstProperty(headerProperty) != null ?
                configuration.getFirstProperty(headerProperty) : null;

        if (StringUtils.isNotEmpty(endPointURL) && StringUtils.isNotEmpty(headerValue)) {
            OtlpGrpcSpanExporterBuilder otlpGrpcSpanExporterBuilder = OtlpGrpcSpanExporter.builder()
                    .setEndpoint(endPointURL)
                    .setCompression("gzip")
                    .addHeader(headerKey, headerValue);

            if (log.isDebugEnabled()) {
                log.debug("OTLP exporter: " + otlpGrpcSpanExporterBuilder + " is configured at " + endPointURL);
            }

            sdkTracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(otlpGrpcSpanExporterBuilder.build()).build())
                    .setResource(getTracerProviderResource(serviceName))
                    .build();

            openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(sdkTracerProvider).
                    setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .build();

            if (log.isDebugEnabled()) {
                log.debug("OpenTelemetry instance: " + openTelemetry + " is configured.");
            }
        } else {
            log.error("Either endpoint url or the header key value is null or empty");
        }
    }

    private Resource getTracerProviderResource(String defaultServiceName) {
        Map<String, String> otelResourceAttributes = getOtelResourceAttributes();
        AttributesBuilder attributesBuilder = Attributes.builder();
        for (Map.Entry<String, String> otelResourceAttribute : otelResourceAttributes.entrySet()) {
            attributesBuilder.put(otelResourceAttribute.getKey(), otelResourceAttribute.getValue());
        }
        Attributes attributes = attributesBuilder.build();

        Resource tracerProviderResource = Resource.getDefault();
        Resource serviceNameResource = Resource.create(
                Attributes.of(ResourceAttributes.SERVICE_NAME, defaultServiceName));
        tracerProviderResource = tracerProviderResource.merge(serviceNameResource);
        tracerProviderResource = tracerProviderResource.merge(Resource.create(attributes));

        return tracerProviderResource;
    }

    private Map<String, String> getOtelResourceAttributes() {
        Map<String, String> otelResourceAttributes = new HashMap<>();

        // Get from configuration
        Set<String> otelResourceAttributeConfigKeys = configuration.getConfigKeySet()
                .stream().filter(entry -> entry.startsWith(OTEL_RESOURCE_ATTRIBUTE_CONFIG_KEYS_PREFIX))
                .collect(Collectors.toSet());
        for (String configKey : otelResourceAttributeConfigKeys) {
            String otelResourceAttributeKey = configKey.substring(OTEL_RESOURCE_ATTRIBUTE_CONFIG_KEYS_PREFIX.length());
            otelResourceAttributes.put(otelResourceAttributeKey, configuration.getFirstProperty(configKey));
        }

        // Get from environment variables
        String environmentVariableValue = System.getenv(OTEL_RESOURCE_ATTRIBUTES_ENV_VAR_NAME);
        if (environmentVariableValue != null) {
            String[] resourceAttributes = StringUtils.split(environmentVariableValue,",");
            for (String keyValuePair : resourceAttributes) {
                String[] keyValue = StringUtils.split(keyValuePair, "=");
                otelResourceAttributes.put(keyValue[0], keyValue[1]);
            }
        }

        return otelResourceAttributes;
    }

    @Override
    public OpenTelemetry getAPIMOpenTelemetry() {

        return openTelemetry;
    }

    @Override
    public Tracer getTelemetryTracer() {

        return openTelemetry.getTracer(TelemetryConstants.OPENTELEMETRY_INSTRUMENTATION_NAME);
    }

    @Override
    public String getName() {

        return NAME;
    }

    @Override
    public void close() {

        if (sdkTracerProvider != null) {
            sdkTracerProvider.close();
        }
    }

    /**
     * Return the header key from properties file for specific OTLP based APM.
     *
     * @return Header key.
     */
    public String getHeaderKeyProperty() {

        Set<String> keySet = configuration.getConfigKeySet();
        for (String property : keySet) {
            if (property.startsWith(TelemetryConstants.OPENTELEMETRY_PROPERTIES_PREFIX)) {
                return property;
            }
        }
        return null;
    }

}
