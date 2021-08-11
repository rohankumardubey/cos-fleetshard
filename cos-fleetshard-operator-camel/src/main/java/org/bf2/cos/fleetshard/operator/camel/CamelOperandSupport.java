package org.bf2.cos.fleetshard.operator.camel;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.bf2.cos.fleetshard.api.KafkaSpec;
import org.bf2.cos.fleetshard.api.ResourceRef;
import org.bf2.cos.fleetshard.operator.camel.model.KameletBinding;

import static java.lang.String.format;
import static org.bf2.cos.fleetshard.support.json.JacksonUtil.iterator;

public final class CamelOperandSupport {
    private CamelOperandSupport() {
    }

    public static boolean isKameletBinding(ResourceRef ref) {
        return Objects.equals(KameletBinding.RESOURCE_API_VERSION, ref.getApiVersion())
            && Objects.equals(KameletBinding.RESOURCE_KIND, ref.getKind());
    }

    public static void configureEndpoint(Properties props, ObjectNode node, String templateId) {
        for (Iterator<Map.Entry<String, JsonNode>> cit = iterator(node); cit.hasNext();) {
            final var property = cit.next();
            final JsonNode pval = property.getValue();
            final String pkey = format(
                "camel.kamelet.%s.%s",
                templateId,
                property.getKey());

            if (pval.isObject()) {
                JsonNode kind = pval.requiredAt("/kind");
                JsonNode value = pval.requiredAt("/value");

                if (!"base64".equals(kind.textValue())) {
                    throw new RuntimeException(
                        "Unsupported field kind " + kind + " (key=" + pkey + ")");
                }

                props.put(pkey, new String(Base64.getDecoder().decode(value.asText())));
            } else {
                props.put(pkey, pval.asText());
            }
        }
    }

    public static void configureStep(Properties props, ObjectNode node, int index, String templateId) {
        for (Iterator<Map.Entry<String, JsonNode>> cit = iterator(node); cit.hasNext();) {
            final var property = cit.next();
            final JsonNode pval = property.getValue();
            final String pkey = format(
                "camel.kamelet.%s.%s.%s",
                templateId,
                stepName(index, templateId),
                property.getKey());

            if (pval.isObject()) {
                JsonNode kind = pval.requiredAt("/kind");
                JsonNode value = pval.requiredAt("/value");

                if (!"base64".equals(kind.textValue())) {
                    throw new RuntimeException(
                        "Unsupported field kind " + kind + " (key=" + pkey + ")");
                }

                props.put(pkey, new String(Base64.getDecoder().decode(value.asText())));
            } else {
                props.put(pkey, pval.asText());
            }
        }
    }

    public static String stepName(int index, String templateId) {
        return templateId + "-" + index;
    }

    public static List<Step> createSteps(JsonNode connectorSpec, CamelShardMetadata shardMetadata) {
        final List<Step> stepDefinitions = new ArrayList<>();

        JsonNode steps = connectorSpec.at("/steps");
        for (int i = 0; i < steps.size(); i++) {
            var element = steps.get(i).fields().next();
            var templateId = shardMetadata.getKamelets().get(element.getKey());

            stepDefinitions.add(new Step(
                templateId,
                stepName(i, templateId)));
        }

        return stepDefinitions;
    }

    public static Properties createApplicationProperties(
        CamelShardMetadata shardMetadata,
        ObjectNode connectorSpec,
        KafkaSpec kafkaSpec) {

        final String connectorKameletId = shardMetadata.getKamelets().get("connector");
        final String kafkaKameletId = shardMetadata.getKamelets().get("kafka");

        Properties props = new Properties();
        if (connectorSpec != null) {
            configureEndpoint(
                props,
                (ObjectNode) connectorSpec.get("connector"),
                connectorKameletId);
            configureEndpoint(
                props,
                (ObjectNode) connectorSpec.get("kafka"),
                kafkaKameletId);

            props.put(
                format("camel.kamelet.%s.user", kafkaKameletId),
                kafkaSpec.getClientId());
            props.put(
                format("camel.kamelet.%s.password", kafkaKameletId),
                new String(Base64.getDecoder().decode(kafkaSpec.getClientSecret())));
            props.put(
                format("camel.kamelet.%s.bootstrapServers", kafkaKameletId),
                kafkaSpec.getBootstrapServers());

            var steps = connectorSpec.at("/steps");
            for (int i = 0; i < steps.size(); i++) {
                var element = steps.get(i).fields().next();
                var templateId = shardMetadata.getKamelets().get(element.getKey());

                configureStep(
                    props,
                    (ObjectNode) element.getValue(),
                    i,
                    templateId);
            }
        }

        return props;
    }

    public static ObjectNode createIntegrationSpec(String secretName, CamelOperandConfiguration cfg) {
        ObjectNode integration = Serialization.jsonMapper().createObjectNode();
        ArrayNode configuration = integration.withArray("configuration");

        configuration.addObject()
            .put("type", "secret")
            .put("value", secretName);

        // TODO relates to https://github.com/apache/camel-k/issues/2539
        // TODO remove once fixed
        configuration.addObject().put("type", "env")
            .put("value", "QUARKUS_LOG_CONSOLE_JSON=false");

        if (cfg.configurations() != null) {
            for (var c : cfg.configurations()) {
                configuration.addObject()
                    .put("type", c.type())
                    .put("value", c.value());
            }
        }

        return integration;
    }

    public static class Step {
        final String templateId;
        final String id;

        public Step(String templateId, String id) {
            this.templateId = templateId;
            this.id = id;
        }
    }

}