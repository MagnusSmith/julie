package com.purbon.kafka.topology.serdes;

import static com.purbon.kafka.topology.serdes.JsonSerdesUtils.validateRequiresKeys;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.google.common.collect.Maps;
import com.purbon.kafka.topology.Configuration;
import com.purbon.kafka.topology.exceptions.TopologyParsingException;
import com.purbon.kafka.topology.model.*;
import com.purbon.kafka.topology.model.Impl.ProjectImpl;
import com.purbon.kafka.topology.model.Impl.TopologyImpl;
import com.purbon.kafka.topology.model.artefact.KafkaConnectArtefact;
import com.purbon.kafka.topology.model.users.Connector;
import com.purbon.kafka.topology.model.users.Consumer;
import com.purbon.kafka.topology.model.users.KStream;
import com.purbon.kafka.topology.model.users.Producer;
import com.purbon.kafka.topology.model.users.Schemas;
import com.purbon.kafka.topology.model.users.platform.ControlCenter;
import com.purbon.kafka.topology.model.users.platform.Kafka;
import com.purbon.kafka.topology.model.users.platform.KafkaConnect;
import com.purbon.kafka.topology.model.users.platform.SchemaRegistry;
import com.purbon.kafka.topology.utils.Pair;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TopologyCustomDeserializer extends StdDeserializer<Topology> {

  private static final Logger LOGGER = LogManager.getLogger(TopologyCustomDeserializer.class);

  private static final String PROJECTS_KEY = "projects";
  private static final String CONTEXT_KEY = "context";

  private static final String PLATFORM_KEY = "platform";
  private static final String KAFKA_KEY = "kafka";
  private static final String KAFKA_CONNECT_KEY = "kafka_connect";
  private static final String SCHEMA_REGISTRY_KEY = "schema_registry";
  private static final String CONTROL_CENTER_KEY = "control_center";

  private static final String NAME_KEY = "name";
  private static final String CONSUMERS_KEY = "consumers";
  private static final String PRODUCERS_KEY = "producers";
  private static final String CONNECTORS_KEY = "connectors";
  private static final String STREAMS_KEY = "streams";
  private static final String SCHEMAS_KEY = "schemas";
  private static final String RBAC_KEY = "rbac";
  private static final String TOPICS_KEY = "topics";
  private static final String PRINCIPAL_KEY = "principal";

  private static final String ACCESS_CONTROL = "access_control";
  private static final String ARTEFACTS = "artefacts";
  private static final String ARTIFACTS = "artifacts";

  private final Configuration config;

  TopologyCustomDeserializer(Configuration config) {
    this(null, config);
  }

  private TopologyCustomDeserializer(Class<?> clazz, Configuration config) {
    super(clazz);
    this.config = config;
  }

  @Override
  public Topology deserialize(JsonParser parser, DeserializationContext context)
      throws IOException {

    JsonNode rootNode = parser.getCodec().readTree(parser);
    validateRequiresKeys(rootNode, CONTEXT_KEY, PROJECTS_KEY);

    Topology topology = new TopologyImpl(config);
    List<String> excludeAttributes = Arrays.asList(PROJECTS_KEY, CONTEXT_KEY, PLATFORM_KEY);

    Iterator<String> fieldNames = rootNode.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      if (!excludeAttributes.contains(fieldName)) {
        topology.addOther(fieldName, rootNode.get(fieldName).asText());
      }
    }
    topology.setContext(rootNode.get(CONTEXT_KEY).asText());

    JsonNode platformNode = rootNode.get(PLATFORM_KEY);
    Platform platform = new Platform();
    if (platformNode != null && platformNode.size() > 0) {
      parse(platformNode, KAFKA_KEY, parser, Kafka.class)
          .ifPresent(obj -> platform.setKafka((Kafka) obj));
      parse(platformNode, KAFKA_CONNECT_KEY, parser, KafkaConnect.class)
          .ifPresent(obj -> platform.setKafkaConnect((KafkaConnect) obj));
      parse(platformNode, SCHEMA_REGISTRY_KEY, parser, SchemaRegistry.class)
          .ifPresent(obj -> platform.setSchemaRegistry((SchemaRegistry) obj));
      parse(platformNode, CONTROL_CENTER_KEY, parser, ControlCenter.class)
          .ifPresent(obj -> platform.setControlCenter((ControlCenter) obj));
    } else {
      LOGGER.debug("No platform components defined in the topology.");
    }

    topology.setPlatform(platform);
    parseProjects(parser, rootNode.get(PROJECTS_KEY), topology, config)
        .forEach(topology::addProject);

    // validate the generated full topics names for valid encoding
    for (Project project : topology.getProjects()) {
      for (Topic topic : project.getTopics()) {
        validateEncodingForTopicName(topic.toString());
      }
    }

    return topology;
  }

  private Optional<Object> parse(JsonNode node, String key, JsonParser parser, Class klass)
      throws JsonProcessingException {
    JsonNode pNode = node.get(key);
    if (pNode == null) {
      LOGGER.debug(String.format("%s key is missing.", key));
      return Optional.empty();
    }
    Object obj = parser.getCodec().treeToValue(pNode, klass);
    LOGGER.debug(String.format("Extracting key %s with value %s", key, obj));
    return Optional.of(obj);
  }

  private List<Project> parseProjects(
      JsonParser parser, JsonNode projectsNode, Topology topology, Configuration config)
      throws IOException {
    List<Project> projects = new ArrayList<>();
    for (int i = 0; i < projectsNode.size(); i++) {
      Project project = parseProject(parser, projectsNode.get(i), topology, config);
      LOGGER.debug(
          String.format(
              "Adding project %s to the Topology %s", project.getName(), topology.getContext()));
      projects.add(project);
    }
    return projects;
  }

  private Project parseProject(
      JsonParser parser, JsonNode rootNode, Topology topology, Configuration config)
      throws IOException {

    List<String> keys =
        Arrays.asList(
            CONSUMERS_KEY, PROJECTS_KEY, PRODUCERS_KEY, CONNECTORS_KEY, STREAMS_KEY, SCHEMAS_KEY);

    Map<String, JsonNode> rootNodes = Maps.asMap(new HashSet<>(keys), (key) -> rootNode.get(key));

    Map<String, PlatformSystem> mapOfValues = new HashMap<>();
    for (String key : rootNodes.keySet()) {
      JsonNode keyNode = rootNodes.get(key);
      if (keyNode != null) {
        Optional<PlatformSystem> optionalPlatformSystem = Optional.empty();
        switch (key) {
          case CONSUMERS_KEY:
            optionalPlatformSystem = doConsumerElements(parser, keyNode);
            break;
          case PRODUCERS_KEY:
            optionalPlatformSystem = doProducerElements(parser, keyNode);
            break;
          case CONNECTORS_KEY:
            optionalPlatformSystem = doKafkaConnectElements(parser, keyNode);
            break;
          case STREAMS_KEY:
            optionalPlatformSystem = doStreamsElements(parser, keyNode);
            break;
          case SCHEMAS_KEY:
            optionalPlatformSystem = doSchemasElements(parser, keyNode);
            break;
        }
        optionalPlatformSystem.ifPresent(ps -> mapOfValues.put(key, ps));
      }
    }

    ProjectImpl project =
        new ProjectImpl(
            rootNode.get(NAME_KEY).asText(),
            Optional.ofNullable(mapOfValues.get(CONSUMERS_KEY)),
            Optional.ofNullable(mapOfValues.get(PRODUCERS_KEY)),
            Optional.ofNullable(mapOfValues.get(STREAMS_KEY)),
            Optional.ofNullable(mapOfValues.get(CONNECTORS_KEY)),
            Optional.ofNullable(mapOfValues.get(SCHEMAS_KEY)),
            parseOptionalRbacRoles(rootNode.get(RBAC_KEY)),
            config);

    project.setPrefixContextAndOrder(topology.asFullContext(), topology.getOrder());

    if (rootNode.get(TOPICS_KEY) == null) {
      throw new IOException(
          TOPICS_KEY
              + "is missing for project: "
              + project.getName()
              + ", this is a required field.");
    }

    new JsonSerdesUtils<Topic>()
        .parseApplicationUser(parser, rootNode.get(TOPICS_KEY), Topic.class)
        .forEach(project::addTopic);

    return project;
  }

  private Optional<PlatformSystem> doConsumerElements(JsonParser parser, JsonNode node)
      throws JsonProcessingException {
    List<Consumer> consumers =
        new JsonSerdesUtils<Consumer>().parseApplicationUser(parser, node, Consumer.class);
    return Optional.of(new PlatformSystem(consumers, Collections.emptyList()));
  }

  private Optional<PlatformSystem> doProducerElements(JsonParser parser, JsonNode node)
      throws JsonProcessingException {
    List<Producer> producers =
        new JsonSerdesUtils<Producer>().parseApplicationUser(parser, node, Producer.class);
    return Optional.of(new PlatformSystem(producers, Collections.emptyList()));
  }

  private Optional<PlatformSystem> doKafkaConnectElements(JsonParser parser, JsonNode node)
      throws IOException {

    JsonNode acNode = node;
    if (node.has(ACCESS_CONTROL)) {
      acNode = node.get(ACCESS_CONTROL);
    }
    List<Connector> connectors =
        new JsonSerdesUtils<Connector>().parseApplicationUser(parser, acNode, Connector.class);
    List<KafkaConnectArtefact> artefacts = Collections.emptyList();
    if (node.has(ARTEFACTS) || node.has(ARTIFACTS)) {
      String key = node.has(ARTEFACTS) ? ARTEFACTS : ARTIFACTS;
      artefacts =
          new JsonSerdesUtils<KafkaConnectArtefact>()
              .parseApplicationUser(parser, node.get(key), KafkaConnectArtefact.class);
      Set<String> serverLabels = config.getKafkaConnectServers().keySet();
      for (KafkaConnectArtefact artefact : artefacts) {
        if (artefact.getPath() == null
            || artefact.getServerLabel() == null
            || artefact.getName() == null) {
          throw new TopologyParsingException(
              "KafkaConnect: Path, name and label are artefact mandatory fields");
        }
        if (serverLabels.contains(artefact.getServerLabel())) {
          throw new TopologyParsingException(
              String.format(
                  "KafkaConnect: Server alias label %s does not exist on the provided configuration, please check",
                  artefact.getServerLabel()));
        }
      }
    }

    return Optional.of(new PlatformSystem(connectors, artefacts));
  }

  private Optional<PlatformSystem> doStreamsElements(JsonParser parser, JsonNode node)
      throws JsonProcessingException {
    List<KStream> streams =
        new JsonSerdesUtils<KStream>().parseApplicationUser(parser, node, KStream.class);
    return Optional.of(new PlatformSystem(streams, Collections.emptyList()));
  }

  private Optional<PlatformSystem> doSchemasElements(JsonParser parser, JsonNode node)
      throws JsonProcessingException {
    List<Schemas> schemas =
        new JsonSerdesUtils<Schemas>().parseApplicationUser(parser, node, Schemas.class);
    return Optional.of(new PlatformSystem(schemas, Collections.emptyList()));
  }

  private void validateEncodingForTopicName(String name) throws IOException {
    Pattern p = Pattern.compile("^[\\x00-\\x7F\\._-]+$");
    if (!p.matcher(name).matches()) {
      String validCharacters = "ASCII alphanumerics, '.', '_' and '-'";
      throw new IOException(
          " Topic name \""
              + name
              + "\" is illegal, it contains a character other than "
              + validCharacters);
    }
  }

  private Map<String, List<String>> parseOptionalRbacRoles(JsonNode rbacRootNode) {
    if (rbacRootNode == null) return new HashMap<>();
    return StreamSupport.stream(rbacRootNode.spliterator(), true)
        .map(
            (Function<JsonNode, Pair<String, JsonNode>>)
                node -> {
                  String key = node.fieldNames().next();
                  return new Pair(key, node.get(key));
                })
        .flatMap(
            (Function<Pair<String, JsonNode>, Stream<Pair<String, String>>>)
                principals ->
                    StreamSupport.stream(principals.getValue().spliterator(), true)
                        .map(
                            node ->
                                new Pair<>(principals.getKey(), node.get(PRINCIPAL_KEY).asText())))
        .collect(groupingBy(Pair::getKey, mapping(Pair::getValue, toList())));
  }
}
