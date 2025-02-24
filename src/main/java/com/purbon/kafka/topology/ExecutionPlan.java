package com.purbon.kafka.topology;

import com.purbon.kafka.topology.actions.Action;
import com.purbon.kafka.topology.actions.BaseAccountsAction;
import com.purbon.kafka.topology.actions.CreateArtefactAction;
import com.purbon.kafka.topology.actions.DeleteArtefactAction;
import com.purbon.kafka.topology.actions.access.ClearBindings;
import com.purbon.kafka.topology.actions.accounts.ClearAccounts;
import com.purbon.kafka.topology.actions.accounts.CreateAccounts;
import com.purbon.kafka.topology.actions.topics.DeleteTopics;
import com.purbon.kafka.topology.actions.topics.SyncTopicAction;
import com.purbon.kafka.topology.model.Artefact;
import com.purbon.kafka.topology.model.artefact.KafkaConnectArtefact;
import com.purbon.kafka.topology.model.cluster.ServiceAccount;
import com.purbon.kafka.topology.roles.TopologyAclBinding;
import com.purbon.kafka.topology.utils.StreamUtils;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ExecutionPlan {

  private static final Logger LOGGER = LogManager.getLogger(ExecutionPlan.class);

  private final List<Action> plan;
  private PrintStream outputStream;
  private BackendController backendController;
  private Set<TopologyAclBinding> bindings;
  private Set<ServiceAccount> serviceAccounts;
  private Set<String> topics;
  private Set<KafkaConnectArtefact> connectors;

  private ExecutionPlan(
      List<Action> plan, PrintStream outputStream, BackendController backendController) {
    this.plan = plan;
    this.outputStream = outputStream;
    this.bindings = new HashSet<>();
    this.serviceAccounts = new HashSet<>();
    this.topics = new HashSet<>();
    this.connectors = new HashSet<>();
    this.backendController = backendController;
    if (backendController.size() > 0) {
      this.bindings.addAll(backendController.getBindings());
      this.serviceAccounts.addAll(backendController.getServiceAccounts());
      this.topics.addAll(backendController.getTopics());
      this.connectors.addAll(backendController.getConnectors());
    }
  }

  public void add(Action action) {
    this.plan.add(action);
  }

  public static ExecutionPlan init(BackendController backendController, PrintStream outputStream)
      throws IOException {
    backendController.load();
    List<Action> listOfActions = Collections.synchronizedList(new LinkedList<>());
    return new ExecutionPlan(listOfActions, outputStream, backendController);
  }

  public void run() throws IOException {
    run(false);
  }

  public void run(boolean dryRun) throws IOException {
    for (Action action : plan) {
      try {
        execute(action, dryRun);
      } catch (IOException e) {
        LOGGER.error(String.format("Something happen running action %s", action), e);
        throw e;
      }
    }

    backendController.reset();
    backendController.addBindings(new ArrayList<>(bindings));
    backendController.addServiceAccounts(serviceAccounts);
    backendController.addTopics(topics);
    backendController.addConnectors(connectors);
    backendController.flushAndClose();
  }

  private void execute(Action action, boolean dryRun) throws IOException {
    LOGGER.debug(String.format("Execution action %s (dryRun=%s)", action, dryRun));
    if (dryRun) {
      outputStream.println(action);
    } else {
      action.run();
      if (action instanceof SyncTopicAction) {
        topics.add(((SyncTopicAction) action).getTopic());
      } else if (action instanceof DeleteTopics) {
        List<String> topicsToBeDeleted = ((DeleteTopics) action).getTopicsToBeDeleted();
        topics =
            new StreamUtils<>(topics.stream())
                .filterAsSet(topic -> !topicsToBeDeleted.contains(topic));
      }
      if (!action.getBindings().isEmpty()) {
        if (action instanceof ClearBindings) {
          bindings =
              new StreamUtils<>(bindings.stream())
                  .filterAsSet(binding -> !action.getBindings().contains(binding));
        } else {
          bindings.addAll(action.getBindings());
        }
      }
      if (action instanceof BaseAccountsAction) {
        if (action instanceof ClearAccounts) {
          Collection<ServiceAccount> toDeletePrincipals = ((ClearAccounts) action).getPrincipals();
          serviceAccounts =
              new StreamUtils<>(serviceAccounts.stream())
                  .filterAsSet(sa -> !toDeletePrincipals.contains(sa));
        } else {
          CreateAccounts createAction = (CreateAccounts) action;
          serviceAccounts.addAll(createAction.getPrincipals());
        }
      }
      // TODO: will need to be extended when KSQL and other artefacts might be managed via JulieOps
      if (action instanceof CreateArtefactAction) {
        Artefact artefact = ((CreateArtefactAction) action).getArtefact();
        connectors.add((KafkaConnectArtefact) artefact);
      } else if (action instanceof DeleteArtefactAction) {
        Artefact toBeDeleted = ((DeleteArtefactAction) action).getArtefact();
        connectors =
            new StreamUtils<>(connectors.stream())
                .filterAsSet(connector -> !connector.equals(toBeDeleted));
      }
    }
  }

  public Set<ServiceAccount> getServiceAccounts() {
    return serviceAccounts;
  }

  public Set<TopologyAclBinding> getBindings() {
    return bindings;
  }

  public Set<String> getTopics() {
    return topics;
  }

  public List<Action> getActions() {
    return plan;
  }

  public Set<KafkaConnectArtefact> getConnectors() {
    return connectors;
  }
}
